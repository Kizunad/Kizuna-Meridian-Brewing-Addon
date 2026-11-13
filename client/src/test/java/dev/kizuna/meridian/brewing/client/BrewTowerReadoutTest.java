package dev.kizuna.meridian.brewing.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.kizuna.meridian.brewing.client.BrewTowerReadout.Shown;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewTowerController;
import dev.kizuna.meridian.brewing.server.TowerNeed;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** 药塔窗口读整数表：逐项同步，各项新旧怎么混都得画得出来。 */
public class BrewTowerReadoutTest {
    private static final int ALL_NEEDS = (1 << TowerNeed.values().length) - 1;
    private static final int UNKNOWN_BITS = 0x7E00;
    private static final int NO_SELECTION = -1;

    @Test
    public void propellantLeftOverFromASplashRecipeStillDrawsOnADrinkOnlyOne() {
        // 2026-11-13 的崩溃：掩码还是上一张泼型药方的「缺火药」，选中的已经换成只能喝的聚气汤。
        List<Shown> shown = BrewTowerReadout.shown(TowerNeed.PROPELLANT.bit(), BrewKind.QI_GATHER.ordinal());
        assertEquals(1, shown.size());
        assertEquals(Optional.of(new BrewTowerController.Requirement(TowerNeed.PROPELLANT,
                BrewCauldron.PROPELLANT_INGREDIENT_ID, BrewTowerController.SETS_PER_BATCH)), shown.getFirst().item());
    }

    @Test
    public void everyMixOfMaskAndRecipeCanBeDrawn() {
        // 掩码、药方各自取遍（含认不得的位和越界的序号）：不抛异常，认得的缺项一样不少，料类的都带着料名和件数。
        for (int selected = NO_SELECTION - 1; selected <= BrewKind.values().length + 1; selected++) {
            for (int mask = 0; mask <= ALL_NEEDS; mask++) {
                check(mask, selected);
                check(mask | UNKNOWN_BITS, selected);
            }
        }
    }

    @Test
    public void stockedNeedsAgreeWithTheStartList() {
        // 窗口单独查到的料名、件数，和服务端开工清单里的是同一份。
        for (BrewKind kind : BrewKind.values()) {
            for (BrewForm form : BrewForm.values()) {
                var recipe = BrewRecipes.require(kind);
                for (BrewTowerController.Requirement listed : BrewTowerController.requirements(recipe, form)) {
                    List<Shown> shown = BrewTowerReadout.shown(listed.need().bit(), kind.ordinal());
                    assertEquals(kind + " " + form, Optional.of(listed), shown.getFirst().item());
                }
            }
        }
    }

    @Test
    public void unknownOrdinalsFallBackToTheFirstValue() {
        BrewForm[] forms = BrewForm.values();
        assertEquals(BrewForm.SPLASH, BrewTowerReadout.pick(forms, BrewForm.SPLASH.ordinal()));
        assertEquals(forms[0], BrewTowerReadout.pick(forms, -1));
        assertEquals(forms[0], BrewTowerReadout.pick(forms, forms.length));
    }

    private static void check(int mask, int selected) {
        List<Shown> shown = BrewTowerReadout.shown(mask, selected);
        String where = "mask=" + mask + " selected=" + selected;
        if (selected < 0 || selected >= BrewKind.values().length) {
            assertTrue(where, shown.isEmpty());
            return;
        }
        assertEquals(where, Integer.bitCount(mask & ALL_NEEDS), shown.size());
        for (Shown one : shown) {
            boolean stocked = one.need().compareTo(TowerNeed.PRIMARY) >= 0 && one.need().compareTo(TowerNeed.GLASS) <= 0;
            assertEquals(where + " " + one.need(), stocked, one.item().isPresent());
        }
    }
}
