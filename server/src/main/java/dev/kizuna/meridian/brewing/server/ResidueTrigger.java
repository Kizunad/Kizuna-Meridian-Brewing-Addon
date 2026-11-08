package dev.kizuna.meridian.brewing.server;

import java.util.UUID;

/** 噀催残药时提交的权威来源、目标和距离。 */
public record ResidueTrigger(UUID source, UUID target, BrewKind kind, double distance) {
    public static final double MAX_TRIGGER_DISTANCE = 16.0;

    public ResidueTrigger {
        if (source == null || target == null || kind == null
                || !Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("残药催发参数不完整");
        }
    }

    public boolean inRange() {
        return distance <= MAX_TRIGGER_DISTANCE;
    }
}
