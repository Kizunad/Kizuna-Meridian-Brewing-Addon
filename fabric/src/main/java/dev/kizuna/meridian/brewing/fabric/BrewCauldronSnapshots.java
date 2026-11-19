package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.CopperCauldronState;
import dev.kizuna.meridian.brewing.server.IngredientBatch;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import dev.kizuna.meridian.brewing.server.PreparationKind;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** 读写釜方块实体的熬制进度、投料记录与铜釜外观。 */
final class BrewCauldronSnapshots {
    private BrewCauldronSnapshots() {
    }

    /** 把一只釜的快照写进存档。 */
    static void write(ValueOutput output, BrewCauldron.Snapshot snapshot) {
        output.putString("state", snapshot.state().name());
        output.putString("form", snapshot.form().name());
        output.putString("recipe_kind", enumName(snapshot.recipeKind()));
        output.putInt("water_units", snapshot.waterUnits());
        output.putDouble("temperature", snapshot.temperature());
        output.putDouble("soak_seconds", snapshot.soakSeconds());
        output.putDouble("brew_elapsed_seconds", snapshot.brewElapsedSeconds());
        output.putDouble("in_band_seconds", snapshot.inBandSeconds());
        output.putDouble("overheat_seconds", snapshot.overheatSeconds());
        output.putDouble("evaporation_seconds", snapshot.evaporationSeconds());
        output.putDouble("heat_phase_seconds", snapshot.heatPhaseSeconds());
        output.putInt("scorch_count", snapshot.scorchCount());
        output.putInt("remaining_bottles", snapshot.remainingBottles());
        output.putString("copper_oxidation", snapshot.copperState().oxidation().name());
        output.putBoolean("copper_waxed", snapshot.copperState().waxed());
        writeIngredients(output, snapshot.ingredients());
        writeIngredientTimes(output, snapshot.ingredientTimes());
    }

    /** 读一只釜的快照；没写铜釜字段的数据沿用放置时的外观。 */
    static BrewCauldron.Snapshot read(ValueInput input, CopperCauldronState placed) {
        BrewCauldronState state = enumValue(input.getStringOr("state", "EMPTY"),
                BrewCauldronState.EMPTY);
        BrewForm form = enumValue(input.getStringOr("form", "DRINK"), BrewForm.DRINK);
        BrewKind recipeKind = enumValueOrNull(input.getStringOr("recipe_kind", ""), BrewKind.class);
        // 没写铜釜字段的数据（如手写 NBT）沿用放置时按方块外观得到的氧化级。
        CopperCauldronState copperState = new CopperCauldronState(
                enumValue(input.getStringOr("copper_oxidation", ""), placed.oxidation()),
                input.getBooleanOr("copper_waxed", placed.waxed()));
        Map<IngredientSlot, IngredientBatch> ingredients = readIngredients(input);
        Map<IngredientSlot, Double> ingredientTimes = readIngredientTimes(input);
        return new BrewCauldron.Snapshot(state, form, recipeKind, ingredients, ingredientTimes,
                input.getIntOr("water_units", 0), input.getDoubleOr("temperature", 0.0),
                input.getDoubleOr("soak_seconds", 0.0),
                input.getDoubleOr("brew_elapsed_seconds", 0.0),
                input.getDoubleOr("in_band_seconds", 0.0),
                input.getDoubleOr("overheat_seconds", 0.0),
                input.getDoubleOr("evaporation_seconds", 0.0),
                input.getDoubleOr("heat_phase_seconds", 0.0),
                input.getIntOr("scorch_count", 0), input.getIntOr("remaining_bottles", 0),
                copperState);
    }

    private static Map<IngredientSlot, IngredientBatch> readIngredients(ValueInput input) {
        Map<IngredientSlot, IngredientBatch> ingredients = new EnumMap<>(IngredientSlot.class);
        for (ValueInput child : input.childrenListOrEmpty("ingredients")) {
            IngredientSlot slot = enumValueOrNull(child.getStringOr("slot", ""), IngredientSlot.class);
            if (slot != null) {
                ingredients.put(slot, readBatch(child));
            }
        }
        return ingredients;
    }

    private static Map<IngredientSlot, Double> readIngredientTimes(ValueInput input) {
        Map<IngredientSlot, Double> times = new EnumMap<>(IngredientSlot.class);
        for (ValueInput child : input.childrenListOrEmpty("ingredient_times")) {
            IngredientSlot slot = enumValueOrNull(child.getStringOr("slot", ""), IngredientSlot.class);
            if (slot != null) {
                times.put(slot, child.getDoubleOr("seconds", 0.0));
            }
        }
        return times;
    }

    /** 读一份料。 */
    static IngredientBatch readBatch(ValueInput input) {
        String itemId = input.getStringOr("item_id", "");
        PreparationKind preparation = enumValue(input.getStringOr("preparation", "RAW"),
                PreparationKind.RAW);
        long preparedDay = input.getLongOr("prepared_game_day", -1L);
        int count = input.getIntOr("count", 1);
        return new IngredientBatch(itemId, preparation, preparedDay, count);
    }

    private static void writeIngredients(ValueOutput output,
                                         Map<IngredientSlot, IngredientBatch> ingredients) {
        ValueOutput.ValueOutputList list = output.childrenList("ingredients");
        for (IngredientSlot slot : IngredientSlot.values()) {
            IngredientBatch batch = ingredients.get(slot);
            if (batch == null) {
                continue;
            }
            ValueOutput child = list.addChild();
            child.putString("slot", slot.name());
            writeBatch(child, batch);
        }
    }

    private static void writeIngredientTimes(ValueOutput output,
                                             Map<IngredientSlot, Double> ingredientTimes) {
        ValueOutput.ValueOutputList list = output.childrenList("ingredient_times");
        for (IngredientSlot slot : IngredientSlot.values()) {
            Double seconds = ingredientTimes.get(slot);
            if (seconds == null) {
                continue;
            }
            ValueOutput child = list.addChild();
            child.putString("slot", slot.name());
            child.putDouble("seconds", seconds);
        }
    }

    /** 写一份料。 */
    static void writeBatch(ValueOutput output, IngredientBatch batch) {
        output.putString("item_id", batch.itemId());
        output.putString("preparation", batch.preparation().name());
        output.putLong("prepared_game_day", batch.preparedGameDay());
        output.putInt("count", batch.count());
    }

    static String enumName(Enum<?> value) {
        if (value == null) {
            return "";
        }
        return value.name();
    }

    static <T extends Enum<T>> T enumValue(String name, T fallback) {
        T value = enumValueOrNull(name, fallback.getDeclaringClass());
        return value == null ? fallback : value;
    }

    static <T extends Enum<T>> T enumValueOrNull(String name, Class<T> type) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
