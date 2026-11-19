package dev.kizuna.meridian.brewing.server;

import dev.kizuna.meridian.brewing.protocol.pb.BrewStep;
import dev.kizuna.meridian.brewing.protocol.pb.BrewTimeline;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceChoice;

/** 药卷和药炉共用的演示；温度触发的动作不虚构固定秒数。 */
public final class BrewSteps {
    private BrewSteps() {}

    public static FurnaceChoice choice(BrewKind kind, BrewForm form) {
        return FurnaceChoice.newBuilder()
                .setRecipe(kind.path())
                .setForm(form.name())
                .build();
    }

    public static BrewForm defaultForm(BrewKind kind) {
        return kind.supports(BrewUse.DRINK) ? BrewForm.DRINK : BrewForm.SPLASH;
    }

    public static BrewTimeline timeline(BrewRecipe recipe, VesselKind vessel, BrewForm form) {
        var choice = choice(recipe.kind(), form);
        var timeline = BrewTimeline.newBuilder()
                .setChoice(choice)
                .setVessel(vessel.name());
        var ingredients = recipe.ingredients();
        int sets = vessel.batch().sets();
        timeline.addSteps(item("WATER", "BEFORE_START", "minecraft:potion", vessel.waterCapacity()));
        timeline.addSteps(item("PRIMARY", "AFTER_WATER", itemId(recipe, IngredientSlot.PRIMARY), sets));
        var soak = heat("SOAK", "WITH_PRIMARY", recipe, BrewFireState.MIN_LEVEL)
                .setLowTemperature(BrewCauldron.MIN_SOAK_TEMPERATURE)
                .setHighTemperature(BrewCauldron.MAX_SOAK_TEMPERATURE)
                .setToSeconds(BrewScoring.SOAK_SECONDS);
        timeline.addSteps(soak);
        timeline.addSteps(item("BASE", "BEFORE_START", BrewCauldron.BASE_INGREDIENT_ID, sets));
        if (form == BrewForm.SPLASH) {
            timeline.addSteps(item("PROPELLANT", "BEFORE_START", BrewCauldron.PROPELLANT_INGREDIENT_ID, sets));
        }
        timeline.addSteps(heat("START", "READY", recipe, BrewFireState.MAX_LEVEL));
        var band = recipe.heatBand();
        var steady = heat("STEADY", "IN_BAND", recipe, steadyGrade(band))
                .setLowTemperature(band.minimumDegrees())
                .setHighTemperature(band.maximumDegrees());
        timeline.addSteps(steady);
        String accessory = itemId(recipe, IngredientSlot.ACCESSORY);
        timeline.addSteps(timed("ACCESSORY", accessory, sets, ingredients.accessoryWindow()));
        String catalyst = itemId(recipe, IngredientSlot.CATALYST);
        timeline.addSteps(timed("CATALYST", catalyst, sets, ingredients.catalystWindow()));
        var cool = heat("COOL", "BREWED", recipe, 0)
                .setFromSeconds(BrewCauldron.BREW_SECONDS)
                .setLowTemperature(BrewCauldron.MIN_SOAK_TEMPERATURE)
                .setHighTemperature(BrewCauldron.COLLECTION_MAX_TEMPERATURE);
        timeline.addSteps(cool);
        timeline.addSteps(item("COLLECT", "COOLED", "minecraft:glass_bottle", vessel.batch().bottles()));
        return timeline.build();
    }

    public static String itemId(BrewRecipe recipe, IngredientSlot slot) {
        var plan = recipe.ingredients();
        return switch (slot) {
            case PRIMARY -> {
                String namespace = recipe.kind().elixir() ? "minecraft:" : "meridian_brewing:";
                yield namespace + plan.preparedPrimary();
            }
            case BASE -> BrewCauldron.BASE_INGREDIENT_ID;
            case PROPELLANT -> BrewCauldron.PROPELLANT_INGREDIENT_ID;
            case ACCESSORY -> "minecraft:" + plan.accessory();
            case CATALYST -> "minecraft:" + plan.catalyst();
        };
    }

    private static int steadyGrade(HeatBand band) {
        // 在炉座的真实平衡温度中取最靠近目标带中心的档位。
        double middle = (band.minimumDegrees() + band.maximumDegrees()) / 2;
        double closest = Double.POSITIVE_INFINITY;
        int best = BrewFireState.MIN_LEVEL;
        for (int grade = BrewFireState.MIN_LEVEL; grade <= BrewFireState.MAX_LEVEL; grade++) {
            double target = BrewFireState.stoveHeatSource(FireKind.SUN_FIRE, grade).equilibriumTemperature();
            double distance = Math.abs(target - middle);
            if (distance < closest) {
                closest = distance;
                best = grade;
            }
        }
        return best;
    }

    private static BrewStep.Builder item(String action, String when, String id, int count) {
        return BrewStep.newBuilder()
                .setAction(action)
                .setCondition(when)
                .setItem(id)
                .setCount(count);
    }

    private static BrewStep.Builder timed(String action, String id, int sets, TimingWindow window) {
        return item(action, "BREW_SECONDS", id, sets)
                .setFromSeconds(window.startSeconds())
                .setToSeconds(window.endSeconds());
    }

    private static BrewStep.Builder heat(String action, String when, BrewRecipe recipe, int grade) {
        return BrewStep.newBuilder()
                .setAction(action)
                .setCondition(when)
                .setFire(recipe.fireKind().name())
                .setGrade(grade);
    }

    public static String current(BrewCauldron pot, BrewRecipe recipe, BrewForm form) {
        return switch (pot.state()) {
            case EMPTY -> "WATER";
            case WATER -> "PRIMARY";
            case RAW -> beforeStart(pot, form);
            case BREWING -> brewing(pot, recipe.ingredients());
            case BREWED -> pot.temperature() > BrewCauldron.COLLECTION_MAX_TEMPERATURE ? "COOL" : "COLLECT";
            case SCORCHED -> "SCORCHED";
        };
    }

    private static String beforeStart(BrewCauldron pot, BrewForm form) {
        if (pot.needed(IngredientSlot.PRIMARY) > 0) {
            return "PRIMARY";
        }
        if (pot.needed(IngredientSlot.BASE) > 0) {
            if (pot.soakSeconds() < BrewScoring.SOAK_SECONDS) {
                return "SOAK";
            }
            return "BASE";
        }
        if (form == BrewForm.SPLASH && pot.needed(IngredientSlot.PROPELLANT) > 0) {
            return "PROPELLANT";
        }
        return "START";
    }

    private static String brewing(BrewCauldron pot, IngredientPlan plan) {
        double elapsed = pot.brewElapsedSeconds();
        if (plan.catalystWindow().contains(elapsed) && pot.needed(IngredientSlot.CATALYST) > 0) {
            return "CATALYST";
        }
        if (plan.accessoryWindow().contains(elapsed) && pot.needed(IngredientSlot.ACCESSORY) > 0) {
            return "ACCESSORY";
        }
        return "STEADY";
    }
}
