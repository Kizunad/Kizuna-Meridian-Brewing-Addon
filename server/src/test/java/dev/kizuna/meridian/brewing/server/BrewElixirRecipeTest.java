package dev.kizuna.meridian.brewing.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** 五行药方沿用同一釜规则完成下料、熬制和瓶装收成。 */
public final class BrewElixirRecipeTest {
    private static final int WATER_UNITS = 3;
    private static final double BREW_SECONDS = 30;
    private static final double COOLING_SECONDS = 1.25;
    private static final double SOAK_SECONDS = 10;
    private static final List<RecipeCase> RECIPES = List.of(
            new RecipeCase(BrewKind.FIRE_ELIXIR, "blaze_powder", "magma_cream", "fire_charge",
                    HeatSourceKind.SUN_BRAZIER_3),
            new RecipeCase(BrewKind.METAL_ELIXIR, "iron_nugget", "quartz", "gold_nugget",
                    HeatSourceKind.SUN_BRAZIER_3),
            new RecipeCase(BrewKind.WOOD_ELIXIR, "moss_block", "glow_berries", "vine",
                    HeatSourceKind.SUN_BRAZIER_2),
            new RecipeCase(BrewKind.WATER_ELIXIR, "packed_ice", "dried_kelp", "prismarine_crystals",
                    HeatSourceKind.SOUL_BRAZIER_2),
            new RecipeCase(BrewKind.EARTH_ELIXIR, "clay_ball", "bone_meal", "brown_mushroom",
                    HeatSourceKind.SUN_BRAZIER_2));

    @Test
    public void allFiveRecipesAcceptTheirIngredientsAndYieldDrinkableBottles() {
        // 独立列出每味药的原料，避免误改药方表时同时改变期望值。
        for (RecipeCase recipe : RECIPES) {
            brew(recipe);
        }
    }

    private static void brew(RecipeCase expected) {
        // 完整推进熬药过程，检查用法门禁、主料识别、辅料和引药及最终成药品质。
        var cauldron = new BrewCauldron(VesselKind.WITCH_CAULDRON, BrewForm.DRINK);
        var heat = BrewStructure.built(expected.heat());
        cauldron.addWater(WATER_UNITS);
        assertTrue(cauldron.addIngredient(IngredientSlot.PRIMARY,
                IngredientBatch.prepared("minecraft:" + expected.primary(), 0)).accepted());
        BrewRecipe recipe = cauldron.recipe().orElseThrow();
        assertEquals(expected.kind(), recipe.kind());
        cauldron.addIngredient(IngredientSlot.BASE, IngredientBatch.raw("minecraft:glowstone_dust"));
        assertTrue(!cauldron.addIngredient(IngredientSlot.PROPELLANT,
                IngredientBatch.raw("minecraft:gunpowder")).accepted());
        HeatSourceKind soaking = HeatSourceKind.SUN_BRAZIER_1;
        if (expected.kind().fireKind() == FireKind.SOUL_FIRE) {
            soaking = HeatSourceKind.SOUL_BRAZIER_1;
        }
        cauldron.tick(SOAK_SECONDS, BrewStructure.built(soaking));
        assertTrue(cauldron.startBrew(heat).accepted());
        double accessoryTime = recipe.ingredients().accessoryWindow().startSeconds();
        double catalystTime = recipe.ingredients().catalystWindow().startSeconds();
        cauldron.tick(accessoryTime, heat);
        assertTrue(cauldron.addIngredient(IngredientSlot.ACCESSORY,
                IngredientBatch.raw("minecraft:" + expected.accessory())).accepted());
        cauldron.tick(catalystTime - accessoryTime, heat);
        assertTrue(cauldron.addIngredient(IngredientSlot.CATALYST,
                IngredientBatch.raw("minecraft:" + expected.catalyst())).accepted());
        cauldron.tick(BREW_SECONDS - catalystTime, heat);
        assertEquals(BrewCauldronState.BREWED, cauldron.state());
        cauldron.tick(COOLING_SECONDS, BrewStructure.built(HeatSourceKind.NONE));
        var result = cauldron.collectBottle(0);
        assertEquals(BrewCollectionKind.POTION, result.kind());
        assertEquals(expected.kind().name(), Optional.of(BrewQuality.SUPERIOR), result.score().orElseThrow().quality());
        assertEquals(java.util.Set.of(BrewUse.DRINK), expected.kind().uses());
    }

    private record RecipeCase(BrewKind kind, String primary, String accessory, String catalyst, HeatSourceKind heat) { }
}
