package dev.kizuna.meridian.brewing.fabric;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** 炼药用到的方块、物品、实体标签。 */
final class BrewingTags {
    static final TagKey<Block> WOODEN_INPUT_CHESTS = blockTag("wooden_input_chests");
    static final TagKey<Block> BREW_CAULDRONS = blockTag("brew_cauldrons");
    static final TagKey<Block> BREW_FIRES = blockTag("brew_fires");
    static final TagKey<Item> STIRRERS = itemTag("stirrers");
    static final TagKey<Block> WITHERS_TO_DIRT = blockTag("withers_to_dirt");
    static final TagKey<Block> WITHERS_TO_NETHERRACK = blockTag("withers_to_netherrack");
    static final TagKey<Block> WITHERS_TO_DEAD_BUSH = blockTag("withers_to_dead_bush");
    static final TagKey<Block> WITHERS_TO_SMOKE = blockTag("withers_to_smoke");
    static final TagKey<EntityType<?>> SCENT_SEEKERS = entityTag("scent_seekers");
    static final TagKey<Item> ARMOR_ROT_IMMUNE = itemTag("armor_rot_immune");
    static final TagKey<Item> ARMOR_ROT_HALF = itemTag("armor_rot_half");
    static final TagKey<Item> COPPER_ARMOR = itemTag("copper_armor");

    private BrewingTags() {
        // 标签只作为注册身份，不保存运行时状态。
    }

    private static TagKey<Block> blockTag(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        return TagKey.create(Registries.BLOCK, id);
    }

    private static TagKey<EntityType<?>> entityTag(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        return TagKey.create(Registries.ENTITY_TYPE, id);
    }

    private static TagKey<Item> itemTag(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        return TagKey.create(Registries.ITEM, id);
    }
}
