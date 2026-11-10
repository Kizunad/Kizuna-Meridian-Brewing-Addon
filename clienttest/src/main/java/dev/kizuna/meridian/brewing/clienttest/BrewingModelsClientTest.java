package dev.kizuna.meridian.brewing.clienttest;

import java.util.List;
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.phys.Vec3;

/**
 * 在真实客户端里拍炼药器具、铜釜四档氧化、枯木与原木对照、掉落物、手持和创造页，供人工检查朝向、贴图错位和缺面。
 * 截图写到运行目录的 {@code screenshots/}，文件名即场景名；这里只摆场景和拍照，不比对像素。
 */
public final class BrewingModelsClientTest implements FabricClientGameTest {
    private static final String BREWING = "meridian_brewing:";
    private static final Identifier CREATIVE_TAB = Identifier.fromNamespaceAndPath("meridian_brewing", "brewing");
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 960;
    private static final List<String> APPARATUS =
            List.of("witch_cauldron", "copper_cauldron", "iron_brazier", "iron_stove", "herb_crate");
    private static final List<String> FACING_APPARATUS = List.of("iron_brazier", "iron_stove", "herb_crate");
    private static final List<String> FACINGS = List.of("north", "east", "south", "west");
    private static final List<String> OXIDATION = List.of("unaffected", "exposed", "weathered", "oxidized");
    private static final List<String> WOODS =
            List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak");
    private static final List<String> AXES = List.of("x", "y", "z");
    private static final String DEFAULT_STATE = "";
    private static final List<String> HELD = List.of("herb_knife", "iron_brazier", "iron_stove", "herb_crate");
    private static final List<String> DROPPED = List.of("herb_knife", "iron_brazier", "iron_stove", "herb_crate",
            "witch_cauldron", "copper_cauldron", "dead_oak_log");
    private static final int ROW_SPACING = 2;
    private static final int PAIRS_PER_ROW = 3;
    private static final int PAIR_SPACING = 3;
    /** 一排最西的原版到最东的枯木相隔的格数。 */
    private static final int PAIRS_EAST_SPAN = (PAIRS_PER_ROW - 1) * PAIR_SPACING + 1;
    private static final double DROP_HEIGHT = 0.2;
    private static final double HALF = 0.5;
    private static final Vec3 FRONT = new Vec3(0, 0.6, 1.7);
    private static final Vec3 OBLIQUE = new Vec3(1.25, 1.3, 1.25);
    private static final Vec3 BEHIND = new Vec3(-1.25, 1.3, -1.25);
    private static final Vec3 ROW_FRONT = new Vec3(0, 1.6, 5.5);
    private static final Vec3 ROW_ABOVE = new Vec3(2.0, 3.8, 3.6);
    private static final Vec3 GRID_ABOVE = new Vec3(0, 7.0, 7.5);
    private static final Vec3 PAIRS_VIEW = new Vec3(1.5, 2.8, 5.0);
    private static final Vec3 DROPPED_VIEW = new Vec3(0, 1.4, 3.4);
    /** 第一人称由动画库画全身，手在身体两侧略靠后：大件低头一半看得到，药刀要几乎垂直往下看。 */
    private static final Vec3 HELD_LOOK = new Vec3(0, -2.4, 2.0);
    private static final Vec3 HELD_LOOK_DOWN = new Vec3(0, -2.4, 0.65);
    private static final Vec3 THIRD_PERSON_LOOK = new Vec3(0, -0.6, 4.0);
    /** 第三人称镜头离人四格，收窄视野让手里的东西看得清。 */
    private static final int THIRD_PERSON_FOV = 30;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            var stage = Stage.prepare(context, world);
            APPARATUS.forEach(id -> single(stage, id, facingSouth(id)));
            row(stage, "apparatus_row", APPARATUS.stream().map(BrewingModelsClientTest::facingSouth).toList());
            row(stage, "copper_oxidation",
                    OXIDATION.stream().map(state -> BREWING + "copper_cauldron[oxidation=" + state + "]").toList());
            FACING_APPARATUS.forEach(id -> row(stage, id + "_facings",
                    FACINGS.stream().map(facing -> BREWING + id + "[facing=" + facing + "]").toList()));
            deadWoodGrid(stage, "log");
            deadWoodRows(stage, "log");
            deadLogAxes(stage);
            deadWoodGrid(stage, "wood");
            deadWoodRows(stage, "wood");
            dropped(stage);
            held(stage);
            creativeTab(context, stage);
        }
    }

    private static String facingSouth(String id) {
        // 正面朝南，镜头在南侧看到的就是正面；两种釜没有朝向。
        if (FACING_APPARATUS.contains(id)) {
            return BREWING + id + "[facing=south]";
        }
        return BREWING + id;
    }

    private static void single(Stage stage, String name, String state) {
        stage.clear();
        stage.place(BlockPos.ZERO, state);
        Vec3 center = stage.center(BlockPos.ZERO);
        stage.look(center.add(FRONT), center);
        stage.shoot(name + "_front");
        stage.look(center.add(OBLIQUE), center);
        stage.shoot(name + "_oblique");
        stage.look(center.add(BEHIND), center);
        stage.shoot(name + "_behind");
    }

    private static void row(Stage stage, String name, List<String> states) {
        // 自西向东隔一格排开，依次对应 states 的顺序。
        stage.clear();
        for (int index = 0; index < states.size(); index++) {
            stage.place(new BlockPos(index * ROW_SPACING, 0, 0), states.get(index));
        }
        int eastSpan = (states.size() - 1) * ROW_SPACING;
        Vec3 middle = stage.center(BlockPos.ZERO).add(eastSpan * HALF, 0, 0);
        stage.look(middle.add(ROW_FRONT), middle);
        stage.shoot(name + "_front");
        stage.look(middle.add(ROW_ABOVE), middle);
        stage.shoot(name + "_above");
    }

    private static void deadWoodGrid(Stage stage, String kind) {
        // 三对一排、三排自南向北；每对西边原版、东边枯木，树种按 WOODS 的顺序。
        stage.clear();
        for (int index = 0; index < WOODS.size(); index++) {
            int column = index % PAIRS_PER_ROW;
            int line = index / PAIRS_PER_ROW;
            var west = new BlockPos(column * PAIR_SPACING, 0, -line * PAIR_SPACING);
            pair(stage, west, WOODS.get(index) + "_" + kind, DEFAULT_STATE);
        }
        int northSpan = (WOODS.size() / PAIRS_PER_ROW - 1) * PAIR_SPACING;
        Vec3 middle = stage.center(BlockPos.ZERO).add(PAIRS_EAST_SPAN * HALF, 0, -northSpan * HALF);
        stage.look(middle.add(GRID_ABOVE), middle);
        stage.shoot("dead_" + kind + "_grid");
    }

    private static void deadWoodRows(Stage stage, String kind) {
        // 每排单独摆一场近拍，前排不挡后排。
        for (int start = 0; start < WOODS.size(); start += PAIRS_PER_ROW) {
            List<String> woods = WOODS.subList(start, start + PAIRS_PER_ROW);
            stage.clear();
            for (int column = 0; column < woods.size(); column++) {
                var west = new BlockPos(column * PAIR_SPACING, 0, 0);
                pair(stage, west, woods.get(column) + "_" + kind, DEFAULT_STATE);
            }
            Vec3 middle = stage.center(BlockPos.ZERO).add(PAIRS_EAST_SPAN * HALF, 0, 0);
            stage.look(middle.add(PAIRS_VIEW), middle);
            stage.shoot("dead_" + kind + "_" + String.join("_", woods));
        }
    }

    private static void deadLogAxes(Stage stage) {
        // 橡木原木与枯橡木原木按 x、y、z 三个轴向各放一对，看横放后端面染色是否跟着转。
        stage.clear();
        for (int column = 0; column < AXES.size(); column++) {
            pair(stage, new BlockPos(column * PAIR_SPACING, 0, 0), "oak_log", "[axis=" + AXES.get(column) + "]");
        }
        Vec3 middle = stage.center(BlockPos.ZERO).add(PAIRS_EAST_SPAN * HALF, 0, 0);
        stage.look(middle.add(PAIRS_VIEW), middle);
        stage.shoot("dead_log_axes");
    }

    private static void pair(Stage stage, BlockPos west, String block, String properties) {
        // 西边原版、东边对应的枯木，方块状态相同。
        stage.place(west, "minecraft:" + block + properties);
        stage.place(west.east(), BREWING + "dead_" + block + properties);
    }

    private static void dropped(Stage stage) {
        // 掉落物自西向东每格一个，依次对应 DROPPED 的顺序。
        stage.clear();
        for (int index = 0; index < DROPPED.size(); index++) {
            Vec3 at = Vec3.atBottomCenterOf(new BlockPos(index, 0, 0)).add(0, DROP_HEIGHT, 0);
            stage.drop(at, BREWING + DROPPED.get(index));
        }
        int eastSpan = DROPPED.size() - 1;
        Vec3 middle = stage.at(Vec3.atBottomCenterOf(BlockPos.ZERO).add(eastSpan * HALF, 0, 0));
        stage.look(middle.add(DROPPED_VIEW), middle);
        stage.shoot("dropped_items");
    }

    private static void held(Stage stage) {
        // 快捷栏前几格放入 HELD；第一人称低头两种角度各拍一遍，再从正面第三人称拍一遍。
        stage.clear();
        stage.play();
        for (int slot = 0; slot < HELD.size(); slot++) {
            stage.command("item replace entity @a hotbar." + slot + " with " + BREWING + HELD.get(slot));
        }
        Vec3 eye = stage.eye(BlockPos.ZERO);
        heldShots(stage, eye.add(HELD_LOOK), "_first_person");
        heldShots(stage, eye.add(HELD_LOOK_DOWN), "_first_person_down");
        // 第三人称时快捷栏和物品名会盖住垂在腰侧的手，先隐藏界面。
        stage.hideHud();
        stage.camera(CameraType.THIRD_PERSON_FRONT);
        int defaultFov = stage.fov(THIRD_PERSON_FOV);
        heldShots(stage, eye.add(THIRD_PERSON_LOOK), "_third_person");
        stage.fov(defaultFov);
        stage.camera(CameraType.FIRST_PERSON);
        stage.showHud();
    }

    private static void heldShots(Stage stage, Vec3 target, String suffix) {
        stage.look(stage.eye(BlockPos.ZERO), target);
        for (int slot = 0; slot < HELD.size(); slot++) {
            stage.selectHotbar(slot);
            stage.shoot("held_" + HELD.get(slot) + suffix);
        }
    }

    private static void creativeTab(ClientGameTestContext context, Stage stage) {
        // 创造模式下按物品栏键打开创造页，切到炼药页；光标移到角落，免得提示框盖住图标。
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitForScreen(CreativeModeInventoryScreen.class);
        CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.getOptional(CREATIVE_TAB).orElseThrow();
        boolean selected = context.computeOnClient(
                client -> ((FabricCreativeModeInventoryScreen) client.gui.screen()).setSelectedTab(tab));
        if (!selected) {
            throw new AssertionError("创造页没有切到炼药页");
        }
        context.getInput().setCursorPos(0, 0);
        stage.settle();
        stage.shoot("creative_tab");
        context.setScreen(() -> null);
    }
}
