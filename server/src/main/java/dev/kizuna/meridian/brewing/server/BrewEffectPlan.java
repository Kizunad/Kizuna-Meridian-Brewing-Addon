package dev.kizuna.meridian.brewing.server;

/** 药效规则转交 Human、Damage 或 Threatening 的无状态 DTO。 */
public record BrewEffectPlan(
        BrewKind kind,
        BrewQuality quality,
        BrewUse use,
        EffectTargetScope target,
        double durationSeconds,
        double primaryValue,
        double secondaryValue,
        double tertiaryValue) {
    public BrewEffectPlan {
        if (kind == null || quality == null || use == null || target == null
                || !Double.isFinite(durationSeconds) || durationSeconds < 0
                || !Double.isFinite(primaryValue) || !Double.isFinite(secondaryValue)
                || !Double.isFinite(tertiaryValue)) {
            throw new IllegalArgumentException("药效计划不完整");
        }
    }
}
