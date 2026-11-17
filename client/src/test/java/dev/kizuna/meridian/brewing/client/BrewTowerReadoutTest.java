package dev.kizuna.meridian.brewing.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.kizuna.meridian.brewing.client.BrewTowerReadout.Shown;
import dev.kizuna.meridian.brewing.client.BrewTowerReadout.Spot;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewTowerController;
import dev.kizuna.meridian.brewing.server.TowerNeed;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** 药塔窗口读整数表：逐项同步，各项新旧怎么混都得画得出来。 */
public class BrewTowerReadoutTest {
    private static final int ALL_NEEDS = (1 << TowerNeed.values().length) - 1;
    private static final int UNKNOWN_BITS = 0x7E00;
    private static final int NO_SELECTION = -1;
    /** 照窗口的尺寸：「缺：」后面从 28 起排，右边到 168，图标一格 18。 */
    private static final int LEFT = 28;
    private static final int RIGHT = 168;
    private static final int ICON = 18;

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
        for (int selected = NO_SELECTION - 1; selected <= BrewKind.towerKinds().length + 1; selected++) {
            for (int mask = 0; mask <= ALL_NEEDS; mask++) {
                check(mask, selected);
                check(mask | UNKNOWN_BITS, selected);
            }
        }
    }

    @Test
    public void stockedNeedsAgreeWithTheStartList() {
        // 窗口单独查到的料名、件数，和服务端开工清单里的是同一份。
        for (BrewKind kind : BrewKind.towerKinds()) {
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

    @Test
    public void fuelLevelComesFromTheServerAndFallsBackToTheRecipe() {
        // 服务端同步来的是塔这一秒实际在等的档数；还没同步到（0）或认不得时，按选中药方的稳定档数写。
        var recipe = BrewRecipes.require(BrewKind.DEPLETION);
        int stable = BrewTowerController.stableLevel(recipe);
        assertEquals(1, BrewTowerReadout.fuelLevel(1, recipe));
        assertEquals(BrewTowerController.FUEL_CELLS, BrewTowerReadout.fuelLevel(BrewTowerController.FUEL_CELLS, recipe));
        assertEquals(stable, BrewTowerReadout.fuelLevel(0, recipe));
        assertEquals(stable, BrewTowerReadout.fuelLevel(-1, recipe));
        assertEquals(stable, BrewTowerReadout.fuelLevel(BrewTowerController.FUEL_CELLS + 1, recipe));
    }

    @Test
    public void aRowThatFitsStaysOnOneLineAtTheOldPositions() {
        // 七个图标以内一行放得下：位置和以前一样，一格接一格。
        List<Spot> spots = BrewTowerReadout.flow(List.of(ICON, ICON, ICON, ICON, ICON, ICON, ICON), LEFT, RIGHT);
        for (int index = 0; index < spots.size(); index++) {
            assertEquals(new Spot(LEFT + index * ICON, 0), spots.get(index));
        }
    }

    @Test
    public void everyCombinationOfNeedsStaysInsideTheWindow() {
        // 九样里任取几样、「环境真元」那几个字宽窄不同：每一样都在左右边界以内，同一行不叠，最多两行。
        for (int qiWidth : new int[] {ICON, 2 * ICON, 4 * ICON}) {
            for (int mask = 1; mask <= ALL_NEEDS; mask++) {
                var widths = new ArrayList<Integer>();
                for (TowerNeed need : TowerNeed.values()) {
                    if ((mask & need.bit()) != 0) {
                        widths.add(need == TowerNeed.AMBIENT_QI ? qiWidth : ICON);
                    }
                }
                checkFlow(widths, "mask=" + mask + " qi=" + qiWidth);
            }
        }
    }

    private static void checkFlow(List<Integer> widths, String where) {
        List<Spot> spots = BrewTowerReadout.flow(widths, LEFT, RIGHT);
        assertEquals(where, widths.size(), spots.size());
        int row = 0;
        int end = LEFT;
        for (int index = 0; index < spots.size(); index++) {
            Spot spot = spots.get(index);
            if (spot.row() != row) {
                assertEquals(where + " 行号要连着", row + 1, spot.row());
                row = spot.row();
                end = LEFT;
            }
            assertTrue(where + " 第 " + index + " 样和前一样叠了", spot.x() >= end);
            end = spot.x() + widths.get(index);
            assertTrue(where + " 第 " + index + " 样伸出右边：" + end, end <= RIGHT);
        }
        assertTrue(where + " 超过两行", row <= 1);
    }

    private static void check(int mask, int selected) {
        List<Shown> shown = BrewTowerReadout.shown(mask, selected);
        String where = "mask=" + mask + " selected=" + selected;
        if (selected < 0 || selected >= BrewKind.towerKinds().length) {
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
