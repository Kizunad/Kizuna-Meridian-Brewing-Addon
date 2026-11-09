package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewPartGroup;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 真实出手记录保留准星分段，实际飞行无横向散布并在目标脚边命中。 */
public final class WitchProjectileGameTest {
    private static final String STRUCTURE = "meridian_brewing_test:witch_range";
    private static final int TIMEOUT = 160;
    private static final int RELEASED = 21;
    private static final int LANDED = 80;
    private static final double DISTANCE = 8;
    private static final double OUTSIDE_RANGE = 16.2;
    private static final double HEAD_HEIGHT = 0.9;
    private static final double TORSO_HEIGHT = 0.6;
    private static final double LEG_HEIGHT = 0.2;
    private static final double VELOCITY_TOLERANCE = 0.000001;
    private static final int FLOOR_RADIUS = 2;

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void headAimSurvivesTurningAfterRelease(GameTestHelper context) {
        // 头颈瞄准只在出手时读取，飞行中转头不改变落地偏向。
        observe(context, HEAD_HEIGHT, BrewPartGroup.HEAD_NECK);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void torsoAimHasNoRandomSpread(GameTestHelper context) {
        // 躯干瞄准的落点仍是目标脚下，而非眼睛所在高度。
        observe(context, TORSO_HEIGHT, BrewPartGroup.TORSO);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void legAimHasNoRandomSpread(GameTestHelper context) {
        // 腿部瞄准沿同一无散布弹道落地，记录的部位组独立于落点高度。
        observe(context, LEG_HEIGHT, BrewPartGroup.LEGS);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void boundingBoxInsideRangeDoesNotLockAnOutOfRangeTarget(GameTestHelper context) {
        // 碰撞箱伸进射程不能把超距目标的脚下变成落点。
        WitchBrewGameTest.ready(context, player -> {
            var target = WitchBrewGameTest.target(context, player, 0, OUTSIDE_RANGE);
            WitchBrewGameTest.hold(player, BrewKind.ARMOR_ROT, BrewQuality.COMMON, BrewUse.SPLASH);
            WitchBrewGameTest.cast(context, player, "lob_seed");
            context.runAfterDelay(RELEASED, () -> {
                var projectile = context.getLevel().getEntitiesOfClass(ThrownSplashPotion.class,
                        player.getBoundingBox().inflate(DISTANCE), potion -> potion.getOwner() == player)
                        .getFirst();
                context.assertTrue(BrewProjectiles.aimedGroup(projectile.getUUID())
                        == BrewPartGroup.BREW_PART_GROUP_UNSPECIFIED, "碰撞箱让超距目标绕过射程");
                projectile.discard();
                target.discard();
                WitchBrewGameTest.finish(context, player);
            });
        });
    }

    private static void observe(GameTestHelper context, double height, BrewPartGroup expected) {
        // 只从正式施放创建飞行实体，不直接调用射出或命中处理器。
        WitchBrewGameTest.ready(context, player -> {
            var target = WitchBrewGameTest.target(context, player, 0, DISTANCE);
            BlockPos ground = target.blockPosition().below();
            for (int x = -FLOOR_RADIUS; x <= FLOOR_RADIUS; x++) {
                for (int z = -FLOOR_RADIUS; z <= FLOOR_RADIUS; z++) {
                    context.getLevel().setBlockAndUpdate(ground.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                }
            }
            Vec3 offset = target.position().add(0, target.getBbHeight() * height, 0)
                    .subtract(player.getEyePosition());
            player.setXRot((float) -Math.toDegrees(Math.atan2(offset.y, Math.hypot(offset.x, offset.z))));
            WitchBrewGameTest.hold(player, BrewKind.ARMOR_ROT, BrewQuality.COMMON, BrewUse.SPLASH);
            WitchBrewGameTest.cast(context, player, "lob_seed");
            context.runAfterDelay(RELEASED, () -> {
                var projectiles = context.getLevel().getEntitiesOfClass(ThrownSplashPotion.class,
                        player.getBoundingBox().inflate(DISTANCE), potion -> potion.getOwner() == player);
                context.assertTrue(projectiles.size() == 1, "掷引没有生成唯一真实药瓶");
                var projectile = projectiles.getFirst();
                context.assertTrue(BrewProjectiles.aimedGroup(projectile.getUUID()) == expected,
                        "准星分段未冻结：" + BrewProjectiles.aimedGroup(projectile.getUUID()) + " != " + expected
                                + " eye=" + player.getEyePosition() + " look=" + player.getLookAngle()
                                + " target=" + target.getBoundingBox());
                context.assertTrue(Math.abs(projectile.getDeltaMovement().x) < VELOCITY_TOLERANCE,
                        "正前方药瓶出现随机横向散布");
                player.setXRot(0);
                context.assertTrue(BrewProjectiles.aimedGroup(projectile.getUUID()) == expected, "转头改变飞行元数据");
            });
            context.runAfterDelay(LANDED, () -> {
                context.assertTrue(BrewResidues.snapshots().stream().anyMatch(value ->
                        value.key().source().equals(player.getUUID()) && value.key().target().equals(target.getUUID())),
                        "药瓶没有落到瞄准目标附近");
                target.discard();
                WitchBrewGameTest.finish(context, player);
            });
        });
    }
}
