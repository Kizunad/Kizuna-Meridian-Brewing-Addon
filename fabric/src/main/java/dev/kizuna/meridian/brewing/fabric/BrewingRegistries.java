package dev.kizuna.meridian.brewing.fabric;

import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.fabricmc.fabric.api.registry.FuelValueEvents;
import net.fabricmc.fabric.api.registry.StrippableBlockRegistry;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/** 枯木的燃料和易燃属性注册。 */
final class BrewingRegistries {
    private static final int DEAD_WOOD_FLAME = 15;
    private static final int DEAD_WOOD_BURN = 10;
    private static final int DEAD_WOOD_FUEL_TICKS = 400;

    private BrewingRegistries() {
        // 注册回调只安装一次。
    }

    static void register() {
        FlammableBlockRegistry flammable = FlammableBlockRegistry.getDefaultInstance();
        Map<BrewingBlocks.DeadWood, Block> deadLogs = BrewingBlocks.deadLogs();
        Map<BrewingBlocks.DeadWood, Block> deadWoods = BrewingBlocks.deadWoods();
        for (var block : deadLogs.values()) {
            flammable.add(block, DEAD_WOOD_FLAME, DEAD_WOOD_BURN);
        }
        for (var block : deadWoods.values()) {
            flammable.add(block, DEAD_WOOD_FLAME, DEAD_WOOD_BURN);
        }
        registerStripping(deadLogs, deadWoods);
        FuelValueEvents.BUILD.register((builder, context) -> {
            for (var block : deadLogs.values()) {
                builder.add(block, DEAD_WOOD_FUEL_TICKS);
            }
            for (var block : deadWoods.values()) {
                builder.add(block, DEAD_WOOD_FUEL_TICKS);
            }
        });
    }

    private static void registerStripping(Map<BrewingBlocks.DeadWood, Block> deadLogs,
                                          Map<BrewingBlocks.DeadWood, Block> deadWoods) {
        for (BrewingBlocks.DeadWood wood : BrewingBlocks.DeadWood.values()) {
            Block strippedLog = vanillaBlock("stripped_" + wood.path() + "_log");
            Block strippedWood = vanillaBlock("stripped_" + wood.path() + "_wood");
            StrippableBlockRegistry.registerCopyState(deadLogs.get(wood), strippedLog);
            StrippableBlockRegistry.registerCopyState(deadWoods.get(wood), strippedWood);
        }
    }

    private static Block vanillaBlock(String path) {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", path);
        return BuiltInRegistries.BLOCK.getOptional(id)
                .orElseThrow(() -> new IllegalStateException("缺少原版去皮方块：" + id));
    }
}
