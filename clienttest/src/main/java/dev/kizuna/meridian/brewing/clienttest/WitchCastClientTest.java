package dev.kizuna.meridian.brewing.clienttest;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhases;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 泼引放招：玩家和一只真炁女巫各经功法库公开入口放一次泼引，拍起手到一半、泼出那一刻、药色粒子散开和收势后。
 * 玩家那组每个时刻拍第三人称背面、正面两张，看泼的动作和身前铺开的那片药色；女巫那组由旁观者站在侧前方看。
 * 截图写到运行目录 {@code screenshots/}。
 */
public final class WitchCastClientTest implements FabricClientGameTest {
    private static final String SCENE = "fling";
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 960;
    private static final String WITCH = "meridian_witch:";
    private static final String FLING = WITCH + "fling_seed";
    private static final List<String> VOLUMES = List.of(WITCH + "guard", WITCH + "seed");
    private static final String TRUE_QI = "true_qi";
    private static final int PLAYER_QI = 100;
    private static final int QI_DENSITY = 1;
    private static final long MILLIS_PER_TICK = 50;
    /** 魅药是粉色，草地上看得清；泼引能泼的药里有它。 */
    private static final BrewKind BREW = BrewKind.CHARM;
    private static final int HALVES = 2;
    /** 泼出后隔一 tick 拍：粒子包已到客户端、刚开始飘。 */
    private static final int AFTER_RELEASE_TICKS = 1;
    /** 再过几 tick 拍粒子散开的样子。 */
    private static final int SPREAD_TICKS = 4;
    /** 收势结束后再等几 tick，PA 的停播已到客户端。 */
    private static final int RECOVERED_TICKS = 5;
    private static final BlockPos PLAYER_SPOT = BlockPos.ZERO;
    private static final Vec3 SOUTH = new Vec3(0, 0, 4);
    private static final Vec3 WITCH_FEET = new Vec3(0.5, 0, 6.5);
    private static final AABB WITCH_BOX = new AABB(-1, -1, -1, 1, 2, 1);
    /** 女巫朝东泼，扇面横在旁观者眼前。 */
    private static final float FACING_EAST = -90;
    private static final BlockPos VIEWER_SPOT = new BlockPos(1, 0, 1);
    private static final Vec3 WITCH_VIEW = new Vec3(2.5, 1.2, 6.5);

    @Override
    public void runTest(ClientGameTestContext context) {
        // 默认世界；先学两卷、开脉、升真炁、补满真元，玩家放一次，再生一只真炁女巫放一次。
        if (!Stage.selected(SCENE)) {
            return;
        }
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Stage stage = Stage.prepare(context, world);
            stage.clear();
            CastPhases phases = stage.computeOnServer(server ->
                    TechniqueFabric.service().registry().findLatest(FLING).orElseThrow().getPhases());
            int warmupTicks = Math.toIntExact(phases.getWarmupMillis() / MILLIS_PER_TICK);
            long castMillis = phases.getWarmupMillis() + phases.getActiveMillis() + phases.getRecoveryMillis();
            int castTicks = Math.toIntExact(castMillis / MILLIS_PER_TICK);
            playerFling(stage, warmupTicks, castTicks);
            witchFling(stage, warmupTicks, castTicks);
        }
    }

    private static void playerFling(Stage stage, int warmupTicks, int castTicks) {
        // 玩家站在原点朝南、主手一瓶喷溅的魅药，走和玩家按键一样的公开入口起手。
        stage.command("gamemode survival @a");
        stage.hideHud();
        VOLUMES.forEach(volume -> stage.command("meridian technique learn @a " + volume));
        stage.command("meridian opening complete @a all");
        stage.command("meridian body realm @a " + TRUE_QI);
        // 和 GameTest 一样把全身内腔覆盖成 100 元、密度 1 的真元；只补满内腔时起手付不出费用。
        stage.command("meridian qi set @a " + PLAYER_QI + " all lumen " + QI_DENSITY);
        Vec3 eye = stage.eye(PLAYER_SPOT);
        stage.look(eye, eye.add(SOUTH));
        stage.onServer(server -> firstPlayer(server).setItemInHand(InteractionHand.MAIN_HAND, splash()));
        stage.settle();
        stage.onServer(server -> cast(firstPlayer(server)));
        moments(stage, warmupTicks, castTicks, "fling_player", WitchCastClientTest::bothSides);
        stage.camera(CameraType.FIRST_PERSON);
    }

    private static void witchFling(Stage stage, int warmupTicks, int castTicks) {
        // 生一只真炁女巫，关 AI、朝东、主手同一瓶药；旁观者在侧前方看它起手、泼出、收势。
        stage.spectate();
        Vec3 feet = stage.at(WITCH_FEET);
        stage.command(String.format(Locale.ROOT, "meridian humanoid spawn minecraft:witch %s %.2f %.2f %.2f",
                TRUE_QI, feet.x, feet.y, feet.z));
        stage.settle();
        stage.command("meridian qi fill @e[type=minecraft:witch,limit=1]");
        stage.onServer(server -> {
            Witch witch = witch(server, feet);
            witch.setNoAi(true /* noAi */);
            witch.setYRot(FACING_EAST);
            witch.setYHeadRot(FACING_EAST);
            witch.setYBodyRot(FACING_EAST);
            witch.setXRot(0);
            witch.setItemSlot(EquipmentSlot.MAINHAND, splash());
        });
        stage.look(stage.eye(VIEWER_SPOT), stage.at(WITCH_VIEW));
        stage.onServer(server -> cast(witch(server, feet)));
        moments(stage, warmupTicks, castTicks, "fling_witch", Stage::shoot);
    }

    private static void moments(Stage stage, int warmupTicks, int castTicks, String prefix, Shot shot) {
        // 起手到一半、泼出后一 tick、粒子散开、收势结束后，四个时刻各拍一次。
        int windup = warmupTicks / HALVES;
        stage.pause(windup);
        shot.take(stage, prefix + "_1_windup");
        stage.pause(warmupTicks - windup + AFTER_RELEASE_TICKS);
        shot.take(stage, prefix + "_2_release");
        stage.pause(SPREAD_TICKS);
        shot.take(stage, prefix + "_3_spread");
        stage.pause(castTicks - warmupTicks - AFTER_RELEASE_TICKS - SPREAD_TICKS + RECOVERED_TICKS);
        shot.take(stage, prefix + "_4_recovered");
    }

    private static void bothSides(Stage stage, String name) {
        // 同一 tick 里先从背后拍、再从正面拍；切镜头只在本机，不推进游戏。
        stage.cameraNow(CameraType.THIRD_PERSON_BACK);
        stage.shoot(name + "_back");
        stage.cameraNow(CameraType.THIRD_PERSON_FRONT);
        stage.shoot(name + "_front");
    }

    private static void cast(LivingEntity caster) {
        // 精确引用取生产注册表，身体从原版伤害桥读，与 GameTest 相同；起手失败直接让本场失败。
        var definition = TechniqueFabric.service().registry().findLatest(FLING).orElseThrow();
        var reference = TechniqueReference.newBuilder()
                .setId(definition.getId())
                .setVersion(definition.getVersion())
                .build();
        var request = CastRequest.newBuilder()
                .setCastId("clienttest_fling_" + UUID.randomUUID())
                .setBodyId(VanillaDamageFabric.inspectBody(caster).orElseThrow().getId())
                .setTechnique(reference)
                .build();
        CastOutcome outcome = TechniqueFabric.service().cast(caster.getUUID(), request);
        if (outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
            throw new AssertionError("泼引起手失败：" + outcome.getReason());
        }
    }

    private static ItemStack splash() {
        // 一瓶寻常品质、做成喷溅的魅药。
        return BrewItemStacks.create(BREW, BrewQuality.COMMON, BrewUse.SPLASH);
    }

    private static LivingEntity firstPlayer(MinecraftServer server) {
        // 单人世界里只有测试客户端这一个玩家。
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static Witch witch(MinecraftServer server, Vec3 feet) {
        // 生成命令在原地生出的那一只；没有或多出来都说明场地不对。
        List<Witch> witches = server.overworld().getEntitiesOfClass(Witch.class, WITCH_BOX.move(feet));
        if (witches.size() != 1) {
            throw new AssertionError("女巫生成位置附近应只有一只：" + witches);
        }
        return witches.getFirst();
    }

    @FunctionalInterface
    private interface Shot {
        void take(Stage stage, String name);
    }
}
