package dev.kizuna.meridian.brewing.server;

import java.util.Collections;
import java.util.Map;

/** 六种主料的炮制规则；具体配方同时由 Fabric 数据包提供。 */
public final class PreparationRecipes {
    private static final double CAMPFIRE_SECONDS = 30.0;
    private static final double SMOKING_SECONDS = 5.0;
    private static final double DAYLIGHT_SECONDS = 1_200.0;
    private static final double KNIFE_SECONDS = 1.0;
    private static final double GRINDSTONE_SECONDS = 2.0;
    private static final Map<String, PreparationRecipe> RECIPES = Map.of(
            "minecraft:short_grass", new PreparationRecipe(
                    "minecraft:short_grass", "meridian_brewing:withered_bush_ash",
                    PreparationMethod.CAMPFIRE_BAKE, CAMPFIRE_SECONDS),
            "minecraft:moss_block", new PreparationRecipe(
                    "minecraft:moss_block", "meridian_brewing:dry_moss",
                    PreparationMethod.SUN_DRY, DAYLIGHT_SECONDS),
            "minecraft:poppy", new PreparationRecipe(
                    "minecraft:poppy", "meridian_brewing:dry_poppy",
                    PreparationMethod.SUN_DRY, DAYLIGHT_SECONDS),
            "minecraft:pufferfish", new PreparationRecipe(
                    "minecraft:pufferfish", "meridian_brewing:dried_pufferfish",
                    PreparationMethod.SMOKING, SMOKING_SECONDS),
            "minecraft:poisonous_potato", new PreparationRecipe(
                    "minecraft:poisonous_potato", "meridian_brewing:poison_potato_slice",
                    PreparationMethod.KNIFE, KNIFE_SECONDS),
            "minecraft:sweet_berries", new PreparationRecipe(
                    "minecraft:sweet_berries", "meridian_brewing:berry_mash",
                    PreparationMethod.GRINDSTONE, GRINDSTONE_SECONDS));

    private PreparationRecipes() {
        // 炮制规则只从静态表读取。
    }

    public static Map<String, PreparationRecipe> all() {
        return Collections.unmodifiableMap(RECIPES);
    }

    public static PreparationRecipe require(String inputItemId) {
        PreparationRecipe recipe = RECIPES.get(inputItemId);
        if (recipe == null) {
            throw new IllegalArgumentException("没有登记炮制配方：" + inputItemId);
        }
        return recipe;
    }
}
