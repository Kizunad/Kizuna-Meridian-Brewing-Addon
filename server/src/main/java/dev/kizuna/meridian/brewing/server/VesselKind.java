package dev.kizuna.meridian.brewing.server;

/** 一阶巫釜和二阶铜釜的容量、导热及添水参数。 */
public enum VesselKind {
    WITCH_CAULDRON(3, 0.12, 15.0, 1.0),
    COPPER_CAULDRON(4, 0.20, 10.0, 1.3);

    private final int waterCapacity;
    private final double convergencePerSecond;
    private final double waterCoolingDegrees;
    private final double qiCost;

    VesselKind(int waterCapacity, double convergencePerSecond, double waterCoolingDegrees, double qiCost) {
        this.waterCapacity = waterCapacity;
        this.convergencePerSecond = convergencePerSecond;
        this.waterCoolingDegrees = waterCoolingDegrees;
        this.qiCost = qiCost;
    }

    public int waterCapacity() {
        return waterCapacity;
    }

    public double convergencePerSecond() {
        return convergencePerSecond;
    }

    public double waterCoolingDegrees() {
        return waterCoolingDegrees;
    }

    public double qiCost() {
        return qiCost;
    }
}
