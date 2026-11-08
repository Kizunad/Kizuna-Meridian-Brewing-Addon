package dev.kizuna.meridian.brewing.server;

/** 蚀甲水面对的原版与 26.2 铜甲材质。 */
public enum ArmorMaterialKind {
    LEATHER(1.0),
    CHAINMAIL(1.0),
    IRON(1.0),
    DIAMOND(1.0),
    GOLD(0.0),
    NETHERITE(0.5),
    COPPER(1.0),
    OTHER(1.0);

    private final double corrosionMultiplier;

    ArmorMaterialKind(double corrosionMultiplier) {
        this.corrosionMultiplier = corrosionMultiplier;
    }

    public double corrosionMultiplier() {
        return corrosionMultiplier;
    }
}
