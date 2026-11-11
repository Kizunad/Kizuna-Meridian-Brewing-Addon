package dev.kizuna.meridian.brewing.clienttest;

import com.mojang.blaze3d.platform.Window;
import dev.kizuna.meridian.brewing.fabric.BrewWitchHut;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 女巫小屋的药方箱：固定种子的普通世界里，玩家自己在聊天栏输入 {@code /locate structure minecraft:swamp_hut}，
 * 传送过去等区块生成，拍小屋外观、屋里后墙一排（箱子、工作台、炼药锅）、打开的箱子和箱子里药方的提示框。
 * 小屋走真实的区块生成，箱子由 {@code WitchHutChestMixin} 在生成时放下。截图写到运行目录 {@code screenshots/}。
 */
public final class WitchHutClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("炼药截图");
    private static final String SCENE = "witch_hut";
    private static final String SEED = "20261111";
    private static final String LOCATE = "locate structure minecraft:swamp_hut";
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 960;
    /** 原版 /locate 的搜索半径，单位区块。 */
    private static final int LOCATE_RADIUS = 100;
    private static final int LOCATE_TICKS = 40;
    /** 小屋结构件从区块西北角起，最宽 9 格；找箱子时多看两格。 */
    private static final int HUT_SPAN = 9;
    private static final int SEARCH_MARGIN = 2;
    private static final int SEARCH_BELOW = 20;
    private static final int SEARCH_ABOVE = 40;
    private static final int ARRIVAL_HEIGHT = 120;
    /** 外观机位：{朝门廊几格, 往工作台那侧几格, 抬高几格}，斜看两面墙的在前。 */
    private static final List<int[]> OUTSIDE_VIEWS = List.of(
            new int[] {10, -4, 6}, new int[] {10, 6, 6}, new int[] {11, 1, 5},
            new int[] {8, -7, 8}, new int[] {8, 9, 8}, new int[] {12, 1, 10});
    /** 视线打在离小屋中心这么近的地方，算是看到了小屋。 */
    private static final double HUT_REACH = 4.5;
    private static final int OVERHEAD_RISE = 16;
    private static final int HUT_CENTER_STEPS = 2;
    /** 进门那一格：箱子正前方第 3 格、再往炼药锅那边 2 格（小屋本地 (4, 2, 3)）。 */
    private static final int DOOR_STEPS = 3;
    private static final int DOOR_SIDE_STEPS = 2;
    private static final double EYE_HEIGHT = 1.62;
    private static final int SLOT_CENTER = 8;
    private static final int TOOLTIP_TICKS = 5;

    /** 箱子的世界坐标、正面朝向和工作台在它哪一侧。 */
    private record HutChest(BlockPos pos, Direction facing, Direction tableSide) {
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Stage.selected(SCENE)) {
            return;
        }
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> {
                    // 普通世界、生成结构、允许命令；种子固定，小屋的位置和箱子里的东西每次一样。
                    settings.setSeed(SEED);
                    settings.setGenerateStructures(true);
                    settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    settings.setAllowCommands(true);
                })
                .create()) {
            Stage stage = Stage.prepare(context, world);
            stage.command("difficulty peaceful");
            BlockPos found = locate(context, stage);
            HutChest chest = arrive(stage, found);
            outside(stage, chest);
            inside(context, stage, chest);
        }
    }

    private static BlockPos locate(ClientGameTestContext context, Stage stage) {
        // 玩家自己在聊天栏输入 /locate，结果留在聊天栏里拍下来；坐标再按原版同一个入口在服务端算一遍，用来传送。
        stage.play();
        context.runOnClient(client -> client.player.connection.sendCommand(LOCATE));
        stage.pause(LOCATE_TICKS);
        context.takeScreenshot(TestScreenshotOptions.of("hut_01_locate").disableCounterPrefix());
        BlockPos found = stage.computeOnServer(server -> {
            ServerLevel level = server.overworld();
            var hut = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.SWAMP_HUT);
            var nearest = level.getChunkSource().getGenerator().findNearestMapStructure(
                    level, HolderSet.direct(hut), level.getRespawnData().pos(), LOCATE_RADIUS, false);
            return nearest == null ? null : nearest.getFirst();
        });
        if (found == null) {
            throw new AssertionError("种子 " + SEED + " 在 " + LOCATE_RADIUS + " 区块内没有女巫小屋");
        }
        LOGGER.info("[炼药截图] /locate 找到女巫小屋：{}", found);
        return found;
    }

    private static HutChest arrive(Stage stage, BlockPos found) {
        // 旁观模式传到小屋所在区块上空，等区块生成、画完，再到服务端找本仓的箱子。
        stage.spectate();
        ChunkPos chunk = ChunkPos.containing(found);
        stage.command(String.format(Locale.ROOT, "tp @a %d %d %d", chunk.getMiddleBlockX(), ARRIVAL_HEIGHT,
                chunk.getMiddleBlockZ()));
        stage.settle();
        HutChest chest = stage.computeOnServer(server -> findChest(server.overworld(), chunk, found.getY()));
        if (chest == null) {
            throw new AssertionError("女巫小屋 " + found + " 里没有本仓的药方箱");
        }
        LOGGER.info("[炼药截图] 小屋药方箱在 {}，正面朝 {}，工作台在它 {} 侧", chest.pos(), chest.facing(),
                chest.tableSide());
        return chest;
    }

    private static HutChest findChest(ServerLevel level, ChunkPos chunk, int locatedY) {
        // 小屋从区块西北角起；找挂着本仓战利品表的箱子，并看工作台在它哪一侧。
        int surface = locatedY == 0 ? level.getSeaLevel() : locatedY;
        BlockPos from = new BlockPos(chunk.getMinBlockX() - SEARCH_MARGIN, surface - SEARCH_BELOW,
                chunk.getMinBlockZ() - SEARCH_MARGIN);
        BlockPos to = new BlockPos(chunk.getMinBlockX() + HUT_SPAN + SEARCH_MARGIN, surface + SEARCH_ABOVE,
                chunk.getMinBlockZ() + HUT_SPAN + SEARCH_MARGIN);
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            if (level.getBlockEntity(pos) instanceof ChestBlockEntity entity
                    && BrewWitchHut.CHEST_TABLE.equals(entity.getLootTable())) {
                Direction facing = level.getBlockState(pos).getValue(ChestBlock.FACING);
                Direction tableSide = level.getBlockState(pos.relative(facing.getClockWise())).is(Blocks.CRAFTING_TABLE)
                        ? facing.getClockWise() : facing.getCounterClockWise();
                return new HutChest(pos.immutable(), facing, tableSide);
            }
        }
        return null;
    }

    private static void outside(Stage stage, HutChest chest) {
        // 从门廊那头的斜上方看整座小屋；沼泽里树多，挑第一个视线没被树叶、树干挡住的位置。
        Vec3 center = Vec3.atCenterOf(chest.pos().relative(chest.facing(), HUT_CENTER_STEPS)
                .relative(chest.tableSide()));
        Vec3 eye = stage.computeOnServer(server -> clearView(server.overworld(), chest, center));
        LOGGER.info("[炼药截图] 小屋外观机位 {}", eye);
        stage.look(eye, center);
        stage.shoot("hut_02_outside");
    }

    private static Vec3 clearView(ServerLevel level, HutChest chest, Vec3 center) {
        // 机位按（朝门廊几格，往工作台那侧几格，抬高几格）排先后；视线第一下打在小屋上才算看得见，都不行就从正上方看。
        for (int[] view : OUTSIDE_VIEWS) {
            Vec3 eye = Vec3.atCenterOf(chest.pos().relative(chest.facing(), view[0])
                    .relative(chest.tableSide(), view[1]).above(view[2]));
            BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
            boolean openEye = level.getBlockState(BlockPos.containing(eye)).isAir();
            if (openEye && hit.getLocation().distanceTo(center) <= HUT_REACH) {
                return eye;
            }
        }
        return center.add(0, OVERHEAD_RISE, 0);
    }

    private static void inside(ClientGameTestContext context, Stage stage, HutChest chest) {
        // 生存模式站进门那一格看后墙一排；右键打开箱子拍里面的东西，有药方就指着它拍提示框。
        stage.play();
        stage.command("gamemode survival @a");
        BlockPos door = chest.pos().relative(chest.facing(), DOOR_STEPS).relative(chest.tableSide(), DOOR_SIDE_STEPS);
        stage.look(Vec3.atBottomCenterOf(door).add(0, EYE_HEIGHT, 0), Vec3.atCenterOf(chest.pos()));
        stage.shoot("hut_03_chest_in_hut");
        stage.use();
        context.waitForScreen(ContainerScreen.class);
        context.getInput().setCursorPos(0, 0);
        stage.settle();
        stage.shoot("hut_04_chest_contents");
        LOGGER.info("[炼药截图] 小屋药方箱里：{}", contents(context));
        if (hoverRecipe(context)) {
            context.waitTicks(TOOLTIP_TICKS);
            stage.shoot("hut_05_recipe_tooltip");
        } else {
            LOGGER.info("[炼药截图] 这只箱子没抽到药方，不拍提示框");
        }
        context.setScreen(() -> null);
        stage.command("gamemode creative @a");
    }

    private static List<String> contents(ClientGameTestContext context) {
        // 箱子格是界面前 27 格；记物品 ID 和数量，药方另记是哪一种。
        return context.computeOnClient(client -> {
            var screen = (ContainerScreen) client.gui.screen();
            int rows = screen.getMenu().getRowCount();
            List<String> items = new ArrayList<>();
            for (Slot slot : screen.getMenu().slots.subList(0, rows * 9)) {
                if (!slot.getItem().isEmpty()) {
                    items.add(BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()) + " ×" + slot.getItem().getCount());
                }
            }
            return items;
        });
    }

    private static boolean hoverRecipe(ClientGameTestContext context) {
        // 光标移到第一张药方的格子中间；界面位置从当前界面读，界面坐标乘缩放换算成窗口像素。
        Vec2 cursor = context.computeOnClient(client -> {
            var screen = (AbstractContainerScreen<?>) client.gui.screen();
            Slot recipe = screen.getMenu().slots.stream()
                    .filter(slot -> BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).getPath().equals("brew_recipe"))
                    .findFirst()
                    .orElse(null);
            if (recipe == null) {
                return null;
            }
            Window window = client.getWindow();
            float scale = (float) window.getScreenWidth() / window.getGuiScaledWidth();
            return new Vec2((screenField(screen, "leftPos") + recipe.x + SLOT_CENTER) * scale,
                    (screenField(screen, "topPos") + recipe.y + SLOT_CENTER) * scale);
        });
        if (cursor == null) {
            return false;
        }
        context.getInput().setCursorPos(cursor.x, cursor.y);
        return true;
    }

    private static int screenField(AbstractContainerScreen<?> screen, String name) {
        // 原版没有公开界面左上角的位置，只在截图夹具里读受保护字段。
        try {
            Field field = AbstractContainerScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(screen);
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("原版 AbstractContainerScreen 没有 " + name + " 字段", missing);
        }
    }
}
