package dev.kizuna.meridian.brewing.server;

import java.util.ArrayList;
import java.util.List;

/**
 * 熬药时给施放者看的一行进度：火种、温度和火候带、还剩几秒、辅料和引药的时窗、焦了几次。
 *
 * <p>时窗还没到时给出几秒后开，正开着时给出还剩几秒；已经下过或已经错过的不列。</p>
 */
public record BrewProgress(
        FireKind fireKind,
        double temperature,
        HeatBand band,
        int remainingSeconds,
        List<Window> windows,
        int scorchCount) {
    public BrewProgress {
        windows = List.copyOf(windows);
    }

    public static BrewProgress of(BrewCauldron cauldron) {
        BrewRecipe recipe = cauldron.recipe()
                .orElseThrow(() -> new IllegalStateException("没有药方的釜没有熬药进度"));
        double elapsed = cauldron.brewElapsedSeconds();
        int remaining = (int) Math.ceil(BrewCauldron.BREW_SECONDS - elapsed);
        List<Window> windows = new ArrayList<>();
        addWindow(windows, cauldron, IngredientSlot.ACCESSORY, recipe.ingredients().accessoryWindow());
        addWindow(windows, cauldron, IngredientSlot.CATALYST, recipe.ingredients().catalystWindow());
        return new BrewProgress(recipe.fireKind(), cauldron.temperature(), recipe.heatBand(),
                Math.max(0, remaining), windows, cauldron.scorchCount());
    }

    public Heat heat() {
        if (temperature < band.minimumDegrees()) {
            return Heat.LOW;
        }
        if (temperature > band.maximumDegrees()) {
            return Heat.HIGH;
        }
        return Heat.IN_BAND;
    }

    private static void addWindow(List<Window> windows, BrewCauldron cauldron, IngredientSlot slot,
                                  TimingWindow window) {
        double elapsed = cauldron.brewElapsedSeconds();
        if (cauldron.ingredients().containsKey(slot) || elapsed > window.endSeconds()) {
            return;
        }
        if (elapsed < window.startSeconds()) {
            windows.add(new Window(slot, Phase.UPCOMING, (int) Math.ceil(window.startSeconds() - elapsed)));
            return;
        }
        windows.add(new Window(slot, Phase.OPEN, (int) Math.ceil(window.endSeconds() - elapsed)));
    }

    /** 温度落在火候带下面、里面还是上面。 */
    public enum Heat {
        LOW,
        IN_BAND,
        HIGH
    }

    /** 时窗还没开或正开着。 */
    public enum Phase {
        UPCOMING,
        OPEN
    }

    /** 一味料的时窗：还没开时 seconds 是几秒后开，开着时是还剩几秒。 */
    public record Window(IngredientSlot slot, Phase phase, int seconds) {
    }
}
