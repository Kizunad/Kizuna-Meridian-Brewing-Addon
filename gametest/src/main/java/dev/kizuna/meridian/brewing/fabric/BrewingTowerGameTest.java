package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewScoring;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.BrewTowerController;
import dev.kizuna.meridian.brewing.server.FireKind;
import dev.kizuna.meridian.brewing.server.IngredientPlan;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import dev.kizuna.meridian.brewing.server.TowerNeed;
import dev.kizuna.meridian.brewing.server.TowerPhase;
import dev.kizuna.meridian.brewing.server.TowerStatus;
import dev.kizuna.meridian.brewing.server.VesselKind;
import dev.kizuna.meridian.brewing.test.BrewSites;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 三阶药塔（SPEC E1 #10–16）：在随包的 lv3 结构上，测试玩家读过药方、空手右键塔北面的玻璃认塔开窗，
 * 用窗口按钮选药方；料、燃料、玻璃瓶预先放进六个木桶，塔每 20 tick 推进一秒，成药进铜箱子。
 *
 * <p>药塔只取环境真元，一批 15.6 元，要水池所在区块的剩余速率每秒至少 0.52；和别的熬药测试同批会抢同一区块的
 * 速率，所以单独一批。同批的几座塔隔着整个场地（17 格加 5 格间距），水池不会落在同一区块。</p>
 */
public final class BrewingTowerGameTest {
    private static final String TOWER_BATCH = "meridian_brewing_test:tower";
    private static final int FLOW_TIMEOUT = 3600;
    private static final int SHORT_TIMEOUT = 600;
    private static final int TICKS_PER_SECOND = 20;
    /** 塔在游戏时间整 20 tick 那一刻的末尾推进；隔半秒再读，读到的一定是这一秒推进完的结果。 */
    private static final int SAMPLE_TICK = 10;
    private static final List<BlockPos> BARRELS = List.of(
            new BlockPos(5, 1, 6), new BlockPos(5, 1, 7), new BlockPos(8, 1, 6),
            new BlockPos(8, 1, 7), new BlockPos(6, 1, 8), new BlockPos(7, 1, 8));
    private static final List<BlockPos> CHEST = List.of(new BlockPos(6, 1, 5), new BlockPos(7, 1, 5));
    private static final List<BlockPos> BULBS = List.of(
            new BlockPos(6, 9, 6), new BlockPos(7, 9, 6), new BlockPos(6, 9, 7), new BlockPos(7, 9, 7));
    private static final List<BlockPos> FUEL_CELLS = List.of(
            new BlockPos(6, 3, 6), new BlockPos(7, 3, 6), new BlockPos(6, 3, 7), new BlockPos(7, 3, 7));
    /** 药池北面那排染色玻璃的西一格，玩家从塔北面的空地点它。 */
    private static final BlockPos ENTRY_GLASS = new BlockPos(6, 6, 5);
    /** 火室北面的一根铁栏杆。 */
    private static final BlockPos IRON_BAR = new BlockPos(6, 3, 5);
    /** 西边北那只木桶正上方的外框格，D5 允许换成漏斗。 */
    private static final BlockPos FEED_HOPPER = new BlockPos(5, 2, 6);
    /** 铜箱子两半正下方的外框格，西、东各一。 */
    private static final List<BlockPos> DRAIN_HOPPERS = List.of(new BlockPos(6, 0, 5), new BlockPos(7, 0, 5));
    private static final String BITTER_PRIMARY = "meridian_brewing:dry_moss";
    private static final int BOTTLES = 8;
    private static final int SETS = BrewTowerController.SETS_PER_BATCH;
    private static final int COAL_BLOCKS = 8;
    private static final int LIT_PER_DIAGONAL = 2;
    private static final int ALTERNATIONS = 3;
    private static final int PAUSED_SECONDS = 2;
    private static final double SECONDS_DELTA = 1e-6;
    private static final double HOG_AMOUNT = 1.0e6;
    private static final String MISMATCH_KEY = BrewText.MESSAGE_PREFIX + "structure.mismatch";
    private static final String CHEST_FULL = "铜箱子要留出 8 个空格";
    private static final String QI_INTERRUPTED = "环境真元不济，这锅断了，重新泡料";
    private static final Identifier MISSING_DEFINITION = Identifier.parse("meridian_brewing_test:missing_tower");
    private static final double DROP_SEARCH = 2.0;
    private static final double NETHER_ROOF_TOP = 128.0;
    private static final double FAR_BORDER = 10_000.0;
    private static final double SMALL_BORDER = 16.0;
    private static final BlockPos LONE_GLASS = new BlockPos(1, 1, 1);

    @GameTest(environment = TOWER_BATCH, maxTicks = FLOW_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void towerBrewsBitterYellowFromBarrelsIntoTheChest(GameTestHelper context) {
        // #10：选完药方人就下线，塔照样跑；铜箱子得 8 瓶、品质压在精良以内；两个蜂蜜瓶退回两个玻璃瓶；
        // 燃料格剩的秒数正好是点过的煤炭块总秒数减去每秒烧着的格数之和。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
        requireAmbient(context, site);
        stock(context, site, bitterStock());
        stock(context, site, List.of(stack("minecraft:cobweb", SETS)));
        TowerRun run = open(context, site, BrewKind.BITTER_YELLOW);
        BrewingTestPlayers.close(run.chooser());
        Timeline timeline = new Timeline();
        eachSecond(context, () -> {
            BrewTowerController tower = run.controller();
            timeline.record(tower);
            if (tower.phase() == TowerPhase.BREWED) {
                context.assertTrue(timingScore(tower) == BrewScoring.TIMING_MAX, "辅料、引药没按时窗正中下："
                        + timingScore(tower));
            }
            if (potions(context, site).size() < BOTTLES || tower.phase() != TowerPhase.IDLE) {
                return;
            }
            assertBitterBatch(context, site, tower, timeline);
            System.out.println("[药塔] 苦黄一批：" + timeline);
            finish(context, run);
        });
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = SHORT_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void switchingToSoulFireTakesOneSoulSand(GameTestHelper context) {
        // #11：塔原来是阳火，魅惑药水要阴火：开工那一秒从木桶扣一块灵魂沙、火种变阴火；
        // 再按停，泡着的两套料退回木桶，灵魂沙不退。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
        requireAmbient(context, site);
        stock(context, site, charmStock());
        stock(context, site, List.of(stack("minecraft:soul_sand", 1)));
        TowerRun run = open(context, site, BrewKind.CHARM);
        eachSecond(context, () -> {
            BrewTowerController tower = run.controller();
            if (tower.phase() != TowerPhase.SOAKING) {
                context.assertTrue(tower.needs().isEmpty(), "魅惑药水开不了工：" + needs(tower));
                return;
            }
            context.assertTrue(tower.fire() == FireKind.SOUL_FIRE, "开工后火种没换成阴火：" + tower.fire());
            context.assertTrue(count(context, site, "minecraft:soul_sand") == 0, "换阴火没扣灵魂沙");
            context.assertTrue(run.chooser().containerMenu instanceof BrewTowerMenu, "药塔窗口提前关了");
            boolean stopped = run.chooser().containerMenu.clickMenuButton(run.chooser(), BrewTowerMenu.BUTTON_STOP);
            context.assertTrue(stopped && tower.phase() == TowerPhase.IDLE, "按停以后塔还在泡料");
            context.assertTrue(count(context, site, "meridian_brewing:dry_poppy") == SETS, "停了没退两套主料");
            context.assertTrue(count(context, site, BrewCauldron.BASE_INGREDIENT_ID) == SETS, "停了没退两套底料");
            context.assertTrue(count(context, site, BrewCauldron.PROPELLANT_INGREDIENT_ID) == SETS,
                    "停了没退两份火药");
            context.assertTrue(count(context, site, "minecraft:soul_sand") == 0, "停了把灵魂沙也退了");
            finish(context, run);
        });
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = SHORT_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void fullChestKeepsTheTowerWaitingWithAlternatingLamps(GameTestHelper context) {
        // #12：铜箱子先塞满：不开工，窗口那句是要留空格，铜灯两组对角一秒一换；木桶里的料一样没动。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
        requireAmbient(context, site);
        stock(context, site, bitterStock());
        fillChest(context, site);
        TowerRun run = open(context, site, BrewKind.BITTER_YELLOW);
        BrewingTestPlayers.close(run.chooser());
        List<Set<Integer>> lit = new ArrayList<>();
        eachSecond(context, () -> {
            BrewTowerController tower = run.controller();
            if (tower.status() != TowerStatus.WAITING) {
                return;
            }
            context.assertTrue(tower.phase() == TowerPhase.IDLE, "铜箱子满了还开了工");
            context.assertTrue(tower.needs().containsKey(TowerNeed.CHEST), "等的东西里没有铜箱子：" + needs(tower));
            String problem = BrewTexts.chinese(tower.problem().orElseThrow());
            context.assertTrue(CHEST_FULL.equals(problem), "窗口那句不对：" + problem);
            lit.add(litBulbs(context, site));
            if (lit.size() < ALTERNATIONS) {
                return;
            }
            assertAlternating(context, lit);
            context.assertTrue(count(context, site, "meridian_brewing:dry_moss") == SETS, "没开工却动了木桶里的料");
            finish(context, run);
        });
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = FLOW_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void missingCatalystStillBrewsWithoutItsTimingPoints(GameTestHelper context) {
        // #13：木桶里不放蛛网：这一批照熬照出 8 瓶，熬好时锅里没有引药，时分只有辅料那 12 分、没有引药那 8 分。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
        requireAmbient(context, site);
        stock(context, site, bitterStock());
        TowerRun run = open(context, site, BrewKind.BITTER_YELLOW);
        BrewingTestPlayers.close(run.chooser());
        boolean[] checkedBrewed = {false};
        eachSecond(context, () -> {
            BrewTowerController tower = run.controller();
            if (tower.phase() == TowerPhase.BREWED && !checkedBrewed[0]) {
                Map<IngredientSlot, Double> times = tower.record().cauldron().ingredientTimes();
                context.assertFalse(times.containsKey(IngredientSlot.CATALYST), "没放引药锅里却有引药");
                context.assertTrue(timingScore(tower) == (int) BrewScoring.ACCESSORY_IN_WINDOW_SCORE,
                        "没有引药时时分应只有辅料那份：" + timingScore(tower));
                checkedBrewed[0] = true;
            }
            if (potions(context, site).size() < BOTTLES || tower.phase() != TowerPhase.IDLE) {
                return;
            }
            context.assertTrue(checkedBrewed[0], "没看到熬好待收的那一秒");
            assertBitterPotions(context, potions(context, site));
            finish(context, run);
        });
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = FLOW_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void hoppersFillABarrelAndDrainTheChest(GameTestHelper context) {
        // #14（D5）：西木桶上方那格外框换成朝下的漏斗，主料只放在这只漏斗里，由它喂进木桶；铜箱子底下两格换成漏斗，
        // 西边那只朝东递给东边那只，成药一进箱子就被抽走。换了这几格结构照样有效，8 瓶全落进两只漏斗。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
        requireAmbient(context, site);
        Container feed = hopper(context, site, FEED_HOPPER, Direction.DOWN);
        hopper(context, site, DRAIN_HOPPERS.getLast(), Direction.DOWN);
        hopper(context, site, DRAIN_HOPPERS.getFirst(), Direction.EAST);
        feed.setItem(0, stack(BITTER_PRIMARY, SETS));
        stock(context, site, bitterSupplies());
        stock(context, site, List.of(stack("minecraft:cobweb", SETS)));
        TowerRun run = open(context, site, BrewKind.BITTER_YELLOW);
        BrewingTestPlayers.close(run.chooser());
        eachSecond(context, () -> {
            BrewTowerController tower = run.controller();
            context.assertTrue(tower.status() != TowerStatus.BROKEN, "换成漏斗的外框格被当成结构不符");
            List<ItemStack> drained = potionsIn(context, site, DRAIN_HOPPERS);
            if (drained.size() < BOTTLES || tower.phase() != TowerPhase.IDLE) {
                return;
            }
            context.assertTrue(feed.isEmpty(), "漏斗里的主料没喂进木桶");
            context.assertTrue(potions(context, site).isEmpty(), "铜箱子底下的漏斗没把药抽走");
            assertBitterPotions(context, drained);
            finish(context, run);
        });
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = SHORT_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void brokenWhileSoakingReturnsBothSetsAndTellsTheChooser(GameTestHelper context) {
        // #15 泡料段：泡料时拆一根铁栏杆，下一秒两套主料、底料全退回木桶、塔闲下来；火室里那块煤炭块还点着，
        // 所以记录留着（复审第 1 条）。选药方的人在线，收到第一处不符。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
        requireAmbient(context, site);
        stock(context, site, bitterStock());
        TowerRun run = open(context, site, BrewKind.BITTER_YELLOW);
        boolean[] broken = {false};
        eachSecond(context, () -> {
            Optional<BrewTowerSavedData.Tower> found = run.tower();
            if (!broken[0]) {
                if (soakingOverLitFuel(found.orElseThrow().controller())) {
                    context.getLevel().setBlockAndUpdate(site.world(IRON_BAR), Blocks.AIR.defaultBlockState());
                    broken[0] = true;
                }
                return;
            }
            BrewTowerController tower = found.orElseThrow(() -> new IllegalStateException("火室还点着，塔记录却删了"))
                    .controller();
            context.assertTrue(tower.phase() == TowerPhase.IDLE, "泡料时结构坏了，塔没闲下来：" + tower.phase());
            context.assertTrue(tower.litSeconds() > 0, "火室里的煤炭块不算点着了");
            context.assertTrue(count(context, site, "meridian_brewing:dry_moss") == SETS, "泡着的主料没退回木桶");
            context.assertTrue(count(context, site, BrewCauldron.BASE_INGREDIENT_ID) == SETS, "泡着的底料没退回木桶");
            context.assertTrue(potions(context, site).isEmpty(), "结构坏了还出了药");
            context.assertTrue(overlayKeys(run.chooser()).contains(MISMATCH_KEY), "选药方的人没收到第一处不符");
            finish(context, run);
        });
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = FLOW_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void brokenHalfwayThroughBrewingFailsTheBatch(GameTestHelper context) {
        // #15 熬制段：熬到一半拆一根铁栏杆：这一批作废、不退料、不出药，铜灯四盏一起闪；闪完锅空，
        // 火室里的煤炭块还点着，记录留着（复审第 1 条）。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
        requireAmbient(context, site);
        stock(context, site, bitterStock());
        TowerRun run = open(context, site, BrewKind.BITTER_YELLOW);
        BrewingTestPlayers.close(run.chooser());
        boolean[] broken = {false};
        boolean[] flashed = {false};
        eachSecond(context, () -> {
            Optional<BrewTowerSavedData.Tower> found = run.tower();
            if (!broken[0]) {
                BrewTowerController tower = found.orElseThrow().controller();
                if (tower.phase() == TowerPhase.BREWING
                        && tower.brewElapsedSeconds() >= BrewCauldron.BREW_SECONDS / 2) {
                    context.getLevel().setBlockAndUpdate(site.world(IRON_BAR), Blocks.AIR.defaultBlockState());
                    broken[0] = true;
                }
                return;
            }
            BrewTowerController tower = found.orElseThrow(() -> new IllegalStateException("火室还点着，塔记录却删了"))
                    .controller();
            if (tower.status() != TowerStatus.BROKEN) {
                assertFailedBatch(context, site, tower);
                flashed[0] |= litBulbs(context, site).size() == BULBS.size();
                return;
            }
            context.assertTrue(flashed[0], "失败时铜灯没有四盏一起亮过");
            context.assertTrue(tower.phase() == TowerPhase.IDLE, "闪完以后塔没闲下来：" + tower.phase());
            context.assertTrue(tower.litSeconds() > 0, "火室里的煤炭块不算点着了");
            finish(context, run);
        });
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = FLOW_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void drainedAmbientInterruptsThenResumesWhenQiReturns(GameTestHelper context) {
        // #16：开熬以后每 tick 把水池所在区块这一秒的速率占满：欠付超过宽限就断锅、回到泡好待熬，
        // 占着期间一直说环境真元不够、不开熬；不占了，环境真元回来，塔自己重新开熬。
        startBetweenPayments(context, () -> {
            BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
            requireAmbient(context, site);
            stock(context, site, bitterStock());
            TowerRun run = open(context, site, BrewKind.BITTER_YELLOW);
            BrewingTestPlayers.close(run.chooser());
            Drain drain = new Drain(context, run, site.cauldron());
            context.onEachTick(drain::take);
            eachSecond(context, drain::observe);
        });
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = SHORT_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void burningLavaKeepsItsSecondsThroughARepairAndCannotBeScoopedBack(GameTestHelper context) {
        // 复审第 1 条：木桶里的燃料只有熔岩桶，泡料时倒一桶进火室点着；按停以后塔闲着，拆一盏铜灯：
        // 记录还在、剩的秒数不变；补回去再右键玻璃，认的还是这座塔，秒数照旧，不会当成新熔岩重新点满。
        // 最后用空桶舀那格点着的熔岩：舀不出东西，熔岩灭掉，下一秒那格不再算点着。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
        requireAmbient(context, site);
        stock(context, site, lavaStock());
        TowerRun run = open(context, site, BrewKind.BITTER_YELLOW);
        FuelRepair repair = new FuelRepair(context, site, run);
        eachSecond(context, repair::observe);
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = SHORT_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void minedBurningCoalBlockDropsNothing(GameTestHelper context) {
        // 复审第 1 条：泡料时火室里点着一块煤炭块；生存模式的人拿铁镐隔着铁栏杆把它挖了：它已经在烧，不掉东西，
        // 免得拿回一块整的再放回去点满。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
        requireAmbient(context, site);
        stock(context, site, bitterStock());
        TowerRun run = open(context, site, BrewKind.BITTER_YELLOW);
        ServerPlayer miner = run.chooser();
        miner.setGameMode(GameType.SURVIVAL);
        // 煤炭块要镐子才掉；空手挖本来就不掉，测不出什么。
        miner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        eachSecond(context, () -> {
            if (run.controller().phase() != TowerPhase.SOAKING || coalCells(context, site) == 0) {
                return;
            }
            BlockPos burning = burningCoal(context, site);
            miner.gameMode.destroyBlock(burning);
            context.assertFalse(context.getLevel().getBlockState(burning).is(Blocks.COAL_BLOCK), "点着的煤炭块挖不掉");
            AABB around = new AABB(burning).inflate(DROP_SEARCH);
            List<ItemEntity> dropped = context.getLevel().getEntitiesOfClass(ItemEntity.class, around,
                    item -> item.getItem().is(Items.COAL_BLOCK));
            context.assertTrue(dropped.isEmpty(), "挖点着的煤炭块掉了一块整的：" + dropped.size());
            finish(context, run);
        });
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = FLOW_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void brewedBatchWaitsThroughABrokenBarrelAndDeliversAfterRepair(GameTestHelper context) {
        // 复审第 4 条：熬着时把铜箱子塞满，熬好后塔停在熬好待收；拆掉南边一只空木桶，这一批留着不作废；
        // 补回木桶、腾空铜箱子，8 瓶照样进箱。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
        requireAmbient(context, site);
        stock(context, site, bitterStock());
        TowerRun run = open(context, site, BrewKind.BITTER_YELLOW);
        BrewingTestPlayers.close(run.chooser());
        BrewedRepair repair = new BrewedRepair(context, site, run);
        eachSecond(context, repair::observe);
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = FLOW_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void stoppingAfterAQiInterruptGivesNoExtraGlassBottle(GameTestHelper context) {
        // 复审第 5 条：辅料蜂蜜瓶下锅以后占满环境真元，欠付超过宽限就断锅回到泡料，这时按停退料。
        // 蜂蜜瓶取出时空瓶已经还了，退料不再整瓶退回：木桶里玻璃瓶加蜂蜜瓶的总数和开工前一样。
        startBetweenPayments(context, () -> {
            BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
            requireAmbient(context, site);
            stock(context, site, bitterStock());
            int bottlesBefore = bottleCount(context, site);
            TowerRun run = open(context, site, BrewKind.BITTER_YELLOW);
            LateRefund refund = new LateRefund(context, site, run, bottlesBefore);
            context.onEachTick(refund::take);
            eachSecond(context, refund::observe);
        });
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = FLOW_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void towerWhoseDefinitionIsGonePausesInsteadOfForgetting(GameTestHelper context) {
        // 复审第 6 条：把一座泡着料的塔改记成一份找不到的定义（和 /reload 删了三阶定义一样）：塔停着，
        // 记录、阶段、泡的秒数都不动；改回原定义，接着泡、接着熬。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
        requireAmbient(context, site);
        stock(context, site, bitterStock());
        TowerRun run = open(context, site, BrewKind.BITTER_YELLOW);
        BrewingTestPlayers.close(run.chooser());
        DefinitionPause pause = new DefinitionPause(context, run);
        eachSecond(context, pause::observe);
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = SHORT_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void markersStayInTheTowersDimensionAndButtonsRecheckPermission(GameTestHelper context) {
        // 复审第 7 条：窗口开着时把世界边界挪开、让水池落到边界外：按钮按不动；挪回来又能按（同一 tick 里挪回，
        // 不影响同批别的塔）。选药方的人去了下界，塔这边拆一盏铜灯：动作栏照样说第一处不符，
        // 标记粒子不发——发了会画在下界的同一坐标。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
        requireAmbient(context, site);
        TowerRun run = open(context, site, BrewKind.BITTER_YELLOW);
        ServerPlayer chooser = run.chooser();
        context.assertFalse(pressWithPoolOutsideBorder(context, site, chooser), "水池在世界边界外还能按窗口按钮");
        context.assertTrue(chooser.containerMenu.clickMenuButton(chooser, BrewTowerMenu.BUTTON_CONTINUOUS),
                "边界挪回来以后按钮按不动了");
        ServerLevel nether = context.getLevel().getServer().getLevel(Level.NETHER);
        Vec3 here = chooser.position();
        context.assertTrue(chooser.teleportTo(nether, here.x, NETHER_ROOF_TOP, here.z, Set.of(), 0, 0,
                false /* resetCamera */), "测试玩家去不了下界");
        int markersBefore = BrewingTestPlayers.markerParticles(chooser);
        context.getLevel().setBlockAndUpdate(site.world(BULBS.getFirst()), Blocks.AIR.defaultBlockState());
        eachSecond(context, () -> {
            if (!overlayKeys(chooser).contains(MISMATCH_KEY)) {
                return;
            }
            int sent = BrewingTestPlayers.markerParticles(chooser) - markersBefore;
            context.assertTrue(sent == 0, "人在下界还发了 " + sent + " 个标记粒子");
            finish(context, run);
        });
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = SHORT_TIMEOUT, structure = BrewSites.LV3_SITE)
    public void loneTintedGlassIsLeftToVanilla(GameTestHelper context) {
        // 复审第 3 条：不在塔上的一块染色玻璃，空手右键交给原版（PASS），也不建塔记录。
        // 每次右键读多少格写在报告里，这里只管认不出就放手。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BlockPos glass = context.absolutePos(LONE_GLASS);
        context.getLevel().setBlockAndUpdate(glass, Blocks.TINTED_GLASS.defaultBlockState());
        Set<UUID> before = towerIds(context.getLevel());
        InteractionResult used = rightClick(player, glass);
        context.assertFalse(used.consumesAction(), "一块不在塔上的玻璃也被药塔接住了：" + used);
        context.assertTrue(towerIds(context.getLevel()).equals(before), "一块玻璃也建了塔记录");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    private static boolean pressWithPoolOutsideBorder(GameTestHelper context, BrewSites.Site site,
                                                      ServerPlayer player) {
        // 边界中心挪到很远、缩成一小块，按一下就原样挪回；返回按钮按没按下去。
        WorldBorder border = context.getLevel().getWorldBorder();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        double size = border.getSize();
        BlockPos pool = site.cauldron();
        border.setCenter(pool.getX() + FAR_BORDER, pool.getZ() + FAR_BORDER);
        border.setSize(SMALL_BORDER);
        boolean pressed = player.containerMenu.clickMenuButton(player, BrewTowerMenu.BUTTON_CONTINUOUS);
        border.setCenter(centerX, centerZ);
        border.setSize(size);
        return pressed;
    }

    private static List<ItemStack> lavaStock() {
        // 苦黄膏两套料、8 个玻璃瓶，燃料换成 4 桶熔岩（有煤炭块会先用煤炭块）。
        List<ItemStack> stock = new ArrayList<>();
        stock.add(stack(BITTER_PRIMARY, SETS));
        stock.add(stack(BrewCauldron.BASE_INGREDIENT_ID, SETS));
        stock.add(stack("minecraft:honey_bottle", SETS));
        stock.add(stack("minecraft:glass_bottle", BOTTLES));
        for (int bucket = 0; bucket < FUEL_CELLS.size(); bucket++) {
            stock.add(new ItemStack(Items.LAVA_BUCKET));
        }
        return stock;
    }

    private static boolean soakingOverLitFuel(BrewTowerController tower) {
        // 开工那一秒只取料、还没点火，下一秒泡料才点着第一格。
        return tower.phase() == TowerPhase.SOAKING && tower.litSeconds() > 0;
    }

    private static BlockPos burningLava(GameTestHelper context, BrewSites.Site site) {
        // 泡料只点一格：火室里唯一的熔岩源。
        for (BlockPos cell : FUEL_CELLS) {
            BlockPos pos = site.world(cell);
            if (context.getLevel().getFluidState(pos).isSourceOfType(Fluids.LAVA)) {
                return pos;
            }
        }
        throw new IllegalStateException("火室里没有熔岩源");
    }

    private static BlockPos burningCoal(GameTestHelper context, BrewSites.Site site) {
        // 火室里放着煤炭块的第一格。
        for (BlockPos cell : FUEL_CELLS) {
            BlockPos pos = site.world(cell);
            if (context.getLevel().getBlockState(pos).is(Blocks.COAL_BLOCK)) {
                return pos;
            }
        }
        throw new IllegalStateException("火室里没有煤炭块");
    }

    private static int bottleCount(GameTestHelper context, BrewSites.Site site) {
        // 木桶里的玻璃瓶，加上蜂蜜瓶里装着的瓶子。
        return count(context, site, "minecraft:glass_bottle") + count(context, site, "minecraft:honey_bottle");
    }

    private static void assertBitterBatch(GameTestHelper context, BrewSites.Site site, BrewTowerController tower,
                                          Timeline timeline) {
        // 成药、退回的玻璃瓶、用掉的料、燃料秒数逐样核对。
        assertBitterPotions(context, potions(context, site));
        context.assertTrue(count(context, site, "minecraft:glass_bottle") == SETS,
                "两个蜂蜜瓶应退回两个玻璃瓶，木桶里实际 " + count(context, site, "minecraft:glass_bottle"));
        for (String used : List.of(BITTER_PRIMARY, BrewCauldron.BASE_INGREDIENT_ID,
                "minecraft:honey_bottle", "minecraft:cobweb")) {
            context.assertTrue(count(context, site, used) == 0, "两套料没用完：" + used);
        }
        int taken = COAL_BLOCKS - count(context, site, "minecraft:coal_block");
        context.assertTrue(coalCells(context, site) == taken, "从木桶补进火室的煤炭块数和火室里的对不上：" + taken);
        double expected = taken * BrewTowers.settings().coalBlockSeconds() - timeline.burnedCellSeconds;
        context.assertTrue(Math.abs(tower.litSeconds() - expected) < SECONDS_DELTA,
                String.format(Locale.ROOT, "燃料秒数扣得不对：剩 %.1f，应剩 %.1f", tower.litSeconds(), expected));
    }

    private static void assertBitterPotions(GameTestHelper context, List<ItemStack> potions) {
        // 正好 8 瓶苦黄膏，品质都不超过精良。
        context.assertTrue(potions.size() == BOTTLES, "应出 8 瓶，实际 " + potions.size());
        for (ItemStack potion : potions) {
            context.assertTrue(BrewItemStacks.kind(potion) == BrewKind.BITTER_YELLOW, "出的不是苦黄膏：" + potion);
            BrewQuality quality = BrewItemStacks.quality(potion);
            context.assertTrue(quality.compareTo(BrewQuality.FINE) <= 0, "药塔出的药超过了精良：" + quality);
        }
    }

    private static void assertFailedBatch(GameTestHelper context, BrewSites.Site site, BrewTowerController tower) {
        // 熬制中坏了：锅清空、不出药，主料不退（开熬时第二套已算用掉），说的是第一处不符。
        context.assertTrue(tower.phase() == TowerPhase.IDLE, "结构坏了这一批没作废：" + tower.phase());
        context.assertTrue(tower.status() == TowerStatus.FAILED, "结构坏了状态不是失败：" + tower.status());
        context.assertTrue(MISMATCH_KEY.equals(tower.problem().orElseThrow().key()),
                "失败原因不是结构不符：" + BrewTexts.chinese(tower.problem().orElseThrow()));
        context.assertTrue(count(context, site, "meridian_brewing:dry_moss") == 0, "熬制中失败却退了主料");
        context.assertTrue(potions(context, site).isEmpty(), "失败的一批出了药");
    }

    private static void assertAlternating(GameTestHelper context, List<Set<Integer>> lit) {
        // 每秒正好亮一组对角两盏，相邻两秒换另一组。
        for (int second = 0; second < lit.size(); second++) {
            context.assertTrue(lit.get(second).size() == LIT_PER_DIAGONAL, "等东西时应亮两盏：" + lit);
            if (second > 0) {
                Set<Integer> overlap = new HashSet<>(lit.get(second));
                overlap.retainAll(lit.get(second - 1));
                context.assertTrue(overlap.isEmpty(), "相邻两秒亮的是同一组：" + lit);
            }
        }
    }

    private static void startBetweenPayments(GameTestHelper context, Runnable start) {
        // 首次查询避开塔付款的整秒 tick，让环境换窗时测试先占满速率。
        // 序列在逐 tick 任务遍历结束后执行，开场可安全登记 onEachTick。
        if (context.getLevel().getGameTime() % TICKS_PER_SECOND == 0) {
            context.startSequence().thenExecuteAfter(1, start);
        } else {
            start.run();
        }
    }

    private static void requireAmbient(GameTestHelper context, BrewSites.Site site) {
        // 场地前提：水池所在区块的剩余速率够一批每秒那一份，不够时下面的断言都没有意义。
        RegionView region = BrewEnvironment.region(context.getLevel(), site.cauldron()).orElseThrow();
        double perSecond = BrewQiPayment.settings().brewCost(VesselKind.TOWER) / BrewCauldron.BREW_SECONDS;
        double spare = region.getSpareAbsorptionPerSecond();
        context.assertTrue(spare >= perSecond, String.format(Locale.ROOT,
                "测试区块剩余速率 %.3f 不够药塔每秒 %.3f，场地前提不成立", spare, perSecond));
    }

    private static TowerRun open(GameTestHelper context, BrewSites.Site site, BrewKind kind) {
        // 照玩家的做法：读过药方，站到塔北面空地上，空手右键玻璃认塔开窗，再按窗口里这个药方的按钮。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewingTestPlayers.readRecipes(player, kind);
        Vec3 stand = Vec3.atBottomCenterOf(site.stand());
        player.setPos(stand.x, stand.y, stand.z);
        ServerLevel level = context.getLevel();
        Set<UUID> before = towerIds(level);
        InteractionResult used = rightClick(player, site.world(ENTRY_GLASS));
        context.assertTrue(used.consumesAction(), "空手右键塔上的玻璃没被药塔接住：" + used);
        context.assertTrue(player.containerMenu instanceof BrewTowerMenu, "右键玻璃没开药塔窗口");
        Set<UUID> added = towerIds(level);
        added.removeAll(before);
        context.assertTrue(added.size() == 1, "认塔应新建一条记录：" + added);
        int button = BrewTowerMenu.BUTTON_FIRST_RECIPE + kind.ordinal();
        context.assertTrue(player.containerMenu.clickMenuButton(player, button), "读过的药方按不下去：" + kind);
        return new TowerRun(level, added.iterator().next(), player);
    }

    private static InteractionResult rightClick(ServerPlayer player, BlockPos pos) {
        // 空手对着这格的北面右键，和站在塔北面点玻璃一样。
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false /* inside */);
        return player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
                hit);
    }

    private static Set<UUID> towerIds(ServerLevel level) {
        // 这个维度现有的塔号；认塔前后一比就知道新建的是哪座。
        Set<UUID> ids = new HashSet<>();
        for (BrewTowerSavedData.Tower tower : BrewTowerSavedData.of(level).towers()) {
            ids.add(tower.id());
        }
        return ids;
    }

    private static void eachSecond(GameTestHelper context, Runnable sample) {
        // 每秒读一次，正好落在塔推进完的半秒后。
        context.onEachTick(() -> {
            if (context.getLevel().getGameTime() % TICKS_PER_SECOND == SAMPLE_TICK) {
                sample.run();
            }
        });
    }

    private static List<ItemStack> bitterStock() {
        // 苦黄膏两套料（不含引药）、收药的 8 个玻璃瓶、8 块煤炭块。
        List<ItemStack> stock = new ArrayList<>(bitterSupplies());
        stock.add(stack(BITTER_PRIMARY, SETS));
        return stock;
    }

    private static List<ItemStack> bitterSupplies() {
        // 除主料以外的：两套底料、辅料，8 个玻璃瓶，8 块煤炭块。
        return List.of(stack(BrewCauldron.BASE_INGREDIENT_ID, SETS), stack("minecraft:honey_bottle", SETS),
                stack("minecraft:glass_bottle", BOTTLES), stack("minecraft:coal_block", COAL_BLOCKS));
    }

    private static List<ItemStack> charmStock() {
        // 魅惑药水只能泼：两套主料、底料、火药、辅料、引药，加玻璃瓶和煤炭块。
        IngredientPlan plan = BrewRecipes.require(BrewKind.CHARM).ingredients();
        return List.of(stack("meridian_brewing:" + plan.preparedPrimary(), SETS),
                stack(BrewCauldron.BASE_INGREDIENT_ID, SETS), stack(BrewCauldron.PROPELLANT_INGREDIENT_ID, SETS),
                stack("minecraft:" + plan.accessory(), SETS), stack("minecraft:" + plan.catalyst(), SETS),
                stack("minecraft:glass_bottle", BOTTLES), stack("minecraft:coal_block", COAL_BLOCKS));
    }

    private static ItemStack stack(String itemId, int count) {
        // 测试用到的物品必须已注册，拿到空气说明 ID 写错了。
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        if (item == Items.AIR) {
            throw new IllegalStateException("测试物品没注册：" + itemId);
        }
        return new ItemStack(item, count);
    }

    private static void stock(GameTestHelper context, BrewSites.Site site, List<ItemStack> stacks) {
        // 和漏斗往里塞同一个入口；六个木桶都塞不下说明测试料备多了。
        for (ItemStack stack : stacks) {
            ItemStack left = stack.copy();
            for (Container barrel : containers(context, site, BARRELS)) {
                left = HopperBlockEntity.addItem(null, barrel, left, null);
            }
            context.assertTrue(left.isEmpty(), "木桶塞不下：" + stack);
        }
    }

    private static Container hopper(GameTestHelper context, BrewSites.Site site, BlockPos cell, Direction facing) {
        // 把结构里这格外框换成漏斗，朝向跟着结构摆放的转向换算。
        BlockPos pos = site.world(cell);
        Direction turned = site.rotation().rotate(facing);
        BlockState state = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, turned);
        context.getLevel().setBlockAndUpdate(pos, state);
        return containers(context, site, List.of(cell)).getFirst();
    }

    private static void fillChest(GameTestHelper context, BrewSites.Site site) {
        // 铜箱子两半的每一格都放一根木棍。
        for (Container half : containers(context, site, CHEST)) {
            for (int slot = 0; slot < half.getContainerSize(); slot++) {
                half.setItem(slot, new ItemStack(Items.STICK));
            }
        }
    }

    private static List<Container> containers(GameTestHelper context, BrewSites.Site site, List<BlockPos> cells) {
        // NBT 里这些格子在世界里的容器；摆上的结构里每格都该有。
        List<Container> containers = new ArrayList<>();
        for (BlockPos cell : cells) {
            if (!(context.getLevel().getBlockEntity(site.world(cell)) instanceof Container container)) {
                throw new IllegalStateException("结构这格没有容器：" + cell);
            }
            containers.add(container);
        }
        return containers;
    }

    private static int count(GameTestHelper context, BrewSites.Site site, String itemId) {
        // 六个木桶里这种物品一共几个。
        Item item = stack(itemId, 1).getItem();
        int total = 0;
        for (Container barrel : containers(context, site, BARRELS)) {
            total += barrel.countItem(item);
        }
        return total;
    }

    private static List<ItemStack> potions(GameTestHelper context, BrewSites.Site site) {
        // 铜箱子两半里的成药。
        return potionsIn(context, site, CHEST);
    }

    private static List<ItemStack> potionsIn(GameTestHelper context, BrewSites.Site site, List<BlockPos> cells) {
        // 这几格容器里的成药，一格一瓶。
        List<ItemStack> potions = new ArrayList<>();
        for (Container container : containers(context, site, cells)) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack item = container.getItem(slot);
                if (BrewItemStacks.isBrew(item)) {
                    potions.add(item);
                }
            }
        }
        return potions;
    }

    private static int coalCells(GameTestHelper context, BrewSites.Site site) {
        // 火室四格里放着煤炭块的格数。
        int cells = 0;
        for (BlockPos cell : FUEL_CELLS) {
            if (context.getLevel().getBlockState(site.world(cell)).is(Blocks.COAL_BLOCK)) {
                cells++;
            }
        }
        return cells;
    }

    private static Set<Integer> litBulbs(GameTestHelper context, BrewSites.Site site) {
        // 四盏铜灯里亮着的序号。
        Set<Integer> lit = new HashSet<>();
        for (int index = 0; index < BULBS.size(); index++) {
            BlockState bulb = context.getLevel().getBlockState(site.world(BULBS.get(index)));
            if (bulb.getValue(CopperBulbBlock.LIT)) {
                lit.add(index);
            }
        }
        return lit;
    }

    private static int timingScore(BrewTowerController tower) {
        // 按虚拟釜记下的辅料、引药下锅时刻算时分，和收药时的打分同一个公式。
        IngredientPlan plan = BrewRecipes.require(tower.batchKind().orElseThrow()).ingredients();
        Map<IngredientSlot, Double> times = tower.record().cauldron().ingredientTimes();
        return BrewScoring.timingScore(plan.accessoryWindow(), times.get(IngredientSlot.ACCESSORY),
                plan.catalystWindow(), times.get(IngredientSlot.CATALYST));
    }

    private static List<String> needs(BrewTowerController tower) {
        // 在等的每一样，中文，断言失败时看。
        List<String> lines = new ArrayList<>();
        tower.needs().values().forEach(text -> lines.add(BrewTexts.chinese(text)));
        return lines;
    }

    private static Set<String> overlayKeys(ServerPlayer player) {
        // 发给这个人的全部动作栏用到的语言键。
        Set<String> keys = new HashSet<>();
        for (BrewingTestPlayers.OverlaySent line : BrewingTestPlayers.overlays(player)) {
            keys.addAll(BrewingTestPlayers.translationKeys(line.text()));
        }
        return keys;
    }

    private static void finish(GameTestHelper context, TowerRun run) {
        // 删掉这座塔的记录、让测试玩家下线，免得场地清掉以后塔还在每秒复查。
        BrewTowerSavedData.of(run.level()).remove(run.id());
        BrewingTestPlayers.close(run.chooser());
        context.succeed();
    }

    /** 一座测试塔：维度、塔号、选药方的人。 */
    private record TowerRun(ServerLevel level, UUID id, ServerPlayer chooser) {
        Optional<BrewTowerSavedData.Tower> tower() {
            // 结构坏了、锅也空了时宿主会删记录，所以可能没有。
            return BrewTowerSavedData.of(level).find(id);
        }

        BrewTowerController controller() {
            // 还在的那座塔的控制器；已删时直接报错。
            return tower().orElseThrow().controller();
        }
    }

    /** 熔岩那条：等泡料→按停→拆铜灯→记录还在、秒数不变→补回、右键→同一座塔→舀熔岩→不再点着。 */
    private static final class FuelRepair {
        private final GameTestHelper context;
        private final BrewSites.Site site;
        private final TowerRun run;
        private final BlockPos bulb;
        private Stage stage = Stage.WAIT_SOAKING;
        private double secondsLeft;
        private BlockPos lava;
        private BlockState bulbState;

        private enum Stage { WAIT_SOAKING, STOPPED, BROKEN, REPAIRED, SCOOPED }

        private FuelRepair(GameTestHelper context, BrewSites.Site site, TowerRun run) {
            // 拆的是第一盏铜灯：它不挨着水池和火室，拆了不会流水、流熔岩。
            this.context = context;
            this.site = site;
            this.run = run;
            this.bulb = site.world(BULBS.getFirst());
        }

        private void observe() {
            // 每秒看一步，按阶段往下走。
            BrewTowerController tower = run.tower()
                    .orElseThrow(() -> new IllegalStateException("火室点着熔岩，塔记录却删了")).controller();
            switch (stage) {
                case WAIT_SOAKING -> stopWhenSoaking(tower);
                case STOPPED -> breakBulb(tower);
                case BROKEN -> repair(tower);
                case REPAIRED -> scoop(tower);
                case SCOOPED -> {
                    context.assertTrue(tower.litSeconds() == 0, "舀掉的熔岩还算点着：" + tower.litSeconds());
                    finish(context, run);
                }
            }
        }

        private void stopWhenSoaking(BrewTowerController tower) {
            // 泡料时熔岩已经倒进一格点着；按停，塔闲下来，熔岩接着在膛里（闲着不烧）。
            if (!soakingOverLitFuel(tower)) {
                return;
            }
            ServerPlayer chooser = run.chooser();
            context.assertTrue(chooser.containerMenu.clickMenuButton(chooser, BrewTowerMenu.BUTTON_STOP), "停按不下去");
            stage = Stage.STOPPED;
        }

        private void breakBulb(BrewTowerController tower) {
            // 停下以后记下剩的秒数，再拆铜灯。
            context.assertTrue(tower.phase() == TowerPhase.IDLE, "按停以后塔没闲下来：" + tower.phase());
            lava = burningLava(context, site);
            secondsLeft = tower.litSeconds();
            context.assertTrue(secondsLeft > 0, "火室里的熔岩没点着");
            bulbState = context.getLevel().getBlockState(bulb);
            context.getLevel().setBlockAndUpdate(bulb, Blocks.AIR.defaultBlockState());
            stage = Stage.BROKEN;
        }

        private void repair(BrewTowerController tower) {
            // 结构断了一秒：记录还在，秒数一点没少；补回铜灯，右键玻璃，认的是同一座塔、秒数照旧。
            context.assertTrue(tower.status() == TowerStatus.BROKEN, "拆了铜灯塔不算失效：" + tower.status());
            assertSecondsKept(tower);
            context.getLevel().setBlockAndUpdate(bulb, bulbState);
            Set<UUID> before = towerIds(context.getLevel());
            InteractionResult used = rightClick(run.chooser(), site.world(ENTRY_GLASS));
            context.assertTrue(used.consumesAction(), "补好以后右键玻璃没认出塔：" + used);
            context.assertTrue(towerIds(context.getLevel()).equals(before), "补好再右键又建了一条新记录");
            assertSecondsKept(tower);
            stage = Stage.REPAIRED;
        }

        private void scoop(BrewTowerController tower) {
            // 补好以后过了一秒，塔闲着不烧；用空桶舀点着的熔岩：舀不出东西，熔岩没了。
            assertSecondsKept(tower);
            BlockState state = context.getLevel().getBlockState(lava);
            ItemStack scooped = ((BucketPickup) state.getBlock()).pickupBlock(run.chooser(), context.getLevel(), lava,
                    state);
            context.assertTrue(scooped.isEmpty(), "点着的熔岩舀出了 " + scooped);
            context.assertFalse(context.getLevel().getFluidState(lava).isSource(), "舀过以后熔岩还在");
            stage = Stage.SCOOPED;
        }

        private void assertSecondsKept(BrewTowerController tower) {
            // 闲着、断着都不烧，秒数应和按停那一刻一样。
            context.assertTrue(Math.abs(tower.litSeconds() - secondsLeft) < SECONDS_DELTA,
                    "熔岩剩的秒数变了：" + secondsLeft + " → " + tower.litSeconds());
        }
    }

    /** 熬好待收那条：熬着时塞满铜箱子→熬好等空格→拆空木桶→这批还在→补回、腾空→8 瓶进箱。 */
    private static final class BrewedRepair {
        private final GameTestHelper context;
        private final BrewSites.Site site;
        private final TowerRun run;
        private final BlockPos barrel;
        private boolean filled;
        private boolean broken;
        private boolean repaired;

        private BrewedRepair(GameTestHelper context, BrewSites.Site site, TowerRun run) {
            // 料都在第一只木桶里，拆最后一只（南边东侧）不会掉料。
            this.context = context;
            this.site = site;
            this.run = run;
            this.barrel = site.world(BARRELS.getLast());
        }

        private void observe() {
            // 每秒看一步。
            BrewTowerController tower = run.tower()
                    .orElseThrow(() -> new IllegalStateException("熬好的一批还没收，塔记录却删了")).controller();
            if (!filled) {
                fillWhenBrewing(tower);
            } else if (!broken) {
                breakWhenWaiting(tower);
            } else if (!repaired) {
                repair(tower);
            } else if (potions(context, site).size() == BOTTLES && tower.phase() == TowerPhase.IDLE) {
                assertBitterPotions(context, potions(context, site));
                finish(context, run);
            }
        }

        private void fillWhenBrewing(BrewTowerController tower) {
            // 开熬以后才塞满：开工时要查铜箱子空格。
            if (tower.phase() == TowerPhase.BREWING) {
                fillChest(context, site);
                filled = true;
            }
        }

        private void breakWhenWaiting(BrewTowerController tower) {
            // 熬好了、凉到能收、等铜箱子空格时拆木桶。
            if (tower.phase() == TowerPhase.BREWED && tower.needs().containsKey(TowerNeed.CHEST)) {
                context.assertTrue(count(context, site, BITTER_PRIMARY) == 0, "要拆的木桶不该有料");
                context.getLevel().setBlockAndUpdate(barrel, Blocks.AIR.defaultBlockState());
                broken = true;
            }
        }

        private void repair(BrewTowerController tower) {
            // 断了一秒：熬好的这批还在、不闪灯；补回木桶、腾空铜箱子。
            context.assertTrue(tower.phase() == TowerPhase.BREWED, "结构断了熬好的一批没了：" + tower.phase());
            context.assertTrue(tower.status() == TowerStatus.BROKEN, "拆了木桶塔不算失效：" + tower.status());
            context.assertTrue(tower.bottles() == BOTTLES, "熬好的一批瓶数变了：" + tower.bottles());
            context.getLevel().setBlockAndUpdate(barrel, Blocks.BARREL.defaultBlockState());
            for (Container half : containers(context, site, CHEST)) {
                half.clearContent();
            }
            repaired = true;
        }
    }

    /** 退料那条：开熬→辅料下锅后占满环境真元→断锅回到泡料→放开、按停→瓶子总数不变。 */
    private static final class LateRefund {
        private final GameTestHelper context;
        private final BrewSites.Site site;
        private final TowerRun run;
        private final int bottlesBefore;
        private final double accessoryDone;
        private boolean draining;
        private boolean stopped;

        private LateRefund(GameTestHelper context, BrewSites.Site site, TowerRun run, int bottlesBefore) {
            // 辅料在时窗正中下锅，过了时窗末尾就一定在锅里。
            this.context = context;
            this.site = site;
            this.run = run;
            this.bottlesBefore = bottlesBefore;
            this.accessoryDone = BrewRecipes.require(BrewKind.BITTER_YELLOW).ingredients().accessoryWindow()
                    .endSeconds();
        }

        private void take() {
            // 占着的时候每 tick 用一个大额取用把本区块这一秒的速率占满。
            if (draining) {
                BrewEnvironment.draw("meridian_brewing_test:tower_drain/" + UUID.randomUUID(), context.getLevel(),
                        site.cauldron(), HOG_AMOUNT);
            }
        }

        private void observe() {
            // 每秒看一步。
            BrewTowerController tower = run.controller();
            if (stopped) {
                context.assertTrue(count(context, site, "minecraft:honey_bottle") == 0, "下了锅的蜂蜜瓶又整瓶退回来了");
                int bottles = bottleCount(context, site);
                context.assertTrue(bottles == bottlesBefore, "退料以后瓶子多了：" + bottlesBefore + " → " + bottles);
                finish(context, run);
            } else if (!draining) {
                draining = tower.phase() == TowerPhase.BREWING && tower.brewElapsedSeconds() > accessoryDone;
            } else if (tower.phase() == TowerPhase.SOAKING) {
                String reason = BrewTexts.chinese(tower.needs().get(TowerNeed.AMBIENT_QI));
                context.assertTrue(QI_INTERRUPTED.equals(reason), "断锅原因不对：" + reason);
                draining = false;
                ServerPlayer chooser = run.chooser();
                context.assertTrue(chooser.containerMenu.clickMenuButton(chooser, BrewTowerMenu.BUTTON_STOP),
                        "停按不下去");
                stopped = true;
            }
        }
    }

    /** 定义没了那条：泡料→改记成找不到的定义→停几秒都不动→改回→接着熬。 */
    private static final class DefinitionPause {
        private final GameTestHelper context;
        private final TowerRun run;
        private BrewTowerSavedData.Site original;
        private BrewTowerSavedData.Tower paused;
        private double soakedWhenPaused;
        private int pausedSeconds;
        private BrewTowerSavedData.Tower resumed;

        private DefinitionPause(GameTestHelper context, TowerRun run) {
            // 一开始照常泡料，等观察到泡料再换记录。
            this.context = context;
            this.run = run;
        }

        private void observe() {
            // 每秒看一步。
            BrewTowerSavedData data = BrewTowerSavedData.of(context.getLevel());
            if (paused == null) {
                pauseWhenSoaking(data);
            } else if (resumed == null) {
                watchPaused(data);
            } else if (data.find(resumed.id()).orElseThrow().controller().phase() == TowerPhase.BREWING) {
                data.remove(resumed.id());
                context.succeed();
            }
        }

        private void pauseWhenSoaking(BrewTowerSavedData data) {
            // 泡着料时把记录换成同一摆法、同一控制器，只是定义 ID 找不到。
            BrewTowerSavedData.Tower tower = run.tower().orElseThrow();
            if (tower.controller().phase() != TowerPhase.SOAKING) {
                return;
            }
            data.remove(tower.id());
            original = tower.site();
            paused = data.add(new BrewTowerSavedData.Site(MISSING_DEFINITION, original.origin(), original.rotation()),
                    tower.controller());
            soakedWhenPaused = paused.controller().brewElapsedSeconds();
        }

        private void watchPaused(BrewTowerSavedData data) {
            // 停着的几秒：记录在、还在泡料、没往下走；停够了换回原定义。
            BrewTowerSavedData.Tower tower = data.find(paused.id())
                    .orElseThrow(() -> new IllegalStateException("定义找不到就把塔忘了"));
            BrewTowerController controller = tower.controller();
            context.assertTrue(controller.phase() == TowerPhase.SOAKING, "定义找不到时塔还在往下走：" + controller.phase());
            context.assertTrue(controller.brewElapsedSeconds() == soakedWhenPaused, "定义找不到时还在熬");
            pausedSeconds++;
            if (pausedSeconds < PAUSED_SECONDS) {
                return;
            }
            data.remove(paused.id());
            resumed = data.add(original, controller);
        }
    }

    /** 每秒记一次阶段，累计每秒烧着的格数（一格烧一秒算一格秒）。 */
    private static final class Timeline {
        private final Map<TowerPhase, Integer> seconds = new EnumMap<>(TowerPhase.class);
        private int burnedCellSeconds;

        private void record(BrewTowerController tower) {
            // 每秒正好一次，所以这一秒的档数就是这一秒烧掉的格秒。
            seconds.merge(tower.phase(), 1, Integer::sum);
            burnedCellSeconds += tower.level();
        }

        @Override
        public String toString() {
            // 打进日志给报告看。
            return "各阶段秒数 " + seconds + "，烧掉 " + burnedCellSeconds + " 格秒";
        }
    }

    /** #16 的占速率和按秒观察：开熬后开始占，断锅后再占两秒确认不开熬，然后放开等它自己重开。 */
    private static final class Drain {
        private final GameTestHelper context;
        private final TowerRun run;
        private final BlockPos pool;
        private boolean draining;
        private boolean interrupted;
        private int pausedSeconds;

        private Drain(GameTestHelper context, TowerRun run, BlockPos pool) {
            // 一开始不占，等塔开熬。
            this.context = context;
            this.run = run;
            this.pool = pool;
        }

        private void take() {
            // 占着的时候每 tick 用一个大额取用把本区块这一秒的速率占满。
            if (draining) {
                BrewEnvironment.draw("meridian_brewing_test:tower_drain/" + UUID.randomUUID(), context.getLevel(),
                        pool, HOG_AMOUNT);
            }
        }

        private void observe() {
            // 开熬→开始占；断锅→记下原因；再占两秒都停在泡好待熬→放开；之后重新开熬就算过。
            BrewTowerController tower = run.controller();
            if (!draining && !interrupted) {
                draining = tower.phase() == TowerPhase.BREWING;
                return;
            }
            if (!interrupted) {
                interrupted = tower.phase() == TowerPhase.SOAKING;
                if (interrupted) {
                    String reason = BrewTexts.chinese(tower.needs().get(TowerNeed.AMBIENT_QI));
                    context.assertTrue(QI_INTERRUPTED.equals(reason), "断锅原因不对：" + reason);
                }
                return;
            }
            if (draining) {
                context.assertTrue(tower.phase() == TowerPhase.SOAKING, "环境真元占满时又开熬了");
                context.assertTrue(tower.needs().containsKey(TowerNeed.AMBIENT_QI), "占满时没说环境真元不够");
                pausedSeconds++;
                draining = pausedSeconds < PAUSED_SECONDS;
                return;
            }
            if (tower.phase() == TowerPhase.BREWING) {
                System.out.println("[药塔] 环境真元耗光后断锅、占满时停了 " + pausedSeconds + " 秒，放开后重新开熬");
                finish(context, run);
            }
        }
    }
}
