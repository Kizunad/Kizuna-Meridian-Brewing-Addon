package dev.kizuna.meridian.brewing.server;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** 首版六张药方；数值集中于此，后续试熬只需改配置来源。 */
public final class BrewRecipes {
    private static final Map<BrewKind, BrewRecipe> RECIPES = buildRecipes();

    private BrewRecipes() {
        // 药方只通过静态登记表读取。
    }

    public static Map<BrewKind, BrewRecipe> all() {
        // 调用方只能读取药方，不能改动服务端规则。
        return RECIPES;
    }

    public static BrewRecipe require(BrewKind kind) {
        // 缺少药方属于内容注册错误，不能把它当成默认药方。
        BrewRecipe recipe = RECIPES.get(kind);
        if (recipe == null) {
            throw new IllegalArgumentException("没有登记药方：" + kind);
        }
        return recipe;
    }

    public static Optional<BrewRecipe> forPreparedPrimary(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Optional.empty();
        }
        return RECIPES.values().stream()
                .filter(recipe -> sameItemId(recipe.ingredients().preparedPrimary(), itemId))
                .findFirst();
    }

    public static boolean sameItemId(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        if (expected.equals(actual)) {
            return true;
        }
        return itemPath(expected).equals(itemPath(actual));
    }

    private static String itemPath(String itemId) {
        int separator = itemId.indexOf(':');
        if (separator < 0) {
            return itemId;
        }
        return itemId.substring(separator + 1);
    }

    private static Map<BrewKind, BrewRecipe> buildRecipes() {
        // 这些火候带和时窗沿用旧稿占位，报告会明确标记为可调参数。
        var recipes = new EnumMap<BrewKind, BrewRecipe>(BrewKind.class);
        recipes.put(BrewKind.DEPLETION, recipe(BrewKind.DEPLETION, 92, 100,
                "withered_bush_ash", "fermented_spider_eye", 6, 10,
                "wither_rose", 20, 24));
        recipes.put(BrewKind.BITTER_YELLOW, recipe(BrewKind.BITTER_YELLOW, 82, 90,
                "dry_moss", "honey_bottle", 6, 10,
                "cobweb", 18, 22));
        recipes.put(BrewKind.CHARM, recipe(BrewKind.CHARM, 80, 88,
                "dry_poppy", "glow_berries", 8, 12,
                "amethyst_shard", 22, 26));
        recipes.put(BrewKind.ARMOR_ROT, recipe(BrewKind.ARMOR_ROT, 90, 98,
                "poison_potato_slice", "redstone", 6, 10,
                "dragon_breath", 20, 24));
        recipes.put(BrewKind.INSECT_LURE, recipe(BrewKind.INSECT_LURE, 84, 92,
                "berry_mash", "sugar", 6, 10,
                "honeycomb", 18, 22));
        recipes.put(BrewKind.QI_GATHER, recipe(BrewKind.QI_GATHER, 86, 94,
                "dried_pufferfish", "prismarine_shard", 8, 12,
                "nautilus_shell", 22, 26));
        return Collections.unmodifiableMap(recipes);
    }

    private static BrewRecipe recipe(BrewKind kind, double minimum, double maximum,
                                     String primary, String accessory, double accessoryStart,
                                     double accessoryEnd, String catalyst, double catalystStart,
                                     double catalystEnd) {
        // 统一构造药方，避免六份配置在时窗和火种校验上出现分叉；真元要求这轮一律不设门槛。
        var band = new HeatBand(minimum, maximum);
        var ingredients = new IngredientPlan(primary, accessory,
                new TimingWindow(accessoryStart, accessoryEnd), catalyst,
                new TimingWindow(catalystStart, catalystEnd));
        return new BrewRecipe(kind, kind.fireKind(), band, ingredients, BrewQiRule.NONE);
    }
}
