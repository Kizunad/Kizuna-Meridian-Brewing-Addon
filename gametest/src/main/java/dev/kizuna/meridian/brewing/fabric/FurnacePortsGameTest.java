package dev.kizuna.meridian.brewing.fabric;

import static dev.kizuna.meridian.brewing.fabric.BrewingStructureGameTest.ARENA;
import static dev.kizuna.meridian.brewing.fabric.BrewingStructureGameTest.first;
import static dev.kizuna.meridian.brewing.fabric.BrewingStructureGameTest.place;

import dev.kizuna.meridian.brewing.fabric.BrewingStructureGameTest.Placement;

import dev.kizuna.meridian.brewing.fabric.FurnacePatterns.Role;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewStructure;
import dev.kizuna.meridian.brewing.server.HeatSourceKind;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import dev.kizuna.meridian.brewing.server.VesselKind;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class FurnacePortsGameTest {
    private static final BlockPos SMALL = new BlockPos(3, 0, 4);
    private static final BlockPos MEDIUM = new BlockPos(5, 0, 5);
    private static final BlockPos LARGE = new BlockPos(8, 0, 8);
    private static final int OFFERED = 7;
    private static final int GREEN_SETS = 3;
    private static final int BLACK_SETS = 4;
    private static final int GREEN_FUEL = 75;
    private static final int COAL_BLOCK = 9;
    private static final int HIGH_FIRE = 4;
    private static final int CHUNK_WIDTH = 16;
    private static final BrewStructure HEAT = BrewStructure.built(HeatSourceKind.SUN_STOVE_2);
    private static final float STRAIGHT_DOWN = 90;
    private static final int BUCKET_UNITS = 3;

    @GameTest(structure = ARENA)
    public void creativeHandoffsConsumeTheFollowingUse(GameTestHelper context) {
        var layout = green(context);
        var player = BrewingTestPlayers.create(context);
        player.setGameMode(GameType.CREATIVE);
        var clicks = new Clicks(player);
        var rim = first(layout, Role.RIM);
        var ports = FurnacePorts.of(context.getLevel(), layout);
        var pot = ports.batch().cauldron;
        var bottle = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        clicks.offer(rim, new ItemStack(Items.WATER_BUCKET));
        context.assertTrue(pot.waterUnits() == BUCKET_UNITS, "创造水桶没有交到炉里");
        var level = player.level();
        context.assertTrue(level.getFluidState(rim).isEmpty() && level.getFluidState(rim.above()).isEmpty(),
                "创造水桶交接后仍执行了原版倒水");
        context.assertTrue(layout.check(level).status() == FurnaceLayout.Status.FORMED, "倒水后炉体损坏");
        clicks.offer(rim, bottle);
        context.assertTrue(pot.waterUnits() == BUCKET_UNITS + 1 && !player.isUsingItem(), "注入水瓶后又开始喝水");
        var fire = first(layout, Role.FIRES);
        var above = level.getBlockState(fire.above());
        clicks.offer(fire, new ItemStack(Items.OAK_LOG));
        context.assertTrue(ports.batch().fuelSeconds() == GREEN_FUEL && level.getBlockState(fire.above()) == above,
                "创造原木没有只添一次燃料");
        clicks.offer(rim, primary(GREEN_SETS));
        clicks.offer(rim, new ItemStack(Items.GLOWSTONE_DUST, GREEN_SETS));
        context.assertTrue(pot.startBrew(HEAT).accepted(), "主料和底料未交齐");
        var recipe = pot.recipe().orElseThrow();
        pot.tick(recipe.ingredients().accessoryWindow().startSeconds(), HEAT);
        clicks.offer(rim, new ItemStack(Items.HONEY_BOTTLE, GREEN_SETS));
        context.assertTrue(pot.needed(IngredientSlot.ACCESSORY) == 0 && !player.isUsingItem(), "投蜂蜜后又开始喝蜂蜜");
        pot.tick(BrewCauldron.BREW_SECONDS, HEAT);
        int before = pot.remainingBottles();
        clicks.offer(rim, new ItemStack(Items.GLASS_BOTTLE));
        context.assertTrue(pot.remainingBottles() == before - 1 && player.getInventory().countItem(Items.POTION) == 1
                && player.getMainHandItem().is(Items.GLASS_BOTTLE), "创造空瓶没有只取一瓶药");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void separateCreativeClicksKeepVanillaUse(GameTestHelper context) {
        var layout = green(context);
        var player = BrewingTestPlayers.create(context);
        player.setGameMode(GameType.CREATIVE);
        var clicks = new Clicks(player);
        var rim = first(layout, Role.RIM);
        clicks.block(rim, new ItemStack(Items.WATER_BUCKET));
        player.connection.handleClientTickEnd(ServerboundClientTickEndPacket.INSTANCE);
        clicks.item();
        var level = player.level();
        context.assertTrue(!level.getFluidState(rim).isEmpty() || !level.getFluidState(rim.above()).isEmpty(),
                "下一次独立使用水桶被炉口拦截或射线没有命中锅沿");
        var plain = context.absolutePos(BlockPos.ZERO).above();
        level.setBlockAndUpdate(plain, Blocks.STONE.defaultBlockState());
        clicks.offer(plain, new ItemStack(Items.WATER_BUCKET));
        context.assertTrue(!level.getFluidState(plain.above()).isEmpty(), "普通方块放行后没有倒水");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest
    public void portableCreativeBucketsFinishAtTheBlock(GameTestHelper context) {
        var player = BrewingTestPlayers.create(context);
        player.setGameMode(GameType.CREATIVE);
        var level = player.level();
        var pos = context.absolutePos(BlockPos.ZERO.above());
        player.setPos(Vec3.atCenterOf(pos.above()));
        for (var block : List.of(BrewingBlocks.COPPER_CAULDRON, BrewingBlocks.WITCH_CAULDRON)) {
            level.setBlockAndUpdate(pos, block.defaultBlockState());
            var stack = new ItemStack(Items.WATER_BUCKET);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false /* inside */);
            var result = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit);
            var entity = (BrewCauldronBlockEntity) level.getBlockEntity(pos);
            var kind = block == BrewingBlocks.COPPER_CAULDRON ? VesselKind.COPPER_CAULDRON : VesselKind.WITCH_CAULDRON;
            context.assertTrue(result.consumesAction() && entity.cauldron().waterUnits() == kind.waterCapacity()
                    && stack.is(Items.WATER_BUCKET) && level.getFluidState(pos.above()).isEmpty(),
                    "创造水桶未在便携锅的方块交互完成");
        }
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    private static final class Clicks {
        private final ServerPlayer player;
        private int sequence;

        Clicks(ServerPlayer player) {
            this.player = player;
            BrewingTestPlayers.acceptLoginPosition(player);
        }

        void offer(BlockPos pos, ItemStack stack) {
            block(pos, stack);
            item();
            player.connection.handleClientTickEnd(ServerboundClientTickEndPacket.INSTANCE);
        }

        void block(BlockPos pos, ItemStack stack) {
            player.setPos(Vec3.atCenterOf(pos.above()));
            player.setXRot(STRAIGHT_DOWN);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false /* inside */);
            var packet = new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, ++sequence);
            player.connection.handleUseItemOn(packet);
        }

        void item() {
            var packet = new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, ++sequence, 0, STRAIGHT_DOWN);
            player.connection.handleUseItem(packet);
        }
    }

    @GameTest(structure = ARENA)
    public void acceptsOnlyNeededPartOfStack(GameTestHelper context) {
        var layout = green(context);
        var player = BrewingTestPlayers.create(context);
        BlockPos rim = first(layout, Role.RIM);
        BlockPos fire = first(layout, Role.FIRES);
        var batch = FurnacePorts.of(context.getLevel(), layout).batch();
        var water = new ItemStack(Items.WATER_BUCKET);
        use(player, fire, water);
        context.assertTrue(water.getCount() == 1 && batch.cauldron.waterUnits() == 0, "炉底吞了水桶");
        use(player, rim, water);
        context.assertTrue(player.getInventory().countItem(Items.BUCKET) == 1, "注水没有归还空桶");
        var primary = primary(OFFERED);
        use(player, rim, primary);
        context.assertTrue(primary.getCount() == OFFERED - GREEN_SETS, "锅沿未按所需套数截取堆叠");
        use(player, rim, primary);
        context.assertTrue(primary.getCount() == OFFERED - GREEN_SETS, "已经收齐的主料又收了一次");
        var early = new ItemStack(Items.HONEY_BOTTLE);
        use(player, rim, early);
        context.assertTrue(early.getCount() == 1, "提前投的辅料被吃掉");
        var coal = new ItemStack(Items.COAL);
        use(player, rim, coal);
        context.assertTrue(coal.getCount() == 1 && batch.fuelSeconds() == 0, "锅沿收了煤");
        use(player, fire, coal);
        context.assertTrue(coal.isEmpty() && batch.fuelSeconds() == GREEN_FUEL, "营火没有收煤");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void unrelatedItemPassesAndEarlyIngredientIsRefused(GameTestHelper context) {
        var layout = green(context);
        var player = BrewingTestPlayers.create(context);
        BlockPos rim = first(layout, Role.RIM);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT));
        // 核对炉的交接返回值，玩家可继续执行原版的放置操作。
        for (var pos : List.of(rim, layout.anchor())) {
            player.setPos(Vec3.atCenterOf(pos.above()));
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false /* inside */);
            var result = UseBlockCallback.EVENT.invoker()
                    .interact(player, player.level(), InteractionHand.MAIN_HAND, hit);
            context.assertTrue(result == InteractionResult.PASS, "拿泥土点锅沿或底座拦截了原版操作");
        }
        var early = new ItemStack(Items.HONEY_BOTTLE);
        context.runAfterDelay(1, () -> {
            context.assertTrue(BrewingTestPlayers.overlays(player).isEmpty(), "无关物品收到了动作栏提示");
            use(player, rim, early);
        });
        context.runAfterDelay(2, () -> {
            var messages = BrewingTestPlayers.overlays(player);
            context.assertTrue(messages.size() == 1 && BrewingTestPlayers.translationKeys(messages.getFirst().text())
                    .contains("message.meridian_brewing.furnace.refused"), "提前交药没有提示不收");
            context.assertTrue(early.getCount() == 1, "提前交药吞了药材");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(structure = ARENA)
    public void droppedItemsRespectMaskOwnerAndQuantity(GameTestHelper context) {
        var layout = green(context);
        var level = context.getLevel();
        var player = BrewingTestPlayers.create(context);
        var ports = FurnacePorts.of(level, layout);
        use(player, first(layout, Role.RIM), new ItemStack(Items.WATER_BUCKET));
        BlockPos feed = first(layout, Role.FEED);
        var dose = drop(player, feed, primary(OFFERED));
        var wrong = drop(player, feed, new ItemStack(Items.REDSTONE));
        var outside = drop(player, layout.anchor().above(), primary(1));
        var fuel = drop(player, first(layout, Role.HEARTH), new ItemStack(Items.OAK_LOG));
        player.setGameMode(GameType.SPECTATOR);
        ports.tickSecond();
        context.assertTrue(dose.getItem().getCount() == OFFERED && fuel.isAlive(), "旁观者的掉落物被收");
        player.setGameMode(GameType.SURVIVAL);
        ports.tickSecond();
        context.assertTrue(dose.getItem().getCount() == OFFERED - GREEN_SETS && wrong.isAlive()
                && outside.isAlive(), "投料区域或地上余量有误");
        context.assertTrue(!fuel.isAlive() && ports.batch().fuelSeconds() == GREEN_FUEL, "炉底未收落地原木");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void litFiresShareFuelAndKeepItWhenCold(GameTestHelper context) {
        var layout = green(context);
        var level = context.getLevel();
        var player = BrewingTestPlayers.create(context);
        var ports = FurnacePorts.of(level, layout);
        use(player, layout.anchor(), new ItemStack(Items.COAL_BLOCK));
        long initial = COAL_BLOCK * GREEN_FUEL;
        context.assertTrue(ports.batch().fuelSeconds() == initial, "底座没有收煤块");
        ports.tickSecond();
        context.assertTrue(ports.batch().fuelSeconds() == initial - HIGH_FIRE, "多火位没有共用大火余量");
        for (var cell : layout.pattern().fires()) {
            BlockPos pos = layout.world(cell);
            var cold = level.getBlockState(pos).setValue(BlockStateProperties.LIT, false /* lit */);
            level.setBlockAndUpdate(pos, cold);
        }
        ports.tickSecond();
        context.assertTrue(ports.batch().fuelSeconds() == initial - HIGH_FIRE, "熄火仍耗燃料");
        BlockPos fire = first(layout, Role.FIRES);
        var lit = level.getBlockState(fire).setValue(BlockStateProperties.LIT, true /* lit */);
        level.setBlockAndUpdate(fire, lit);
        ports.tickSecond();
        context.assertTrue(ports.batch().fuelSeconds() == initial - HIGH_FIRE - 1, "只点一处不是小火");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void automaticWaitsForWorker(GameTestHelper context) {
        var layout = place(context, "lv3-auto", LARGE, Mirror.NONE, Rotation.NONE);
        var player = BrewingTestPlayers.create(context);
        var ports = FurnacePorts.of(context.getLevel(), layout);
        BlockPos rim = first(layout, Role.RIM);
        use(player, rim, new ItemStack(Items.WATER_BUCKET));
        use(player, rim, primary(BLACK_SETS));
        use(player, rim, new ItemStack(Items.GLOWSTONE_DUST, BLACK_SETS));
        use(player, first(layout, Role.FIRES), new ItemStack(Items.CHARCOAL));
        var pot = ports.batch().cauldron;
        context.assertTrue(pot.needed(IngredientSlot.PRIMARY) == 0 && pot.needed(IngredientSlot.BASE) == 0,
                "三级测试缺料");
        var start = pot.startBrew(HEAT);
        context.assertTrue(!start.accepted() && start.message().key().endsWith("furnace.worker"), "三级齐料无工人仍起熬");
        ports.tickSecond();
        context.assertTrue(pot.state() == BrewCauldronState.RAW && ports.batch().fuelSeconds() > 0, "三级自行炼药");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void lastBottleGoesToOneHandOrDrop(GameTestHelper context) {
        var layout = place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE);
        var level = context.getLevel();
        var firstPlayer = BrewingTestPlayers.create(context);
        var secondPlayer = BrewingTestPlayers.create(context);
        var ports = FurnacePorts.of(level, layout);
        BlockPos rim = first(layout, Role.RIM);
        // 按真实制药规则制好仅一份水的夹具；装瓶走玩家点击和地上投瓶。
        use(firstPlayer, rim, PotionContents.createItemStack(Items.POTION, Potions.WATER));
        use(firstPlayer, rim, primary(2));
        use(firstPlayer, rim, new ItemStack(Items.GLOWSTONE_DUST, 2));
        var pot = ports.batch().cauldron;
        context.assertTrue(pot.startBrew(HEAT).accepted(), "手动炉备料不全");
        pot.tick(BrewCauldron.BREW_SECONDS, HEAT);
        context.assertTrue(pot.remainingBottles() == 1, "缺水多出药");
        var thrown = drop(secondPlayer, first(layout, Role.FEED), new ItemStack(Items.GLASS_BOTTLE));
        var bottle = new ItemStack(Items.GLASS_BOTTLE);
        use(firstPlayer, rim, bottle);
        var other = new ItemStack(Items.GLASS_BOTTLE);
        use(secondPlayer, rim, other);
        ports.tickSecond();
        context.assertTrue(bottle.isEmpty() && thrown.isAlive() && other.getCount() == 1, "末瓶被重复扣取或吞空瓶");
        context.assertTrue(firstPlayer.getInventory().countItem(Items.POTION) == 1 && pot.remainingBottles() == 0,
                "末瓶没有交付唯一一份成药");
        BrewingTestPlayers.close(firstPlayer);
        BrewingTestPlayers.close(secondPlayer);
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void brokenFurnaceSavesStockUntilRepair(GameTestHelper context) throws IOException {
        var layout = green(context);
        var level = context.getLevel();
        var player = BrewingTestPlayers.create(context);
        var ports = FurnacePorts.of(level, layout);
        BlockPos rim = first(layout, Role.RIM);
        use(player, rim, new ItemStack(Items.WATER_BUCKET));
        use(player, rim, primary(1));
        use(player, layout.anchor(), new ItemStack(Items.CHARCOAL));
        var before = ports.batch().save();
        var slab = level.getBlockState(rim);
        level.setBlockAndUpdate(rim, Blocks.AIR.defaultBlockState());
        BrewingFurnaces.recheck(level);
        var register = FurnaceRegister.of(level);
        context.assertTrue(register.sites().stream().noneMatch(site -> site.anchor().equals(layout.anchor())),
                "损坏炉仍可操作");
        var folder = Files.createTempDirectory("furnace-stock");
        try (var storage = new SavedDataStorage(folder, level.getServer().getFixerUpper(), level.registryAccess())) {
            storage.set(FurnaceRegister.TYPE, register);
            storage.saveAndJoin();
        }
        try (var storage = new SavedDataStorage(folder, level.getServer().getFixerUpper(), level.registryAccess())) {
            var restored = storage.get(FurnaceRegister.TYPE);
            context.assertTrue(restored != null && restored.batch(layout).save().equals(before), "拆坏后存读档丢失物资");
        }
        level.setBlockAndUpdate(rim, slab);
        use(player, rim, primary(OFFERED));
        context.assertTrue(ports.batch().cauldron.needed(IngredientSlot.PRIMARY) == 0
                && player.getMainHandItem().getCount() == OFFERED - (GREEN_SETS - 1), "修好后未接回原半锅");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(structure = ARENA, maxTicks = 600)
    public void unloadedStockResumesOnce(GameTestHelper context) {
        // 单独一口炉完全放在测试场外的一个区块，移走玩家后等原版真的卸载。
        var level = context.getLevel();
        BlockPos yard = context.absolutePos(SMALL);
        final int distant = 32768;
        int x = Math.floorDiv(yard.getX() + distant, CHUNK_WIDTH) * CHUNK_WIDTH;
        int z = Math.floorDiv(yard.getZ(), CHUNK_WIDTH) * CHUNK_WIDTH;
        BlockPos corner = new BlockPos(x, yard.getY(), z);
        var placement = new Placement(corner, Mirror.NONE, Rotation.NONE);
        var layout = BrewingStructureGameTest.placeAt(level, "lv1", SMALL, placement);
        var player = BrewingTestPlayers.create(context);
        BlockPos rim = first(layout, Role.RIM);
        use(player, rim, new ItemStack(Items.WATER_BUCKET));
        use(player, rim, primary(1));
        BrewingTestPlayers.close(player);
        var batch = FurnacePorts.of(level, layout).batch();
        var before = batch.save();
        context.succeedWhen(() -> {
            context.assertTrue(!level.hasChunkAt(corner), "等原版卸载测试区块");
            BrewingFurnaces.recheck(level);
            context.assertFalse(level.hasChunkAt(corner), "炉记录强加载了区块");
            level.getChunkAt(corner);
            var repaired = BrewingFurnaces.inspect(level, rim);
            context.assertTrue(repaired != null && repaired.check().status() == FurnaceLayout.Status.FORMED,
                    "重载后炉体未恢复");
            var restored = FurnacePorts.of(level, repaired.layout()).batch();
            context.assertTrue(restored.save().equals(before) && restored.cauldron.needed(IngredientSlot.PRIMARY) == 1,
                    "卸载重载改变了水、余料或阶段");
        });
    }

    private static FurnaceLayout green(GameTestHelper context) {
        return place(context, "lv2", MEDIUM, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90);
    }

    private static ItemStack primary(int count) {
        return new ItemStack(BrewingItems.item("dry_moss"), count);
    }

    private static void use(ServerPlayer player, BlockPos pos, ItemStack item) {
        player.setPos(Vec3.atCenterOf(pos.above()));
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false /* inside */);
        player.gameMode.useItemOn(player, player.level(), item, InteractionHand.MAIN_HAND, hit);
    }

    private static ItemEntity drop(ServerPlayer owner, BlockPos pos, ItemStack stack) {
        ServerLevel level = owner.level();
        var center = Vec3.atCenterOf(pos);
        var dropped = new ItemEntity(level, center.x, center.y, center.z, stack);
        dropped.setThrower(owner);
        level.addFreshEntity(dropped);
        return dropped;
    }
}
