package dev.kizuna.meridian.brewing.clienttest;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewTowerMenu;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.TowerNeed;
import dev.kizuna.meridian.brewing.server.TowerPhase;
import dev.kizuna.meridian.brewing.server.TowerStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * 三座熬药结构的真实客户端场景（SPEC E2）：随包 NBT 用原版 {@code /place template} 摆出，白天从东南方高处斜 45°
 * 各拍一张全景；一阶拆掉一格外框，空手右键看釜，拍动作栏和不符那格的标记粒子；三阶站上北面台子空手右键玻璃开药塔窗口，
 * 拍没选药方、选了苦黄膏缺料、没学会的药方提示；木桶里备齐料后拍泡料，等铜灯亮北边两盏（熬制前半）从北面缝里拍灯，
 * 再开窗口拍熬制中；最后等铜箱子里 8 瓶，打开拍一张。
 *
 * <p>开窗、选药方、开箱子都是真实右键和鼠标点击；学药方用调试指令，备料用原版 {@code /item replace}。每步把塔窗口的整数表、
 * 灯和箱子写进日志，截图只摆场景，不比对像素。世界用 Fabric 默认的超平坦平原，环境真元够药塔开工。</p>
 */
public final class BrewStructureClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SCENE = "structures";
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 960;
    private static final String BREWING = "meridian_brewing:";
    private static final String MAIN_HAND = "weapon.mainhand";
    private static final String EMPTY = "minecraft:air";
    private static final Shipped LV1 = new Shipped(BREWING + "potion-lv1", new BlockPos(-2, 0, -2),
            new Vec3(0.5, 2.0, 0.5), new Vec3(5.0, 4.5, 5.0));
    private static final Shipped LV2 = new Shipped(BREWING + "potion-lv2", new BlockPos(-5, 0, -5),
            new Vec3(0.5, 2.5, 0.5), new Vec3(9.5, 8.0, 9.5));
    private static final Shipped LV3 = new Shipped(BREWING + "potion-lv3-auto", new BlockPos(-7, 0, -7),
            new Vec3(0.5, 6.5, 0.0), new Vec3(14.0, 11.0, 14.0));
    /** 一阶 NBT (1,1,3) 西南那根墙，拆掉结构就不认了；铜釜 NBT (2,2,2)。人站在南面地上，釜和拆掉那格都在眼前。 */
    private static final BlockPos LV1_REMOVED = new BlockPos(-1, 1, 1);
    private static final BlockPos LV1_CAULDRON = new BlockPos(0, 2, 0);
    private static final BlockPos LV1_STAND = new BlockPos(0, 0, 4);
    /** 默认界面缩放下动作栏那句太长、两头出屏；缩到 2 才拍得全。 */
    private static final int WIDE_GUI_SCALE = 2;
    /** 三阶北面玻璃前的台子：NBT (6,4,4) 上半砖，脚站在 (6,5,4)；对着药池北面那排玻璃西一格 NBT (6,6,5) 右键。 */
    private static final Vec3 LEDGE_EYE = new Vec3(-0.5, 6.62, -2.6);
    private static final BlockPos ENTRY_GLASS = new BlockPos(-1, 6, -2);
    /** 西边北那只木桶 NBT (5,1,6)；料全放这一只里，塔按西 → 东 → 南取。 */
    private static final BlockPos FIRST_BARREL = new BlockPos(-2, 1, -1);
    /** 苦黄膏两套料（主料、底料、辅料、引药），8 个玻璃瓶，8 块煤炭块。 */
    private static final List<String> BITTER_STOCK = List.of(BREWING + "dry_moss 2", "minecraft:glowstone_dust 2",
            "minecraft:honey_bottle 2", "minecraft:cobweb 2", "minecraft:glass_bottle 8", "minecraft:coal_block 8");
    /** 铜灯 NBT (6..7,9,6..7)，按结构里自下而上、北到南、西到东的顺序；熬制前半亮北边那排两盏。 */
    private static final List<BlockPos> LAMPS = List.of(new BlockPos(-1, 9, -1), new BlockPos(0, 9, -1),
            new BlockPos(-1, 9, 0), new BlockPos(0, 9, 0));
    private static final int NORTH_ROW_LIT = 0b0011;
    /** 灯顶上盖着下半砖，北面 y=9 半砖和 y=10 之间留半格缝，从北边稍高处看得到灯的上半截。 */
    private static final Vec3 LAMP_EYE = new Vec3(0.0, 10.3, -3.5);
    private static final Vec3 LAMP_FACE = new Vec3(0.0, 9.75, -1.0);
    /** 铜大箱子 NBT (6..7,1,5)，箱前是两格高的凹口；人站在凹口外面。 */
    private static final List<BlockPos> CHEST = List.of(new BlockPos(-1, 1, -2), new BlockPos(0, 1, -2));
    private static final Vec3 CHEST_EYE = new Vec3(-0.5, 2.62, -3.7);
    private static final int BOTTLES = 8;
    /** 照 {@code BrewTowerScreen} 的布局：底板 176×166 居中，药方格从 (8, 18) 起一格 16×18。 */
    private static final int TOWER_WIDTH = 176;
    private static final int TOWER_HEIGHT = 166;
    private static final int TILE_LEFT = 8;
    private static final int TILE_TOP = 18;
    private static final int TILE_WIDTH = 16;
    private static final int TILE_HEIGHT = 18;
    private static final int TICKS_PER_SECOND = 20;
    private static final int OPEN_TIMEOUT_TICKS = 2 * TICKS_PER_SECOND;
    /** 泡料 13 秒加升温、再熬到半程；熬完 30 秒、待收 5 秒装瓶，都留出余量。 */
    private static final int TOWER_TIMEOUT_TICKS = 90 * TICKS_PER_SECOND;
    private static final int POTIONS_TIMEOUT_TICKS = 120 * TICKS_PER_SECOND;
    private static final int POLL_TICKS = TICKS_PER_SECOND;
    private static final int LOG_EVERY_POLLS = 5;
    private static final int CLICK_TICKS = 5;
    /** 标记粒子 4 秒、动作栏 3 秒；等它们都散了再拍那格的缺口。 */
    private static final int MARKER_GONE_TICKS = 5 * TICKS_PER_SECOND;
    private static final double TENTHS = 10.0;
    private static final int NO_WINDOW = -1;

    /** 一座结构：模板、NBT 原点落在场地哪一格、全景对准哪、镜头在那一点的哪个方向多远。 */
    private record Shipped(String template, BlockPos origin, Vec3 focus, Vec3 camera) {
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Stage.selected(SCENE)) {
            return;
        }
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Stage stage = Stage.prepare(context, world);
            overview(stage, LV2, "struct_02_lv2_overview");
            overview(stage, LV1, "struct_01_lv1_overview");
            lv1Mismatch(stage);
            overview(stage, LV3, "struct_03_lv3_overview");
            towerWindow(context, stage);
            towerBrewing(context, stage);
            towerPotions(context, stage);
        }
    }

    private static void overview(Stage stage, Shipped shipped, String shot) {
        // 清场摆上这座结构，旁观、不画界面，从东南方高处斜 45° 看全貌。
        stage.clear();
        stage.spectate();
        stage.template(shipped.origin(), shipped.template());
        Vec3 focus = stage.at(shipped.focus());
        stage.look(focus.add(shipped.camera()), focus);
        stage.shoot(shot);
    }

    private static void lv1Mismatch(Stage stage) {
        // 一阶已摆好：拆掉一格外框，创造模式空手对着釜右键，动作栏写哪一格不对，那格冒应有方块的标记。
        // 标记画的就是该放的方块，和旁边的外框看不出分别，所以等它散了再拍一张，看得到那格的缺口。
        stage.place(LV1_REMOVED, EMPTY);
        stage.play();
        stage.command("clear @a");
        int previousScale = stage.guiScale(WIDE_GUI_SCALE);
        stage.look(stage.eye(LV1_STAND), stage.center(LV1_CAULDRON));
        stage.use();
        stage.settle();
        stage.shoot("struct_04_lv1_mismatch");
        stage.pause(MARKER_GONE_TICKS);
        stage.shoot("struct_04b_lv1_gap");
        stage.guiScale(previousScale);
        LOGGER.info("[结构截图] struct_04_lv1_mismatch：拆掉 {}，釜在 {}", stage.block(LV1_REMOVED),
                stage.block(LV1_CAULDRON));
    }

    private static void towerWindow(ClientGameTestContext context, Stage stage) {
        // 学会除招虫水以外的药方，开窗拍没选药方；点苦黄膏，木桶是空的，缺料那排有内容；再指着没学会的招虫水看提示。
        stage.command("meridian progression recipes learn @a all");
        stage.command("meridian progression recipes forget @a " + BrewKind.INSECT_LURE.path());
        openTower(context, stage);
        windowShot(context, stage, "struct_05_lv3_window_pick");
        clickTile(context, BrewKind.BITTER_YELLOW);
        context.waitTicks(CLICK_TICKS);
        context.getInput().setCursorPos(0, 0);
        stage.settle();
        windowShot(context, stage, "struct_06_lv3_window_needs");
        Vec2 unlearned = tileCursor(context, BrewKind.INSECT_LURE);
        context.getInput().setCursorPos(unlearned.x, unlearned.y);
        stage.settle();
        windowShot(context, stage, "struct_07_lv3_unlearned_tooltip");
        context.getInput().setCursorPos(0, 0);
    }

    private static void towerBrewing(ClientGameTestContext context, Stage stage) {
        // 窗口开着往木桶里备料，塔下一秒就开工；拍泡料，等北边两盏灯亮（熬制前半）从北面拍灯，再回台子开窗拍熬制中。
        for (int slot = 0; slot < BITTER_STOCK.size(); slot++) {
            stage.container(FIRST_BARREL, slot, BITTER_STOCK.get(slot));
        }
        waitWindow(context, stage, TowerStatus.SOAKING);
        windowShot(context, stage, "struct_08_lv3_window_soaking");
        waitLamps(context, stage);
        context.runOnClient(client -> client.player.closeContainer());
        stage.spectate();
        stage.look(stage.at(LAMP_EYE), stage.at(LAMP_FACE));
        stage.shoot("struct_09_lv3_lamps_two");
        LOGGER.info("[结构截图] struct_09_lv3_lamps_two：亮灯 {}", Integer.toBinaryString(litLamps(stage)));
        openTower(context, stage);
        windowShot(context, stage, "struct_10_lv3_window_brewing");
        context.runOnClient(client -> client.player.closeContainer());
    }

    private static void towerPotions(ClientGameTestContext context, Stage stage) {
        // 等这一批装进铜箱子，站到凹口外右键箱子，拍 8 瓶苦黄膏。
        for (int waited = 0; potions(stage) < BOTTLES; waited += POLL_TICKS) {
            if (waited >= POTIONS_TIMEOUT_TICKS) {
                throw new AssertionError("等铜箱子里 " + BOTTLES + " 瓶超时：现有 " + potions(stage));
            }
            stage.pause(POLL_TICKS);
        }
        stage.play();
        stage.look(stage.at(CHEST_EYE), stage.center(CHEST.getFirst()));
        stage.use();
        context.waitForScreen(ContainerScreen.class);
        context.getInput().setCursorPos(0, 0);
        stage.settle();
        stage.shoot("struct_11_lv3_chest_potions");
        LOGGER.info("[结构截图] struct_11_lv3_chest_potions：铜箱子里 {} 瓶", potions(stage));
        context.runOnClient(client -> client.player.closeContainer());
    }

    private static void openTower(ClientGameTestContext context, Stage stage) {
        // 创造模式、主手空着，站上北面台子对着玻璃右键一下，等药塔窗口开，光标挪到角上不挡字。
        stage.play();
        stage.hand(MAIN_HAND, EMPTY);
        stage.look(stage.at(LEDGE_EYE), stage.center(ENTRY_GLASS));
        stage.use();
        context.waitFor(BrewStructureClientTest::towerOpen, OPEN_TIMEOUT_TICKS);
        context.getInput().setCursorPos(0, 0);
        stage.settle();
    }

    private static void windowShot(ClientGameTestContext context, Stage stage, String shot) {
        // 拍窗口，并把窗口这一刻的整数表写进日志。
        stage.shoot(shot);
        LOGGER.info("[结构截图] {}：{}", shot, describeWindow(context));
    }

    private static void waitWindow(ClientGameTestContext context, Stage stage, TowerStatus wanted) {
        // 窗口开着等塔到这个状态；隔几秒记一次窗口，超时把最后一次写进失败信息。
        for (int polls = 0; status(context) != wanted.ordinal(); polls++) {
            if (polls * POLL_TICKS >= TOWER_TIMEOUT_TICKS) {
                throw new AssertionError("等药塔「" + wanted + "」超时：" + describeWindow(context));
            }
            if (polls % LOG_EVERY_POLLS == 0) {
                LOGGER.info("[结构截图] 等「{}」：{}", wanted, describeWindow(context));
            }
            stage.pause(POLL_TICKS);
        }
    }

    private static void waitLamps(ClientGameTestContext context, Stage stage) {
        // 等铜灯正好亮北边两盏；窗口还开着，隔几秒记一次窗口和灯。
        for (int polls = 0; litLamps(stage) != NORTH_ROW_LIT; polls++) {
            String lit = Integer.toBinaryString(litLamps(stage));
            if (polls * POLL_TICKS >= TOWER_TIMEOUT_TICKS) {
                throw new AssertionError("等铜灯亮两盏超时：亮 " + lit + "，" + describeWindow(context));
            }
            if (polls % LOG_EVERY_POLLS == 0) {
                LOGGER.info("[结构截图] 等铜灯两盏：亮 {}，{}", lit, describeWindow(context));
            }
            stage.pause(POLL_TICKS);
        }
    }

    private static boolean towerOpen(Minecraft client) {
        // 客户端当前界面是药塔窗口。
        return client.gui.screen() instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() instanceof BrewTowerMenu;
    }

    private static int status(ClientGameTestContext context) {
        // 窗口里同步来的状态序号；窗口没开时是 NO_WINDOW。
        return context.computeOnClient(client -> {
            if (!towerOpen(client)) {
                return NO_WINDOW;
            }
            var menu = (BrewTowerMenu) ((AbstractContainerScreen<?>) client.gui.screen()).getMenu();
            return menu.data(BrewTowerMenu.DATA_STATUS);
        });
    }

    private static String describeWindow(ClientGameTestContext context) {
        // 状态、阶段、温度、火、燃料、进度、缺的东西、真元，都是窗口整数表里同步来的。
        return context.computeOnClient(client -> {
            if (!towerOpen(client)) {
                return "药塔窗口没开";
            }
            var menu = (BrewTowerMenu) ((AbstractContainerScreen<?>) client.gui.screen()).getMenu();
            return String.format(Locale.ROOT, "%s / %s，%.1f°，火 %d 档，燃料 %d 秒，进度 %.1f 秒，缺 %s，真元已付 %d%%",
                    TowerStatus.values()[menu.data(BrewTowerMenu.DATA_STATUS)],
                    TowerPhase.values()[menu.data(BrewTowerMenu.DATA_PHASE)],
                    menu.data(BrewTowerMenu.DATA_TEMPERATURE_TENTHS) / TENTHS, menu.data(BrewTowerMenu.DATA_LEVEL),
                    menu.data(BrewTowerMenu.DATA_FUEL_SECONDS), menu.data(BrewTowerMenu.DATA_PROGRESS_TENTHS) / TENTHS,
                    needs(menu.data(BrewTowerMenu.DATA_NEEDS)), menu.data(BrewTowerMenu.DATA_QI_PERCENT));
        });
    }

    private static List<TowerNeed> needs(int mask) {
        // 缺料位图拆成名字。
        List<TowerNeed> needs = new ArrayList<>();
        for (TowerNeed need : TowerNeed.values()) {
            if ((mask & need.bit()) != 0) {
                needs.add(need);
            }
        }
        return needs;
    }

    private static void clickTile(ClientGameTestContext context, BrewKind kind) {
        // 光标移到这张药方中间，左键点一下，和玩家点切石机配方一样。
        Vec2 cursor = tileCursor(context, kind);
        context.getInput().setCursorPos(cursor.x, cursor.y);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static Vec2 tileCursor(ClientGameTestContext context, BrewKind kind) {
        // 药方格坐标相对底板左上角，底板在窗口里居中；换算成窗口像素。
        return context.computeOnClient(client -> {
            Window window = client.getWindow();
            float scale = (float) window.getScreenWidth() / window.getGuiScaledWidth();
            int left = (window.getGuiScaledWidth() - TOWER_WIDTH) / 2;
            int top = (window.getGuiScaledHeight() - TOWER_HEIGHT) / 2;
            int x = left + TILE_LEFT + kind.ordinal() * TILE_WIDTH + TILE_WIDTH / 2;
            int y = top + TILE_TOP + TILE_HEIGHT / 2;
            return new Vec2(x * scale, y * scale);
        });
    }

    private static int litLamps(Stage stage) {
        // 四盏铜灯哪几盏亮着，第 i 位对应 LAMPS 第 i 盏。
        return stage.computeOnServer(server -> {
            int lit = 0;
            for (int index = 0; index < LAMPS.size(); index++) {
                BlockState lamp = server.overworld().getBlockState(stage.block(LAMPS.get(index)));
                if (lamp.hasProperty(BlockStateProperties.LIT) && lamp.getValue(BlockStateProperties.LIT)) {
                    lit |= 1 << index;
                }
            }
            return lit;
        });
    }

    private static int potions(Stage stage) {
        // 铜箱子两半里一共几瓶成药。
        return stage.computeOnServer(server -> potionsIn(stage, server));
    }

    private static int potionsIn(Stage stage, MinecraftServer server) {
        // 一格一瓶，只数炼药模组的药。
        int total = 0;
        for (BlockPos half : CHEST) {
            var container = (Container) server.overworld().getBlockEntity(stage.block(half));
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (BrewItemStacks.isBrew(container.getItem(slot))) {
                    total++;
                }
            }
        }
        return total;
    }
}
