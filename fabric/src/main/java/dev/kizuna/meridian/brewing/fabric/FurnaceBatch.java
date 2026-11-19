package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.IngredientBatch;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import dev.kizuna.meridian.brewing.server.VesselKind;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** 炉膛共享一份余燃；整锅只使用釜的配方、投料时刻和收药状态。 */
final class FurnaceBatch {
    final VesselKind vessel;
    final BrewCauldron cauldron;
    private long fuelSeconds;

    FurnaceBatch(int tier) {
        vessel = VesselKind.forTier(tier);
        cauldron = new BrewCauldron(vessel, BrewForm.DRINK);
    }

    long fuelSeconds() {
        return fuelSeconds;
    }

    boolean empty() {
        return fuelSeconds == 0 && cauldron.state() == BrewCauldronState.EMPTY;
    }

    CompoundTag save() {
        var output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        BrewCauldronSnapshots.write(output, cauldron.snapshot());
        output.putLong("fuel_seconds", fuelSeconds);
        return output.buildResult();
    }

    void load(CompoundTag tag) {
        var input = TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag);
        cauldron.restore(BrewCauldronSnapshots.read(input, cauldron.copperState()));
        fuelSeconds = input.getLongOr("fuel_seconds", 0L);
    }

    int addFuel(ItemStack stack) {
        int units = BrewFireBlockEntity.fuelUnits(stack);
        if (units == 0) {
            return 0;
        }
        int seconds = units * vessel.batch().fuelSeconds();
        long room = (Long.MAX_VALUE - fuelSeconds) / seconds;
        int accepted = (int) Math.min(room, stack.getCount());
        fuelSeconds += (long) accepted * seconds;
        return accepted;
    }

    boolean burn(int rate) {
        if (rate == 0 || fuelSeconds == 0) {
            return false;
        }
        fuelSeconds = Math.max(0, fuelSeconds - rate);
        return true;
    }

    int addIngredient(ItemStack stack, long day) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        var target = cauldron.ingredientSlotFor(id);
        if (target.isEmpty()) {
            return 0;
        }
        IngredientSlot slot = target.orElseThrow();
        int count = Math.min(stack.getCount(), cauldron.needed(slot));
        if (count == 0) {
            return 0;
        }
        IngredientBatch batch;
        if (slot == IngredientSlot.PRIMARY) {
            if (vessel == VesselKind.AUTOMATIC_FURNACE && BrewRecipes.forPreparedPrimary(id)
                    .orElseThrow().kind().elixir()) {
                return 0;
            }
            long prepared = stack.getOrDefault(BrewingComponents.PREPARED_DAY, day);
            batch = IngredientBatch.prepared(id, prepared, count);
        } else {
            batch = IngredientBatch.raw(id, count);
        }
        if (!cauldron.addIngredient(slot, batch).accepted()) {
            return 0;
        }
        return count;
    }
}
