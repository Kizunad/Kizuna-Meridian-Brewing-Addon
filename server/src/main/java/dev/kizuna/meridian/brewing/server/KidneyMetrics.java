package dev.kizuna.meridian.brewing.server;

/** 一只肾的吞吐和当前/最大 HP；来自 Human inspect 的只读结果。 */
public record KidneyMetrics(double throughputPerSecond, double currentHp, double maximumHp) {
    public KidneyMetrics {
        // 最大 HP 必须为正，当前 HP 裁剪在身体允许的范围内。
        if (!Double.isFinite(throughputPerSecond) || throughputPerSecond < 0
                || !Double.isFinite(currentHp) || !Double.isFinite(maximumHp) || maximumHp <= 0) {
            throw new IllegalArgumentException("非法肾脏参数");
        }
    }

    public double detoxPower() {
        // 受伤器官按当前 HP 比例降低排毒力，坏肾自然贡献为零。
        double healthRatio = Math.max(0, Math.min(1, currentHp / maximumHp));
        return throughputPerSecond * healthRatio;
    }
}
