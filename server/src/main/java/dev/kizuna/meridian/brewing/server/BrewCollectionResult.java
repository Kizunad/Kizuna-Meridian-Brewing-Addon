package dev.kizuna.meridian.brewing.server;

import java.util.Optional;

/** 一瓶成品的服务端判分结果。 */
public record BrewCollectionResult(
        BrewCollectionKind kind,
        Optional<BrewScore> score,
        int remainingBottles,
        String reason) {
    public BrewCollectionResult {
        if (kind == null || score == null || remainingBottles < 0 || reason == null) {
            throw new IllegalArgumentException("舀药结果不完整");
        }
    }
}
