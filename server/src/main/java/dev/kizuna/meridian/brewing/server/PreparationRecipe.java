package dev.kizuna.meridian.brewing.server;

/** 一种药材炮制方法及其持续时间。 */
public record PreparationRecipe(
        String inputItemId,
        String outputItemId,
        PreparationMethod method,
        double durationSeconds) {
    public PreparationRecipe {
        if (inputItemId == null || inputItemId.isBlank()
                || outputItemId == null || outputItemId.isBlank()
                || method == null || !Double.isFinite(durationSeconds) || durationSeconds <= 0.0) {
            throw new IllegalArgumentException("炮制配方不完整");
        }
    }
}
