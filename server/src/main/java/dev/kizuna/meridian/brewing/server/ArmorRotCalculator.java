package dev.kizuna.meridian.brewing.server;

/** 把蚀甲曲线折算为原版耐久扣减；真正写回物品由 Fabric 宿主负责。 */
public final class ArmorRotCalculator {
    private static final double MINIMUM_DURABILITY_DAMAGE = 1.0;

    private ArmorRotCalculator() {
        // 耐久计算不保存实体状态。
    }

    public static ArmorRotResult damage(ArmorRotProfile profile, ArmorMaterialKind material,
                                        int maximumDurability, double elapsedSeconds) {
        if (profile == null || material == null || maximumDurability <= 0
                || !Double.isFinite(elapsedSeconds) || elapsedSeconds < 0) {
            throw new IllegalArgumentException("蚀甲输入不完整");
        }
        double rawDamage = maximumDurability * profile.durabilityPercentPerSecond()
                * elapsedSeconds * material.corrosionMultiplier();
        int damagePoints = 0;
        if (material != ArmorMaterialKind.GOLD) {
            damagePoints = (int) Math.max(MINIMUM_DURABILITY_DAMAGE, Math.ceil(rawDamage));
        }
        return new ArmorRotResult(material, damagePoints, profile.armorMultiplier());
    }
}
