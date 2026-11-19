package dev.kizuna.meridian.brewing.server;

public enum VesselKind {
    COPPER_CAULDRON(new Batch(3, 2, 1, 60), 0.12, 15.0, 1.0),
    WITCH_CAULDRON(new Batch(2, 1, 1, 60), 0.20, 10.0, 1.3),
    SMALL_FURNACE(new Batch(6, 6, 2, 60), 0.20, 10.0, 2.0),
    MEDIUM_FURNACE(new Batch(10, 10, 3, 75), 0.20, 10.0, 3.0),
    AUTOMATIC_FURNACE(new Batch(16, 16, 4, 90), 0.20, 10.0, 4.0);

    public record Batch(int water, int bottles, int sets, int fuelSeconds) {}

    public static VesselKind forTier(int tier) {
        return switch (tier) {
            case 1 -> SMALL_FURNACE;
            case 2 -> MEDIUM_FURNACE;
            case 3 -> AUTOMATIC_FURNACE;
            default -> throw new IllegalArgumentException("未知药炉等级 " + tier);
        };
    }

    public Batch batch() {
        return batch;
    }

    private final Batch batch;
    private final double convergencePerSecond;
    private final double waterCoolingDegrees;
    private final double qiCost;

    VesselKind(Batch batch, double convergencePerSecond, double waterCoolingDegrees, double qiCost) {
        this.batch = batch;
        this.convergencePerSecond = convergencePerSecond;
        this.waterCoolingDegrees = waterCoolingDegrees;
        this.qiCost = qiCost;
    }

    public int waterCapacity() {
        return batch.water();
    }

    public double convergencePerSecond() {
        return convergencePerSecond;
    }

    public double convergencePerSecond(CopperOxidation oxidation) {
        if (this != COPPER_CAULDRON) {
            return convergencePerSecond;
        }
        if (oxidation == null) {
            throw new IllegalArgumentException("铜釜氧化状态不能为空");
        }
        return oxidation.convergencePerSecond();
    }

    public double waterCoolingDegrees() {
        return waterCoolingDegrees;
    }

    public double qiCost() {
        return qiCost;
    }
}
