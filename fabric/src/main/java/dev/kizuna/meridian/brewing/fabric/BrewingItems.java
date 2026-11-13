package dev.kizuna.meridian.brewing.fabric;

import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** 炮制药材、药渣、药方、《熬药总纲》和药刀的物品身份。具体交互由服务端宿主接入。 */
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
        registerCustomItem("brew_recipe", new RecipeScrollItem(itemProperties("brew_recipe")
                .stacksTo(1)
                .component(BrewingComponents.RECIPE_KIND, "depletion")));
        registerCustomItem("brew_manual", new BrewManualItem(itemProperties("brew_manual")
                .stacksTo(1)));
        registerCustomItem("herb_knife", new HerbKnifeItem(itemProperties("herb_knife")
                .stacksTo(1)
                .durability(128)));
    }

    static Map<String, Item> all() {
        return Map.copyOf(ITEMS);
    }

    static Item item(String path) {
        Item item = ITEMS.get(path);
        if (item == null) {
            throw new IllegalArgumentException("未登记物品：" + path);
        }
        return item;
    }

    private static void registerPrepared(String path) {
        registerCustomItem(path, new PreparedIngredientItem(itemProperties(path)));
    }

    private static void registerItem(String path, Item.Properties properties) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        Item item = Registry.register(BuiltInRegistries.ITEM, id, new Item(properties.setId(itemKey)));
        ITEMS.put(path, item);
    }

    private static Item.Properties itemProperties(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        return new Item.Properties().setId(itemKey);
    }

    private static void registerCustomItem(String path, Item item) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        ITEMS.put(path, Registry.register(BuiltInRegistries.ITEM, id, item));
    }
}
