package dev.kizuna.meridian.brewing.server;

/** 内存残药的可审计快照。 */
public record ResidueSnapshot(ResidueKey key, BrewQuality quality, double dose, double elapsedSeconds) {
    public ResidueSnapshot {
        if (key == null || quality == null || !Double.isFinite(dose) || dose <= 0
                || !Double.isFinite(elapsedSeconds) || elapsedSeconds < 0) {
            throw new IllegalArgumentException("残药快照不完整");
        }
    }
}
