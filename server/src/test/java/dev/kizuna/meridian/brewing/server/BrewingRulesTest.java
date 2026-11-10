package dev.kizuna.meridian.brewing.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class BrewingRulesTest {
    private static final double COLLECTION_COOLING_SECONDS = 1.25;
    private static final BrewStructure STABLE_HEAT = new BrewStructure(
            HeatSourceKind.SUN_BRAZIER_2,
            BrewStructure.REQUIRED_HEARTH_STONES,
            SmokeVentState.OPEN,
            HerbState.PRESENT);

    @Test
    public void freshnessReachesConfiguredFloor() {
        assertEquals(1.0, IngredientFreshness.multiplier(0, 3), 0.0001);
        assertEquals(0.8, IngredientFreshness.multiplier(0, 6), 0.0001);
        assertEquals(0.6, IngredientFreshness.multiplier(0, 10), 0.0001);
        assertEquals(0.6, IngredientFreshness.multiplier(0, 20), 0.0001);
    }

    @Test
    public void superiorRequiresCatalyst() {
        BrewScoreInput withoutCatalyst = new BrewScoreInput(25, 10, 30, 12, 15, 0,
                CatalystRequirement.OMITTED);
        BrewScoreInput withCatalyst = new BrewScoreInput(25, 10, 30, 20, 15, 0,
                CatalystRequirement.INCLUDED);
        assertEquals(BrewQuality.FINE, BrewScoring.calculate(withoutCatalyst).quality());
        assertEquals(BrewQuality.SUPERIOR, BrewScoring.calculate(withCatalyst).quality());
    }

    @Test
    public void kidneyAndBrainThresholdsRemainDistinct() {
        KidneyMetrics healthyKidney = new KidneyMetrics(1.0, 12.0, 12.0);
        KidneyMetrics injuredKidney = new KidneyMetrics(1.0, 6.0, 12.0);
        assertEquals(DetoxTier.GOOD, DetoxCalculator.resolve(healthyKidney, healthyKidney).tier());
        KidneyMetrics failingKidney = new KidneyMetrics(1.0, 0.0, 12.0);
        assertEquals(DetoxTier.POOR, DetoxCalculator.resolve(failingKidney, injuredKidney).tier());

        BrainMetrics brain = new BrainMetrics(600.0, 0.0, 12.0, 12.0);
        BrainMetrics attention = new BrainMetrics(300.0, 0.0, 12.0, 12.0);
        assertEquals(CharmOutcome.STAGGER,
                CharmCalculator.resolve(brain, attention, BrewQuality.POOR).outcome());
        assertEquals(CharmOutcome.CONTROL,
                CharmCalculator.resolve(brain, attention, BrewQuality.SUPERIOR).outcome());
    }

    @Test
    public void armorAndWitheringUseMaterialAndDistanceRules() {
        ArmorRotProfile profile = BrewEffectProfiles.armorRot(BrewQuality.SUPERIOR);
        assertEquals(0, ArmorRotCalculator.damage(profile, ArmorMaterialKind.GOLD, 100, 1.0).damagePoints());
        assertEquals(8, ArmorRotCalculator.damage(profile, ArmorMaterialKind.NETHERITE, 1000, 1.0).damagePoints());
        assertEquals(1.0, WitheringRules.chance(0.0, 4.5), 0.0001);
        assertEquals(0.0, WitheringRules.chance(4.5, 4.5), 0.0001);
        List<WitheringCandidate> candidates = List.of(
                new WitheringCandidate("minecraft:oak_log", 1.0, 0.0),
                new WitheringCandidate("minecraft:birch_log", 2.0, 0.0),
                new WitheringCandidate("minecraft:spruce_log", 3.0, 0.0));
        assertEquals(3, WitheringRules.budget(candidates).size());
        assertEquals(WitheringOutcome.DEAD_LOG,
                WitheringRules.classify("minecraft:pale_oak_log").outcome());
    }

    @Test
    public void residueOverwritesDecaysAndTriggers() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        ResidueStore store = new ResidueStore();
        store.put(new ResidueSeed(source, target, BrewKind.DEPLETION, BrewQuality.FINE, "right_arm", 1.0));
        store.put(new ResidueSeed(source, target, BrewKind.DEPLETION, BrewQuality.SUPERIOR, "right_arm", 2.0));
        assertEquals(1, store.snapshots().size());
        store.tick(20.0, RecoveryState.INACTIVE);
        assertEquals(1.0, store.snapshots().get(0).dose(), 0.0001);
        List<ResidueActivation> activations = store.trigger(
                new ResidueTrigger(source, target, BrewKind.DEPLETION, ResidueTrigger.MAX_TRIGGER_DISTANCE));
        assertEquals(1, activations.size());
        assertEquals(0, store.snapshots().size());
    }

    @Test
    public void cauldronCollectsThreeQualityCheckedBottles() {
        BrewCauldron cauldron = new BrewCauldron(VesselKind.WITCH_CAULDRON, BrewForm.DRINK);
        cauldron.addWater(3);
        cauldron.addIngredient(IngredientSlot.PRIMARY,
                IngredientBatch.prepared("dry_moss", 0));
        cauldron.addIngredient(IngredientSlot.BASE, IngredientBatch.raw("minecraft:glowstone_dust"));
        cauldron.startBrew(STABLE_HEAT);
        cauldron.tick(8.0, STABLE_HEAT);
        cauldron.addIngredient(IngredientSlot.ACCESSORY,
                IngredientBatch.raw("honey_bottle"));
        cauldron.tick(12.0, STABLE_HEAT);
        cauldron.addIngredient(IngredientSlot.CATALYST,
                IngredientBatch.raw("cobweb"));
        cauldron.tick(10.0, STABLE_HEAT);
        assertEquals(BrewCauldronState.BREWED, cauldron.state());
        cauldron.tick(COLLECTION_COOLING_SECONDS, new BrewStructure(
                HeatSourceKind.NONE, 0, SmokeVentState.OPEN, HerbState.PRESENT));
        BrewCollectionResult result = cauldron.collectBottle(3);
        assertEquals(BrewCollectionKind.POTION, result.kind());
        assertEquals(BrewQuality.SUPERIOR, result.score().orElseThrow().quality());
        assertEquals(2, result.remainingBottles());
    }

    @Test
    public void blockedStructureCapsTemperatureAndLowWaterScorches() {
        BrewStructure blocked = new BrewStructure(
                HeatSourceKind.SUN_BRAZIER_4, 3, SmokeVentState.OPEN, HerbState.PRESENT);
        assertEquals(BrewStructure.BLOCKED_STRUCTURE_MAXIMUM, blocked.maximumTemperature(), 0.0001);

        BrewCauldron cauldron = new BrewCauldron(VesselKind.WITCH_CAULDRON, BrewForm.SPLASH);
        cauldron.addWater(1);
        cauldron.addIngredient(IngredientSlot.PRIMARY,
                IngredientBatch.prepared("withered_bush_ash", 0));
        cauldron.addIngredient(IngredientSlot.BASE, IngredientBatch.raw("minecraft:glowstone_dust"));
        cauldron.addIngredient(IngredientSlot.PROPELLANT,
                IngredientBatch.raw("minecraft:gunpowder"));
        cauldron.startBrew(new BrewStructure(
                HeatSourceKind.SOUL_CAMPFIRE,
                BrewStructure.REQUIRED_HEARTH_STONES,
                SmokeVentState.OPEN,
                HerbState.PRESENT));
        cauldron.addIngredient(IngredientSlot.ACCESSORY,
                IngredientBatch.raw("fermented_spider_eye"));
        cauldron.addIngredient(IngredientSlot.CATALYST,
                IngredientBatch.raw("wither_rose"));
        cauldron.tick(21.0, new BrewStructure(
                HeatSourceKind.SOUL_CAMPFIRE,
                BrewStructure.REQUIRED_HEARTH_STONES,
                SmokeVentState.OPEN,
                HerbState.PRESENT));
        assertTrue(cauldron.state() == BrewCauldronState.SCORCHED
                || cauldron.scorchCount() > 0);
    }

    @Test
    public void allFourHearthDirectionsAreRequired() {
        for (BrewDirection missing : BrewDirection.values()) {
            EnumSet<BrewDirection> present = EnumSet.allOf(BrewDirection.class);
            present.remove(missing);
            BrewStructure structure = BrewStructure.fromHearthDirections(
                    HeatSourceKind.SUN_BRAZIER_4,
                    present,
                    SmokeVentState.OPEN,
                    HerbState.PRESENT);
            assertEquals(3, structure.hearthStoneCount());
            assertEquals(BrewStructure.BLOCKED_STRUCTURE_MAXIMUM,
                    structure.maximumTemperature(), 0.0001);
        }
        BrewStructure complete = BrewStructure.fromHearthDirections(
                HeatSourceKind.SUN_BRAZIER_4,
                EnumSet.allOf(BrewDirection.class),
                SmokeVentState.OPEN,
                HerbState.PRESENT);
        assertTrue(complete.accepts(FireKind.SUN_FIRE));
        assertEquals(BrewStructure.COMPLETE_STRUCTURE_MAXIMUM,
                complete.maximumTemperature(), 0.0001);
    }

    @Test
    public void wrongPrimaryIsReturnedWithoutChoosingRecipe() {
        BrewCauldron cauldron = new BrewCauldron(VesselKind.WITCH_CAULDRON, BrewForm.DRINK);
        cauldron.addWater(1);
        IngredientBatch wrongPrimary = IngredientBatch.prepared("meridian_brewing:unknown", 0);
        BrewActionResult result = cauldron.addIngredient(IngredientSlot.PRIMARY, wrongPrimary);
        assertTrue(!result.accepted());
        assertTrue(result.returnedIngredient().orElseThrow() == wrongPrimary);
        assertTrue(cauldron.recipe().isEmpty());
    }

    @Test
    public void adjustableFireConsumesFuelAndChangesHeatKind() {
        BrewFireState fire = BrewFireState.brazier(FireKind.SUN_FIRE);
        assertEquals(0, fire.level());
        fire.addFuel("minecraft:coal");
        assertEquals(1, fire.level());
        assertEquals(HeatSourceKind.SUN_BRAZIER_1, fire.heatSource());
        fire.changeFireKind(FireKind.SOUL_FIRE);
        assertEquals(HeatSourceKind.SOUL_BRAZIER_1, fire.heatSource());
        fire.tick(BrewFireState.FUEL_SECONDS_PER_LEVEL);
        assertEquals(0, fire.level());
        assertEquals(HeatSourceKind.NONE, fire.heatSource());
    }

    @Test
    public void copperParametersMatchSecondTierRules() {
        assertEquals(4, VesselKind.COPPER_CAULDRON.waterCapacity());
        assertEquals(10.0, VesselKind.COPPER_CAULDRON.waterCoolingDegrees(), 0.0001);
        assertEquals(1.3, VesselKind.COPPER_CAULDRON.qiCost(), 0.0001);
        CopperCauldronState state = new CopperCauldronState(
                CopperOxidation.UNAFFECTED, false);
        state = state.age(CopperExposure.EXPOSED);
        assertEquals(CopperOxidation.EXPOSED, state.oxidation());
        state = state.wax();
        assertTrue(state.age(CopperExposure.EXPOSED).waxed());
        assertEquals(CopperOxidation.UNAFFECTED, state.scrape().oxidation());
    }

    @Test
    public void placedCopperStartsAtShownOxidationUnwaxed() {
        BrewCauldron copper = new BrewCauldron(VesselKind.COPPER_CAULDRON, BrewForm.DRINK);
        copper.waxCopper();
        copper.restoreCopperOxidation(CopperOxidation.WEATHERED);
        assertEquals(new CopperCauldronState(CopperOxidation.WEATHERED, false), copper.copperState());
        BrewCauldron witch = new BrewCauldron(VesselKind.WITCH_CAULDRON, BrewForm.DRINK);
        assertThrows(IllegalArgumentException.class,
                () -> witch.restoreCopperOxidation(CopperOxidation.EXPOSED));
    }

    @Test
    public void witheringBudgetAndPermissionsAreEnforced() {
        List<WitheringCandidate> candidates = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> new WitheringCandidate(
                        "minecraft:grass_block", index, 0.0))
                .toList();
        assertEquals(WitheringRules.MAX_BLOCKS_PER_TICK,
                WitheringRules.budget(candidates).size());
        WitheringPermissions protectedSpawn = new WitheringPermissions(
                WitheringSource.PLAYER, true, true, true);
        WitheringPermissions disabledForMob = new WitheringPermissions(
                WitheringSource.MOB, true, false, false);
        assertTrue(!protectedSpawn.allowsBlockChange());
        assertTrue(!disabledForMob.allowsBlockChange());
    }
}
