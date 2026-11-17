package dev.kizuna.meridian.brewing.server;

import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CapabilityModifier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import java.util.UUID;

/** 肺燥沿用聚气汤的器官摄入修正，时间仅由当前丹毒剩余时长推导。 */
public final class BrewMetalToxinPlan {
    private static final String PREFIX = "meridian_brewing:metal_toxin/";

    private BrewMetalToxinPlan() {
        // 这里只生成身体活动，由宿主执行校验与保存。
    }

    public static BodyActivity clear(BodySnapshot body) {
        // 只撤肺燥自己的修正，聚气汤与其他来源保持原状。
        var activity = BodyActivity.newBuilder();
        for (CapabilityModifier modifier : body.getModifiersList()) {
            if (modifier.getId().startsWith(PREFIX)) {
                activity.addRemoveModifiers(modifier.getId());
            }
        }
        return activity.build();
    }

    public static BodyActivity synchronize(BodySnapshot body, long remainingMillis, double multiplier) {
        // 已有相同强度且仍有效的修正无需反复提交；过期或等级变化才替换。
        long now = body.getSettledAt().getElapsedMillis();
        var activity = BodyActivity.newBuilder();
        for (var organ : body.getStructure().getOrgansList()) {
            if (!organ.getKind().equals("human:lung")) {
                continue;
            }
            var existing = body.getModifiersList().stream()
                    .filter(item -> item.getId().startsWith(PREFIX) && item.getOrganId().equals(organ.getId()))
                    .findFirst();
            if (existing.isPresent()) {
                CapabilityModifier modifier = existing.get();
                long end = modifier.getStartMillis() + modifier.getCurveList().getLast().getOffsetMillis();
                if (modifier.getIntakePeak() == multiplier && end > now) {
                    continue;
                }
                activity.addRemoveModifiers(modifier.getId());
            }
            activity.addAddModifiers(modifier(organ.getId(), now, remainingMillis, multiplier));
        }
        return activity.build();
    }

    private static CapabilityModifier modifier(String organ, long now, long remainingMillis, double multiplier) {
        // 长时丹毒分段续接，遵守 Framework 单条曲线时限；普通五分钟丹毒只需一段。
        long duration = Math.min(remainingMillis,
                dev.kizuna.meridian.core.CapabilityModifier.MAX_CURVE_DURATION_MILLIS);
        CurvePoint start = point(0);
        CurvePoint end = point(duration);
        // 同笔撤旧加新也不能复用身份；按器官定位旧修正，用新身份原子替换。
        return CapabilityModifier.newBuilder()
                .setId(PREFIX + UUID.randomUUID())
                .setSource(BrewBodyProgress.KEY)
                .setOrganId(organ)
                .setStartMillis(now)
                .setIntakePeak(multiplier)
                .setStrengthPeak(1)
                .setBurstPeak(1)
                .setEndurancePeak(1)
                .setRecoveryPeak(1)
                .addCurve(start)
                .addCurve(end)
                .build();
    }

    private static CurvePoint point(long offset) {
        // 丹毒有效期间保持固定强度，移除效果时直接撤销。
        return CurvePoint.newBuilder()
                .setOffsetMillis(offset)
                .setValue(1)
                .build();
    }
}
