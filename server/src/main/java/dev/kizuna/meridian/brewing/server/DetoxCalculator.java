package dev.kizuna.meridian.brewing.server;

/** 按两肾的实际吞吐和健康比例计算药毒排出档位。 */
public final class DetoxCalculator {
    private DetoxCalculator() {
        // 排毒判定不保存实体状态。
    }

    public static DetoxResult resolve(KidneyMetrics left, KidneyMetrics right) {
        // 两只肾独立按 HP 比例贡献，再按旧稿界线选择档位。
        double power = left.detoxPower() + right.detoxPower();
        return new DetoxResult(power, DetoxTier.fromPower(power));
    }
}
