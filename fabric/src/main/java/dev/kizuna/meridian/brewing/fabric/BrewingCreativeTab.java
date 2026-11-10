package dev.kizuna.meridian.brewing.fabric;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/** 炼药独占一页创造栏：器具、工具和药材在前，枯木在后。药水由原版食物与饮品页按注册表列出。 */
public final class BrewingCreativeTab {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "brewing");
    private static final List<String> ITEM_ORDER = List.of(
            "herb_knife",
            "brew_recipe",
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
                .displayItems((context, output) -> entries().forEach(output::accept))
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ID, tab);
    }

    private static List<ItemLike> entries() {
        // 搭巫釜结构要用的器具排最前，便于直接取用。
        var result = new ArrayList<ItemLike>();
        result.add(BrewingBlocks.WITCH_CAULDRON);
        result.add(BrewingBlocks.COPPER_CAULDRON);
        result.add(BrewingBlocks.IRON_BRAZIER);
        result.add(BrewingBlocks.IRON_STOVE);
        result.add(BrewingBlocks.HERB_CRATE);
        ITEM_ORDER.forEach(path -> result.add(BrewingItems.item(path)));
        var logs = BrewingBlocks.deadLogs();
        var woods = BrewingBlocks.deadWoods();
        for (BrewingBlocks.DeadWood wood : BrewingBlocks.DeadWood.values()) {
            result.add(logs.get(wood));
            result.add(woods.get(wood));
        }
        return List.copyOf(result);
    }
}
