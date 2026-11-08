package dev.kizuna.meridian.brewing.test;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 炼药模组的真实 Fabric 注册、Potion 内容和品质组件检查。 */
public final class BrewingGameTest {
    private static final int DEFAULT_TIMEOUT = 100;
    private static final String MOD_ID = "meridian_brewing";
    private static final List<String> EFFECT_IDS = List.of(
            "depletion", "armor_rot", "insect_lure", "qi_gather", "bitter_yellow",
            "charm_stagger", "charm_control");
    private static final List<String> POTION_IDS = List.of(
            "depletion", "bitter_yellow", "armor_rot", "insect_lure", "qi_gather", "charm");
    private static final List<String> COMPONENT_IDS = List.of(
            "quality", "prepared_day", "recipe_kind");
    private static final List<String> BLOCK_IDS = List.of(
            "witch_cauldron", "copper_cauldron",
            "dead_oak_log", "dead_oak_wood",
            "dead_spruce_log", "dead_spruce_wood",
            "dead_birch_log", "dead_birch_wood",
            "dead_jungle_log", "dead_jungle_wood",
            "dead_acacia_log", "dead_acacia_wood",
            "dead_dark_oak_log", "dead_dark_oak_wood",
            "dead_mangrove_log", "dead_mangrove_wood",
            "dead_cherry_log", "dead_cherry_wood",
            "dead_pale_oak_log", "dead_pale_oak_wood");
    private static final List<String> ITEM_IDS = List.of(
            "withered_bush_ash", "dry_moss", "dry_poppy", "dried_pufferfish",
            "poison_potato_slice", "berry_mash", "brew_residue", "brew_recipe", "herb_knife");

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void registersEffectsAndPotions(GameTestHelper context) {
        assertRegistered(context, BuiltInRegistries.MOB_EFFECT, EFFECT_IDS, "状态效果");
        assertRegistered(context, BuiltInRegistries.POTION, POTION_IDS, "Potion");
        assertRegistered(context, BuiltInRegistries.DATA_COMPONENT_TYPE, COMPONENT_IDS, "数据组件");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void registersCauldronsDeadWoodAndPreparedItems(GameTestHelper context) {
        assertRegistered(context, BuiltInRegistries.BLOCK, BLOCK_IDS, "方块");
        assertRegistered(context, BuiltInRegistries.ITEM, ITEM_IDS, "炮制物品");
        for (String path : BLOCK_IDS) {
            assertItemRegistered(context, path, "方块物品");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void potionContentsRoundTripKindAndQuality(GameTestHelper context) {
        for (BrewKind kind : BrewKind.values()) {
            BrewUse use = preferredUse(kind);
            ItemStack stack = BrewItemStacks.create(kind, BrewQuality.SUPERIOR, use);
            context.assertTrue(BrewItemStacks.isBrew(stack), "药水未被识别为巫药：" + kind);
            context.assertTrue(BrewItemStacks.kind(stack) == kind, "Potion 种类解析错误：" + kind);
            context.assertTrue(BrewItemStacks.quality(stack) == BrewQuality.SUPERIOR,
                    "品质组件解析错误：" + kind);
            Item expectedItem = expectedItem(use);
            context.assertTrue(stack.is(expectedItem), "药水物品类型错误：" + kind);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void everyQualityHasStableComponentValue(GameTestHelper context) {
        for (BrewQuality quality : BrewQuality.values()) {
            ItemStack stack = BrewItemStacks.create(
                    BrewKind.BITTER_YELLOW, quality, BrewUse.DRINK);
            context.assertTrue(BrewItemStacks.quality(stack) == quality,
                    "品质组件往返失败：" + quality);
        }
        context.succeed();
    }

    private static <T> void assertRegistered(GameTestHelper context,
                                             net.minecraft.core.Registry<T> registry,
                                             List<String> paths,
                                             String kind) {
        for (String path : paths) {
            Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, path);
            context.assertTrue(registry.containsKey(id), kind + "未注册：" + id);
        }
    }

    private static void assertItemRegistered(GameTestHelper context, String path, String kind) {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, path);
        context.assertTrue(BuiltInRegistries.ITEM.containsKey(id), kind + "未注册：" + id);
    }

    private static BrewUse preferredUse(BrewKind kind) {
        if (kind.supports(BrewUse.DRINK)) {
            return BrewUse.DRINK;
        }
        return BrewUse.SPLASH;
    }

    private static Item expectedItem(BrewUse use) {
        if (use == BrewUse.DRINK) {
            return Items.POTION;
        }
        return Items.SPLASH_POTION;
    }
}
