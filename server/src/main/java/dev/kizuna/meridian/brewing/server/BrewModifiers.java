package dev.kizuna.meridian.brewing.server;

import dev.kizuna.meridian.brewing.protocol.pb.BrewEffectMessages.BrewDose;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CapabilityModifier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 药效来源与曲线到身体能力修正的单向映射。 */
public final class BrewModifiers {
    private static final long RAMP_MILLIS = 500;
    private static final long FADE_MILLIS = 1_000;
    private static final double MILLIS_PER_SECOND = 1_000;
    /** 聚气汤药力过后肺虚期间的吐纳摄入倍率。 */
    public static final double EXHAUSTED_INTAKE = 0.8;

    private BrewModifiers() {
        // 不单独维护修正列表，结果与药效进度一起提交到身体。
    }

    public static List<CapabilityModifier> create(BodySnapshot body, BrewDose dose) {
        // 只有具备对应目标的药创建修正，蚀甲与魅惑留给各自宿主接口。
        BrewKind kind = BrewKind.valueOf(dose.getKind());
        if (kind == BrewKind.QI_GATHER) {
            return lungs(body, dose);
        }
        if (kind != BrewKind.DEPLETION && kind != BrewKind.BITTER_YELLOW) {
            return List.of();
        }
        var modifiers = new ArrayList<CapabilityModifier>();
        for (var tissue : body.getStructure().getTissuesList()) {
            if (tissue.getKind().equals("human:muscle") && dose.getPartIdsList().contains(tissue.getPartId())) {
                var modifier = base(dose).setTissueId(tissue.getId());
                tissuePeaks(modifier, dose);
                modifiers.add(modifier.build());
            }
        }
        return List.copyOf(modifiers);
    }

    private static void tissuePeaks(CapabilityModifier.Builder modifier, BrewDose dose) {
        // 枯竭累积扣耐力，药封只对正在治疗的部位改变速度与力量。
        BrewQuality quality = BrewQuality.fromComponentValue(dose.getQuality());
        if (BrewKind.valueOf(dose.getKind()) == BrewKind.DEPLETION) {
            modifier.setEndurancePeak(1 - dose.getEnduranceLoss());
            double tempoLoss = 1 - BrewEffectProfiles.depletion(BrewPotency.quality(dose)).tempoMultiplier();
            modifier.setTempoPeak(1 - tempoLoss * BrewPotency.strength(dose));
        } else {
            var profile = BrewEffectProfiles.bitterYellow(quality, BrewUse.valueOf(dose.getUse()));
            modifier.setStrengthPeak(profile.strengthMultiplier());
            modifier.setTempoPeak(profile.tempoMultiplier());
        }
    }

    private static List<CapabilityModifier> lungs(BodySnapshot body, BrewDose dose) {
        // 聚气和肺虚共用一条允许负值的曲线，炼化配方与守恒比例保持原值。
        var original = BrewEffectProfiles.qiGather(BrewQuality.fromComponentValue(dose.getQuality()));
        var profile = BrewEffectProfiles.qiGather(BrewPotency.quality(dose));
        long active = Math.round(original.durationSeconds() * BrewPotency.duration(dose) * MILLIS_PER_SECOND);
        long total = dose.getEndsMillis() - dose.getStartedMillis();
        double intakeGain = (profile.intakeMultiplier() - 1) * BrewPotency.strength(dose);
        double exhaustion = (EXHAUSTED_INTAKE - 1) / intakeGain;
        if (total > dev.kizuna.meridian.core.CapabilityModifier.MAX_CURVE_DURATION_MILLIS) {
            return longLungEffect(body, dose, active, 1 + intakeGain);
        }
        List<CurvePoint> curve = List.of(point(0, 0), point(RAMP_MILLIS, 1), point(active - RAMP_MILLIS, 1),
                point(active, 0), point(active + RAMP_MILLIS, exhaustion),
                point(total - RAMP_MILLIS, exhaustion), point(total, 0));
        var modifiers = new ArrayList<CapabilityModifier>();
        for (var organ : body.getStructure().getOrgansList()) {
            if (organ.getKind().equals("human:lung")) {
                var modifier = base(dose)
                        .setOrganId(organ.getId())
                        .setIntakePeak(1 + intakeGain)
                        .clearCurve()
                        .addAllCurve(curve)
                        .build();
                modifiers.add(modifier);
            }
        }
        return List.copyOf(modifiers);
    }

    private static List<CapabilityModifier> longLungEffect(BodySnapshot body, BrewDose dose,
                                                           long active, double intakePeak) {
        // 长效聚气跨过单条曲线时限时，正效与肺虚分开排期，仍由同一剂量统一撤销。
        long total = dose.getEndsMillis() - dose.getStartedMillis();
        long exhaustion = total - active;
        List<CurvePoint> activeCurve = List.of(point(0, 0), point(RAMP_MILLIS, 1),
                point(active - RAMP_MILLIS, 1), point(active, 0));
        List<CurvePoint> exhaustedCurve = List.of(point(0, 0), point(RAMP_MILLIS, 1),
                point(exhaustion - RAMP_MILLIS, 1), point(exhaustion, 0));
        var modifiers = new ArrayList<CapabilityModifier>();
        for (var organ : body.getStructure().getOrgansList()) {
            if (organ.getKind().equals("human:lung")) {
                var positive = base(dose)
                        .setOrganId(organ.getId())
                        .setIntakePeak(intakePeak)
                        .clearCurve()
                        .addAllCurve(activeCurve)
                        .build();
                var negative = base(dose)
                        .setOrganId(organ.getId())
                        .setStartMillis(dose.getStartedMillis() + active)
                        .setIntakePeak(EXHAUSTED_INTAKE)
                        .clearCurve()
                        .addAllCurve(exhaustedCurve)
                        .build();
                modifiers.add(positive);
                modifiers.add(negative);
            }
        }
        return List.copyOf(modifiers);
    }

    private static CapabilityModifier.Builder base(BrewDose dose) {
        // 每次替换使用新 ID；Framework 的先撤后加仍保留来源与重试校验。
        long duration = dose.getEndsMillis() - dose.getStartedMillis();
        long ramp = RAMP_MILLIS;
        long fade = FADE_MILLIS;
        if (dose.getKind().equals(BrewKind.BITTER_YELLOW.name())) {
            ramp = BitterHealing.RAMP_MILLIS;
            fade = BitterHealing.FADE_MILLIS;
        }
        return CapabilityModifier.newBuilder()
                .setId(BrewBodyProgress.KEY + "/" + UUID.randomUUID())
                .setSource(BrewBodyProgress.KEY)
                .setStartMillis(dose.getStartedMillis())
                .setStrengthPeak(1)
                .setBurstPeak(1)
                .setEndurancePeak(1)
                .setRecoveryPeak(1)
                .setTempoPeak(1)
                .addCurve(point(0, 0))
                .addCurve(point(ramp, 1))
                .addCurve(point(duration - fade, 1))
                .addCurve(point(duration, 0));
    }

    private static CurvePoint point(long offset, double value) {
        // 所有峰值维度共用相同的线性插值与起止时刻。
        return CurvePoint.newBuilder()
                .setOffsetMillis(offset)
                .setValue(value)
                .build();
    }

    public static CapabilityModifier fadeOut(CapabilityModifier modifier, long now) {
        // 治满后从当前曲线值平滑退封，不能重新从峰值开始或延长原本更短的尾段。
        long duration = modifier.getStartMillis() + modifier.getCurveList().getLast().getOffsetMillis() - now;
        long fade = Math.min(BitterHealing.FADE_MILLIS, duration);
        double value = curveValue(modifier, now);
        return modifier.toBuilder()
                .setId(BrewBodyProgress.KEY + "/" + UUID.randomUUID())
                .setStartMillis(now)
                .clearCurve()
                .addCurve(point(0, value))
                .addCurve(point(fade, 0))
                .build();
    }

    private static double curveValue(CapabilityModifier modifier, long now) {
        // 已存在修正由 Framework 保证时间严格递增，按所在段线性插值。
        long offset = now - modifier.getStartMillis();
        List<CurvePoint> points = modifier.getCurveList();
        for (int index = 1; index < points.size(); index++) {
            CurvePoint right = points.get(index);
            if (offset <= right.getOffsetMillis()) {
                CurvePoint left = points.get(index - 1);
                double progress = (offset - left.getOffsetMillis())
                        / (double) (right.getOffsetMillis() - left.getOffsetMillis());
                return left.getValue() + progress * (right.getValue() - left.getValue());
            }
        }
        return 0;
    }

    public static CapabilityModifier remaining(CapabilityModifier modifier, long now) {
        // 核心禁止新修正回溯生效，重登只提交当前值及未来曲线，不重放已经经历的区间。
        if (modifier.getStartMillis() >= now) {
            return modifier;
        }
        long elapsed = now - modifier.getStartMillis();
        var remaining = modifier.toBuilder()
                .setStartMillis(now)
                .clearCurve()
                .addCurve(point(0, curveValue(modifier, now)));
        for (CurvePoint point : modifier.getCurveList()) {
            if (point.getOffsetMillis() > elapsed) {
                remaining.addCurve(point(point.getOffsetMillis() - elapsed, point.getValue()));
            }
        }
        return remaining.build();
    }
}
