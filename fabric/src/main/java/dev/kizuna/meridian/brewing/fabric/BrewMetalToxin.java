package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewMetalToxinPlan;
import dev.kizuna.meridian.core.CapabilityModifier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** 原版丹毒效果与唯一身体上的肺摄入修正同步。 */
final class BrewMetalToxin {
    private static final long MILLIS_PER_TICK = 50;

    private BrewMetalToxin() {
        // 不保存丹毒时间或额外身体状态。
    }

    static void synchronize(LivingEntity entity) {
        // 身体暂停期间保留现场，恢复后用仍在身上的效果重建缺失修正。
        if (!(entity.level() instanceof ServerLevel)) {
            return;
        }
        var effect = entity.getEffect(BrewingEffects.status(BrewKind.METAL_ELIXIR));
        if (effect == null) {
            return;
        }
        int remainingTicks = effect.getDuration();
        if (remainingTicks <= 0) {
            return;
        }
        var found = BrewBodyHost.observe(entity).found();
        if (found.isEmpty()) {
            return;
        }
        var binding = found.get();
        BodySnapshot body = binding.body();
        if (body.getVitality().getDead()) {
            return;
        }
        double multiplier = BrewElixirs.settings().toxins().multiplier(BrewKind.METAL_ELIXIR,
                effect.getAmplifier() + 1);
        var plan = BrewMetalToxinPlan.synchronize(body, remainingTicks * MILLIS_PER_TICK, multiplier);
        int retained = body.getModifiersCount() - plan.getRemoveModifiersCount();
        int additions = plan.getAddModifiersCount();
        if (retained + additions > CapabilityModifier.MAX_PER_BODY) {
            if (entity instanceof ServerPlayer player) {
                player.sendOverlayMessage(Component.translatable("message.meridian_brewing.modifier_limit"));
            }
            return;
        }
        if (additions > 0) {
            BrewBodyHost.activity(binding, plan);
        }
    }

    static void clear(LivingEntity entity) {
        // 自然到期、命令移除与重登校准都撤掉旧曲线，再按当前效果决定是否重建。
        if (!(entity.level() instanceof ServerLevel)) {
            return;
        }
        var found = BrewBodyHost.observe(entity).found();
        if (found.isEmpty()) {
            return;
        }
        var binding = found.get();
        BodySnapshot body = binding.body();
        if (body.getVitality().getDead()) {
            return;
        }
        var plan = BrewMetalToxinPlan.clear(body);
        if (plan.getRemoveModifiersCount() > 0) {
            BrewBodyHost.activity(binding, plan);
        }
    }
}
