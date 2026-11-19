package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.fabric.FurnacePatterns.Role;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class BrewingStructureGameTest {
    static final String ARENA = "meridian_brewing_test:furnace_yard";
    private static final BlockPos CORNER = new BlockPos(1, 1, 1);
    private static final BlockPos SMALL = new BlockPos(3, 0, 4);
    private static final int SMALL_FLOOR_HEIGHT = 5;
    private static final BlockPos MEDIUM = new BlockPos(5, 0, 5);
    private static final BlockPos LARGE = new BlockPos(8, 0, 8);
    private static final int TIMEOUT = 100;
    private static final int AFTER_RECHECK = 22;

    @GameTest(structure = ARENA)
    public void orangeShellBreaksAndRepairs(GameTestHelper context) {
        repair(context, "lv1", SMALL);
    }

    @GameTest(structure = ARENA)
    public void greenShellBreaksAndRepairs(GameTestHelper context) {
        repair(context, "lv2", MEDIUM);
    }

    @GameTest(structure = ARENA)
    public void blackShellBreaksAndRepairs(GameTestHelper context) {
        repair(context, "lv3-auto", LARGE);
    }

    private static void repair(GameTestHelper context, String tier, BlockPos center) {
        var layout = place(context, tier, center, Mirror.NONE, Rotation.NONE);
        requireFormed(context, layout);
        var level = context.getLevel();
        BlockPos fire = layout.world(layout.pattern().fires().getFirst());
        var original = level.getBlockState(fire);
        level.removeBlock(fire, false /* moving */);
        var broken = BrewingFurnaces.inspect(level, layout.anchor());
        context.assertTrue(broken != null && broken.check().status() == FurnaceLayout.Status.BROKEN
                && fire.equals(broken.check().first().pos()), "未指出缺少的营火 " + fire);
        level.setBlockAndUpdate(fire, original);
        requireFormed(context, layout);
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void fourRotationsForm(GameTestHelper context) {
        orientations(context, Mirror.NONE);
    }

    @GameTest(structure = ARENA)
    public void fourMirroredRotationsForm(GameTestHelper context) {
        orientations(context, Mirror.FRONT_BACK);
    }

    private static void orientations(GameTestHelper context, Mirror mirror) {
        for (Rotation rotation : Rotation.values()) {
            var layout = place(context, "lv3-auto", LARGE, mirror, rotation);
            var found = requireFormed(context, layout);
            context.assertTrue(found.layout().mirror() == mirror && found.layout().rotation() == rotation,
                    "三级营火和箱子在此摆法不等价 " + mirror + " " + rotation);
        }
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void liquidFootprintMustBeAir(GameTestHelper context) {
        var layout = place(context, "lv2", MEDIUM, Mirror.NONE, Rotation.NONE);
        BlockPos liquid = first(layout, Role.LIQUID);
        context.getLevel().setBlockAndUpdate(liquid, Blocks.GLASS.defaultBlockState());
        var found = BrewingFurnaces.inspect(context.getLevel(), layout.anchor());
        context.assertTrue(found != null && found.check().status() == FurnaceLayout.Status.BROKEN
                && found.check().first().pos().equals(liquid), "液面被占仍成形，或标错了格子");
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void copperOxidationWaxAndFireStateAreAllowed(GameTestHelper context) {
        var layout = place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE);
        var cell = layout.pattern().cells().stream().filter(c -> c.shown().is(block("waxed_cut_copper")))
                .findFirst().orElseThrow();
        var level = context.getLevel();
        BlockPos fire = first(layout, Role.FIRES);
        BlockPos floor = first(layout, Role.LIQUID).below();
        level.setBlockAndUpdate(fire, level.getBlockState(fire).setValue(BlockStateProperties.LIT, false /* lit */));
        for (Block copper : List.of(block("oxidized_cut_copper"), block("waxed_weathered_cut_copper"))) {
            var copperState = copper.defaultBlockState();
            level.setBlockAndUpdate(layout.world(cell), copperState);
            level.setBlockAndUpdate(floor, copperState);
            requireFormed(context, layout);
        }
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void inputChestCanHoldItemsBesideHopper(GameTestHelper context) {
        var layout = place(context, "lv3-auto", LARGE, Mirror.NONE, Rotation.NONE);
        var level = context.getLevel();
        BlockPos input = first(layout, Role.INPUTS);
        Direction outward = level.getBlockState(input).getValue(BlockStateProperties.HORIZONTAL_FACING);
        var hopper = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, outward.getOpposite());
        level.setBlockAndUpdate(input.relative(outward), hopper);
        var chest = (ChestBlockEntity) level.getBlockEntity(input);
        chest.setItem(0, new ItemStack(Items.DIRT));
        requireFormed(context, layout);
        context.assertTrue(chest.getItem(0).is(Items.DIRT), "认炉时改动了库存");
        context.succeed();
    }

    @GameTest(structure = ARENA, maxTicks = TIMEOUT)
    public void breakingRegisteredRimInvalidatesWithoutInteraction(GameTestHelper context) {
        var layout = place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE);
        requireFormed(context, layout);
        var level = context.getLevel();
        level.removeBlock(first(layout, Role.RIM), false /* moving */);
        context.runAfterDelay(AFTER_RECHECK, () -> {
            context.assertTrue(FurnaceRegister.of(level).sites().stream()
                    .noneMatch(site -> site.anchor().equals(layout.anchor())), "拆锅沿后仍然登记成形");
            context.succeed();
        });
    }

    @GameTest(structure = ARENA)
    public void repeatedInspectionRegistersOnePersistedFurnace(GameTestHelper context) throws IOException {
        var layout = place(context, "lv3-auto", LARGE, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90);
        requireFormed(context, layout);
        var level = context.getLevel();
        var rim = BrewingFurnaces.inspect(level, first(layout, Role.RIM));
        context.assertTrue(rim != null && rim.layout().anchor().equals(layout.anchor()), "点锅沿改变了锚点");
        var register = FurnaceRegister.of(level);
        long count = register.sites().stream().filter(site -> site.anchor().equals(layout.anchor())).count();
        context.assertTrue(count == 1, "同炉登记了多次");
        Path folder = Files.createTempDirectory("brewing-furnace-save");
        try (var store = storage(folder, level)) {
            store.set(FurnaceRegister.TYPE, register);
            store.saveAndJoin();
        }
        register.setDirty();
        try (var reopened = storage(folder, level)) {
            var read = reopened.get(FurnaceRegister.TYPE);
            context.assertTrue(read != null && read.sites().contains(FurnaceRegister.Site.of(layout)),
                    "新存档读取器未读到原等级、图样版本和摆法");
        }
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void intactRimOpensMenuAndBrokenRimReportsFault(GameTestHelper context) {
        var layout = place(context, "lv2", MEDIUM, Mirror.NONE, Rotation.NONE);
        var player = BrewingTestPlayers.create(context);
        var level = context.getLevel();
        BlockPos rim = first(layout, Role.RIM);
        var hit = new BlockHitResult(Vec3.atCenterOf(rim), Direction.UP, rim, false /* inside */);
        player.setPos(Vec3.atCenterOf(rim.above()));
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        context.assertTrue(player.containerMenu instanceof FurnaceMenu, "成形后没有打开炉窗");
        player.closeContainer();
        level.setBlockAndUpdate(first(layout, Role.LIQUID), Blocks.GLASS.defaultBlockState());
        player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        context.runAfterDelay(1, () -> {
            var name = Component.translatable("structure.meridian_brewing.cauldron-v2-lv2");
            var messages = BrewingTestPlayers.overlays(player).stream().map(sent -> sent.text()).toList();
            var formed = Component.translatable("message.meridian_brewing.furnace.formed", name);
            var broken = Component.translatable("message.meridian_brewing.furnace.broken", name);
            context.assertTrue(!messages.contains(formed) && messages.contains(broken),
                    "动作栏应只报带二阶蓝图名的缺损提示 " + messages);
            context.assertTrue(player.containerMenu == player.inventoryMenu, "认炉时打开了菜单");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(structure = ARENA)
    public void missingChunksStayUnloaded(GameTestHelper context) {
        var level = context.getLevel();
        var pattern = FurnacePatterns.all(level.getServer()).getFirst();
        final int distant = 2_000_000;
        var anchor = new BlockPos(distant, 0, distant);
        var layout = new FurnaceLayout(pattern, anchor, Mirror.NONE, Rotation.NONE);
        context.assertFalse(level.hasChunkAt(anchor), "远处测试区块已加载");
        context.assertTrue(layout.check(level).status() == FurnaceLayout.Status.UNLOADED, "未加载却给出判定");
        context.assertFalse(level.hasChunkAt(anchor), "检查时强加载了区块");
        context.succeed();
    }

    private static SavedDataStorage storage(Path folder, ServerLevel level) {
        return new SavedDataStorage(folder, level.getServer().getFixerUpper(), level.registryAccess());
    }

    @GameTest(structure = ARENA)
    public void retiredTowerHasNoFurnace(GameTestHelper context) {
        var level = context.getLevel();
        var id = Identifier.parse("meridian_brewing_test:retired_tower");
        BlockPos origin = context.absolutePos(CORNER);
        var template = level.getStructureManager().get(id).orElseThrow();
        template.placeInWorld(level, origin, origin, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_ALL);
        for (var pos : List.of(origin.offset(6, 5, 6), origin.offset(6, 6, 5), origin)) {
            context.assertTrue(BrewingFurnaces.inspect(level, pos) == null, "旧塔被认作新炉 " + pos);
        }
        context.succeed();
    }

    private static Block block(String name) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(name));
    }

    @GameTest(structure = ARENA)
    public void oldShroomlightMarksSmallFloorCell(GameTestHelper context) {
        var layout = place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE);
        BlockPos center = layout.anchor().above(SMALL_FLOOR_HEIGHT);
        replaceAndCheck(context, layout, center, Blocks.SHROOMLIGHT.defaultBlockState());
    }

    @GameTest(structure = ARENA)
    public void orangeConcreteMarksSmallFloor(GameTestHelper context) {
        checkFloor(context, "lv1", SMALL, block("orange_concrete"));
    }

    @GameTest(structure = ARENA)
    public void purpleConcreteMarksMediumFloor(GameTestHelper context) {
        checkFloor(context, "lv2", MEDIUM, block("purple_concrete"));
    }

    @GameTest(structure = ARENA)
    public void limeConcreteMarksLargeFloor(GameTestHelper context) {
        checkFloor(context, "lv3-auto", LARGE, block("lime_concrete"));
    }

    @GameTest(structure = ARENA)
    public void missingSmallFloorCellIsMarked(GameTestHelper context) {
        checkFloor(context, "lv1", SMALL, Blocks.AIR);
    }

    @GameTest(structure = ARENA)
    public void missingMediumFloorCellIsMarked(GameTestHelper context) {
        checkFloor(context, "lv2", MEDIUM, Blocks.AIR);
    }

    @GameTest(structure = ARENA)
    public void missingLargeFloorCellIsMarked(GameTestHelper context) {
        checkFloor(context, "lv3-auto", LARGE, Blocks.AIR);
    }

    private static void checkFloor(GameTestHelper context, String tier, BlockPos center, Block wrong) {
        var layout = place(context, tier, center, Mirror.NONE, Rotation.NONE);
        requireFormed(context, layout);
        var level = context.getLevel();
        var replacement = wrong.defaultBlockState();
        // 从液面足迹逐格向下取样，也能查出图样里漏标的炉身格。
        var surface = layout.pattern().cells().stream().filter(cell -> cell.role() == Role.LIQUID).toList();
        context.assertFalse(surface.isEmpty(), "缺少液面足迹 " + tier);
        for (var cell : surface) {
            BlockPos floor = layout.world(cell).below();
            var original = level.getBlockState(floor);
            assertReplacementFault(context, layout, floor, replacement);
            level.setBlockAndUpdate(floor, original);
        }
        requireFormed(context, layout);
        context.succeed();
    }

    @GameTest(structure = ARENA)
    public void wrongBodyMaterialMarksCopperCell(GameTestHelper context) {
        var layout = place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE);
        Block copper = block("waxed_cut_copper");
        var body = layout.pattern().cells().stream()
                .filter(cell -> cell.role() == Role.BODY && cell.shown().is(copper)).findFirst().orElseThrow();
        replaceAndCheck(context, layout, layout.world(body), Blocks.STONE.defaultBlockState());
    }

    @GameTest(structure = ARENA)
    public void upsideDownSlabMarksRimCell(GameTestHelper context) {
        var layout = place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE);
        BlockPos pos = first(layout, Role.RIM);
        var slab = context.getLevel().getBlockState(pos);
        SlabType reversed = slab.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.TOP
                ? SlabType.BOTTOM : SlabType.TOP;
        replaceAndCheck(context, layout, pos, slab.setValue(BlockStateProperties.SLAB_TYPE, reversed));
    }

    @GameTest(structure = ARENA)
    public void soulFireMarksOrdinaryFireCell(GameTestHelper context) {
        var layout = place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE);
        replaceMaterial(context, layout, Role.FIRES, Blocks.SOUL_CAMPFIRE);
    }

    @GameTest(structure = ARENA)
    public void ordinaryFireMarksSoulFireCell(GameTestHelper context) {
        var layout = place(context, "lv2", MEDIUM, Mirror.NONE, Rotation.NONE);
        replaceMaterial(context, layout, Role.FIRES, Blocks.CAMPFIRE);
    }

    @GameTest(structure = ARENA)
    public void copperChestMarksWoodenInput(GameTestHelper context) {
        var layout = place(context, "lv3-auto", LARGE, Mirror.NONE, Rotation.NONE);
        replaceMaterial(context, layout, Role.INPUTS, block("waxed_copper_chest"));
    }

    @GameTest(structure = ARENA)
    public void woodenChestMarksCopperOutput(GameTestHelper context) {
        var layout = place(context, "lv3-auto", LARGE, Mirror.NONE, Rotation.NONE);
        replaceMaterial(context, layout, Role.OUTPUTS, Blocks.CHEST);
    }

    @GameTest(structure = ARENA)
    public void doubleChestMarksInputHalf(GameTestHelper context) {
        var layout = place(context, "lv3-auto", LARGE, Mirror.NONE, Rotation.NONE);
        var level = context.getLevel();
        BlockPos input = first(layout, Role.INPUTS);
        var chest = level.getBlockState(input);
        Direction side = chest.getValue(BlockStateProperties.HORIZONTAL_FACING).getClockWise();
        var right = chest.setValue(BlockStateProperties.CHEST_TYPE, ChestType.RIGHT);
        level.setBlock(input.relative(side), right, Block.UPDATE_CLIENTS);
        replaceAndCheck(context, layout, input, chest.setValue(BlockStateProperties.CHEST_TYPE, ChestType.LEFT));
    }

    @GameTest(structure = ARENA)
    public void turnedChestMarksOutputCell(GameTestHelper context) {
        var layout = place(context, "lv3-auto", LARGE, Mirror.NONE, Rotation.NONE);
        BlockPos output = first(layout, Role.OUTPUTS);
        var chest = context.getLevel().getBlockState(output);
        replaceAndCheck(context, layout, output, chest.rotate(Rotation.CLOCKWISE_90));
    }

    @GameTest(structure = ARENA)
    public void extraCampfireMarksHearthCell(GameTestHelper context) {
        var layout = place(context, "lv1", SMALL, Mirror.NONE, Rotation.NONE);
        replaceAndCheck(context, layout, first(layout, Role.HEARTH), Blocks.CAMPFIRE.defaultBlockState());
    }

    private static void replaceMaterial(GameTestHelper context, FurnaceLayout layout, Role role, Block wrong) {
        // 沿用原朝向，把材质错误与几何错误分别测出来。
        BlockPos pos = first(layout, role);
        BlockState replacement = wrong.withPropertiesOf(context.getLevel().getBlockState(pos));
        replaceAndCheck(context, layout, pos, replacement);
    }

    private static void replaceAndCheck(GameTestHelper context, FurnaceLayout layout, BlockPos pos, BlockState wrong) {
        assertReplacementFault(context, layout, pos, wrong);
        context.succeed();
    }

    private static void assertReplacementFault(GameTestHelper context, FurnaceLayout layout, BlockPos pos,
                                               BlockState wrong) {
        var level = context.getLevel();
        level.setBlockAndUpdate(pos, wrong);
        context.assertTrue(level.getBlockState(pos).equals(wrong), "错放的方块没有留在测试位置 " + pos);
        var found = BrewingFurnaces.inspect(level, layout.anchor());
        context.assertTrue(found != null && found.check().status() == FurnaceLayout.Status.BROKEN,
                "错放 " + wrong + " 后仍成形或找不到炉 " + pos);
        context.assertTrue(found.check().first().pos().equals(pos), "标记应在 " + pos + "，实际 " + found.check().first());
    }

    static BlockPos first(FurnaceLayout layout, Role role) {
        var cell = layout.pattern().cells().stream().filter(c -> c.role() == role).findFirst().orElseThrow();
        return layout.world(cell);
    }

    private static BrewingFurnaces.Inspection requireFormed(GameTestHelper context, FurnaceLayout layout) {
        var found = BrewingFurnaces.inspect(context.getLevel(), layout.anchor());
        context.assertTrue(found != null && found.check().status() == FurnaceLayout.Status.FORMED
                && found.layout().anchor().equals(layout.anchor()) && found.layout().pattern() == layout.pattern(),
                "没有认成 " + layout.pattern().id() + " " + layout.mirror() + " " + layout.rotation()
                        + "，该摆法的首个错误 " + layout.check(context.getLevel()).first());
        return found;
    }

    static FurnaceLayout place(GameTestHelper context, String suffix, BlockPos center,
                                       Mirror mirror, Rotation rotation) {
        var placement = new Placement(context.absolutePos(CORNER), mirror, rotation);
        return placeAt(context.getLevel(), suffix, center, placement);
    }

    record Placement(BlockPos corner, Mirror mirror, Rotation rotation) {}

    static FurnaceLayout placeAt(ServerLevel level, String suffix, BlockPos center, Placement placement) {
        Mirror mirror = placement.mirror();
        Rotation rotation = placement.rotation();
        BlockPos corner = placement.corner();
        var id = Identifier.parse("meridian_brewing:cauldron-v2-" + suffix);
        var template = level.getStructureManager().get(id).orElseThrow();
        var pattern = FurnacePatterns.all(level.getServer()).stream().filter(p -> p.id().equals(id))
                .findFirst().orElseThrow();
        var box = template.getBoundingBox(BlockPos.ZERO, rotation, BlockPos.ZERO, mirror);
        BlockPos origin = corner.offset(-box.minX(), -box.minY(), -box.minZ());
        var settings = new StructurePlaceSettings()
                .setMirror(mirror)
                .setRotation(rotation);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_ALL);
        BlockPos anchor = origin.offset(StructureTemplate.transform(center, mirror, rotation, BlockPos.ZERO));
        return new FurnaceLayout(pattern, anchor, mirror, rotation);
    }
}
