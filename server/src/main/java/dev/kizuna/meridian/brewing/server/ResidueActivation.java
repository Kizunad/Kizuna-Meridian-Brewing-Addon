package dev.kizuna.meridian.brewing.server;

/** 一份残药被催发后交给药效宿主的半强度计划。 */
public record ResidueActivation(
        ResidueKey key,
        BrewQuality quality,
        double doseMultiplier,
        double durationMultiplier) {
    public ResidueActivation {
        if (key == null || quality == null || !Double.isFinite(doseMultiplier)
                || !Double.isFinite(durationMultiplier) || doseMultiplier <= 0 || durationMultiplier <= 0) {
            throw new IllegalArgumentException("残药催发结果不完整");
        }
    }
}
