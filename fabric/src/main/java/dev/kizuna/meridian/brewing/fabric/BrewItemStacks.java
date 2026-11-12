package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.TooltipDisplay;

/** 品质药水的创建和服务端解析 API，以及药方里写的料名换成物品 ID。 */
public final class BrewItemStacks {
    private BrewItemStacks() {
        // 物品组件访问只通过静态方法。
    }

    /** 药方里的料名换成物品 ID：写全了就照写；只写路径的先找本仓物品，再按原版物品。 */
    public static Identifier recipeItemId(String recipeItem) {
        // 药方的料名是规则层的字符串，不带注册表；本仓有同名物品时以本仓为准。
        if (recipeItem.indexOf(':') >= 0) {
            return Identifier.parse(recipeItem);
        }
        Identifier own = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, recipeItem);
        if (BuiltInRegistries.ITEM.containsKey(own)) {
            return own;
        }
        return Identifier.withDefaultNamespace(recipeItem);
    }

    public static ItemStack create(BrewKind kind, BrewQuality quality, BrewUse use) {
        if (kind == null || quality == null || use == null || !kind.supports(use)) {
            throw new IllegalArgumentException("巫药用法不受支持");
        }
        Item item = Items.SPLASH_POTION;
        if (use == BrewUse.DRINK) {
            item = Items.POTION;
        }
        ItemStack stack = PotionContents.createItemStack(item, BrewingPotions.potion(kind));
        stack.set(BrewingComponents.QUALITY, quality.componentValue());
        // 登记用的显示效果只有一秒，原版那行会写成「(0:01)」；藏掉它，药效由本仓的提示写。
        TooltipDisplay tooltip = TooltipDisplay.DEFAULT.withHidden(DataComponents.POTION_CONTENTS, true);
        stack.set(DataComponents.TOOLTIP_DISPLAY, tooltip);
        return stack;
    }

    public static BrewQuality quality(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("空物品没有巫药品质");
        }
        Integer value = stack.get(BrewingComponents.QUALITY);
        if (value == null) {
            throw new IllegalArgumentException("物品没有巫药品质组件");
        }
        return BrewQuality.fromComponentValue(value);
    }

    public static BrewKind kind(ItemStack stack) {
        // 没有 Potion 内容和有内容但不是本仓的药，各自报清楚。
        Optional<Holder<Potion>> potion = potion(stack);
        if (potion.isEmpty()) {
            throw new IllegalArgumentException("物品没有巫药 Potion 内容");
        }
        return registeredKind(potion.orElseThrow())
                .orElseThrow(() -> new IllegalArgumentException("不是本仓登记的巫药"));
    }

    /** 是不是本仓的品质巫药：本仓登记的 Potion，加上认得的品质值。只看组件，不靠抛异常判断。 */
    public static boolean isBrew(ItemStack stack) {
        // 提示框每帧、酿造台放料、手持物事实都会问，非巫药最常见，先看最便宜的品质组件。
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Integer quality = stack.get(BrewingComponents.QUALITY);
        if (quality == null || !BrewQuality.isComponentValue(quality)) {
            return false;
        }
        return potion(stack).flatMap(BrewItemStacks::registeredKind).isPresent();
    }

    private static Optional<Holder<Potion>> potion(ItemStack stack) {
        // 物品上的 Potion 内容；没有组件或者是空药水都算没有。
        if (stack == null) {
            return Optional.empty();
        }
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return Optional.empty();
        }
        return contents.potion();
    }

    private static Optional<BrewKind> registeredKind(Holder<Potion> potion) {
        // 和本仓登记的每一种巫药 Potion 逐个比。
        for (Map.Entry<BrewKind, Holder.Reference<Potion>> entry : BrewingPotions.all().entrySet()) {
            if (potion.equals(entry.getValue())) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }
}
