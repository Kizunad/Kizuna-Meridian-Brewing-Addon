package dev.kizuna.meridian.brewing.server;

/**
 * 药塔开不了工、停着等的原因；窗口按位掩码显示缺什么，顺序就是开工前查的顺序。
 */
public enum TowerNeed {
    CHEST,
    PRIMARY,
    BASE,
    PROPELLANT,
    ACCESSORY,
    GLASS,
    FUEL,
    FIRE_STARTER,
    AMBIENT_QI;

    /** 窗口同步用的位。 */
    public int bit() {
        return 1 << ordinal();
    }
}
