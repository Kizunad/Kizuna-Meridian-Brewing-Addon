package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.protocol.pb.FurnaceCatalog;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceClock;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceDose;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceOrders;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceView;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewRecipe;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewSteps;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import java.util.Set;

/** 先按观看者的知识裁去不可见内容，再比较变化；清空一组同样会发包。 */
final class FurnaceReadout {
    private FurnaceReadout() {}

    static FurnaceView view(int menu, BrewStation ports, Set<BrewKind> known) {
        var batch = ports.batch();
        var catalog = FurnaceCatalog.newBuilder().setVessel(batch.vessel.name());
        for (var kind : BrewKind.values()) {
            if (known.contains(kind) && batch.allows(kind)) {
                catalog.addVisible(kind.path());
            }
        }
        var orders = FurnaceOrders.newBuilder().setVersion(batch.version);
        var actual = batch.cauldron.recipe();
        if (batch.next != null && known.contains(batch.next.kind())) {
            orders.setNext(BrewSteps.choice(batch.next.kind(), batch.next.form()));
        }
        boolean hidden = false;
        if (actual.isPresent()) {
            var kind = actual.orElseThrow().kind();
            hidden = !known.contains(kind);
            if (!hidden) {
                orders.setActual(BrewSteps.choice(kind, effectiveForm(batch)));
            }
        } else if (batch.next != null) {
            hidden = !known.contains(batch.next.kind());
        }
        orders.setBusy(actual.isPresent() || batch.next != null);
        var clock = FurnaceClock.newBuilder().setPhase(batch.cauldron.state().name());
        if (!hidden) {
            orders.setBatch(batch.batchNumber);
            fill(clock, ports, actual.orElse(null));
        }
        return FurnaceView.newBuilder()
                .setMenu(menu)
                .setFull(true /* full */)
                .setCatalog(catalog)
                .setOrders(orders)
                .setClock(clock)
                .build();
    }

    private static BrewForm effectiveForm(FurnaceBatch batch) {
        if (batch.active != null) {
            return batch.active.form();
        }
        var state = batch.cauldron.state();
        if (batch.next != null && state != BrewCauldronState.BREWING && state != BrewCauldronState.BREWED
                && batch.ingredientsMatch(batch.next)) {
            return batch.next.form();
        }
        var kind = batch.cauldron.recipe().orElseThrow().kind();
        if (!kind.supports(BrewUse.DRINK)) {
            return BrewForm.SPLASH;
        }
        return batch.cauldron.form();
    }

    private static void fill(FurnaceClock.Builder clock, BrewStation ports, BrewRecipe actual) {
        var batch = ports.batch();
        var pot = batch.cauldron;
        int grade = ports.grade();
        int water = pot.waterUnits();
        var fire = ports.fireKind();
        clock.setFire(fire.name())
                .setManual(batch.manualBrew())
                .setGrade(grade)
                .setFuel(ports.fuelSeconds())
                .setContinuousFire(ports.continuousFire())
                .setWater(water)
                .setBottles(pot.remainingBottles())
                .setTemperature(pot.temperature())
                .setElapsed(pot.brewElapsedSeconds());
        BrewRecipe recipe = actual;
        if (recipe == null && batch.next != null) {
            recipe = BrewRecipes.require(batch.next.kind());
        }
        if (!batch.waiting.isEmpty()) {
            clock.addMissing(batch.waiting);
        }
        clock.setReclaimable(actual != null && batch.active == null && pot.state() == BrewCauldronState.RAW);
        if (water == 0) {
            clock.addMissing("water");
        }
        if (grade == 0 || recipe != null && recipe.fireKind() != fire) {
            clock.addMissing("fire");
        }
        if (recipe == null) {
            clock.addMissing("recipe");
            return;
        }
        var form = effectiveForm(batch);
        clock.setCurrentStep(BrewSteps.current(pot, recipe, form));
        doses(clock, batch, recipe, form);
    }

    private static void doses(FurnaceClock.Builder clock, FurnaceBatch batch, BrewRecipe recipe, BrewForm form) {
        int sets = batch.vessel.batch().sets();
        var pot = batch.cauldron;
        double elapsed = pot.brewElapsedSeconds();
        for (var slot : IngredientSlot.values()) {
            if (slot == IngredientSlot.PROPELLANT && form == BrewForm.DRINK) {
                continue;
            }
            int missing = pot.needed(slot);
            var dose = FurnaceDose.newBuilder()
                    .setSlot(slot.name())
                    .setItem(BrewSteps.itemId(recipe, slot))
                    .setAdded(sets - missing)
                    .setRequired(sets);
            var timing = recipe.ingredients().window(slot);
            if (missing > 0 && timing.isPresent()) {
                var window = timing.orElseThrow();
                dose.setMissed(elapsed > window.endSeconds());
                dose.setLateAllowed(dose.getMissed() && pot.state() == BrewCauldronState.BREWING
                        && batch.active == null && batch.vessel.portable());
                if (pot.state() == BrewCauldronState.BREWING && window.contains(elapsed)) {
                    clock.addMissing(slot.name());
                }
                if (!dose.getMissed() && clock.getWindowEnd() == 0 && (pot.state() == BrewCauldronState.BREWING
                        || pot.state() == BrewCauldronState.RAW || pot.state() == BrewCauldronState.WATER)) {
                    clock.setWindowStart(window.startSeconds())
                            .setWindowEnd(window.endSeconds());
                }
            } else if (missing > 0) {
                clock.addMissing(slot.name());
            }
            clock.addDoses(dose);
        }
    }

    static FurnaceView changes(FurnaceView previous, FurnaceView current) {
        if (previous == null) {
            return current;
        }
        var update = FurnaceView.newBuilder().setMenu(current.getMenu());
        if (!previous.getCatalog().equals(current.getCatalog())) {
            update.setCatalog(current.getCatalog());
        }
        if (!previous.getOrders().equals(current.getOrders())) {
            update.setOrders(current.getOrders());
        }
        if (!previous.getClock().equals(current.getClock())) {
            update.setClock(current.getClock());
        }
        return update.build();
    }
}
