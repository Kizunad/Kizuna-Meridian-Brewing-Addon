package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewFireSource;
import dev.kizuna.meridian.brewing.server.VesselKind;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** 巫釜、火源、药材箱和 26.2 九种枯木的方块身份。 */
final class BrewingBlocks {
    private static final float WITCH_CAULDRON_STRENGTH = 1.0F;
    private static final float COPPER_CAULDRON_STRENGTH = 2.0F;
    private static final float METALWARE_STRENGTH = 2.0F;
    private static final float HERB_CRATE_STRENGTH = 1.5F;
    private static final float DEAD_WOOD_STRENGTH = 1.0F;
    private static final Map<DeadWood, Block> DEAD_LOGS = new EnumMap<>(DeadWood.class);
    private static final Map<DeadWood, Block> DEAD_WOODS = new EnumMap<>(DeadWood.class);
    static Block WITCH_CAULDRON;
    static Block COPPER_CAULDRON;
    static Block IRON_BRAZIER;
    static Block IRON_STOVE;
    static Block HERB_CRATE;
    static BlockEntityType<BrewCauldronBlockEntity> BREW_CAULDRON_ENTITY;
    static BlockEntityType<BrewFireBlockEntity> BREW_FIRE_ENTITY;
    static BlockEntityType<HerbCrateBlockEntity> HERB_CRATE_ENTITY;

    private BrewingBlocks() {
        // 方块和对应物品只注册一次。
    }

    static void register() {
        WITCH_CAULDRON = registerBlock("witch_cauldron",
                new BrewCauldronBlock(VesselKind.WITCH_CAULDRON,
                        blockProperties("witch_cauldron", WITCH_CAULDRON_STRENGTH)));
        COPPER_CAULDRON = registerBlock("copper_cauldron",
                new BrewCauldronBlock(VesselKind.COPPER_CAULDRON,
                        blockProperties("copper_cauldron", COPPER_CAULDRON_STRENGTH)));
        IRON_BRAZIER = registerBlock("iron_brazier",
                new BrewFireBlock(BrewFireSource.BRAZIER,
                        blockProperties("iron_brazier", METALWARE_STRENGTH)));
        IRON_STOVE = registerBlock("iron_stove",
                new BrewFireBlock(BrewFireSource.STOVE,
                        blockProperties("iron_stove", METALWARE_STRENGTH)));
        HERB_CRATE = registerBlock("herb_crate",
                new HerbCrateBlock(blockProperties("herb_crate", HERB_CRATE_STRENGTH)));
        registerDeadWood();
        BREW_CAULDRON_ENTITY = registerCauldronEntity();
        BREW_FIRE_ENTITY = registerFireEntity();
        HERB_CRATE_ENTITY = registerCrateEntity();
    }

    static Map<DeadWood, Block> deadLogs() {
        return Map.copyOf(DEAD_LOGS);
    }

    static Map<DeadWood, Block> deadWoods() {
        return Map.copyOf(DEAD_WOODS);
    }

    private static void registerDeadWood() {
        for (DeadWood wood : DeadWood.values()) {
            String logPath = "dead_" + wood.path() + "_log";
            String woodPath = "dead_" + wood.path() + "_wood";
            DEAD_LOGS.put(wood, registerBlock(logPath,
                    new RotatedPillarBlock(blockProperties(logPath, DEAD_WOOD_STRENGTH))));
            DEAD_WOODS.put(wood, registerBlock(woodPath,
                    new RotatedPillarBlock(blockProperties(woodPath, DEAD_WOOD_STRENGTH))));
        }
    }

    private static BlockEntityType<BrewCauldronBlockEntity> registerCauldronEntity() {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "brew_cauldron");
        BlockEntityType<BrewCauldronBlockEntity> type = FabricBlockEntityTypeBuilder.create(
                (pos, state) -> new BrewCauldronBlockEntity(
                        pos, state, state.is(COPPER_CAULDRON) ? VesselKind.COPPER_CAULDRON
                                : VesselKind.WITCH_CAULDRON),
                WITCH_CAULDRON, COPPER_CAULDRON).build();
        return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id, type);
    }

    private static BlockEntityType<BrewFireBlockEntity> registerFireEntity() {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "brew_fire");
        BlockEntityType<BrewFireBlockEntity> type = FabricBlockEntityTypeBuilder.create(
                (pos, state) -> new BrewFireBlockEntity(pos, state,
                        state.is(IRON_BRAZIER) ? BrewFireSource.BRAZIER : BrewFireSource.STOVE),
                IRON_BRAZIER, IRON_STOVE).build();
        return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id, type);
    }

    private static BlockEntityType<HerbCrateBlockEntity> registerCrateEntity() {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "herb_crate");
        BlockEntityType<HerbCrateBlockEntity> type = FabricBlockEntityTypeBuilder.create(
                HerbCrateBlockEntity::new, HERB_CRATE).build();
        return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id, type);
    }

    private static Block registerBlock(String path, Block block) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        Registry.register(BuiltInRegistries.BLOCK, id, block);
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        Item.Properties properties = new Item.Properties().setId(itemKey);
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, properties));
        return block;
    }

    private static BlockBehaviour.Properties blockProperties(String path, float strength) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
        return BlockBehaviour.Properties.of().strength(strength).setId(blockKey);
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
