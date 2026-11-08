package dev.kizuna.meridian.brewing.fabric;

import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** 炮制药材、药渣、药方和药刀的物品身份。具体交互由服务端宿主接入。 */
final class BrewingItems {
    private static final Map<String, Item> ITEMS = new TreeMap<>();

    private BrewingItems() {
        // 物品只通过模组初始化注册。
    }

    static void register() {
        registerPrepared("withered_bush_ash");
        registerPrepared("dry_moss");
        registerPrepared("dry_poppy");
        registerPrepared("dried_pufferfish");
        registerPrepared("poison_potato_slice");
        registerPrepared("berry_mash");
        registerItem("brew_residue", new Item.Properties());
        registerItem("brew_recipe", new Item.Properties().stacksTo(1));
        registerItem("herb_knife", new Item.Properties().stacksTo(1).durability(128));
    }

    static Map<String, Item> all() {
        return Map.copyOf(ITEMS);
    }

    private static void registerPrepared(String path) {
        registerItem(path, new Item.Properties());
    }

    private static void registerItem(String path, Item.Properties properties) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        Item item = Registry.register(BuiltInRegistries.ITEM, id, new Item(properties.setId(itemKey)));
        ITEMS.put(path, item);
    }
}
