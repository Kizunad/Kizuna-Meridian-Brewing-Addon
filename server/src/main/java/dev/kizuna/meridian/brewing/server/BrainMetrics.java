package dev.kizuna.meridian.brewing.server;

/** 脑或注意组织的耐力、疲劳和 HP 只读快照。 */
public record BrainMetrics(double endurance, double fatigueRatio, double currentHp, double maximumHp) {
    public BrainMetrics {
        // 可用耐力计算需要有限的非负输入，疲劳比例会在计算时裁剪到 0 到 1。
        if (!Double.isFinite(endurance) || endurance < 0 || !Double.isFinite(fatigueRatio)
                || !Double.isFinite(currentHp) || !Double.isFinite(maximumHp) || maximumHp <= 0) {
            throw new IllegalArgumentException("非法脑组织参数");
        }
    }

    public double availablePower() {
        // 疲劳降低可用耐力，头部受伤再按 HP 比例降低贡献。
        double fatigueFactor = 1 - Math.max(0, Math.min(1, fatigueRatio));
        double healthFactor = Math.max(0, Math.min(1, currentHp / maximumHp));
        return endurance * fatigueFactor * healthFactor;
    }
}
