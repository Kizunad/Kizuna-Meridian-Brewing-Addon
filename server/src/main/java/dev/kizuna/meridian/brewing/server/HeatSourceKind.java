package dev.kizuna.meridian.brewing.server;

/** 巫釜可识别的火源及其平衡温度。 */
public enum HeatSourceKind {
    NONE(null, 20.0, 0.0),
    SUN_CAMPFIRE(FireKind.SUN_FIRE, 100.0, 3.0),
    SOUL_CAMPFIRE(FireKind.SOUL_FIRE, 100.0, 3.0),
    SUN_BRAZIER_1(FireKind.SUN_FIRE, 75.0, 3.0),
    SUN_BRAZIER_2(FireKind.SUN_FIRE, 85.0, 3.0),
    SUN_BRAZIER_3(FireKind.SUN_FIRE, 93.0, 3.0),
    SUN_BRAZIER_4(FireKind.SUN_FIRE, 100.0, 3.0),
    SOUL_BRAZIER_1(FireKind.SOUL_FIRE, 75.0, 3.0),
    SOUL_BRAZIER_2(FireKind.SOUL_FIRE, 85.0, 3.0),
    SOUL_BRAZIER_3(FireKind.SOUL_FIRE, 93.0, 3.0),
    SOUL_BRAZIER_4(FireKind.SOUL_FIRE, 100.0, 3.0),
    SUN_STOVE_1(FireKind.SUN_FIRE, 75.0, 1.0),
    SUN_STOVE_2(FireKind.SUN_FIRE, 85.0, 1.0),
    SUN_STOVE_3(FireKind.SUN_FIRE, 93.0, 1.0),
    SUN_STOVE_4(FireKind.SUN_FIRE, 100.0, 1.0),
    SOUL_STOVE_1(FireKind.SOUL_FIRE, 75.0, 1.0),
    SOUL_STOVE_2(FireKind.SOUL_FIRE, 85.0, 1.0),
    SOUL_STOVE_3(FireKind.SOUL_FIRE, 93.0, 1.0),
    SOUL_STOVE_4(FireKind.SOUL_FIRE, 100.0, 1.0);

    private final FireKind fireKind;
    private final double equilibriumTemperature;
    private final double fluctuationDegrees;

    HeatSourceKind(FireKind fireKind, double equilibriumTemperature, double fluctuationDegrees) {
        this.fireKind = fireKind;
        this.equilibriumTemperature = equilibriumTemperature;
        this.fluctuationDegrees = fluctuationDegrees;
    }

    public FireKind fireKind() {
        return fireKind;
    }

    public double equilibriumTemperature() {
        return equilibriumTemperature;
    }

    public double fluctuationDegrees() {
        return fluctuationDegrees;
    }

    /** 铁炉座（以及同档的药塔火室）某个火种、某档的火源；0 档就是没火。 */
    public static HeatSourceKind stove(FireKind fireKind, int level) {
        // 火室和铁炉座用同一组 75 / 85 / 93 / 100° 数值，不另造。
        if (fireKind == FireKind.SOUL_FIRE) {
            return switch (level) {
                case 0 -> NONE;
                case 1 -> SOUL_STOVE_1;
                case 2 -> SOUL_STOVE_2;
                case 3 -> SOUL_STOVE_3;
                case 4 -> SOUL_STOVE_4;
                default -> throw new IllegalArgumentException("炉座没有这一档：" + level);
            };
        }
        return switch (level) {
            case 0 -> NONE;
            case 1 -> SUN_STOVE_1;
            case 2 -> SUN_STOVE_2;
            case 3 -> SUN_STOVE_3;
            case 4 -> SUN_STOVE_4;
            default -> throw new IllegalArgumentException("炉座没有这一档：" + level);
        };
    }
}
