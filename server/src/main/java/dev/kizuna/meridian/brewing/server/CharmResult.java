package dev.kizuna.meridian.brewing.server;

/** 魅惑判定留下的可审计脑力数值。 */
public record CharmResult(double brainPower, double suppressedPower, CharmOutcome outcome) {
    public CharmResult {
        if (!Double.isFinite(brainPower) || !Double.isFinite(suppressedPower) || outcome == null) {
            throw new IllegalArgumentException("非法魅惑判定");
        }
    }
}
