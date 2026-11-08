package dev.kizuna.meridian.brewing.server;

/** 巫釜可识别的火源及其平衡温度。 */
public enum HeatSourceKind {
    NONE(null, 20.0, 0.0, false),
    SUN_CAMPFIRE(FireKind.SUN_FIRE, 100.0, 3.0, true),
    SOUL_CAMPFIRE(FireKind.SOUL_FIRE, 100.0, 3.0, true),
    SUN_BRAZIER_1(FireKind.SUN_FIRE, 75.0, 3.0, true),
    SUN_BRAZIER_2(FireKind.SUN_FIRE, 85.0, 3.0, true),
    SUN_BRAZIER_3(FireKind.SUN_FIRE, 93.0, 3.0, true),
    SUN_BRAZIER_4(FireKind.SUN_FIRE, 100.0, 3.0, true),
    SOUL_BRAZIER_1(FireKind.SOUL_FIRE, 75.0, 3.0, true),
    SOUL_BRAZIER_2(FireKind.SOUL_FIRE, 85.0, 3.0, true),
    SOUL_BRAZIER_3(FireKind.SOUL_FIRE, 93.0, 3.0, true),
    SOUL_BRAZIER_4(FireKind.SOUL_FIRE, 100.0, 3.0, true),
    SUN_STOVE_1(FireKind.SUN_FIRE, 75.0, 1.0, false),
    SUN_STOVE_2(FireKind.SUN_FIRE, 85.0, 1.0, false),
    SUN_STOVE_3(FireKind.SUN_FIRE, 93.0, 1.0, false),
    SUN_STOVE_4(FireKind.SUN_FIRE, 100.0, 1.0, false),
    SOUL_STOVE_1(FireKind.SOUL_FIRE, 75.0, 1.0, false),
    SOUL_STOVE_2(FireKind.SOUL_FIRE, 85.0, 1.0, false),
    SOUL_STOVE_3(FireKind.SOUL_FIRE, 93.0, 1.0, false),
    SOUL_STOVE_4(FireKind.SOUL_FIRE, 100.0, 1.0, false);

    private final FireKind fireKind;
    private final double equilibriumTemperature;
    private final double fluctuationDegrees;
    private final boolean needsHearthStones;

    HeatSourceKind(FireKind fireKind, double equilibriumTemperature, double fluctuationDegrees,
                   boolean needsHearthStones) {
        this.fireKind = fireKind;
        this.equilibriumTemperature = equilibriumTemperature;
        this.fluctuationDegrees = fluctuationDegrees;
        this.needsHearthStones = needsHearthStones;
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

    public boolean needsHearthStones() {
        return needsHearthStones;
    }
}
