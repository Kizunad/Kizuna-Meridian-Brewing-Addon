package dev.kizuna.meridian.brewing.fabric;

import net.fabricmc.api.ModInitializer;

/** 炼药 26.2 Fabric 注册入口；世界行为仍由宿主接口接入。 */
public final class BrewingFabric implements ModInitializer {
    public static final String MOD_ID = "meridian_brewing";

    @Override
    public void onInitialize() {
        BrewingComponents.register();
        BrewingItems.register();
        BrewingEffects.register();
        BrewingPotions.register();
        BrewingBlocks.register();
        BrewingRegistries.register();
        PreparationInteractions.register();
        BrewingRuntime.register();
    }
}
