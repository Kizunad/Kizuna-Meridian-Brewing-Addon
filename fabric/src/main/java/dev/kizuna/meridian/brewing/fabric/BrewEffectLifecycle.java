package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.protocol.pb.BrewEffectMessages.BrewBodyState;
import dev.kizuna.meridian.brewing.protocol.pb.BrewEffectMessages.BrewDose;
import dev.kizuna.meridian.brewing.server.BitterHealing;
import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BrewDoseOperations;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewModifiers;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** 状态效果的周期与移除事件驱动药效，持续记录始终存在身体进度中。 */
public final class BrewEffectLifecycle {
    private BrewEffectLifecycle() {
        // 不建立独立 tick 会话，离线保存由原版状态和身体宿主完成。
    }

    static void tick(LivingEntity entity, BrewKind kind, BrewQuality quality) {
        // 读取此刻实际身体，随后一次治疗与一次进度事务都保持预期版本。
        var found = BrewBodyHost.inspect(entity);
        if (found.isEmpty()) {
            if (kind == BrewKind.ARMOR_ROT) {
                BrewArmorEffects.tickUnbound(entity, quality);
            }
            return;
        }
        BrewBodyHost.Binding binding = found.get();
        if (binding.body().getVitality().getDead()) {
            return;
        }
        BrewBodyState state = BrewBodyProgress.read(binding.body());
        if (kind == BrewKind.ARMOR_ROT) {
            BrewArmorEffects.tick(entity, binding.body(), state.getDosesList());
        }
        BrewBodyHost.Binding healed = heal(binding, kind, state.getDosesList());
        advance(healed, kind, state);
    }

    private static BrewBodyHost.Binding heal(BrewBodyHost.Binding binding, BrewKind kind, List<BrewDose> doses) {
        // 同部位有重叠治疗时只取当期较大剂量，不让重复瓶数乘叠。
        if (kind != BrewKind.BITTER_YELLOW) {
            return binding;
        }
        var amounts = new LinkedHashMap<String, Double>();
        BodySnapshot body = binding.body();
        long now = body.getSettledAt().getElapsedMillis();
        for (BrewDose dose : doses) {
            if (dose.getKind().equals(kind.name())) {
                BitterHealing.doses(body, dose, now).forEach((part, amount) -> amounts.merge(part, amount, Math::max));
            }
        }
        var command = BitterHealing.command(body, amounts);
        if (command.getDosesCount() == 0) {
            return binding;
        }
        return BrewBodyHost.heal(binding, command);
    }

    private static void advance(BrewBodyHost.Binding binding, BrewKind kind, BrewBodyState previous) {
        // 治满部位撤掉药封，过期来源撤掉剩余修正；尚有效的其他药保持原曲线。
        BodySnapshot body = binding.body();
        long now = body.getSettledAt().getElapsedMillis();
        var activity = BodyActivity.newBuilder();
        var state = previous.toBuilder().clearDoses();
        for (BrewDose dose : previous.getDosesList()) {
            if (!dose.getKind().equals(kind.name())) {
                state.addDoses(dose);
            } else if (dose.getEndsMillis() <= now) {
                BrewDoseOperations.removeModifiers(body, dose, activity);
            } else if (kind == BrewKind.BITTER_YELLOW) {
                state.addDoses(healedDose(body, dose, activity));
            } else {
                state.addDoses(dose);
            }
        }
        BrewBodyState updated = state.build();
        if (!updated.equals(previous) || activity.getRemoveModifiersCount() > 0) {
            BrewBodyProgress.write(activity, updated);
            BrewBodyHost.activity(binding, activity.build());
        }
    }

    private static BrewDose healedDose(BodySnapshot body, BrewDose dose, BodyActivity.Builder activity) {
        // 已补满的部位不因药效尚在而重新受封；剩余部位继续按原预算与曲线治疗。
        List<String> parts = dose.getPartIdsList().stream()
                .filter(part -> BitterHealing.wound(body, part) > 0).toList();
        var updated = dose.toBuilder()
                .setLastHealedMillis(body.getSettledAt().getElapsedMillis())
                .clearPartIds()
                .addAllPartIds(parts)
                .clearModifierIds();
        for (String id : dose.getModifierIdsList()) {
            var found = body.getModifiersList().stream().filter(modifier -> modifier.getId().equals(id)).findFirst();
            if (found.isEmpty()) {
                continue;
            }
            String part = BrewDoseOperations.partOf(body, found.get().getTissueId());
            if (parts.contains(part) || dose.getFadingPartIdsList().contains(part)) {
                updated.addModifierIds(id);
            } else {
                activity.addRemoveModifiers(id);
                var fading = BrewModifiers.fadeOut(found.get(), body.getSettledAt().getElapsedMillis());
                activity.addAddModifiers(fading);
                updated.addModifierIds(fading.getId());
                updated.addFadingModifiers(fading);
                if (!updated.getFadingPartIdsList().contains(part)) {
                    updated.addFadingPartIds(part);
                }
            }
        }
        return updated.build();
    }

    public static void removed(LivingEntity entity, Collection<MobEffectInstance> removed) {
        // 牛奶、命令清除和自然到期共用撤销路径，不恢复已经造成的伤势与物品耐久。
        if (!(entity.level() instanceof ServerLevel)) {
            return;
        }
        Set<BrewKind> kinds = new HashSet<>();
        for (MobEffectInstance effect : removed) {
            BrewingEffects.kind(effect.getEffect()).ifPresent(kinds::add);
        }
        if (kinds.isEmpty()) {
            return;
        }
        if (kinds.contains(BrewKind.ARMOR_ROT)) {
            ((BrewArmorTarget) entity).meridianBrewing$armorSlot("");
        }
        var found = BrewBodyHost.inspect(entity);
        if (found.isEmpty() || found.get().body().getVitality().getDead()) {
            return;
        }
        BrewBodyHost.Binding binding = found.get();
        if (kinds.contains(BrewKind.BITTER_YELLOW)) {
            BrewBodyState state = BrewBodyProgress.read(binding.body());
            clear(heal(binding, BrewKind.BITTER_YELLOW, state.getDosesList()), kinds);
        } else {
            clear(binding, kinds);
        }
    }

    private static void clear(BrewBodyHost.Binding binding, Set<BrewKind> kinds) {
        // 按来源记录删除自己的修正，不能碰功法或其他药的曲线。
        BodySnapshot body = binding.body();
        BrewBodyState previous = BrewBodyProgress.read(body);
        var state = previous.toBuilder().clearDoses();
        var activity = BodyActivity.newBuilder();
        for (BrewDose dose : previous.getDosesList()) {
            if (kinds.contains(BrewKind.valueOf(dose.getKind()))) {
                BrewDoseOperations.removeModifiers(body, dose, activity);
            } else {
                state.addDoses(dose);
            }
        }
        if (kinds.contains(BrewKind.BITTER_YELLOW)) {
            state.setNauseaUntilMillis(0);
        }
        BrewBodyState updated = state.build();
        if (!updated.equals(previous)) {
            BrewBodyProgress.write(activity, updated);
            BrewBodyHost.activity(binding, activity.build());
        }
    }
}
