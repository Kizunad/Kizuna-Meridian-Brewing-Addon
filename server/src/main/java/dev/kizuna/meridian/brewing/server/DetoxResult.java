package dev.kizuna.meridian.brewing.server;

/** 两肾合计排毒力的判定结果。 */
public record DetoxResult(double power, DetoxTier tier) {
    public DetoxResult {
        // 排毒力不应出现负数或非有限值，避免持续时长反向增长。
        if (!Double.isFinite(power) || power < 0 || tier == null) {
            throw new IllegalArgumentException("非法排毒判定");
        }
    }

    public double durationSeconds(double baseSeconds) {
        // 只有持续部分乘肾脏倍率，枯竭药水的一次腐蚀由调用方单独提交。
        if (!Double.isFinite(baseSeconds) || baseSeconds < 0) {
            throw new IllegalArgumentException("非法基础持续时间：" + baseSeconds);
        }
        return baseSeconds * tier.durationMultiplier();
    }
}
