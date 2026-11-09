package dev.kizuna.meridian.brewing.server;

/** 肾脏排毒档位及对持续药效的时间倍率。 */
public enum DetoxTier {
    HARMLESS(0.0),
    EXCELLENT(0.7),
    GOOD(1.0),
    ORDINARY(1.25),
    POOR(1.5);

    private final double durationMultiplier;

    DetoxTier(double durationMultiplier) {
        // 档位只定义持续倍率，排毒力界线由配置提供。
        this.durationMultiplier = durationMultiplier;
    }

    public double durationMultiplier() {
        // 无害档用零表示免除持续部分，单次腐蚀由药效计划另行保留。
        return durationMultiplier;
    }

    static DetoxTier fromPower(double power) {
        // 纯规则调用使用安装配置的默认内容。
        return fromPower(power, BrewThresholds.DEFAULT.kidneys());
    }

    public static DetoxTier fromPower(double power, BrewThresholds.Kidneys thresholds) {
        // 上界使用开区间，档位界线由宿主配置提供。
        if (power >= thresholds.harmless()) {
            return HARMLESS;
        }
        if (power >= thresholds.excellent()) {
            return EXCELLENT;
        }
        if (power >= thresholds.good()) {
            return GOOD;
        }
        if (power >= thresholds.ordinary()) {
            return ORDINARY;
        }
        return POOR;
    }
}
