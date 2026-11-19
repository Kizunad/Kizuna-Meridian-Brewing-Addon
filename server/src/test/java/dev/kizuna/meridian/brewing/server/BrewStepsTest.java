package dev.kizuna.meridian.brewing.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.kizuna.meridian.brewing.protocol.pb.BrewStep;
import dev.kizuna.meridian.brewing.protocol.pb.BrewTimeline;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceClock;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceOrders;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceRequest;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceView;
import java.io.IOException;
import java.util.List;
import org.junit.Test;

public final class BrewStepsTest {
    @Test
    public void allRecipesMatchTimedDosesHeatAndBatchSize() throws IOException {
        for (var recipe : BrewRecipes.all().values()) {
            for (var vessel : VesselKind.values()) {
                if (vessel == VesselKind.AUTOMATIC_FURNACE && recipe.kind().elixir()) {
                    continue;
                }
                for (var form : BrewForm.values()) {
                    var use = form == BrewForm.DRINK ? BrewUse.DRINK : BrewUse.SPLASH;
                    if (recipe.kind().supports(use)) {
                        checkTimeline(recipe, vessel, form);
                    }
                }
            }
        }
    }

    private static void checkTimeline(BrewRecipe recipe, VesselKind vessel, BrewForm form) throws IOException {
        var timeline = BrewSteps.timeline(recipe, vessel, form);
        assertEquals(timeline, BrewTimeline.parseFrom(timeline.toByteArray()));
        var steps = timeline.getStepsList();
        var plan = recipe.ingredients();
        int sets = vessel.batch().sets();
        assertEquals(sets, step(steps, "PRIMARY").getCount());
        assertTrue(step(steps, "PRIMARY").getItem().endsWith(":" + plan.preparedPrimary()));
        assertEquals(BrewCauldron.BASE_INGREDIENT_ID, step(steps, "BASE").getItem());
        assertEquals(sets, step(steps, "BASE").getCount());
        checkTimed(step(steps, "ACCESSORY"), plan.accessory(), plan.accessoryWindow(), sets);
        checkTimed(step(steps, "CATALYST"), plan.catalyst(), plan.catalystWindow(), sets);
        assertEquals(form == BrewForm.SPLASH, steps.stream().anyMatch(s -> s.getAction().equals("PROPELLANT")));
        if (form == BrewForm.SPLASH) {
            assertEquals(sets, step(steps, "PROPELLANT").getCount());
        }
        var steady = step(steps, "STEADY");
        assertEquals(recipe.fireKind().name(), steady.getFire());
        assertEquals(recipe.heatBand().minimumDegrees(), steady.getLowTemperature(), 0);
        assertEquals(recipe.heatBand().maximumDegrees(), steady.getHighTemperature(), 0);
        assertEquals(BrewScoring.SOAK_SECONDS, step(steps, "SOAK").getToSeconds(), 0);
        assertEquals(BrewCauldron.BREW_SECONDS, step(steps, "COOL").getFromSeconds(), 0);
        assertEquals(vessel.batch().bottles(), steps.getLast().getCount());
    }

    private static void checkTimed(BrewStep step, String item, TimingWindow window, int sets) {
        assertEquals("minecraft:" + item, step.getItem());
        assertEquals(sets, step.getCount());
        assertEquals(window.startSeconds(), step.getFromSeconds(), 0);
        assertEquals(window.endSeconds(), step.getToSeconds(), 0);
    }

    private static BrewStep step(List<BrewStep> steps, String action) {
        return steps.stream().filter(step -> step.getAction().equals(action)).findFirst().orElseThrow();
    }

    @Test
    public void requestAndViewPreservePresenceAndLongCounters() throws IOException {
        var choice = BrewSteps.choice(BrewKind.BITTER_YELLOW, BrewForm.SPLASH);
        var request = FurnaceRequest.newBuilder()
                .setMenu(1)
                .setVersion(Long.MAX_VALUE)
                .setSelect(choice)
                .build();
        assertEquals(request, FurnaceRequest.parseFrom(request.toByteArray()));
        var orders = FurnaceOrders.newBuilder()
                .setNext(choice)
                .setVersion(Long.MAX_VALUE)
                .build();
        var delta = FurnaceView.newBuilder()
                .setMenu(1)
                .setOrders(orders)
                .setClock(FurnaceClock.getDefaultInstance())
                .build();
        var decoded = FurnaceView.parseFrom(delta.toByteArray());
        assertEquals(delta, decoded);
        assertTrue(decoded.hasClock() && !decoded.hasCatalog() && !decoded.getClock().hasWorker());
    }
}
