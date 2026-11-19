package dev.kizuna.meridian.brewing.fabric;

import net.fabricmc.api.ModInitializer;

/** 炼药 26.2 Fabric 注册入口；世界行为仍由宿主接口接入。 */
public final class BrewingFabric implements ModInitializer {
    public static final String MOD_ID = "meridian_brewing";

    @Override
    public void onInitialize() {
        // 先注册物品与状态身份，再安装世界事件和必需身体宿主。
        BrewingComponents.register();
        BrewingItems.register();
        BrewingEffects.register();
        BrewingPotions.register();
        BrewingBlocks.register();
        BrewingWeights.register();
        BrewingRegistries.register();
        BrewingCreativeTab.register();
        BrewingLoot.register();
        BrewLore.register();
        PreparationInteractions.register();
        HerbKnifeDraw.register();
        BrewingRuntime.register();
        BrewRecipeCommands.register();
        BrewingDispensers.register();
        FurnacePatterns.register();
        BrewStructureMarkers.register();
        FurnaceMenu.register();
        FurnaceNetwork.register();
        BrewingFurnaces.register();
        RecipeLearnedTrigger.register();
        FurnaceBuiltTrigger.register();
        BrewBodyEffects.initialize();
    }
}
