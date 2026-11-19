package dev.kizuna.meridian.brewing.clienttest;

import java.lang.reflect.Field;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.Hud;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 使用发布包的摆炉函数，拍摄从外观到空手检查、拆错与修复的全过程。 */
public final class FurnacesClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(FurnacesClientTest.class);
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int GUI_SCALE = 2;
    private static final int SHOT_TICKS = 28;
    private static final int APPROACH_TICKS = 25;
    private static final int ACTION_TIMEOUT = 40;
    private static final int MIN_VISIBLE_FRAMES = 20;
    private static final int AIR_SLICE = 6;
    private static final int SKY_HEIGHT = 24;
    private static final double OVERHEAD_RISE = 2.0;
    private static final double BLOCK_CENTER = 0.5;
    private static final int WEST = -16;
    private static final int EAST = 52;
    private static final int NORTH = -18;
    private static final int SOUTH = 32;
    private static final BlockPos BROKEN_RIM = new BlockPos(35, 10, 16);
    private static final BlockPos WRONG_COPPER = new BlockPos(2, 6, 6);
    private static final String FORMED = "message.meridian_brewing.furnace.formed";
    private static final String BROKEN = "message.meridian_brewing.furnace.broken";
    private static final Field MESSAGE = hudField("overlayMessageString");
    private static final Field REMAINING = hudField("overlayMessageTime");
    // 均相对摆炉函数的原点。近景朝向锅沿外侧，脚下和镜头到锅沿之间都有净空。
    private static final Shot SMALL = new Shot("lv1", new BlockPos(2, 7, 7), new Vec3(-10, 11, 15),
            new Vec3(5, 4, 6), new Vec3(-1.5, 8.7, 7.3));
    private static final Shot MEDIUM = new Shot("lv2", new BlockPos(18, 8, 3), new Vec3(23, 13, -14),
            new Vec3(18, 4, 7), new Vec3(18.5, 10.1, -0.2));
    private static final Shot LARGE = new Shot("lv3-auto", new BlockPos(36, 10, 16), new Vec3(46, 17, 33),
            new Vec3(36, 6, 10), new Vec3(36.5, 12.2, 19.7));
    private static final List<Shot> SHOTS = List.of(SMALL, MEDIUM, LARGE);

    private record Shot(String suffix, BlockPos rim, Vec3 farEye, Vec3 center, Vec3 nearEye) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Stage.selected("furnaces")) {
            return;
        }
        context.getInput().resizeWindow(WIDTH, HEIGHT);
        try (var world = context.worldBuilder().create()) {
            Stage stage = prepare(context, world);
            try (var film = new FurnaceFilm(context)) {
                for (Shot shot : SHOTS) {
                    filmExteriorAndFloor(stage, film, shot);
                }
                enterCreativeFlight(stage);
                for (Shot shot : SHOTS) {
                    approach(stage, shot);
                    inspect(context, stage, film, shot, FORMED);
                }
                breakAndRepair(context, stage, film);
                approach(stage, SMALL);
                // 透明玻璃让镜头看得到原版发在错误方块中心的标记。
                stage.place(WRONG_COPPER, "glass");
                inspect(context, stage, film, SMALL, BROKEN);
            }
        }
    }

    private static void filmExteriorAndFloor(Stage stage, FurnaceFilm film, Shot shot) {
        Vec3 center = shot.center();
        stage.look(stage.at(shot.farEye()), stage.at(center));
        holdShot(stage, film, shot, "exterior");
        // 正上方稍向南偏，既能看到整片锅底，也能辨认近侧锅沿。
        Vec3 floor = center.add(BLOCK_CENTER, 0, BLOCK_CENTER);
        Vec3 eye = new Vec3(floor.x, shot.farEye().y + OVERHEAD_RISE, floor.z + 1);
        stage.look(stage.at(eye), stage.at(floor));
        holdShot(stage, film, shot, "floor");
    }

    private static void holdShot(Stage stage, FurnaceFilm film, Shot shot, String label) {
        int first = film.frame() + 1;
        stage.pause(SHOT_TICKS);
        LOGGER.info("furnaces {} {} frames {}–{}", shot.suffix(), label, first, film.frame());
    }

    private static Stage prepare(ClientGameTestContext context, TestSingleplayerContext world) {
        // 旁观镜头可以垂直看进锅口，角色身体不会挡住锅底。
        Stage stage = Stage.prepare(context, world);
        stage.guiScale(GUI_SCALE);
        stage.hand("weapon.mainhand", "air");
        stage.hand("weapon.offhand", "air");
        for (int y = 0; y < SKY_HEIGHT; y += AIR_SLICE) {
            stage.fill(new BlockPos(WEST, y, NORTH), new BlockPos(EAST, y + AIR_SLICE - 1, SOUTH), "air");
        }
        stage.fill(new BlockPos(WEST, -1, NORTH), new BlockPos(EAST, -1, SOUTH), "grass_block");
        stage.runFunction("meridian_brewing:test/furnaces");
        stage.look(stage.at(SMALL.farEye()), stage.at(SMALL.center()));
        return stage;
    }

    private static void enterCreativeFlight(Stage stage) {
        // 玩家飞近锅沿后，实际空手右键触发服务端检查。
        stage.play();
        stage.onServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        });
    }

    private static void approach(Stage stage, Shot shot) {
        Vec3 start = stage.at(shot.farEye());
        Vec3 end = stage.at(shot.nearEye());
        Vec3 center = stage.at(shot.center());
        BlockPos rim = stage.block(shot.rim());
        Vec3 aim = stage.computeOnServer(server -> {
            var level = server.overworld();
            var shape = level.getBlockState(rim).getShape(level, rim);
            return shape.bounds().move(rim).getCenter();
        });
        for (int tick = 0; tick <= APPROACH_TICKS; tick++) {
            double t = (double) tick / APPROACH_TICKS;
            stage.aim(start.lerp(end, t), center.lerp(aim, t));
            stage.pause(1);
        }
        stage.settle();
    }

    private static void breakAndRepair(ClientGameTestContext context, Stage stage, FurnaceFilm film) {
        BlockPos rim = stage.block(BROKEN_RIM);
        var original = stage.computeOnServer(server -> server.overworld().getBlockState(rim));
        stage.place(BROKEN_RIM, "air");
        inspect(context, stage, film, LARGE, BROKEN);
        stage.onServer(server -> server.overworld().setBlock(rim, original, Block.UPDATE_ALL));
        inspect(context, stage, film, LARGE, FORMED);
    }

    private static void inspect(ClientGameTestContext context, Stage stage, FurnaceFilm film, Shot shot, String key) {
        BlockPos rim = stage.block(shot.rim());
        context.waitFor(client -> client.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(rim),
                ACTION_TIMEOUT);
        stage.use();
        var name = Component.translatable("structure.meridian_brewing.cauldron-v2-" + shot.suffix());
        Component expected = Component.translatable(key, name);
        context.waitFor(client -> visible(client.gui.hud, expected), ACTION_TIMEOUT);
        int first = film.frame();
        for (int tick = 0; tick < SHOT_TICKS; tick++) {
            context.waitTick();
            if (!context.computeOnClient(client -> visible(client.gui.hud, expected))) {
                throw new AssertionError("动作栏提前消失 " + key + " 第 " + tick + " 刻");
            }
        }
        int last = film.frame();
        if (last - first < MIN_VISIBLE_FRAMES) {
            throw new AssertionError("动作栏可见帧不足 " + first + "–" + last);
        }
        LOGGER.info("furnaces {} {} frames {}–{}", shot.suffix(), key, first + 1, last);
    }

    private static Field hudField(String name) {
        // 原版 HUD 没有公开动作栏的读接口，只在客户端场景中读取实际文字和倒计时。
        try {
            Field field = Hud.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(name, failure);
        }
    }

    private static boolean visible(Hud hud, Component expected) {
        try {
            return !hud.isHidden() && REMAINING.getInt(hud) > 0 && expected.equals(MESSAGE.get(hud));
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("读取动作栏失败", failure);
        }
    }
}
