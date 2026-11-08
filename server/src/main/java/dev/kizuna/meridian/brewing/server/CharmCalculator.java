package dev.kizuna.meridian.brewing.server;

/** 按脑和注意组织的可用耐力计算魅惑结果。 */
public final class CharmCalculator {
    public static final double IGNORE_THRESHOLD = 1_200;
    public static final double STAGGER_THRESHOLD = 700;

    private CharmCalculator() {
        // 魅惑判定不保存实体状态。
    }

    public static CharmResult resolve(BrainMetrics brain, BrainMetrics attention, BrewQuality quality) {
        // 药力压制随品质增加，噀催时由调用方传入同样逻辑的减半药力。
        double brainPower = brain.availablePower() + attention.availablePower();
        CharmProfile profile = BrewEffectProfiles.charm(quality);
        double suppressedPower = brainPower - profile.suppression();
        CharmOutcome outcome = outcome(suppressedPower);
        return new CharmResult(brainPower, suppressedPower, outcome);
    }

    private static CharmOutcome outcome(double suppressedPower) {
        // 高于强阈值无视，中间档只僵直，低于良好阈值交给受惑控制入口。
        if (suppressedPower >= IGNORE_THRESHOLD) {
            return CharmOutcome.IGNORE;
        }
        if (suppressedPower >= STAGGER_THRESHOLD) {
            return CharmOutcome.STAGGER;
        }
        return CharmOutcome.CONTROL;
    }
}
