package dev.kizuna.meridian.brewing.server;

/** 一件盔甲在本次蚀甲 tick 中应扣除的耐久。 */
public record ArmorRotResult(ArmorMaterialKind material, int damagePoints, double armorMultiplier) {
    public ArmorRotResult {
        if (material == null || damagePoints < 0 || !Double.isFinite(armorMultiplier)) {
            throw new IllegalArgumentException("蚀甲结果不完整");
        }
    }
}
