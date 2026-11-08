package dev.kizuna.meridian.brewing.server;

/** 一个方块 ID 的纯枯萎转换结果。 */
public record WitheringDecision(String sourceBlockId, String targetBlockId, WitheringOutcome outcome) {
    public WitheringDecision {
        if (sourceBlockId == null || targetBlockId == null || outcome == null) {
            throw new IllegalArgumentException("枯萎转换不完整");
        }
    }
}
