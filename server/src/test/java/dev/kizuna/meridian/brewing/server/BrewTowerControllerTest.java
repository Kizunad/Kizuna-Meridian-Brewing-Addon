package dev.kizuna.meridian.brewing.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

/** 三阶药塔的规则：控火档位、两套料、品质上限、换火种、燃料秒数、耗元中断、存档往返。 */
public final class BrewTowerControllerTest {
    private static final double DELTA = 1e-9;
    private static final UUID CHOOSER = UUID.fromString("00000000-0000-0000-0000-000000000991");
    private static final BrewTowerSettings FINE_CAP = new BrewTowerSettings(BrewQuality.FINE, 540, 60, 750, 10);
    private static final BrewTowerSettings NO_CAP = new BrewTowerSettings(BrewQuality.SUPERIOR, 540, 60, 750, 10);
    private static final BrewQiSettings QI = new BrewQiSettings(true, 6.0, 3.0);
    private static final int BOTTLES = 8;
    private static final int CHEST_SLOTS = 27;
    private static final int MAX_SECONDS = 400;
    private static final double PLENTY_OF_QI = 1000;

    @Test
    public void stableLevelsMatchTheSpec() {
        assertEquals(2, BrewTowerController.stableLevel(BrewRecipes.require(BrewKind.BITTER_YELLOW)));
        assertEquals(2, BrewTowerController.stableLevel(BrewRecipes.require(BrewKind.CHARM)));
        assertEquals(2, BrewTowerController.stableLevel(BrewRecipes.require(BrewKind.INSECT_LURE)));
        assertEquals(3, BrewTowerController.stableLevel(BrewRecipes.require(BrewKind.DEPLETION)));
        assertEquals(3, BrewTowerController.stableLevel(BrewRecipes.require(BrewKind.ARMOR_ROT)));
        assertEquals(3, BrewTowerController.stableLevel(BrewRecipes.require(BrewKind.QI_GATHER)));
    }

    @Test
    public void everyRecipeBrewsEightBottlesOfAtLeastFineFromTwoSets() {
        for (BrewKind kind : BrewKind.values()) {
            var world = FakeTower.stockedFor(kind, 1);
            if (kind.fireKind() == FireKind.SOUL_FIRE) {
                world.add("minecraft:soul_sand", 1);
            }
            var tower = new BrewTowerController(NO_CAP, QI);
            tower.select(kind, CHOOSER);
            runUntilIdle(tower, world);
            assertEquals(kind + " 出药瓶数", BOTTLES, world.potions.size());
            for (Delivered potion : world.potions) {
                assertEquals(kind, potion.kind());
                assertTrue(kind + " 塔熬出的品质不到精良：" + potion.quality(),
                        potion.quality().compareTo(BrewQuality.FINE) >= 0);
            }
            assertEquals(kind + " 两套料该全用掉", Map.of(), world.leftovers());
            assertFalse("单批熬完应停下", tower.armed());
        }
    }

    @Test
    public void qualityIsCappedAtFine() {
        var uncapped = FakeTower.stockedFor(BrewKind.BITTER_YELLOW, 1);
        var free = new BrewTowerController(NO_CAP, QI);
        free.select(BrewKind.BITTER_YELLOW, CHOOSER);
        runUntilIdle(free, uncapped);
        assertEquals("不压上限时这一锅应能到优质，否则上限测不出来", BrewQuality.SUPERIOR,
                uncapped.potions.getFirst().quality());

        var capped = FakeTower.stockedFor(BrewKind.BITTER_YELLOW, 1);
        var tower = new BrewTowerController(FINE_CAP, QI);
        tower.select(BrewKind.BITTER_YELLOW, CHOOSER);
        runUntilIdle(tower, capped);
        assertEquals(BOTTLES, capped.potions.size());
        for (Delivered potion : capped.potions) {
            assertEquals(BrewQuality.FINE, potion.quality());
        }
    }

    @Test
    public void switchingToSoulFireTakesOneSoulSandOnceForConsecutiveBatches() {
        var world = FakeTower.stockedFor(BrewKind.CHARM, 2);
        world.add("minecraft:soul_sand", 3);
        var tower = new BrewTowerController(FINE_CAP, QI);
        tower.select(BrewKind.CHARM, CHOOSER);
        tower.runContinuously();
        runUntilPotions(tower, world, BOTTLES * 2);
        assertEquals("连着两批阴火只扣一块灵魂沙", 2, world.count("minecraft:soul_sand"));
        assertEquals(FireKind.SOUL_FIRE, tower.fire());
    }

    @Test
    public void switchingFireWithoutStarterWaitsAndNamesIt() {
        var world = FakeTower.stockedFor(BrewKind.DEPLETION, 1);
        var tower = new BrewTowerController(FINE_CAP, QI);
        tower.select(BrewKind.DEPLETION, CHOOSER);
        tower.second(world);
        assertEquals(TowerStatus.WAITING, tower.status());
        assertEquals(Set.of(TowerNeed.FIRE_STARTER), tower.needs().keySet());
        assertEquals(BrewText.message("tower.need.starter.soul_fire"), tower.needs().get(TowerNeed.FIRE_STARTER));
        world.add("minecraft:soul_soil", 1);
        tower.second(world);
        assertEquals(TowerPhase.SOAKING, tower.phase());
        assertEquals(0, world.count("minecraft:soul_soil"));
    }

    @Test
    public void emptyTowerListsEveryMissingThing() {
        var world = new FakeTower();
        var tower = new BrewTowerController(FINE_CAP, QI);
        tower.select(BrewKind.QI_GATHER, CHOOSER);
        tower.second(world);
        assertEquals(TowerStatus.WAITING, tower.status());
        assertEquals(Set.of(TowerNeed.PRIMARY, TowerNeed.BASE, TowerNeed.ACCESSORY, TowerNeed.GLASS,
                TowerNeed.FUEL, TowerNeed.AMBIENT_QI), tower.needs().keySet());
        int first = world.lamps.getLast();
        tower.second(world);
        assertTrue("在等东西时两组对角交替闪", first != world.lamps.getLast());
    }

    @Test
    public void fuelBurnsForItsConfiguredSecondsOneLevelPerCell() {
        var world = FakeTower.stockedFor(BrewKind.BITTER_YELLOW, 1);
        world.barrelFuel.clear();
        world.barrelFuel.add(TowerFuel.COAL_BLOCK);
        world.cells[0] = TowerFuel.LOG;
        var tower = new BrewTowerController(FINE_CAP, QI);
        tower.select(BrewKind.BITTER_YELLOW, CHOOSER);
        tower.second(world);
        tower.second(world);
        assertEquals("泡料 1 档，一格原木烧了一秒", 1, tower.level());
        assertEquals(FINE_CAP.logSeconds() - 1, tower.litSeconds(), DELTA);
    }

    @Test
    public void spentCellsTurnToAirAndRefillFromBarrels() {
        var shortFire = new BrewTowerSettings(BrewQuality.FINE, 540, 60, 750, 2);
        var world = FakeTower.stockedFor(BrewKind.BITTER_YELLOW, 1);
        world.barrelFuel.clear();
        world.barrelFuel.add(TowerFuel.COAL_BLOCK);
        world.cells[0] = TowerFuel.FIRE;
        var tower = new BrewTowerController(shortFire, QI);
        tower.select(BrewKind.BITTER_YELLOW, CHOOSER);
        tower.second(world);
        tower.second(world);
        tower.second(world);
        assertEquals("火烧完变空气", 1, world.burnedOut);
        tower.second(world);
        assertEquals(TowerFuel.COAL_BLOCK, world.cells[0]);
        assertEquals(1, tower.level());
    }

    @Test
    public void ambientShortfallBeyondGraceInterruptsThenRestarts() {
        var world = FakeTower.stockedFor(BrewKind.BITTER_YELLOW, 1);
        world.rate = 0.1;
        var tower = new BrewTowerController(FINE_CAP, QI);
        tower.select(BrewKind.BITTER_YELLOW, CHOOSER);
        int seconds = 0;
        while (tower.phase() != TowerPhase.BREWING && seconds++ < MAX_SECONDS) {
            tower.second(world);
        }
        while (tower.phase() == TowerPhase.BREWING && seconds++ < MAX_SECONDS) {
            tower.second(world);
        }
        assertEquals(TowerPhase.SOAKING, tower.phase());
        assertEquals(BrewText.message("tower.qi.interrupted"), tower.needs().get(TowerNeed.AMBIENT_QI));
        world.rate = PLENTY_OF_QI;
        runUntilIdle(tower, world);
        assertEquals(BOTTLES, world.potions.size());
    }

    @Test
    public void stopWhileSoakingReturnsBothSets() {
        var world = FakeTower.stockedFor(BrewKind.BITTER_YELLOW, 1);
        Map<String, Integer> before = world.leftovers();
        var tower = new BrewTowerController(FINE_CAP, QI);
        tower.select(BrewKind.BITTER_YELLOW, CHOOSER);
        tower.second(world);
        tower.second(world);
        assertEquals(TowerPhase.SOAKING, tower.phase());
        tower.stop(world);
        assertEquals(TowerPhase.IDLE, tower.phase());
        assertEquals(before, world.leftovers());
        assertFalse(tower.armed());
    }

    @Test
    public void brokenWhileBrewingFailsFlashesAndBecomesDisposableOnceTheFuelIsGone() {
        // 熬着时结构断：断锅、闪灯；火室还烧着就留记录（复审第 1 条），燃料被拿走后下一秒才能删。
        var world = FakeTower.stockedFor(BrewKind.BITTER_YELLOW, 1);
        var tower = new BrewTowerController(FINE_CAP, QI);
        tower.select(BrewKind.BITTER_YELLOW, CHOOSER);
        int seconds = 0;
        while (tower.phase() != TowerPhase.BREWING && seconds++ < MAX_SECONDS) {
            tower.second(world);
        }
        BrewText reason = BrewText.message("structure.no_fire");
        tower.broken(world, reason);
        assertEquals(TowerPhase.IDLE, tower.phase());
        assertEquals(TowerStatus.FAILED, tower.status());
        assertEquals(Optional.of(reason), tower.problem());
        assertFalse(tower.disposable());
        world.lamps.clear();
        for (int second = 0; second < 6; second++) {
            tower.broken(world, reason);
        }
        assertFalse("火室还烧着，记录不能删", tower.disposable());
        Arrays.fill(world.cells, null);
        tower.broken(world, reason);
        assertTrue(tower.disposable());
        assertEquals(0, world.potions.size());
        assertTrue("失败时铜灯一起闪", world.lamps.contains(0b1111) && world.lamps.contains(0));
    }

    @Test
    public void savedRecordContinuesTheSameBatch() {
        var world = FakeTower.stockedFor(BrewKind.QI_GATHER, 1);
        var tower = new BrewTowerController(FINE_CAP, QI);
        tower.select(BrewKind.QI_GATHER, CHOOSER);
        int seconds = 0;
        while (tower.brewElapsedSeconds() < BrewCauldron.BREW_SECONDS / 2 && seconds++ < MAX_SECONDS) {
            tower.second(world);
        }
        BrewTowerRecord saved = tower.record();
        var restored = BrewTowerController.restore(FINE_CAP, QI, saved);
        assertEquals(saved, restored.record());
        runUntilIdle(restored, world);
        assertEquals(BOTTLES, world.potions.size());
        assertEquals(BrewQuality.FINE, world.potions.getFirst().quality());
    }

    private static void runUntilIdle(BrewTowerController tower, FakeTower world) {
        // 至少走一秒，直到这一批收完回到空闲；超时说明塔卡住了。
        int seconds = 0;
        do {
            tower.second(world);
            seconds++;
        } while (tower.phase() != TowerPhase.IDLE && seconds < MAX_SECONDS);
        assertEquals("塔没在 " + MAX_SECONDS + " 秒内熬完：" + tower.needs(), TowerPhase.IDLE, tower.phase());
    }

    private static void runUntilPotions(BrewTowerController tower, FakeTower world, int potions) {
        // 连熬时一直走到箱子里有这么多瓶。
        int seconds = 0;
        while (world.potions.size() < potions && seconds++ < MAX_SECONDS * 2) {
            tower.second(world);
        }
        assertEquals(potions, world.potions.size());
    }

    private record Delivered(BrewKind kind, BrewQuality quality, BrewForm form) {
    }

    /** 假塔：木桶是一张物品表，铜箱子只数格子；环境真元估计有 {@code ambientStock}，每次最多给 {@code rate}。 */
    @Test
    public void changingTheRecipeDropsNeedsCheckedForThePreviousOne() {
        // 泼型的枯竭药水缺火药；换成只能喝的聚气汤后，窗口当场读到的缺料不能还挂着火药（2026-11-13 窗口崩溃）。
        var world = FakeTower.stockedFor(BrewKind.QI_GATHER, 1);
        var tower = new BrewTowerController(FINE_CAP, QI);
        tower.select(BrewKind.DEPLETION, CHOOSER);
        tower.second(world);
        assertTrue(tower.needs().containsKey(TowerNeed.PROPELLANT));
        tower.select(BrewKind.QI_GATHER, CHOOSER);
        assertNeedsBelongToTheSelection(tower);
        tower.second(world);
        assertEquals("聚气汤的料齐了该开工", TowerPhase.SOAKING, tower.phase());
    }

    @Test
    public void changingTheFormDropsNeedsCheckedForThePreviousOne() {
        // 苦黄膏喝、泼都行：选泼缺火药，改成喝以后不再要火药。
        var world = FakeTower.stockedFor(BrewKind.BITTER_YELLOW, 1);
        var tower = new BrewTowerController(FINE_CAP, QI);
        tower.chooseForm(BrewForm.SPLASH);
        tower.select(BrewKind.BITTER_YELLOW, CHOOSER);
        tower.second(world);
        assertEquals(Set.of(TowerNeed.PROPELLANT), tower.needs().keySet());
        tower.chooseForm(BrewForm.DRINK);
        assertNeedsBelongToTheSelection(tower);
    }

    @Test
    public void fuelNeedNamesTheLevelTheTowerIsActuallyWaitingFor() {
        // 开工前按药方的稳定档数说；开工以后按这一秒实际要烧的档数说（泡料只要 1 档），中途换了药方也还是这一批的。
        var world = FakeTower.stockedFor(BrewKind.BITTER_YELLOW, 1);
        world.barrelFuel.clear();
        var tower = new BrewTowerController(FINE_CAP, QI);
        tower.select(BrewKind.BITTER_YELLOW, CHOOSER);
        tower.second(world);
        int stable = BrewTowerController.stableLevel(BrewRecipes.require(BrewKind.BITTER_YELLOW));
        assertEquals(stable, tower.fuelWanted());
        assertEquals(BrewText.message("tower.need.fuel", stable), tower.needs().get(TowerNeed.FUEL));
        for (int cell = 0; cell < BrewTowerController.FUEL_CELLS; cell++) {
            world.barrelFuel.add(TowerFuel.COAL_BLOCK);
        }
        tower.second(world);
        tower.second(world);
        assertEquals(TowerPhase.SOAKING, tower.phase());
        assertEquals("燃料够时没有在等的档数", 0, tower.fuelWanted());
        Arrays.fill(world.cells, null);
        world.barrelFuel.clear();
        tower.second(world);
        assertEquals("泡料只要 1 档", 1, tower.fuelWanted());
        assertEquals(BrewText.message("tower.need.fuel", 1), tower.needs().get(TowerNeed.FUEL));
        tower.select(BrewKind.DEPLETION, CHOOSER);
        assertEquals("换了药方，等的还是这一批的档数", 1, tower.fuelWanted());
        tower.second(world);
        assertEquals(1, tower.fuelWanted());
        assertEquals(BrewText.message("tower.need.fuel", 1), tower.needs().get(TowerNeed.FUEL));
    }

    private static void assertNeedsBelongToTheSelection(BrewTowerController tower) {
        // 没开工时在等的料，每一样都得在现在这张药方、这个形态的开工清单里。
        BrewRecipe recipe = BrewRecipes.require(tower.selected().orElseThrow());
        Set<TowerNeed> listed = new HashSet<>(Set.of(TowerNeed.CHEST, TowerNeed.FUEL, TowerNeed.FIRE_STARTER,
                TowerNeed.AMBIENT_QI));
        for (BrewTowerController.Requirement requirement : BrewTowerController.requirements(recipe, tower.form())) {
            listed.add(requirement.need());
        }
        for (TowerNeed need : tower.needs().keySet()) {
            assertTrue("还挂着上一张药方的缺料：" + need, listed.contains(need));
        }
    }

    private static final class FakeTower implements TowerWorld {
        private final Map<String, Integer> stock = new LinkedHashMap<>();
        private final List<Delivered> potions = new ArrayList<>();
        private final TowerFuel[] cells = new TowerFuel[BrewTowerController.FUEL_CELLS];
        private final Deque<TowerFuel> barrelFuel = new ArrayDeque<>();
        private final List<Integer> lamps = new ArrayList<>();
        private final Set<String> draws = new HashSet<>();
        private double rate = PLENTY_OF_QI;
        private double ambientStock;
        private int residue;
        private int burnedOut;

        static FakeTower stockedFor(BrewKind kind, int batches) {
            // 每批两套料、八只玻璃瓶、四块煤炭块；泼型药方加火药。
            var world = new FakeTower();
            world.ambientStock = PLENTY_OF_QI;
            IngredientPlan plan = BrewRecipes.require(kind).ingredients();
            int sets = BrewTowerController.SETS_PER_BATCH * batches;
            world.add(plan.preparedPrimary(), sets);
            world.add(BrewCauldron.BASE_INGREDIENT_ID, sets);
            world.add(plan.accessory(), sets);
            world.add(plan.catalyst(), sets);
            if (!kind.supports(BrewUse.DRINK)) {
                world.add(BrewCauldron.PROPELLANT_INGREDIENT_ID, sets);
            }
            world.add(BrewTowerController.GLASS_BOTTLE_ID, BOTTLES * batches);
            for (int fuel = 0; fuel < BrewTowerController.FUEL_CELLS; fuel++) {
                world.barrelFuel.add(TowerFuel.COAL_BLOCK);
            }
            return world;
        }

        void add(String itemId, int count) {
            stock.merge(itemId, count, Integer::sum);
        }

        Map<String, Integer> leftovers() {
            // 还剩的药料和玻璃瓶，数到 0 的不列。
            var left = new LinkedHashMap<String, Integer>();
            stock.forEach((itemId, count) -> {
                if (count > 0) {
                    left.put(itemId, count);
                }
            });
            return left;
        }

        @Override
        public String itemId(String recipeItem) {
            return recipeItem;
        }

        @Override
        public int count(String itemId) {
            return stock.entrySet().stream()
                    .filter(entry -> BrewRecipes.sameItemId(entry.getKey(), itemId))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
        }

        @Override
        public List<IngredientBatch> take(String itemId, int count) {
            // 主料按今天炮制的算，其余是生料。
            String key = stock.keySet().stream()
                    .filter(candidate -> BrewRecipes.sameItemId(candidate, itemId))
                    .findFirst()
                    .orElseThrow();
            int taken = Math.min(count, stock.get(key));
            stock.put(key, stock.get(key) - taken);
            boolean primary = BrewRecipes.forPreparedPrimary(key).isPresent();
            var batches = new ArrayList<IngredientBatch>();
            for (int index = 0; index < taken; index++) {
                if (primary) {
                    batches.add(IngredientBatch.prepared(key, gameDay()));
                } else {
                    batches.add(IngredientBatch.raw(key));
                }
            }
            return batches;
        }

        @Override
        public void putBack(IngredientBatch batch) {
            add(batch.itemId(), batch.count());
        }

        @Override
        public int chestFreeSlots() {
            return CHEST_SLOTS - potions.size() - residue;
        }

        @Override
        public void deliverPotion(BrewKind kind, BrewQuality quality, BrewForm form) {
            potions.add(new Delivered(kind, quality, form));
        }

        @Override
        public void deliverResidue() {
            residue++;
        }

        @Override
        public Optional<TowerFuel> fuelAt(int cell) {
            return Optional.ofNullable(cells[cell]);
        }

        @Override
        public void burnOut(int cell) {
            cells[cell] = null;
            burnedOut++;
        }

        @Override
        public int barrelFuel() {
            return barrelFuel.size();
        }

        @Override
        public Optional<TowerFuel> refuel(int cell) {
            TowerFuel fuel = barrelFuel.poll();
            cells[cell] = fuel;
            return Optional.ofNullable(fuel);
        }

        @Override
        public Optional<BrewText> ambientProblem(double total) {
            if (ambientStock < total) {
                return Optional.of(BrewText.message("tower.qi.short", ambientStock, total));
            }
            return Optional.empty();
        }

        @Override
        public double drawAmbient(String drawKey, double amount) {
            // 同一个键只给一次，和 Human 的取用去重一致。
            if (!draws.add(drawKey)) {
                return 0;
            }
            return Math.min(amount, rate);
        }

        @Override
        public long gameDay() {
            return 0;
        }

        @Override
        public void showLamps(int mask) {
            lamps.add(mask);
        }
    }
}
