package dev.kizuna.meridian.brewing.fabric;

import com.google.gson.JsonObject;
import dev.kizuna.backpack3d.item.ItemMetadata;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * 本仓物品在 BS3D 里的重量，交给负重附属 {@code bs3d_weights} 读。克数是占位值。
 *
 * <p>熬药卷轴虽在本仓命名空间下，却是功法库按目录生成的物品，这里不登记；瓶装巫药是原版药水物品，也不登记。</p>
 */
final class BrewingWeights {
    static final Identifier WEIGHT_FIELD = Identifier.fromNamespaceAndPath("bs3d_weights", "weight");
    private static final int DEAD_WOOD_GRAMS = 1500;
    private static final Map<String, Integer> GRAMS = Map.ofEntries(
            Map.entry("withered_bush_ash", 20),
            Map.entry("dry_moss", 150),
            Map.entry("dry_poppy", 10),
            Map.entry("dried_pufferfish", 200),
            Map.entry("poison_potato_slice", 50),
            Map.entry("berry_mash", 80),
            Map.entry("brew_residue", 100),
            Map.entry("brew_recipe", 10),
            Map.entry("herb_knife", 300),
            Map.entry("herb_crate", 2500),
            Map.entry("copper_cauldron", 6000),
            Map.entry("witch_cauldron", 8000),
            Map.entry("iron_brazier", 5000),
            Map.entry("iron_stove", 12000));

    private BrewingWeights() {
    }

    /** 方块和物品都登记完以后调用。 */
    static void register() {
        for (Map.Entry<Item, Integer> entry : grams().entrySet()) {
            JsonObject weight = new JsonObject();
            weight.addProperty("grams", entry.getValue());
            ItemMetadata.register(entry.getKey(), "", Map.of(WEIGHT_FIELD, weight));
        }
    }

    /** 登记了重量的物品和克数。 */
    static Map<Item, Integer> grams() {
        Map<Item, Integer> grams = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : GRAMS.entrySet()) {
            Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, entry.getKey());
            Item item = BuiltInRegistries.ITEM.getOptional(id)
                    .orElseThrow(() -> new IllegalStateException("重量表里的物品没有登记：" + id));
            grams.put(item, entry.getValue());
        }
        for (Block log : BrewingBlocks.deadLogs().values()) {
            grams.put(log.asItem(), DEAD_WOOD_GRAMS);
        }
        for (Block wood : BrewingBlocks.deadWoods().values()) {
            grams.put(wood.asItem(), DEAD_WOOD_GRAMS);
        }
        return Map.copyOf(grams);
    }
}
