package dev.kizuna.meridian.brewing.client;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.locale.Language;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/**
 * 把协议里的标识换成给玩家看的名字和图标：药方、剂型、阶段、火种、炉型、步骤、物品。
 *
 * <p>协议传的是枚举名和注册 ID，这里一律查语言文件；语言文件里没有的标识显示成「不明」，不把标识本身露给玩家。</p>
 */
final class BrewNames {
    static final String SCREEN = "screen.meridian_brewing.";
    private static final String MESSAGE = "message.meridian_brewing.";
    private static final String WATER_ITEM = "minecraft:potion";
    /** 炉型用结构名或方块名，和世界里看到的叫法一致。 */
    private static final Map<String, String> VESSELS = Map.of(
            "SMALL_FURNACE", "structure.meridian_brewing.cauldron-v2-lv1",
            "MEDIUM_FURNACE", "structure.meridian_brewing.cauldron-v2-lv2",
            "AUTOMATIC_FURNACE", "structure.meridian_brewing.cauldron-v2-lv3-auto",
            "WITCH_CAULDRON", "block.meridian_brewing.witch_cauldron",
            "COPPER_CAULDRON", "block.meridian_brewing.copper_cauldron");

    private BrewNames() {
        // 只有静态入口。
    }

    static Component recipe(String path) {
        // 药名和药水物品、书页篇名共用一个键。
        Optional<BrewKind> kind = RecipeKnowledge.kind(path);
        if (kind.isEmpty()) {
            return unknown();
        }
        return Component.translatable(BrewCauldron.recipeName(kind.orElseThrow()).key());
    }

    static Component form(String form) {
        return known(SCREEN + "furnace.form." + lower(form));
    }

    static Component phase(String phase) {
        return known(MESSAGE + "state." + lower(phase));
    }

    static Component fire(String fire) {
        return known(MESSAGE + "fire." + lower(fire));
    }

    static Component slot(String slot) {
        return known(MESSAGE + "slot." + lower(slot));
    }

    static Component step(String action) {
        return known(SCREEN + "step." + lower(action));
    }

    static Component vessel(String vessel) {
        return known(VESSELS.getOrDefault(vessel, ""));
    }

    /** 药方列表里的图标：这张方熬出来的那瓶药，能喝的画饮用瓶，只能泼的画喷溅瓶。 */
    static ItemStack potion(String path) {
        Optional<BrewKind> kind = RecipeKnowledge.kind(path);
        if (kind.isEmpty()) {
            return ItemStack.EMPTY;
        }
        BrewKind known = kind.orElseThrow();
        BrewUse use = known.supports(BrewUse.DRINK) ? BrewUse.DRINK : BrewUse.SPLASH;
        return BrewItemStacks.create(known, BrewQuality.COMMON, use);
    }

    /** 步骤和材料表里的物品图标；添水那一步传的是药水物品，画成水瓶。 */
    static ItemStack item(String id) {
        if (id.equals(WATER_ITEM)) {
            return PotionContents.createItemStack(Items.POTION, Potions.WATER);
        }
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null) {
            return ItemStack.EMPTY;
        }
        return BuiltInRegistries.ITEM.getOptional(parsed).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    /** 秒数、度数这类数：整数不带小数点，其余留一位小数。 */
    static String number(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static Component known(String key) {
        if (!Language.getInstance().has(key)) {
            return unknown();
        }
        return Component.translatable(key);
    }

    private static Component unknown() {
        return Component.translatable(SCREEN + "unknown");
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
