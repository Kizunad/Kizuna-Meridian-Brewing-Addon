package dev.kizuna.meridian.brewing.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只存在于服务端内存的残药账本；实体卸载时由宿主丢弃。 */
public final class ResidueStore {
    public static final double HALF_LIFE_SECONDS = 20.0;
    public static final double RECOVERY_HALF_LIFE_DIVISOR = 3.0;
    public static final double MINIMUM_TRACKED_DOSE = 0.01;
    private static final double TRIGGER_DOSE_MULTIPLIER = 0.5;
    private static final double TRIGGER_DURATION_MULTIPLIER = 0.5;

    private final Map<ResidueKey, Entry> entries = new HashMap<>();

    public void put(ResidueSeed seed) {
        if (seed == null) {
            throw new IllegalArgumentException("残药种子不能为空");
        }
        entries.put(seed.key(), new Entry(seed.quality(), seed.dose(), 0.0));
    }

    public void tick(double seconds, RecoveryState recoveryState) {
        if (!Double.isFinite(seconds) || seconds < 0 || recoveryState == null) {
            throw new IllegalArgumentException("残药推进参数不完整");
        }
        if (seconds == 0 || entries.isEmpty()) {
            return;
        }
        double halfLife = HALF_LIFE_SECONDS;
        if (recoveryState == RecoveryState.ACTIVE) {
            halfLife = HALF_LIFE_SECONDS / RECOVERY_HALF_LIFE_DIVISOR;
        }
        double decayFactor = Math.pow(0.5, seconds / halfLife);
        entries.replaceAll((key, entry) -> entry.decay(decayFactor, seconds));
        entries.entrySet().removeIf(entry -> entry.getValue().dose() < MINIMUM_TRACKED_DOSE);
    }

    public List<ResidueSnapshot> snapshots() {
        List<ResidueSnapshot> snapshots = new ArrayList<>();
        entries.forEach((key, entry) -> snapshots.add(
                new ResidueSnapshot(key, entry.quality(), entry.dose(), entry.elapsedSeconds())));
        return List.copyOf(snapshots);
    }

    public List<ResidueActivation> trigger(ResidueTrigger trigger) {
        if (trigger == null || !trigger.inRange()) {
            return List.of();
        }
        List<ResidueActivation> activations = new ArrayList<>();
        entries.entrySet().removeIf(entry -> {
            ResidueKey key = entry.getKey();
            boolean matches = key.source().equals(trigger.source())
                    && key.target().equals(trigger.target()) && key.kind() == trigger.kind();
            if (matches) {
                activations.add(new ResidueActivation(key, entry.getValue().quality(),
                        TRIGGER_DOSE_MULTIPLIER * entry.getValue().dose(),
                        TRIGGER_DURATION_MULTIPLIER));
            }
            return matches;
        });
        return List.copyOf(activations);
    }

    public void clearTarget(UUID target) {
        if (target == null) {
            throw new IllegalArgumentException("清理目标不能为空");
        }
        entries.keySet().removeIf(key -> key.target().equals(target));
    }

    private record Entry(BrewQuality quality, double dose, double elapsedSeconds) {
        private Entry decay(double factor, double seconds) {
            return new Entry(quality, dose * factor, elapsedSeconds + seconds);
        }
    }
}
