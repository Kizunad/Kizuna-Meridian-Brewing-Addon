package dev.kizuna.meridian.brewing.server;

import dev.kizuna.meridian.brewing.protocol.pb.BrewEffectMessages.BrewBodyState;
import dev.kizuna.meridian.brewing.protocol.pb.BrewEffectMessages.BrewDose;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CapabilityModifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 按原版保存的状态剩余时长恢复药效，离线区间不产生治疗。 */
public final class BrewDoseResume {
    private BrewDoseResume() {
        // 恢复仅返回候选，仍由同一个身体活动事务提交。
    }

    public static BodyActivity plan(BodySnapshot body, Map<BrewKind, Long> remainingMillis) {
        // 身体离线推进可能已清掉旧修正，按持久剂量重建来源并整体平移治疗游标。
        BrewBodyState previous = BrewBodyProgress.read(body);
        var state = previous.toBuilder().clearDoses();
        var activity = BodyActivity.newBuilder();
        for (BrewDose dose : previous.getDosesList()) {
            BrewDoseOperations.removeModifiers(body, dose, activity);
            BrewKind kind = BrewKind.valueOf(dose.getKind());
            Long remaining = remainingMillis.get(kind);
            if (remaining != null) {
                long shift = shift(body, previous, kind, remaining);
                if (dose.getEndsMillis() + shift > body.getSettledAt().getElapsedMillis()) {
                    Restored restored = restore(body, dose, shift);
                    state.addDoses(restored.dose());
                    activity.addAllAddModifiers(restored.modifiers());
                }
            }
        }
        Long bitterRemaining = remainingMillis.get(BrewKind.BITTER_YELLOW);
        if (bitterRemaining == null) {
            state.setNauseaUntilMillis(0);
        } else if (previous.getNauseaUntilMillis() > 0) {
            long shift = shift(body, previous, BrewKind.BITTER_YELLOW, bitterRemaining);
            state.setNauseaUntilMillis(previous.getNauseaUntilMillis() + shift);
        }
        BrewBodyProgress.write(activity, state.build());
        return activity.build();
    }

    private static long shift(BodySnapshot body, BrewBodyState state, BrewKind kind, long remaining) {
        // 一个图标覆盖同药所有部位；部位间原有的期限差保留。
        long originalEnd = state.getDosesList().stream().filter(dose -> dose.getKind().equals(kind.name()))
                .mapToLong(BrewDose::getEndsMillis).max().orElse(0);
        long statusEnd = originalEnd;
        if (kind == BrewKind.BITTER_YELLOW) {
            statusEnd = Math.max(originalEnd, state.getNauseaUntilMillis());
        }
        return Math.max(0, body.getSettledAt().getElapsedMillis() + remaining - statusEnd);
    }

    private record Restored(BrewDose dose, List<CapabilityModifier> modifiers) { }

    private static Restored restore(BodySnapshot body, BrewDose dose, long shift) {
        // 药封渐退保留离线前的曲线，不能重登后重新从峰值开始。
        var restored = dose.toBuilder()
                .setStartedMillis(dose.getStartedMillis() + shift)
                .setEndsMillis(dose.getEndsMillis() + shift)
                .setLastHealedMillis(dose.getLastHealedMillis() + shift)
                .clearModifierIds()
                .clearFadingModifiers();
        for (CapabilityModifier fading : dose.getFadingModifiersList()) {
            var shifted = fading.toBuilder()
                    .setId(BrewBodyProgress.KEY + "/" + UUID.randomUUID())
                    .setStartMillis(fading.getStartMillis() + shift)
                    .build();
            if (shifted.getStartMillis() + shifted.getCurveList().getLast().getOffsetMillis()
                    > body.getSettledAt().getElapsedMillis()) {
                restored.addFadingModifiers(BrewModifiers.remaining(shifted, body.getSettledAt().getElapsedMillis()));
            }
        }
        // 在生成最终计划前固定新 ID，避免进度保存的身份与提交修正不同。
        var modifiers = new ArrayList<>(BrewModifiers.create(body, restored.build()).stream()
                .map(modifier -> BrewModifiers.remaining(modifier, body.getSettledAt().getElapsedMillis())).toList());
        modifiers.addAll(restored.getFadingModifiersList());
        restored.addAllModifierIds(modifiers.stream().map(CapabilityModifier::getId).toList());
        return new Restored(restored.build(), List.copyOf(modifiers));
    }
}
