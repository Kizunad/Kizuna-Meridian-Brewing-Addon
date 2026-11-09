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
    public static final double MINIMUM_TRACKED_DOSE = 0.05;
    private static final double TRIGGER_DOSE_MULTIPLIER = 0.5;
    private static final double TRIGGER_DURATION_MULTIPLIER = 0.5;

    private final Map<ResidueKey, Entry> entries = new HashMap<>();

    public void put(ResidueSeed seed) {
        // 相同来源、药种和部位覆盖旧剂量，不能叠成额外强度。
        if (seed == null) {
            throw new IllegalArgumentException("残药种子不能为空");
        }
        entries.put(seed.key(), new Entry(seed.quality(), seed.dose(), 0.0));
    }

    public void tick(double seconds, RecoveryState recoveryState) {
        // 不含肾脏差异的调用保持原始半衰期。
        tick(seconds, key -> new Decay(1, recoveryState));
    }

    public record Decay(double kidneyMultiplier, RecoveryState recovery) { }

    public void tick(double seconds, java.util.function.Function<ResidueKey, Decay> conditions) {
        // 宿主按目标提供当前肾脏和恢复事实，存储层只计算指数衰减。
        if (!Double.isFinite(seconds) || seconds < 0 || conditions == null) {
            throw new IllegalArgumentException("残药推进参数不完整");
        }
        if (seconds == 0 || entries.isEmpty()) {
            return;
        }
        entries.replaceAll((key, entry) -> decay(entry, seconds, conditions.apply(key)));
        entries.entrySet().removeIf(entry -> entry.getValue().dose() < MINIMUM_TRACKED_DOSE);
    }

    public List<ResidueSnapshot> snapshots() {
        // 只读快照供宿主查询与施放，不暴露可变存储。
        List<ResidueSnapshot> snapshots = new ArrayList<>();
        entries.forEach((key, entry) -> snapshots.add(
                new ResidueSnapshot(key, entry.quality(), entry.dose(), entry.elapsedSeconds())));
        return List.copyOf(snapshots);
    }

    public List<ResidueActivation> trigger(ResidueTrigger trigger) {
        // 普通催发规则保留减半参数，功法调用可由目录显式覆盖。
        return trigger(trigger, new ActivationScale(TRIGGER_DOSE_MULTIPLIER, TRIGGER_DURATION_MULTIPLIER));
    }

    public record ActivationScale(double strength, double duration) { }

    public List<ResidueActivation> trigger(ResidueTrigger trigger, ActivationScale scale) {
        // 只提取同源同种目标，取出后立即清除，下一次噀催不能复用。
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
                        scale.strength() * entry.getValue().dose(), scale.duration()));
            }
            return matches;
        });
        return List.copyOf(activations);
    }

    public void clearTarget(UUID target) {
        // 牛奶、死亡和卸载清除该目标的全部来源。
        if (target == null) {
            throw new IllegalArgumentException("清理目标不能为空");
        }
        entries.keySet().removeIf(key -> key.target().equals(target));
    }

    private record Entry(BrewQuality quality, double dose, double elapsedSeconds) {
        private Entry decay(double factor, double seconds) {
            // 只累加经过的时间和衰减后的剂量。
            return new Entry(quality, dose * factor, elapsedSeconds + seconds);
        }
    }

    private static Entry decay(Entry entry, double seconds, Decay conditions) {
        // 无害肾脏立即清除需要排毒的残药，恢复类功法进一步缩短半衰期。
        if (conditions == null || conditions.recovery() == null
                || !Double.isFinite(conditions.kidneyMultiplier()) || conditions.kidneyMultiplier() < 0) {
            throw new IllegalArgumentException("残药排毒条件非法");
        }
        if (conditions.kidneyMultiplier() == 0) {
            return entry.decay(0, seconds);
        }
        double ordinaryHalfLife = HALF_LIFE_SECONDS * conditions.kidneyMultiplier();
        double halfLife = ordinaryHalfLife;
        if (conditions.recovery() == RecoveryState.ACTIVE) {
            halfLife = ordinaryHalfLife / RECOVERY_HALF_LIFE_DIVISOR;
        }
        return entry.decay(Math.pow(0.5, seconds / halfLife), seconds);
    }
}
