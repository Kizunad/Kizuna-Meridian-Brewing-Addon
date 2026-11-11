package dev.kizuna.meridian.brewing.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.Test;

/** 熬药耗元的配置、账目和自身分摊。 */
public final class BrewQiTest {
    private static final double DELTA = 1e-9;
    private static final double FREE_DENSITY = 0.01;

    @Test
    public void defaultConfigCostsSixPerCopperBrewAndMoreInWitchCauldron() {
        BrewQiSettings settings = BrewQiSettings.read(defaults(), (key, value) -> {
            throw new AssertionError("默认配置不该缺键：" + key);
        });
        assertTrue(settings.enabled());
        assertEquals(6.0, settings.brewCost(VesselKind.COPPER_CAULDRON), DELTA);
        assertEquals(6.0 * VesselKind.WITCH_CAULDRON.qiCost(), settings.brewCost(VesselKind.WITCH_CAULDRON), DELTA);
    }

    @Test
    public void oldConfigWithoutQiKeysUsesDefaultsAndReportsEachKeyOnce() {
        var old = new Properties();
        old.setProperty("kidney.ordinary", "1.0");
        List<String> missing = new ArrayList<>();
        BrewQiSettings settings = BrewQiSettings.read(old, (key, value) -> missing.add(key + "=" + value));
        assertEquals(List.of("qi.enabled=true", "qi.cost_per_brew=6.0"), missing);
        assertEquals(6.0, settings.costPerBrew(), DELTA);
    }

    @Test
    public void disabledOrInvalidConfig() {
        var off = new Properties();
        off.setProperty("qi.enabled", "false");
        off.setProperty("qi.cost_per_brew", "6.0");
        assertEquals(0, BrewQiSettings.read(off, (key, value) -> { }).brewCost(VesselKind.WITCH_CAULDRON), DELTA);
        var bad = new Properties();
        bad.setProperty("qi.enabled", "yes");
        bad.setProperty("qi.cost_per_brew", "6.0");
        assertThrows(IllegalArgumentException.class, () -> BrewQiSettings.read(bad, (key, value) -> { }));
        var negative = new Properties();
        negative.setProperty("qi.enabled", "true");
        negative.setProperty("qi.cost_per_brew", "-1");
        assertThrows(IllegalArgumentException.class, () -> BrewQiSettings.read(negative, (key, value) -> { }));
    }

    @Test
    public void tallyPaysOneThirtiethPerSecondAndStopsAtTotal() {
        BrewQiTally tally = BrewQiTally.start(6.0);
        assertEquals(0.2, tally.nextShare(), DELTA);
        for (int second = 0; second < BrewCauldron.BREW_SECONDS; second++) {
            double share = tally.nextShare();
            tally = second % 2 == 0 ? tally.pay(share, 0) : tally.pay(share * 0.25, share * 0.75);
        }
        assertEquals(6.0, tally.paid(), 1e-6);
        assertEquals(0, tally.nextShare(), 1e-6);
        assertEquals(3.0 + 0.75, tally.ambient(), 1e-6);
        assertEquals(2.25, tally.self(), 1e-6);
    }

    @Test
    public void selfDebitSplitsByMeridianBalanceAndKeepsAttributeMix() {
        Map<String, QiInventory> lumens = new LinkedHashMap<>();
        lumens.put("a", inventory(new Qi(Qi.QiAttribute.WOOD, 3.0, FREE_DENSITY)));
        lumens.put("b", inventory(new Qi(Qi.QiAttribute.WOOD, 0.5, FREE_DENSITY),
                new Qi(Qi.QiAttribute.FIRE, 0.5, FREE_DENSITY)));
        Map<String, QiInventory> debits = BrewSelfQi.debits(lumens, 0.4);
        assertEquals(0.3, debits.get("a").totalQi(), DELTA);
        assertEquals(0.1, debits.get("b").totalQi(), DELTA);
        assertEquals(2, debits.get("b").entries().size());
        for (Map.Entry<String, QiInventory> debit : debits.entrySet()) {
            QiAccounting.debit(lumens.get(debit.getKey()), debit.getValue());
        }
        assertEquals(4.0, BrewSelfQi.balance(lumens), DELTA);
    }

    @Test
    public void selfDebitRefusesMoreThanBalance() {
        Map<String, QiInventory> lumens = Map.of("a", inventory(new Qi(Qi.QiAttribute.WOOD, 0.1, FREE_DENSITY)));
        assertThrows(IllegalArgumentException.class, () -> BrewSelfQi.debits(lumens, 0.2));
    }

    @Test
    public void everyRecipeHasNoQiGateThisRound() {
        assertFalse(BrewRecipes.all().isEmpty());
        for (BrewRecipe recipe : BrewRecipes.all().values()) {
            assertEquals(BrewQiRule.NONE, recipe.qi());
        }
        assertEquals(BrewQiRule.Source.ANY, BrewQiRule.NONE.source());
        assertTrue(BrewQiRule.NONE.attributes().isEmpty());
    }

    private static Properties defaults() {
        var properties = new Properties();
        try (var input = BrewQiTest.class.getResourceAsStream("/meridian-brewing.properties")) {
            properties.load(input);
        } catch (java.io.IOException invalid) {
            throw new AssertionError(invalid);
        }
        return properties;
    }

    private static QiInventory inventory(Qi... entries) {
        return new QiInventory(List.of(entries));
    }
}
