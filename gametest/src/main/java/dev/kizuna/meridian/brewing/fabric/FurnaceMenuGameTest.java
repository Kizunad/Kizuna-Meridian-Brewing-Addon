package dev.kizuna.meridian.brewing.fabric;

import static dev.kizuna.meridian.brewing.fabric.BrewingStructureGameTest.ARENA;
import static dev.kizuna.meridian.brewing.fabric.BrewingStructureGameTest.first;
import static dev.kizuna.meridian.brewing.fabric.BrewingStructureGameTest.place;

import dev.kizuna.meridian.brewing.fabric.FurnacePatterns.Role;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceRequest;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceClock;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewSteps;
import dev.kizuna.meridian.brewing.server.TimingWindow;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

public final class FurnaceMenuGameTest {
    private static final BlockPos SMALL = new BlockPos(3, 0, 4);
    private static final BlockPos MEDIUM = new BlockPos(5, 0, 5);
    private static final BlockPos LARGE = new BlockPos(8, 0, 8);
    private static final int BUCKET_WATER = 3;
    private static final int ACCESSORY_SECOND = 8;
    private static final int CATALYST_SECOND = 20;
    private static final int SMALL_BOTTLES = 6;
    private static final int MEDIUM_BOTTLES = 10;
    private static final int PREPARED_DAY = 7;
    private static final BrewKind BITTER = BrewKind.BITTER_YELLOW;

    @GameTest(structure = ARENA)
    public void unreadChoiceAndDetailsArePrivate(GameTestHelper context) {
        var layout = place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE);
        var learner = BrewingTestPlayers.create(context);
        BrewingTestPlayers.readRecipes(learner, BITTER, BITTER);
        var firstMenu = open(learner, layout);
        var catalog = firstMenu.snapshot().getCatalog();
        context.assertTrue(catalog.getVisibleCount() == 1 && catalog.getVisible(0).equals(BITTER.path()),
                "打开卷轴即合书没有得到唯一可选项");
        choose(learner, firstMenu, BITTER, 0);
        var outsider = BrewingTestPlayers.create(context);
        var other = open(outsider, layout);
        long version = other.snapshot().getOrders().getVersion();
        choose(outsider, other, BrewKind.QI_GATHER, version);
        var state = other.snapshot();
        context.assertTrue(state.getOrders().getVersion() == version, "伪造未读药方被接受");
        context.assertTrue(state.getCatalog().getVisibleCount() == 0 && state.getOrders().getBusy()
                && !state.getOrders().hasNext() && state.getClock().getDosesCount() == 0
                && other.timeline() == null, "未读者看到别人的药方");
        var scroll = RecipeScrollItem.stack(BITTER);
        outsider.setItemInHand(InteractionHand.OFF_HAND, scroll);
        context.assertTrue(FurnaceNetwork.book(outsider, BITTER.path()).getChoice().getRecipe().equals(BITTER.path())
                && FurnaceNetwork.book(outsider, BrewKind.QI_GATHER.path()) == null, "书中步骤没有绑定手持卷轴");
        BrewingTestPlayers.close(outsider);
        BrewingTestPlayers.close(learner);
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void pausedReaderCanStudyHeldScrollWithoutUnlock(GameTestHelper context) {
        var layout = place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE);
        var player = BrewingTestPlayers.create(context);
        BrewingTestPlayers.readRecipes(player, BITTER);
        BrewingBodyGameTest.pauseAfterLogin(context, player, () -> {
            var scroll = RecipeScrollItem.stack(BrewKind.QI_GATHER);
            player.setItemInHand(InteractionHand.MAIN_HAND, scroll);
            scroll.getItem().use(player.level(), player, InteractionHand.MAIN_HAND);
            var menu = open(player, layout);
            context.assertTrue(menu.snapshot().getCatalog().getVisibleCount() == 0
                    && FurnaceNetwork.book(player, BrewKind.QI_GATHER.path()) != null, "暂停应能看卷轴但不能选方");
            BrewingTestPlayers.close(player);
            var rejoined = BrewingTestPlayers.rejoin(context, player);
            var known = open(rejoined, layout).snapshot().getCatalog();
            context.assertTrue(known.getVisibleList().equals(List.of(BITTER.path())), "暂停时误写药方");
            BrewingTestPlayers.close(rejoined);
            context.succeed();
        });
    }

    @GameTest(structure = ARENA)
    public void staleSelectionRefreshesWithoutChangingNext(GameTestHelper context) {
        var layout = place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE);
        var firstPlayer = BrewingTestPlayers.create(context);
        var secondPlayer = BrewingTestPlayers.create(context);
        BrewingTestPlayers.readRecipes(firstPlayer, BITTER);
        BrewingTestPlayers.readRecipes(secondPlayer, BrewKind.QI_GATHER, BITTER);
        var firstMenu = open(firstPlayer, layout);
        var secondMenu = open(secondPlayer, layout);
        long old = secondMenu.snapshot().getOrders().getVersion();
        choose(firstPlayer, firstMenu, BITTER, old);
        firstPlayer.closeContainer();
        choose(secondPlayer, secondMenu, BrewKind.QI_GATHER, old);
        var refreshed = secondMenu.snapshot().getOrders();
        context.assertTrue(refreshed.getNext().getRecipe().equals(BITTER.path()), "过期选择覆盖先到的药方");
        choose(secondPlayer, secondMenu, BrewKind.QI_GATHER, refreshed.getVersion());
        context.assertTrue(secondMenu.snapshot().getOrders().getNext().getRecipe().equals(BrewKind.QI_GATHER.path()),
                "一人关窗后另一人不能改方");
        BrewingTestPlayers.close(firstPlayer);
        BrewingTestPlayers.close(secondPlayer);
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void reclaimReturnsOriginalStackBeforeRecipeChange(GameTestHelper context) {
        var layout = place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE);
        var player = BrewingTestPlayers.create(context);
        BrewingTestPlayers.readRecipes(player, BITTER, BrewKind.QI_GATHER);
        var menu = open(player, layout);
        choose(player, menu, BITTER, 0);
        var ports = FurnacePorts.of(context.getLevel(), layout);
        offer(player, ports, Role.RIM, new ItemStack(Items.WATER_BUCKET));
        var moss = new ItemStack(BrewingItems.item("dry_moss"));
        moss.set(BrewingComponents.PREPARED_DAY, (long) PREPARED_DAY);
        offer(player, ports, Role.RIM, moss);
        long version = ports.batch().version;
        choose(player, menu, BrewKind.QI_GATHER, version);
        context.assertTrue(ports.batch().next.kind() == BITTER, "改方吞了半锅料");
        var reclaim = FurnaceRequest.newBuilder()
                .setMenu(menu.containerId)
                .setVersion(version)
                .setReclaim(true /* reclaim */)
                .build();
        FurnaceNetwork.handle(player, reclaim);
        FurnaceNetwork.handle(player, reclaim);
        var inventory = player.getInventory();
        context.assertTrue(inventory.countItem(BrewingItems.item("dry_moss")) == 1, "退料重复或丢失");
        var refunded = inventory.getNonEquipmentItems().stream()
                .filter(stack -> stack.is(BrewingItems.item("dry_moss"))).findFirst().orElseThrow();
        context.assertTrue(refunded.getOrDefault(BrewingComponents.PREPARED_DAY, -1L) == PREPARED_DAY,
                "炮制日期被重置");
        choose(player, menu, BrewKind.QI_GATHER, ports.batch().version);
        context.assertTrue(ports.batch().next.kind() == BrewKind.QI_GATHER, "退回后不能换方");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void smallManualBatch(GameTestHelper context) {
        manual(context, place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE), SMALL_BOTTLES);
    }

    @GameTest(structure = ARENA)
    public void missedDosesAdvanceWithTheFurnaceClock(GameTestHelper context) {
        var layout = place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE);
        var player = BrewingTestPlayers.create(context);
        BrewingTestPlayers.readRecipes(player, BITTER);
        BrewingTestPlayers.stockQi(player);
        var menu = open(player, layout);
        choose(player, menu, BITTER, 0);
        var ports = FurnacePorts.of(context.getLevel(), layout);
        prepare(player, ports);
        ports.tickSecond();
        verifyMissedWindows(context, menu, ports, player);
        var finished = menu.snapshot().getClock();
        context.assertTrue(List.of("COOL", "COLLECT").contains(finished.getCurrentStep())
                && finished.getDosesList().stream().filter(dose -> dose.getMissed()).count() == 2,
                "熬完后没有进入收药或丢了漏投记录");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    private static void verifyMissedWindows(GameTestHelper context, FurnaceMenu menu, FurnacePorts ports,
                                           ServerPlayer player) {
        var plan = ports.batch().cauldron.recipe().orElseThrow().ingredients();
        var accessory = plan.accessoryWindow();
        var catalyst = plan.catalystWindow();
        for (int second = 0; second < BrewCauldron.BREW_SECONDS; second++) {
            var clock = menu.snapshot().getClock();
            String expected = "STEADY";
            if (accessory.contains(second)) {
                expected = "ACCESSORY";
            } else if (catalyst.contains(second)) {
                expected = "CATALYST";
            }
            context.assertTrue(clock.getElapsed() == second && clock.getCurrentStep().equals(expected)
                    && menu.timeline().getCurrentStep().equals(expected), "炉钟或步骤未按时窗前进，秒数 " + second);
            assertDose(context, clock, "ACCESSORY", accessory, second);
            assertDose(context, clock, "CATALYST", catalyst, second);
            if (second == accessory.endSeconds()) {
                offer(player, ports, Role.RIM, new ItemStack(Items.HONEY_BOTTLE));
            } else if (second == accessory.endSeconds() + 1) {
                offer(player, ports, Role.RIM, new ItemStack(Items.HONEY_BOTTLE));
                context.assertTrue(player.getMainHandItem().getCount() == 1, "关窗后仍收蜂蜜");
            } else if (second == catalyst.endSeconds() + 1) {
                offer(player, ports, Role.RIM, new ItemStack(Items.COBWEB));
                context.assertTrue(player.getMainHandItem().getCount() == 1 && clock.getWindowEnd() == 0,
                        "引药关窗后仍收料或仍显示旧窗口");
            }
            ports.tickSecond();
        }
    }

    private static void assertDose(GameTestHelper context, FurnaceClock clock, String slot,
                                   TimingWindow window, int second) {
        var dose = clock.getDosesList().stream().filter(row -> row.getSlot().equals(slot)).findFirst().orElseThrow();
        context.assertTrue(clock.getMissingList().contains(slot) == window.contains(second),
                slot + " 缺项与可投时间不符，秒数 " + second);
        context.assertTrue(dose.getMissed() == (second > window.endSeconds()), slot + " 漏投标记不符，秒数 " + second);
        if (slot.equals("ACCESSORY") && dose.getMissed()) {
            context.assertTrue(dose.getAdded() == 1, "最后一秒下的那份蜂蜜没有保留");
        }
    }

    @GameTest(structure = ARENA)
    public void mediumManualBatch(GameTestHelper context) {
        manual(context, place(context, "lv2", MEDIUM, Mirror.NONE, Rotation.NONE), MEDIUM_BOTTLES);
    }

    private static void manual(GameTestHelper context, FurnaceLayout layout, int bottles) {
        var player = BrewingTestPlayers.create(context);
        BrewingTestPlayers.readRecipes(player, BITTER, BrewKind.QI_GATHER);
        BrewingTestPlayers.stockQi(player);
        var menu = open(player, layout);
        choose(player, menu, BITTER, 0);
        var ports = FurnacePorts.of(context.getLevel(), layout);
        var batch = ports.batch();
        prepare(player, ports);
        ports.tickSecond();
        context.assertTrue(batch.active != null && batch.cauldron.state() == BrewCauldronState.BREWING,
                "选方人齐料在场没有开熬：" + batch.waiting);
        choose(player, menu, BrewKind.QI_GATHER, batch.version);
        var timeline = menu.timeline();
        context.assertTrue(timeline.getChoice().getRecipe().equals(BITTER.path())
                && batch.next.kind() == BrewKind.QI_GATHER, "改下一方改变了本批步骤");
        player.closeContainer();
        ports.tickSecond();
        var paid = batch.tally;
        BrewingTestPlayers.close(player);
        ports.tickSecond();
        context.assertTrue(batch.tally.equals(paid), "出元人断线仍推进或扣元");
        var rejoined = BrewingTestPlayers.rejoin(context, player);
        var resumed = open(rejoined, layout).snapshot();
        context.assertTrue(resumed.getClock().getElapsed() == paid.seconds(), "重连进度不符");
        var restored = new FurnaceBatch(layout.pattern().tier());
        restored.load(batch.save());
        context.assertTrue(restored.active.equals(batch.active) && restored.next.equals(batch.next)
                && restored.tally.equals(paid) && restored.batchNumber == batch.batchNumber, "存读档丢失批次");
        collect(context, rejoined, ports, bottles);
        BrewingTestPlayers.close(rejoined);
        context.succeed();
    }

    private static void prepare(ServerPlayer player, FurnacePorts ports) {
        var vessel = ports.batch().vessel;
        int sets = vessel.batch().sets();
        for (int water = 0; water < vessel.waterCapacity(); water += BUCKET_WATER) {
            offer(player, ports, Role.RIM, new ItemStack(Items.WATER_BUCKET));
        }
        offer(player, ports, Role.RIM, new ItemStack(BrewingItems.item("dry_moss"), sets));
        offer(player, ports, Role.RIM, new ItemStack(Items.GLOWSTONE_DUST, sets));
        offer(player, ports, Role.FIRES, new ItemStack(Items.SAND));
        offer(player, ports, Role.FIRES, new ItemStack(Items.COAL_BLOCK));
        // 两档稳火制苦黄膏，避免多蒸一瓶水。
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SHOVEL));
        ports.use(player, InteractionHand.MAIN_HAND, ports.layout().anchor());
        ports.use(player, InteractionHand.MAIN_HAND, ports.layout().anchor());
    }

    private static void collect(GameTestHelper context, ServerPlayer player, FurnacePorts ports, int bottles) {
        var batch = ports.batch();
        int sets = batch.vessel.batch().sets();
        for (int elapsed = batch.tally.seconds(); elapsed < BrewCauldron.BREW_SECONDS; elapsed++) {
            if (elapsed == ACCESSORY_SECOND) {
                offer(player, ports, Role.RIM, new ItemStack(Items.HONEY_BOTTLE, sets));
            }
            if (elapsed == CATALYST_SECOND) {
                offer(player, ports, Role.RIM, new ItemStack(Items.COBWEB, sets));
            }
            ports.tickSecond();
        }
        context.assertTrue(batch.tally.paid() > 0 && batch.cauldron.remainingBottles() == bottles,
                "没有真实耗元或未出满锅");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE, bottles));
        var rim = first(ports.layout(), Role.RIM);
        for (int bottle = 0; bottle < bottles; bottle++) {
            ports.use(player, InteractionHand.MAIN_HAND, rim);
        }
        context.assertTrue(player.getInventory().countItem(Items.POTION) == bottles && batch.active == null,
                "实物成药或收锅不符");
    }

    @GameTest(structure = ARENA)
    public void automaticSelectionWaitsForGolem(GameTestHelper context) {
        var layout = place(context, "lv3-auto", LARGE, Mirror.NONE, Rotation.NONE);
        var player = BrewingTestPlayers.create(context);
        BrewingTestPlayers.readRecipes(player, BITTER, BrewKind.FIRE_ELIXIR);
        var menu = open(player, layout);
        choose(player, menu, BITTER, 0);
        var ports = FurnacePorts.of(context.getLevel(), layout);
        prepare(player, ports);
        ports.tickSecond();
        var view = menu.snapshot();
        context.assertTrue(view.getCatalog().getVisibleCount() == 1 && view.getOrders().hasNext()
                && !view.getClock().hasWorker() && ports.batch().waiting.endsWith("furnace.worker")
                && ports.batch().active == null, "自动锅炉缺工人却开工或给出了丹露方");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    private static void offer(ServerPlayer player, FurnacePorts ports, Role role, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        ports.use(player, InteractionHand.MAIN_HAND, first(ports.layout(), role));
    }

    private static FurnaceMenu open(ServerPlayer player, FurnaceLayout layout) {
        var rim = first(layout, Role.RIM);
        player.setPos(Vec3.atCenterOf(rim.above()));
        FurnaceMenu.open(player, layout, rim);
        return (FurnaceMenu) player.containerMenu;
    }

    private static void choose(ServerPlayer player, FurnaceMenu menu, BrewKind kind, long version) {
        var choice = BrewSteps.choice(kind, BrewForm.DRINK);
        var request = FurnaceRequest.newBuilder()
                .setMenu(menu.containerId)
                .setVersion(version)
                .setSelect(choice)
                .build();
        FurnaceNetwork.handle(player, request);
    }
}
