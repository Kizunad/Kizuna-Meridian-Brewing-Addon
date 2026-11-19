package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.IngredientBatch;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import dev.kizuna.meridian.brewing.server.VesselKind;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewFireState;
import dev.kizuna.meridian.brewing.server.BrewQiTally;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.FireKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** 炉膛共享一份余燃；整锅只使用釜的配方、投料时刻和收药状态。 */
final class FurnaceBatch {
    final VesselKind vessel;
    final BrewCauldron cauldron;
    private long fuelSeconds;
    FurnaceOrder next;
    FurnaceOrder active;
    long version;
    long batchNumber;
    BrewQiTally tally;
    FireKind fire;
    int fireLimit = BrewFireState.MAX_LEVEL;
    String waiting = "";
    final List<ItemStack> refundable = new ArrayList<>();

    boolean allows(BrewKind kind) {
        return vessel != VesselKind.AUTOMATIC_FURNACE || !kind.elixir();
    }

    boolean manualBrew() {
        var state = cauldron.state();
        return vessel.portable() && active == null
                && (state == BrewCauldronState.BREWING || state == BrewCauldronState.BREWED);
    }

    boolean ingredientsMatch(FurnaceOrder order) {
        var recipe = cauldron.recipe();
        return (recipe.isEmpty() || recipe.orElseThrow().kind() == order.kind())
                && (cauldron.form() != BrewForm.SPLASH || order.form() == BrewForm.SPLASH);
    }

    String select(ServerPlayer player, FurnaceOrder choice) {
        if (!allows(choice.kind()) || !BrewRecipeBook.lookup(player).knows(choice.kind())) {
            return "unread";
        }
        var use = choice.form() == BrewForm.DRINK ? BrewUse.DRINK : BrewUse.SPLASH;
        if (!choice.kind().supports(use)) {
            return "invalid_form";
        }
        if (active == null && cauldron.state() == BrewCauldronState.RAW && cauldron.recipe().isPresent()
                && (!ingredientsMatch(choice)
                || next != null && (next.kind() != choice.kind() || next.form() != choice.form()))) {
            return "reclaim_first";
        }
        if (!choice.equals(next)) {
            next = choice;
            version++;
        }
        return "";
    }

    boolean reclaim(ServerPlayer player) {
        if (active != null || cauldron.state() != BrewCauldronState.RAW) {
            return false;
        }
        cauldron.reclaimIngredients();
        var returned = List.copyOf(refundable);
        refundable.clear();
        version++;
        returned.forEach(stack -> BrewingItemReturns.giveOrDrop(player, stack));
        return true;
    }

    void restoreRefunds(List<ItemStack> stacks) {
        refundable.addAll(stacks);
        if (!stacks.isEmpty() || active != null || cauldron.state() != BrewCauldronState.RAW) {
            return;
        }
        // 旧半锅只存了物品、数量和炮制日，按存下来的三项还原。
        for (var dose : cauldron.ingredients().values()) {
            var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(dose.itemId()));
            var stack = new ItemStack(item, dose.count());
            if (dose.preparedGameDay() >= 0) {
                stack.set(BrewingComponents.PREPARED_DAY, dose.preparedGameDay());
            }
            refundable.add(stack);
        }
    }

    FurnaceBatch(int tier) {
        this(VesselKind.forTier(tier));
    }

    FurnaceBatch(VesselKind vessel) {
        this.vessel = vessel;
        cauldron = new BrewCauldron(vessel, BrewForm.DRINK);
        fire = defaultFire();
    }

    private FireKind defaultFire() {
        return vessel == VesselKind.MEDIUM_FURNACE ? FireKind.SOUL_FIRE : FireKind.SUN_FIRE;
    }

    long fuelSeconds() {
        return fuelSeconds;
    }

    boolean empty() {
        return fuelSeconds == 0 && version == 0 && fireLimit == BrewFireState.MAX_LEVEL
                && fire == defaultFire()
                && cauldron.state() == BrewCauldronState.EMPTY;
    }

    CompoundTag save() {
        var output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        BrewCauldronSnapshots.write(output, cauldron.snapshot());
        output.putLong("fuel_seconds", fuelSeconds);
        output.putString("fire_kind", fire.name());
        output.putInt("fire_limit", fireLimit);
        writeOrders(output);
        return output.buildResult();
    }

    void writeOrders(ValueOutput output) {
        output.putLong("order_version", version);
        output.putLong("batch_number", batchNumber);
        if (next != null) {
            next.write(output.child("next"));
        }
        if (active != null) {
            active.write(output.child("active"));
        }
        if (tally != null) {
            var qi = output.child("qi");
            qi.putDouble("total", tally.total());
            qi.putDouble("grace", tally.graceSeconds());
            qi.putInt("seconds", tally.seconds());
            qi.putDouble("ambient", tally.ambient());
            qi.putDouble("self", tally.self());
        }
    }

    void load(CompoundTag tag) {
        var input = TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag);
        cauldron.restore(BrewCauldronSnapshots.read(input, cauldron.copperState()));
        fuelSeconds = input.getLongOr("fuel_seconds", 0L);
        fire = FireKind.valueOf(input.getStringOr("fire_kind", defaultFire().name()));
        fireLimit = input.getIntOr("fire_limit", BrewFireState.MAX_LEVEL);
        readOrders(input);
    }

    void readOrders(ValueInput input) {
        version = input.getLongOr("order_version", 0L);
        batchNumber = input.getLongOr("batch_number", 0L);
        next = input.child("next").map(FurnaceOrder::read).orElse(null);
        active = input.child("active").map(FurnaceOrder::read).orElse(null);
        tally = input.child("qi").map(qi -> new BrewQiTally(qi.getDoubleOr("total", 0),
                qi.getDoubleOr("grace", 0), qi.getIntOr("seconds", 0), qi.getDoubleOr("ambient", 0),
                qi.getDoubleOr("self", 0))).orElse(null);
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
        if (accepted > 0 && fireLimit < BrewFireState.MAX_LEVEL) {
            fireLimit++;
        }
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
        if (!acceptsIngredient(slot, id)) {
            return 0;
        }
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
        if (active == null && cauldron.state() == BrewCauldronState.RAW) {
            refundable.add(stack.copyWithCount(count));
        }
        return count;
    }

    boolean acceptsIngredient(IngredientSlot slot, String id) {
        var recipe = cauldron.recipe();
        if (recipe.isPresent() && recipe.orElseThrow().ingredients().window(slot)
                .filter(window -> cauldron.brewElapsedSeconds() > window.endSeconds()).isPresent()) {
            return false;
        }
        if (active != null || next == null) {
            return true;
        }
        return switch (slot) {
            case PRIMARY -> {
                String primary = BrewRecipes.require(next.kind()).ingredients().preparedPrimary();
                yield BrewRecipes.sameItemId(primary, id);
            }
            case PROPELLANT -> next.form() == BrewForm.SPLASH;
            default -> true;
        };
    }
}
