package dev.kizuna.meridian.brewing.clienttest;

import dev.kizuna.playeranimation.animation.PlayerAnimationAction;
import dev.kizuna.playeranimation.server.ServerPlayerAnimations;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 药刀动作和贴地的真实客户端截图。
 *
 * <p>开刃、切药各拍一组真实触发（按快捷栏键、按住使用键）后逐 tick 的画面，再用 PA 定格（scrub）拍规格里的确切时刻，
 * 正面一组、头偏向右前方一组（每张拍前直接定身体和头的朝向）；第一人称定格在切药 0.55 s，逐 5° 低头拍，量药材进画面要低头多少度；
 * 药刀、晒药匾、两件炉具和原版炼药锅的掉落物等到上下浮动的最低点再冻结实体，从贴地的侧面拍，三件器具另拍放下的方块。
 */
final class KnifeScenes {
    private static final String BREWING = "meridian_brewing:";
    private static final PlayerAnimationAction DRAW = PlayerAnimationAction.HERB_KNIFE_DRAW;
    private static final PlayerAnimationAction CUT = PlayerAnimationAction.HERB_KNIFE_CUT;
    private static final Vec3 THIRD_PERSON_LOOK = new Vec3(0, -0.6, 4.0);
    /** 第三人称镜头对着眼睛，手在眼下约 0.85 格；40° 视野才把手和刀都框进来。 */
    private static final int THIRD_PERSON_FOV = 40;
    /** 正面镜头跟着头转；身体朝南、头偏 40° 时镜头在玩家右前方，看得到刀刃侧面。 */
    private static final float FRONT_YAW = 0;
    private static final float SIDE_YAW = 40;
    private static final int GROUND_FOV = 30;
    private static final int EMPTY_SLOT = 0;
    private static final int KNIFE_SLOT = 1;
    private static final float DRAW_SECONDS = 0.60f;
    private static final float CUT_SECONDS = 1.00f;
    private static final List<Float> DRAW_MOMENTS = List.of(0.10f, 0.23f, 0.60f);
    private static final List<Float> CUT_MOMENTS = List.of(0.20f, 0.55f, 0.75f);
    private static final float FIRST_PERSON_CUT_MOMENT = 0.55f;
    /** 按键后第几个客户端 tick 拍；开刃逐 tick 连拍一整段，看服务端剪辑到之前刀先开着的那一两 tick。 */
    private static final int DRAW_CLIP_TICKS = 12;
    private static final List<Integer> DRAW_TICKS = IntStream.rangeClosed(1, DRAW_CLIP_TICKS).boxed().toList();
    private static final List<Integer> CUT_TICKS = List.of(4, 11, 15);
    private static final int CUT_SETTLE_TICKS = 25;
    private static final int FIRST_PITCH = 30;
    private static final int LAST_PITCH = 90;
    private static final int PITCH_STEP = 5;
    private static final float FACING_SOUTH = 0;
    private static final List<String> DROPPED = List.of(BREWING + "herb_knife", BREWING + "herb_crate",
            BREWING + "iron_brazier", BREWING + "iron_stove", "minecraft:cauldron");
    private static final List<String> PLACED = List.of("herb_crate", "iron_brazier[facing=south]",
            "iron_stove[facing=south]");
    private static final double DROP_HEIGHT = 0.2;
    private static final int LAND_TICKS = 30;
    private static final Vec3 GROUND_LIFT = new Vec3(0, 0.1, 0);
    private static final Vec3 DROPPED_SIDE = new Vec3(0, 0, 1.6);
    private static final Vec3 PLACED_SIDE = new Vec3(0, 0.15, 4.0);
    /** 掉落物上下浮动 bob = sin(age / 10 + bobOffs) × 0.1 + 0.1，相位 3π/2 时最低。 */
    private static final double LOWEST_BOB_PHASE = 1.5 * Math.PI;
    private static final double TWO_PI = 2 * Math.PI;
    private static final double BOB_TICKS_PER_RADIAN = 10;

    private KnifeScenes() {
    }

    static void run(ClientGameTestContext context, Stage stage) {
        actionStage(stage);
        int defaultFov = stage.fov(THIRD_PERSON_FOV);
        draw(stage);
        cut(stage);
        moments(stage, DRAW, DRAW_SECONDS, DRAW_MOMENTS, SIDE_YAW, "knife_draw_side_at");
        moments(stage, CUT, CUT_SECONDS, CUT_MOMENTS, SIDE_YAW, "knife_cut_side_at");
        stage.fov(defaultFov);
        firstPersonCut(stage);
        stage.camera(CameraType.FIRST_PERSON);
        stage.command("clear @a");
        stage.spectate();
        stage.fov(GROUND_FOV);
        DROPPED.forEach(item -> dropped(context, stage, item));
        PLACED.forEach(state -> placed(stage, state));
        stage.fov(defaultFov);
    }

    private static void actionStage(Stage stage) {
        // 创造模式、隐藏界面、正面第三人称；快捷栏 0 空着，1 是药刀，副手拿毒马铃薯。
        stage.clear();
        stage.play();
        stage.hideHud();
        stage.command("clear @a");
        stage.command("item replace entity @a hotbar." + KNIFE_SLOT + " with " + BREWING + "herb_knife");
        stage.command("item replace entity @a weapon.offhand with minecraft:poisonous_potato 64");
        stage.selectHotbar(EMPTY_SLOT);
        Vec3 eye = stage.eye(BlockPos.ZERO);
        stage.look(eye, eye.add(THIRD_PERSON_LOOK));
        stage.camera(CameraType.THIRD_PERSON_FRONT);
    }

    private static void draw(Stage stage) {
        // 真实触发：按快捷栏键换成药刀，服务端看到主手变化后播开刃。
        stage.pressHotbar(KNIFE_SLOT);
        burst(stage, "knife_draw_real_tick", DRAW_TICKS);
        stage.settle();
        moments(stage, DRAW, DRAW_SECONDS, DRAW_MOMENTS, FRONT_YAW, "knife_draw_at");
    }

    private static void cut(Stage stage) {
        // 真实触发：按住使用键切一刀，拍完松手；再按规格时刻定格。
        stage.holdUse();
        burst(stage, "knife_cut_real_tick", CUT_TICKS);
        stage.releaseUse();
        stage.pause(CUT_SETTLE_TICKS);
        moments(stage, CUT, CUT_SECONDS, CUT_MOMENTS, FRONT_YAW, "knife_cut_at");
    }

    private static void moments(Stage stage, PlayerAnimationAction action, float seconds, List<Float> moments,
                                float headYaw, String prefix) {
        for (float moment : moments) {
            hold(stage, action, moment / seconds);
            stage.face(FACING_SOUTH, headYaw);
            stage.shoot(name(prefix, moment));
        }
        stop(stage, action);
    }

    private static void firstPersonCut(Stage stage) {
        // 定格在刃口压过药材的 0.55 s，原版视野下第一人称从平视下 30° 起每 5° 拍一张。
        stage.camera(CameraType.FIRST_PERSON);
        hold(stage, CUT, FIRST_PERSON_CUT_MOMENT / CUT_SECONDS);
        for (int pitch = FIRST_PITCH; pitch <= LAST_PITCH; pitch += PITCH_STEP) {
            stage.turn(FACING_SOUTH, pitch);
            // 身体摆正到头的朝向，手和药材在画面里的位置才不随身体偏转漂。
            stage.face(FACING_SOUTH, FACING_SOUTH);
            stage.shoot(String.format(Locale.ROOT, "knife_cut_first_person_pitch_%02d", pitch));
        }
        stop(stage, CUT);
    }

    private static void burst(Stage stage, String prefix, List<Integer> ticks) {
        int elapsed = 0;
        for (int tick : ticks) {
            stage.pause(tick - elapsed);
            elapsed = tick;
            stage.face(FACING_SOUTH, FRONT_YAW);
            stage.shoot(String.format(Locale.ROOT, "%s%02d", prefix, tick));
        }
    }

    private static void hold(Stage stage, PlayerAnimationAction action, float progress) {
        stage.onServer(server -> ServerPlayerAnimations.scrub(player(server), action, progress));
        stage.settle();
    }

    private static void stop(Stage stage, PlayerAnimationAction action) {
        stage.onServer(server -> ServerPlayerAnimations.stop(player(server), action));
        stage.settle();
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static void dropped(ClientGameTestContext context, Stage stage, String item) {
        // 掉落物落地后等到浮动最低点冻结实体，镜头贴地从南面平视；原版炼药锅作对照。
        stage.clear();
        Vec3 at = Vec3.atBottomCenterOf(BlockPos.ZERO).add(0, DROP_HEIGHT, 0);
        stage.drop(at, item);
        stage.pause(LAND_TICKS);
        stage.pause(ticksToLowestBob(context));
        stage.command("tick freeze");
        Vec3 target = stage.at(Vec3.atBottomCenterOf(BlockPos.ZERO)).add(GROUND_LIFT);
        stage.look(target.add(DROPPED_SIDE), target);
        stage.shoot("grounded_dropped_" + item.substring(item.indexOf(':') + 1));
        stage.command("tick unfreeze");
    }

    private static int ticksToLowestBob(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            for (Entity entity : client.level.entitiesForRendering()) {
                if (entity instanceof ItemEntity item) {
                    double phase = item.tickCount / BOB_TICKS_PER_RADIAN + item.bobOffs;
                    double remaining = ((LOWEST_BOB_PHASE - phase) % TWO_PI + TWO_PI) % TWO_PI;
                    // 至少等一 tick；晚一 tick 只差 0.0005 格。
                    return Math.max(1, (int) Math.round(remaining * BOB_TICKS_PER_RADIAN));
                }
            }
            throw new AssertionError("客户端没有看到掉落物");
        });
    }

    private static void placed(Stage stage, String state) {
        // 放下的方块从南面贴地平视。
        stage.clear();
        stage.place(BlockPos.ZERO, BREWING + state);
        Vec3 target = stage.at(Vec3.atBottomCenterOf(BlockPos.ZERO)).add(GROUND_LIFT);
        stage.look(target.add(PLACED_SIDE), target);
        String id = state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
        stage.shoot("grounded_placed_" + id);
    }

    private static String name(String prefix, float seconds) {
        return String.format(Locale.ROOT, "%s_%03d", prefix, Math.round(seconds * 100));
    }
}
