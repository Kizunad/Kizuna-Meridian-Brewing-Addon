package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTabOutput;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.ItemLike;

/**
 * 炼药独占一页创造栏：器具、药刀、六张药方、《熬药总纲》和药材在前，枯木其次，最后是每种巫药每种形态的四档品质。
 *
 * <p>原版会按药水登记在食物与饮品、战斗页和搜索页里列出巫药的普通、喷溅、滞留药水和药箭。
 * 这些没有品质组件，喝了不起作用，所以从所有页里拿掉。</p>
 */
public final class BrewingCreativeTab {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "brewing");
    // 瓶装的只有内服和喷溅两种；涂、掷、扇、雾是功法对同一瓶药的用法。
    private static final List<BrewUse> BOTTLED_USES = List.of(BrewUse.DRINK, BrewUse.SPLASH);
    private static final List<String> MATERIAL_ORDER = List.of(
            "withered_bush_ash",
            "dry_moss",
            "dry_poppy",
            "dried_pufferfish",
            "poison_potato_slice",
            "berry_mash",
            "brew_residue");

    private BrewingCreativeTab() {
        // 创造栏只在模组初始化时注册一次。
    }

    static void register() {
        // 方块和物品都已注册后才建页，条目直接取注册时的实例。
        CreativeModeTab tab = FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.meridian_brewing.brewing"))
                .icon(() -> new ItemStack(BrewingBlocks.WITCH_CAULDRON))
                .displayItems((context, output) -> {
                    apparatus().forEach(output::accept);
                    output.accept(BrewingItems.item("herb_knife"));
                    recipes().forEach(output::accept);
                    output.accept(BrewingItems.item("brew_manual"));
                    materials().forEach(output::accept);
                    qualityBrews().forEach(output::accept);
                })
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ID, tab);
        CreativeModeTabEvents.MODIFY_OUTPUT_ALL.register(BrewingCreativeTab::removeQualitylessBrews);
    }

    /** 7 种形态 × 4 档品质，和熬出来的药是同一种物品数据。 */
    static List<ItemStack> qualityBrews() {
        List<ItemStack> stacks = new ArrayList<>();
        for (BrewKind kind : BrewKind.values()) {
            for (BrewUse use : BOTTLED_USES) {
                if (kind.supports(use)) {
                    addQualities(stacks, kind, use);
                }
            }
        }
        return List.copyOf(stacks);
    }

    /** 每个药方一张，和女巫掉落、小屋箱子里的同一份物品数据。 */
    static List<ItemStack> recipes() {
        List<ItemStack> stacks = new ArrayList<>();
        for (BrewKind kind : BrewKind.values()) {
            stacks.add(RecipeScrollItem.stack(kind));
        }
        return List.copyOf(stacks);
    }

    /** 原版按登记自动列出的巫药：药水内容是本仓的药，但没有品质组件。 */
    static boolean qualitylessBrew(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null || contents.potion().isEmpty() || stack.has(BrewingComponents.QUALITY)) {
            return false;
        }
        return BrewingPotions.all().containsValue(contents.potion().orElseThrow());
    }

    private static void addQualities(List<ItemStack> stacks, BrewKind kind, BrewUse use) {
        for (BrewQuality quality : BrewQuality.values()) {
            stacks.add(BrewItemStacks.create(kind, quality, use));
        }
    }

    private static void removeQualitylessBrews(CreativeModeTab tab, FabricCreativeModeTabOutput output) {
        output.getDisplayStacks().removeIf(BrewingCreativeTab::qualitylessBrew);
        output.getSearchTabStacks().removeIf(BrewingCreativeTab::qualitylessBrew);
    }

    private static List<ItemLike> apparatus() {
        // 搭巫釜结构要用的器具排最前，便于直接取用。
        return List.of(BrewingBlocks.WITCH_CAULDRON, BrewingBlocks.COPPER_CAULDRON, BrewingBlocks.IRON_BRAZIER,
                BrewingBlocks.IRON_STOVE, BrewingBlocks.HERB_CRATE);
    }

    private static List<ItemLike> materials() {
        // 药材、药渣，后面接枯木原木和木头。
        var result = new ArrayList<ItemLike>();
        MATERIAL_ORDER.forEach(path -> result.add(BrewingItems.item(path)));
        var logs = BrewingBlocks.deadLogs();
        var woods = BrewingBlocks.deadWoods();
        for (BrewingBlocks.DeadWood wood : BrewingBlocks.DeadWood.values()) {
            result.add(logs.get(wood));
            result.add(woods.get(wood));
        }
        return List.copyOf(result);
    }
}
