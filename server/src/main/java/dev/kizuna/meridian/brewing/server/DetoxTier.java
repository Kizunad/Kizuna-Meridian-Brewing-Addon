package dev.kizuna.meridian.brewing.server;

/** 肾脏排毒档位及对持续药效的时间倍率。 */
public enum DetoxTier {
    HARMLESS(4.0, Double.POSITIVE_INFINITY, 0.0),
    EXCELLENT(2.5, 4.0, 0.7),
    GOOD(1.8, 2.5, 1.0),
    ORDINARY(1.0, 1.8, 1.25),
    POOR(0.0, 1.0, 1.5);

    private final double minimumPower;
    private final double maximumPower;
    private final double durationMultiplier;

    DetoxTier(double minimumPower, double maximumPower, double durationMultiplier) {
        // 界线和倍率来自旧稿，集中于枚举以便未来迁移到配置文件。
        this.minimumPower = minimumPower;
        this.maximumPower = maximumPower;
        this.durationMultiplier = durationMultiplier;
    }

    public double durationMultiplier() {
        // 无害档用零表示免除持续部分，单次腐蚀由药效计划另行保留。
        return durationMultiplier;
    }

    static DetoxTier fromPower(double power) {
        // 上界使用开区间，避免 2.5 或 1.8 在相邻档位重复归类。
        if (power >= HARMLESS.minimumPower) {
            return HARMLESS;
        }
        if (power >= EXCELLENT.minimumPower) {
            return EXCELLENT;
        }
        if (power >= GOOD.minimumPower) {
            return GOOD;
        }
        if (power >= ORDINARY.minimumPower) {
            return ORDINARY;
        }
        return POOR;
    }
}
