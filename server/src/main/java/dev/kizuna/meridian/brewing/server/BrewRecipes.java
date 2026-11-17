package dev.kizuna.meridian.brewing.server;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** 六张巫药方与五行丹露方共用原料、火候带和时窗目录。 */
public final class BrewRecipes {
    private static final HeatBand FIRE_HEAT = new HeatBand(88, 96);
    private static final HeatBand METAL_HEAT = new HeatBand(90, 98);
    private static final HeatBand WOOD_HEAT = new HeatBand(80, 88);
    private static final HeatBand WATER_HEAT = new HeatBand(82, 90);
    private static final HeatBand EARTH_HEAT = new HeatBand(84, 92);
    private static final TimingWindow EARLY_ACCESSORY = new TimingWindow(6, 10);
    private static final TimingWindow LATE_ACCESSORY = new TimingWindow(8, 12);
    private static final TimingWindow EARLY_CATALYST = new TimingWindow(18, 22);
    private static final TimingWindow MIDDLE_CATALYST = new TimingWindow(20, 24);
    private static final TimingWindow LATE_CATALYST = new TimingWindow(22, 26);
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
        // 药方表集中声明火候带、原料和时窗。
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
        addElementElixirs(recipes);
        return Collections.unmodifiableMap(recipes);
    }

    private static void addElementElixirs(Map<BrewKind, BrewRecipe> recipes) {
        // 五种主料都是原版成品，直接按现成主料识别。
        var fire = new IngredientPlan("blaze_powder", "magma_cream", LATE_ACCESSORY,
                "fire_charge", LATE_CATALYST);
        var metal = new IngredientPlan("iron_nugget", "quartz", EARLY_ACCESSORY, "gold_nugget", MIDDLE_CATALYST);
        var wood = new IngredientPlan("moss_block", "glow_berries", LATE_ACCESSORY, "vine", LATE_CATALYST);
        var water = new IngredientPlan("packed_ice", "dried_kelp", EARLY_ACCESSORY,
                "prismarine_crystals", EARLY_CATALYST);
        var earth = new IngredientPlan("clay_ball", "bone_meal", EARLY_ACCESSORY, "brown_mushroom", EARLY_CATALYST);
        recipes.put(BrewKind.FIRE_ELIXIR, new BrewRecipe(BrewKind.FIRE_ELIXIR,
                FireKind.SUN_FIRE, FIRE_HEAT, fire, BrewQiRule.NONE));
        recipes.put(BrewKind.METAL_ELIXIR, new BrewRecipe(BrewKind.METAL_ELIXIR,
                FireKind.SUN_FIRE, METAL_HEAT, metal, BrewQiRule.NONE));
        recipes.put(BrewKind.WOOD_ELIXIR, new BrewRecipe(BrewKind.WOOD_ELIXIR,
                FireKind.SUN_FIRE, WOOD_HEAT, wood, BrewQiRule.NONE));
        recipes.put(BrewKind.WATER_ELIXIR, new BrewRecipe(BrewKind.WATER_ELIXIR,
                FireKind.SOUL_FIRE, WATER_HEAT, water, BrewQiRule.NONE));
        recipes.put(BrewKind.EARTH_ELIXIR, new BrewRecipe(BrewKind.EARTH_ELIXIR,
                FireKind.SUN_FIRE, EARTH_HEAT, earth, BrewQiRule.NONE));
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
