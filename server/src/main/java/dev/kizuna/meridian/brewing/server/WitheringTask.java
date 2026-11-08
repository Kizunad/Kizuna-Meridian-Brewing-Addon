package dev.kizuna.meridian.brewing.server;

/** 枯萎渗开队列中的一格。 */
public record WitheringTask(WitheringCandidate candidate, long delayTicks) {
    public WitheringTask {
        if (candidate == null || delayTicks < 0) {
            throw new IllegalArgumentException("枯萎任务不完整");
        }
    }
}
