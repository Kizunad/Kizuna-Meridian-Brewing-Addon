package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

/** 品质药水的创建和服务端解析 API。 */
public final class BrewItemStacks {
    private BrewItemStacks() {
        // 物品组件访问只通过静态方法。
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
        PotionContents contents = null;
        if (stack != null) {
            contents = stack.get(DataComponents.POTION_CONTENTS);
        }
        if (contents == null || contents.potion().isEmpty()) {
            throw new IllegalArgumentException("物品没有巫药 Potion 内容");
        }
        Holder<Potion> potion = contents.potion().orElseThrow();
        for (Map.Entry<BrewKind, Holder.Reference<Potion>> entry : BrewingPotions.all().entrySet()) {
            if (potion.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("不是本仓登记的巫药");
    }

    public static boolean isBrew(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            kind(stack);
            quality(stack);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
