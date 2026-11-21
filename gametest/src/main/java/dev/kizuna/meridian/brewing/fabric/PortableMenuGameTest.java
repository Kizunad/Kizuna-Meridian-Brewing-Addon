package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.protocol.pb.FurnaceRequest;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewSteps;
import dev.kizuna.meridian.brewing.server.VesselKind;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class PortableMenuGameTest {
    private static final BlockPos POT = new BlockPos(1, 2, 1);
    private static final int SECOND = 20;
    private static final int WARM_SECONDS = 45;
    private static final int ACCESSORY = 8;
    private static final int CATALYST = 20;
    private static final int FAR = 12;
    private static final int FULL_SCORE = 100;
    private static final double BASE_COST = 6.0;
    private static final double EPSILON = 0.00001;
    private static final long TICKS_PER_DAY = 24_000;
    private static final int REMOTE_DISTANCE = 32_768;
    private static final int UNLOAD_TIMEOUT = 600;
    private static final BrewKind KIND = BrewKind.BITTER_YELLOW;

    private record Kitchen(GameTestHelper test, ServerPlayer player, BrewCauldronBlockEntity pot) {
        FurnaceMenu open() {
            offer(ItemStack.EMPTY);
            test.assertTrue(player.containerMenu instanceof FurnaceMenu, "空手点釜未开炼药窗口");
            return (FurnaceMenu) player.containerMenu;
        }

        void select(BrewKind kind) {
            var menu = open();
            choose(player, menu, kind, menu.snapshot().getOrders().getVersion());
        }

        void offer(ItemStack stack) {
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            var pos = pot.getBlockPos();
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false /* inside */);
            player.gameMode.useItemOn(player, player.level(), stack, InteractionHand.MAIN_HAND, hit);
        }

        void second() {
            var ticker = BrewCauldronBlockEntity.ticker(BrewingBlocks.BREW_CAULDRON_ENTITY);
            for (int tick = 0; tick < SECOND; tick++) {
                ticker.tick(player.level(), pot.getBlockPos(), pot.getBlockState(), pot);
            }
        }

        void warm() {
            offer(new ItemStack(Items.WATER_BUCKET));
            offer(new ItemStack(BrewingItems.item("dry_moss")));
            offer(new ItemStack(Items.COAL));
            for (int second = 0; second < WARM_SECONDS; second++) {
                second();
            }
            offer(new ItemStack(Items.COAL));
            for (int second = 0; second < WARM_SECONDS; second++) {
                second();
            }
            offer(new ItemStack(Items.GLOWSTONE_DUST));
        }
    }

    private static Kitchen kitchen(GameTestHelper test, VesselKind kind) {
        return kitchen(test, kind, test.absolutePos(POT));
    }

    private static Kitchen kitchen(GameTestHelper test, VesselKind kind, BlockPos pos) {
        var level = test.getLevel();
        level.setBlockAndUpdate(pos.below(), BrewingBlocks.IRON_STOVE.defaultBlockState());
        var block = kind == VesselKind.COPPER_CAULDRON ? BrewingBlocks.COPPER_CAULDRON : BrewingBlocks.WITCH_CAULDRON;
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        var player = BrewingTestPlayers.create(test);
        player.setPos(Vec3.atCenterOf(pos.above()));
        BrewingTestPlayers.readRecipes(player, KIND);
        BrewingTestPlayers.stockQi(player);
        return new Kitchen(test, player, (BrewCauldronBlockEntity) level.getBlockEntity(pos));
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

    private static void reclaim(ServerPlayer player, FurnaceMenu menu) {
        var request = FurnaceRequest.newBuilder()
                .setMenu(menu.containerId)
                .setVersion(menu.snapshot().getOrders().getVersion())
                .setReclaim(true /* reclaim */)
                .build();
        FurnaceNetwork.handle(player, request);
    }

    @GameTest
    public void copperAutomaticBottlesAndPayment(GameTestHelper test) {
        automatic(test, VesselKind.COPPER_CAULDRON);
    }

    @GameTest
    public void witchAutomaticBottlesAndPayment(GameTestHelper test) {
        automatic(test, VesselKind.WITCH_CAULDRON);
    }

    private static void automatic(GameTestHelper test, VesselKind vessel) {
        var kit = kitchen(test, vessel);
        var menu = kit.open();
        var initial = menu.snapshot();
        test.assertTrue(initial.getCatalog().getVessel().equals(vessel.name())
                && initial.getClock().getMissingList().contains("water")
                && initial.getClock().getMissingList().contains("fire"), "空锅炉型、水火缺项不符");
        kit.select(KIND);
        kit.warm();
        kit.second();
        var batch = kit.pot().batch();
        test.assertTrue(batch.active != null && batch.active.owner().equals(kit.player().getUUID()), "没有自动开熬");
        var clock = kit.open().snapshot().getClock();
        test.assertTrue(clock.getFuel() > 0 && clock.getGrade() == 2 && !clock.getManual(), "炉底火档、余燃不符");
        var timeline = kit.open().timeline();
        test.assertTrue(timeline.getVessel().equals(vessel.name()) && !timeline.getManual(), "步骤炉型或熬法不符");
        finishCooking(kit);
        var tally = batch.tally;
        test.assertTrue(Math.abs(tally.paid() - BASE_COST * vessel.qiCost()) < EPSILON
                && tally.seconds() == BrewCauldron.BREW_SECONDS, "自动批次未按便携炉型实付");
        collect(kit, BrewQuality.FINE);
        BrewingTestPlayers.close(kit.player());
        test.succeed();
    }

    @GameTest
    public void manualPerfectBatchKeepsSuperior(GameTestHelper test) {
        var kit = kitchen(test, VesselKind.WITCH_CAULDRON);
        kit.select(KIND);
        kit.warm();
        startManual(kit);
        var menu = kit.open();
        test.assertTrue(menu.snapshot().getClock().getManual() && menu.timeline().getManual(), "手熬标记没有传到两处");
        finishCooking(kit);
        collect(kit, BrewQuality.SUPERIOR);
        BrewingTestPlayers.close(kit.player());
        test.succeed();
    }

    private static void startManual(Kitchen kit) {
        var result = kit.pot().startBrew(kit.player());
        kit.test().assertTrue(result.accepted(), "木棍入口没有先开熬：" + result.message());
        kit.pot().beginTechniqueControl(kit.player().getUUID());
    }

    private static void finishCooking(Kitchen kit) {
        var pot = kit.pot();
        var player = kit.player();
        boolean manual = pot.batch().active == null;
        for (int second = 0; second < BrewCauldron.BREW_SECONDS; second++) {
            if (second == ACCESSORY) {
                kit.offer(new ItemStack(Items.HONEY_BOTTLE));
            } else if (second == CATALYST) {
                kit.offer(new ItemStack(Items.COBWEB));
            }
            kit.second();
            if (manual) {
                kit.test().assertTrue(pot.cauldron().brewElapsedSeconds() == second, "手熬被自动炉钟推进");
                pot.advanceTechniqueBrew(player, "portable/manual/" + player.getUUID() + "/" + second);
            }
        }
        kit.offer(new ItemStack(Items.IRON_SHOVEL));
        for (int second = 0; second < WARM_SECONDS; second++) {
            kit.second();
        }
        var batch = pot.batch();
        var copy = new BrewCauldron(batch.vessel, BrewForm.DRINK);
        copy.restore(pot.cauldron().snapshot());
        var day = Math.floorDiv(player.level().getOverworldClockTime(), TICKS_PER_DAY);
        var score = copy.collectBottle(day).score().orElseThrow();
        kit.test().assertTrue(score.totalScore() == FULL_SCORE, "对比锅未达到满分：" + score);
    }

    private static void collect(Kitchen kit, BrewQuality expected) {
        var batch = kit.pot().batch();
        int count = batch.vessel.batch().bottles();
        kit.test().assertTrue(batch.cauldron.remainingBottles() == count, "出瓶数不符");
        for (int i = 0; i < count; i++) {
            kit.offer(new ItemStack(Items.GLASS_BOTTLE));
        }
        int collected = kit.player().getInventory().getNonEquipmentItems().stream()
                .filter(BrewItemStacks::isBrew)
                .filter(stack -> BrewItemStacks.quality(stack) == expected)
                .mapToInt(ItemStack::getCount).sum();
        kit.test().assertTrue(collected == count, "收药品质或瓶数不符，预期 " + expected + " × " + count);
        kit.test().assertTrue(batch.active == null && batch.cauldron.state() == BrewCauldronState.EMPTY, "末瓶未清锅");
    }

    @GameTest
    public void automaticOwnsBatchBeforeStaff(GameTestHelper test) {
        var kit = kitchen(test, VesselKind.WITCH_CAULDRON);
        kit.select(KIND);
        kit.warm();
        kit.second();
        var refusal = kit.pot().startBrew(kit.player());
        test.assertTrue(!refusal.accepted() && refusal.message().key().endsWith("automatic_in_charge"),
                "木棍起熬没有识别已接管的自动批次");
        verifyLate(kit);
        BrewingTestPlayers.close(kit.player());
        test.succeed();
    }

    @GameTest
    public void staffOwnsBatchBeforeAutomatic(GameTestHelper test) {
        var kit = kitchen(test, VesselKind.WITCH_CAULDRON);
        kit.select(KIND);
        kit.warm();
        startManual(kit);
        kit.second();
        test.assertTrue(kit.pot().batch().active == null && kit.pot().cauldron().brewElapsedSeconds() == 0,
                "木棍先开后自动炉钟仍开锅或扣时");
        verifyLate(kit);
        BrewingTestPlayers.close(kit.player());
        test.succeed();
    }

    private static void verifyLate(Kitchen kit) {
        var pot = kit.pot();
        boolean manual = pot.batch().active == null;
        var player = kit.player();
        double end = pot.cauldron().recipe().orElseThrow().ingredients().accessoryWindow().endSeconds();
        for (int second = 0; second <= end; second++) {
            if (manual) {
                pot.advanceTechniqueBrew(player, "portable/late/" + player.getUUID() + "/" + second);
            } else {
                kit.second();
            }
        }
        var clock = kit.open().snapshot().getClock();
        var row = clock.getDosesList().stream().filter(dose -> dose.getSlot().equals("ACCESSORY")).findFirst()
                .orElseThrow();
        kit.test().assertTrue(row.getMissed() && row.getLateAllowed() == manual, "手熬、自动迟投提示不符");
        kit.offer(new ItemStack(Items.HONEY_BOTTLE));
        kit.test().assertTrue(player.getMainHandItem().is(Items.HONEY_BOTTLE) != manual, "时窗关闭后投料规则不符");
    }

    @GameTest
    public void payerMustBeNearToStartAndContinue(GameTestHelper test) {
        var kit = kitchen(test, VesselKind.COPPER_CAULDRON);
        kit.select(KIND);
        kit.warm();
        var player = kit.player();
        player.closeContainer();
        var near = player.position();
        var far = near.add(FAR, 0, 0);
        player.setPos(far);
        kit.second();
        var batch = kit.pot().batch();
        test.assertTrue(batch.active == null && batch.tally == null, "选方人离场仍自动起熬");
        player.setPos(near);
        kit.second();
        kit.second();
        var paid = batch.tally;
        player.setPos(far);
        kit.second();
        test.assertTrue(paid != null && batch.tally.equals(paid) && batch.cauldron.brewElapsedSeconds() == 1,
                "离釜后进度或扣元仍走");
        player.setPos(near);
        kit.second();
        test.assertTrue(batch.tally.seconds() == paid.seconds() + 1, "回到釜旁没有续原账");
        BrewingTestPlayers.close(player);
        test.succeed();
    }

    @GameTest
    public void disconnectedPayerBatchCanBeReclaimedByAnotherPlayer(GameTestHelper test) {
        var kit = kitchen(test, VesselKind.WITCH_CAULDRON);
        kit.select(KIND);
        kit.warm();
        kit.offer(new ItemStack(Items.HONEY_BOTTLE));
        kit.second();
        var batch = kit.pot().batch();
        test.assertTrue(batch.active != null && kit.pot().cauldron().state() == BrewCauldronState.BREWING,
                "自动批次没有先开熬");

        BrewingTestPlayers.close(kit.player());
        var other = BrewingTestPlayers.create(test);
        other.setPos(Vec3.atCenterOf(kit.pot().getBlockPos().above()));
        BrewingTestPlayers.readRecipes(other, KIND, BrewKind.QI_GATHER);
        var guest = new Kitchen(test, other, kit.pot());
        kit.second();
        test.assertTrue(batch.active != null && batch.tally != null
                && kit.pot().cauldron().state() == BrewCauldronState.BREWING, "出元人断线时批次没有暂停");

        var menu = guest.open();
        test.assertTrue(menu.snapshot().getClock().getReclaimable(), "断线后的半锅没有退料入口");
        reclaim(other, menu);
        test.assertTrue(batch.active == null && batch.tally == null
                && other.getInventory().countItem(BrewingItems.item("dry_moss")) == 1
                && other.getInventory().countItem(Items.GLOWSTONE_DUST) == 1
                && other.getInventory().countItem(Items.HONEY_BOTTLE) == 0
                && kit.pot().cauldron().state() == BrewCauldronState.WATER, "另一玩家未能取回已有药材");
        choose(other, menu, BrewKind.QI_GATHER, batch.version);
        test.assertTrue(batch.next != null && batch.next.kind() == BrewKind.QI_GATHER, "退料后仍不能改方");
        BrewingTestPlayers.close(other);
        test.succeed();
    }

    @GameTest
    public void interruptedManualBrewDoesNotRefundContainerIngredient(GameTestHelper test) {
        var kit = kitchen(test, VesselKind.WITCH_CAULDRON);
        kit.warm();
        startManual(kit);
        var player = kit.player();
        for (int second = 0; second < ACCESSORY; second++) {
            kit.second();
            kit.pot().advanceTechniqueBrew(player, "portable/refund/" + player.getUUID() + "/" + second);
        }
        kit.offer(new ItemStack(Items.HONEY_BOTTLE));
        test.assertTrue(player.getInventory().countItem(Items.GLASS_BOTTLE) == 1
                && player.getInventory().countItem(Items.HONEY_BOTTLE) == 0, "蜂蜜瓶投料没有只返空瓶");
        kit.pot().interruptTechniqueBrew();

        var pot = kit.pot();
        var position = pot.getBlockPos();
        var title = pot.getBlockState().getBlock().getName();
        FurnaceMenu.open(player, new PortableStation(player.level(), pot), position, title);
        var menu = (FurnaceMenu) player.containerMenu;
        boolean refundableHoneyBottle = kit.pot().batch().refundable.stream()
                .anyMatch(stack -> stack.is(Items.HONEY_BOTTLE));
        test.assertTrue(!refundableHoneyBottle, "重建退料表仍含蜂蜜瓶");
        reclaim(player, menu);
        test.assertTrue(player.getInventory().countItem(Items.GLASS_BOTTLE) == 1
                && player.getInventory().countItem(Items.HONEY_BOTTLE) == 0,
                "手熬中断退料重复返还了已返空瓶的整瓶材料");
        BrewingTestPlayers.close(player);
        test.succeed();
    }

    @GameTest
    public void interruptedSplashMustBeReclaimedBeforeDrink(GameTestHelper test) {
        var kit = kitchen(test, VesselKind.WITCH_CAULDRON);
        kit.warm();
        kit.offer(new ItemStack(Items.GUNPOWDER));
        startManual(kit);
        kit.select(KIND);
        var pot = kit.pot();
        pot.interruptTechniqueBrew();
        kit.second();
        var menu = kit.open();
        var view = menu.snapshot();
        var orders = view.getOrders();
        var clock = view.getClock();
        test.assertTrue(orders.getActual().getForm().equals(BrewForm.SPLASH.name())
                && orders.getNext().getForm().equals(BrewForm.DRINK.name()), "中断后的泼剂被下一锅饮剂覆盖");
        test.assertTrue(pot.batch().active == null && clock.getReclaimable()
                && clock.getMissingList().contains("message.meridian_brewing.furnace.reclaim_first"),
                "锅中有火药却按饮剂开熬");
        var player = kit.player();
        reclaim(player, menu);
        test.assertTrue(player.getInventory().countItem(Items.GUNPOWDER) == 1
                && pot.cauldron().state() == BrewCauldronState.WATER, "退料没有还回火药或清除旧剂型");
        BrewingTestPlayers.close(player);
        test.succeed();
    }

    @GameTest
    public void privateChoicesRefundAndBreak(GameTestHelper test) {
        var kit = kitchen(test, VesselKind.COPPER_CAULDRON);
        var player = kit.player();
        BrewingTestPlayers.readRecipes(player, BrewKind.QI_GATHER);
        var other = BrewingTestPlayers.create(test);
        other.setPos(player.position());
        var guest = new Kitchen(test, other, kit.pot());
        var first = kit.open();
        var second = guest.open();
        long version = second.snapshot().getOrders().getVersion();
        choose(other, second, KIND, version);
        test.assertTrue(second.snapshot().getCatalog().getVisibleCount() == 0
                && kit.pot().batch().next == null, "未读者看到或伪造了选方");
        choose(player, first, KIND, version);
        test.assertTrue(second.snapshot().getClock().getDosesCount() == 0 && second.timeline() == null,
                "另一人看到了未读药方的步骤或材料");
        BrewingTestPlayers.readRecipes(other, BrewKind.QI_GATHER);
        choose(other, guest.open(), BrewKind.QI_GATHER, version);
        test.assertTrue(kit.pot().batch().next.kind() == KIND, "旧版本选方覆盖了先到的请求");
        other.closeContainer();
        refundAndBreak(kit, first);
        BrewingTestPlayers.close(other);
        BrewingTestPlayers.close(player);
        test.succeed();
    }

    private static void refundAndBreak(Kitchen kit, FurnaceMenu first) {
        var player = kit.player();
        var test = kit.test();
        kit.offer(new ItemStack(Items.WATER_BUCKET));
        var ingredient = BrewingItems.item("dry_moss");
        var named = new ItemStack(ingredient);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("药包"));
        kit.offer(named.copy());
        var batch = kit.pot().batch();
        choose(player, first, BrewKind.QI_GATHER, batch.version);
        test.assertTrue(batch.next.kind() == KIND && first.snapshot().getClock().getReclaimable(), "改方丢了半锅药");
        var request = FurnaceRequest.newBuilder()
                .setMenu(first.containerId)
                .setVersion(batch.version)
                .setReclaim(true /* reclaim */)
                .build();
        FurnaceNetwork.handle(player, request);
        FurnaceNetwork.handle(player, request);
        test.assertTrue(player.getInventory().countItem(ingredient) == 1, "取回原料重复或丢失");
        breakWithIngredient(kit, named);
    }

    private static void breakWithIngredient(Kitchen kit, ItemStack named) {
        kit.offer(named.copy());
        var level = kit.player().level();
        var pos = kit.pot().getBlockPos();
        var state = level.getBlockState(pos);
        var tag = kit.pot().saveWithFullMetadata(level.registryAccess());
        var restored = (BrewCauldronBlockEntity) BlockEntity.loadStatic(pos, state, tag, level.registryAccess());
        kit.test().assertTrue(restored != null && ItemStack.matches(restored.batch().refundable.getFirst(), named),
                "存读档改变了原料组件");
        kit.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        kit.player().gameMode.destroyBlock(pos);
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1));
        int count = drops.stream().map(ItemEntity::getItem).filter(stack -> ItemStack.matches(stack, named))
                .mapToInt(ItemStack::getCount).sum();
        kit.test().assertTrue(level.getBlockEntity(pos) == null && count == 1, "拆釜后带组件的锅料没有只掉一份");
    }

    @GameTest(maxTicks = UNLOAD_TIMEOUT)
    public void unloadedBatchResumesForSamePayer(GameTestHelper test) {
        var pos = test.absolutePos(POT).east(REMOTE_DISTANCE);
        var kit = kitchen(test, VesselKind.WITCH_CAULDRON, pos);
        kit.select(KIND);
        kit.warm();
        kit.second();
        kit.second();
        var batch = kit.pot().batch();
        var paid = batch.tally;
        var order = batch.active;
        var level = test.getLevel();
        var player = kit.player();
        player.closeContainer();
        BrewingTestPlayers.close(player);
        kit.second();
        test.assertTrue(batch.tally.equals(paid), "断线后仍付元");
        test.succeedWhen(() -> {
            test.assertTrue(!level.hasChunkAt(pos), "等待便携锅所在区块真实卸载");
            level.getChunkAt(pos);
            var restored = (BrewCauldronBlockEntity) level.getBlockEntity(pos);
            var loaded = restored.batch();
            test.assertTrue(loaded.tally.equals(paid) && loaded.active.equals(order) && loaded.next.equals(batch.next)
                    && loaded.batchNumber == batch.batchNumber && loaded.version == batch.version, "重载丢失批次或账");
            var rejoined = BrewingTestPlayers.rejoin(test, player);
            rejoined.setPos(Vec3.atCenterOf(pos.above()));
            var resumed = new Kitchen(test, rejoined, restored);
            test.assertTrue(resumed.open().snapshot().getClock().getElapsed() == paid.seconds(), "重开菜单进度不符");
            resumed.second();
            test.assertTrue(loaded.tally.seconds() == paid.seconds() + 1, "重登未续同一笔账");
            BrewingTestPlayers.close(rejoined);
        });
    }
}
