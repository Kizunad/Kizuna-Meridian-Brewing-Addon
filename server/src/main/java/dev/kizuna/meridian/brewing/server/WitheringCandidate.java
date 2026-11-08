package dev.kizuna.meridian.brewing.server;

/** 枯萎扩散队列中的一个可见方块。 */
public record WitheringCandidate(String blockId, double distance, double randomValue) {
    public WitheringCandidate {
        if (blockId == null || !Double.isFinite(distance) || distance < 0
                || !Double.isFinite(randomValue) || randomValue < 0 || randomValue > 1) {
            throw new IllegalArgumentException("枯萎候选不完整");
        }
    }
}
