package dev.kizuna.meridian.brewing.clienttest;

import com.mojang.blaze3d.platform.Window;
import java.util.List;
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * 在真实客户端里拍炼药器具、铜釜四档氧化、枯木与原木对照、掉落物、手持、展示框、人体模型手里的药刀、
 * 物品栏和创造页（含晒药匾的物品名提示框），药刀开刃、切药动作和掉落物贴地（见 {@link KnifeScenes}），
 * 以及晒药匾界面、熬一锅药、舀药提示、药效说明和状态效果图标（见 {@link BrewPlayScenes}），
 * 供人工检查朝向、贴图错位、缺面、物品名、动作和玩家看到的提示。
 * 截图写到运行目录的 {@code screenshots/}，文件名即场景名；这里只摆场景和拍照，不比对像素。
 */
public final class BrewingModelsClientTest implements FabricClientGameTest {
    private static final String BREWING = "meridian_brewing:";
    private static final Identifier CREATIVE_TAB = Identifier.fromNamespaceAndPath("meridian_brewing", "brewing");
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 960;
    private static final List<String> APPARATUS =
            List.of("witch_cauldron", "copper_cauldron", "iron_brazier", "iron_stove", "herb_crate");
    private static final List<String> FACING_APPARATUS = List.of("iron_brazier", "iron_stove");
    private static final List<String> FACINGS = List.of("north", "east", "south", "west");
    private static final List<String> OXIDATION = List.of("unaffected", "exposed", "weathered", "oxidized");
    private static final List<String> WOODS =
            List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak");
    private static final List<String> AXES = List.of("x", "y", "z");
    private static final String DEFAULT_STATE = "";
    private static final String HERB_KNIFE = "herb_knife";
    private static final List<String> HELD = List.of(HERB_KNIFE, "iron_brazier", "iron_stove", "herb_crate");
    private static final List<String> DROPPED = List.of(HERB_KNIFE, "iron_brazier", "iron_stove", "herb_crate",
            "witch_cauldron", "copper_cauldron", "dead_oak_log");
    /** 本仓自画图标的八种物品；物品栏场景放在快捷栏药刀之后。 */
    private static final List<String> ICON_ITEMS = List.of("brew_recipe", "withered_bush_ash", "dry_moss", "dry_poppy",
            "dried_pufferfish", "poison_potato_slice", "berry_mash", "brew_residue");
    private static final List<String> BACKPACK = List.of("witch_cauldron", "copper_cauldron", "iron_brazier",
            "iron_stove", "herb_crate", "dead_oak_log");
    /** 原版创造页界面的宽高；界面在窗口里居中。 */
    private static final int CREATIVE_WIDTH = 195;
    private static final int CREATIVE_HEIGHT = 136;
    private static final int SLOT_CENTER = 8;
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
    private static final Vec3 ABOVE = new Vec3(0, 2.0, 0.4);
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
    /** 掉落物每秒绕竖轴转一弧度，隔一秒拍一张。 */
    private static final int SPIN_SHOTS = 4;
    private static final int SPIN_TICKS = 20;
    private static final Vec3 KNIFE_DROP_VIEW = new Vec3(0, 0.5, 1.0);
    private static final Vec3 KNIFE_DROP_LIFT = new Vec3(0, 0.25, 0);
    private static final Vec3 FRAME_FRONT = new Vec3(0, 0, 1.3);
    private static final Vec3 FRAME_OBLIQUE = new Vec3(0.9, 0.3, 0.9);
    /** 人体模型面朝南（+Z），右手在西侧；持物时手臂略向前抬。 */
    private static final Vec3 MANNEQUIN_HAND = new Vec3(-0.37, 0.85, 0.25);
    private static final List<View> MANNEQUIN_VIEWS = List.of(
            new View("right", new Vec3(-2.6, 0.3, 0)),
            new View("front_right", new Vec3(-1.8, 0.4, 1.8)),
            new View("back_right", new Vec3(-1.8, 0.4, -1.8)),
            new View("front", new Vec3(0, 0.3, 2.6)));

    private record View(String name, Vec3 offset) {
    }

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
            droppedKnife(stage);
            framedKnife(stage);
            mannequinKnife(stage);
            held(stage);
            inventory(context, stage);
            creativeTab(context, stage);
            KnifeScenes.run(context, stage);
            BrewPlayScenes.run(context, stage);
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
        stage.look(center.add(ABOVE), center);
        stage.shoot(name + "_above");
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

    private static void droppedKnife(Stage stage) {
        // 单独一把药刀的掉落物近拍：掉落物绕竖轴自转，隔一秒拍一张，看不同相位下收刃药刀的样子。
        stage.clear();
        Vec3 offset = Vec3.atBottomCenterOf(BlockPos.ZERO).add(0, DROP_HEIGHT, 0);
        stage.drop(offset, BREWING + HERB_KNIFE);
        Vec3 target = stage.at(offset).add(KNIFE_DROP_LIFT);
        stage.look(target.add(KNIFE_DROP_VIEW), target);
        for (int shot = 0; shot < SPIN_SHOTS; shot++) {
            stage.shoot("dropped_herb_knife_" + shot);
            stage.pause(SPIN_TICKS);
        }
    }

    private static void framedKnife(Stage stage) {
        // 北边立一面三格宽、三格高的木板墙，中间一格的南面挂展示框放药刀；镜头从南面正对、东南斜对各拍一张。
        stage.clear();
        stage.fill(new BlockPos(-1, 0, 0), new BlockPos(1, 2, 0), "minecraft:oak_planks");
        Vec3 frame = stage.center(new BlockPos(0, 1, 1));
        stage.summon("item_frame", frame,
                "{Facing:3b,Fixed:1b,Item:{id:\"" + BREWING + HERB_KNIFE + "\",count:1}}");
        stage.look(frame.add(FRAME_FRONT), frame);
        stage.shoot("framed_herb_knife_front");
        stage.look(frame.add(FRAME_OBLIQUE), frame);
        stage.shoot("framed_herb_knife_oblique");
    }

    private static void mannequinKnife(Stage stage) {
        // 原版人体模型面朝南站在原点，主手拿药刀；收窄视野，从右侧、右前、右后和正面对着右手拍展开的药刀。
        stage.clear();
        Vec3 feet = stage.at(Vec3.atBottomCenterOf(BlockPos.ZERO));
        stage.summon("mannequin", feet, "{equipment:{mainhand:{id:\"" + BREWING + HERB_KNIFE
                + "\",count:1}},Rotation:[0f,0f],immovable:1b,hide_description:1b}");
        Vec3 hand = feet.add(MANNEQUIN_HAND);
        int defaultFov = stage.fov(THIRD_PERSON_FOV);
        for (View view : MANNEQUIN_VIEWS) {
            stage.look(hand.add(view.offset()), hand);
            stage.shoot("mannequin_herb_knife_" + view.name());
        }
        stage.fov(defaultFov);
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

    private static void inventory(ClientGameTestContext context, Stage stage) {
        // 生存模式打开物品栏：快捷栏是药刀和八个自画图标，背包第一排是器具和枯木；选中药刀，左上人物手里是展开的刀。
        stage.command("clear @a");
        stage.command("gamemode survival @a");
        stage.command("item replace entity @a hotbar.0 with " + BREWING + HERB_KNIFE);
        for (int index = 0; index < ICON_ITEMS.size(); index++) {
            stage.command("item replace entity @a hotbar." + (index + 1) + " with " + BREWING + ICON_ITEMS.get(index));
        }
        for (int index = 0; index < BACKPACK.size(); index++) {
            stage.command("item replace entity @a inventory." + index + " with " + BREWING + BACKPACK.get(index));
        }
        stage.selectHotbar(0);
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitForScreen(InventoryScreen.class);
        context.getInput().setCursorPos(0, 0);
        stage.settle();
        stage.shoot("inventory");
        context.setScreen(() -> null);
        stage.command("gamemode creative @a");
        stage.settle();
    }

    private static void hoverCreativeItem(ClientGameTestContext context, String id) {
        // 光标移到创造页里这件物品的格子中间，弹出物品名提示框。格子坐标相对界面左上角，换算成窗口坐标再交给输入。
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(BREWING + id));
        Vec2 cursor = context.computeOnClient(client -> {
            Window window = client.getWindow();
            Slot slot = ((CreativeModeInventoryScreen) client.gui.screen()).getMenu().slots.stream()
                    .filter(candidate -> candidate.getItem().is(item))
                    .findFirst()
                    .orElseThrow();
            float scale = (float) window.getScreenWidth() / window.getGuiScaledWidth();
            int left = (window.getGuiScaledWidth() - CREATIVE_WIDTH) / 2;
            int top = (window.getGuiScaledHeight() - CREATIVE_HEIGHT) / 2;
            return new Vec2((left + slot.x + SLOT_CENTER) * scale, (top + slot.y + SLOT_CENTER) * scale);
        });
        context.getInput().setCursorPos(cursor.x, cursor.y);
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
        hoverCreativeItem(context, "herb_crate");
        stage.settle();
        stage.shoot("creative_tab_tooltip_herb_crate");
        context.setScreen(() -> null);
    }
}
