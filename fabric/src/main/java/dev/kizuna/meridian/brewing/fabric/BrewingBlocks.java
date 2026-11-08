package dev.kizuna.meridian.brewing.fabric;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** 巫釜、铜釜和 26.2 九种枯木的服务端方块身份。 */
final class BrewingBlocks {
    private static final float WITCH_CAULDRON_STRENGTH = 1.0F;
    private static final float COPPER_CAULDRON_STRENGTH = 2.0F;
    private static final float DEAD_WOOD_STRENGTH = 1.0F;
    private static final Map<DeadWood, Block> DEAD_LOGS = new EnumMap<>(DeadWood.class);
    private static final Map<DeadWood, Block> DEAD_WOODS = new EnumMap<>(DeadWood.class);
    static Block WITCH_CAULDRON;
    static Block COPPER_CAULDRON;

    private BrewingBlocks() {
        // 方块和对应物品只注册一次。
    }

    static void register() {
        WITCH_CAULDRON = registerBlock("witch_cauldron", WITCH_CAULDRON_STRENGTH);
        COPPER_CAULDRON = registerBlock("copper_cauldron", COPPER_CAULDRON_STRENGTH);
        for (DeadWood wood : DeadWood.values()) {
            DEAD_LOGS.put(wood, registerBlock("dead_" + wood.path() + "_log", DEAD_WOOD_STRENGTH));
            DEAD_WOODS.put(wood, registerBlock("dead_" + wood.path() + "_wood", DEAD_WOOD_STRENGTH));
        }
    }

    static Map<DeadWood, Block> deadLogs() {
        return Map.copyOf(DEAD_LOGS);
    }

    static Map<DeadWood, Block> deadWoods() {
        return Map.copyOf(DEAD_WOODS);
    }

    private static Block registerBlock(String path, float strength) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
        Block block = new Block(BlockBehaviour.Properties.of().strength(strength).setId(blockKey));
        Registry.register(BuiltInRegistries.BLOCK, id, block);
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        Item.Properties properties = new Item.Properties().setId(itemKey);
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, properties));
        return block;
    }

    enum DeadWood {
        OAK("oak"),
        SPRUCE("spruce"),
        BIRCH("birch"),
        JUNGLE("jungle"),
        ACACIA("acacia"),
        DARK_OAK("dark_oak"),
        MANGROVE("mangrove"),
        CHERRY("cherry"),
        PALE_OAK("pale_oak");

        private final String path;

        DeadWood(String path) {
            this.path = path;
        }

        String path() {
            return path;
        }
    }
}
