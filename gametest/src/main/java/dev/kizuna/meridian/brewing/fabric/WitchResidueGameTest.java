package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewBodyFacts;
import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewThresholds;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CapabilityModifier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.consume_effects.ClearAllStatusEffectsConsumeEffect;
import net.minecraft.world.level.block.Blocks;

/** 残药必须由真实玩家施放产生，随后在世界时钟、身体和牛奶入口中验证。 */
public final class WitchResidueGameTest {
    private static final int TIMEOUT = 520;
    private static final int FAN_EFFECT = 17;
    private static final int FAN_FINISHED = 37;
    private static final int MIST_EFFECT = 33;
    private static final int MIST_READY = 250;
    private static final int HALF_LIFE_TICKS = 400;
    private static final double TARGET_DISTANCE = 2;
    private static final double SIDE = 0.3;
    private static final double OUT_OF_RANGE = 17;
    private static final double SECONDS_PER_TICK = 0.05;
    private static final double HALF_LIFE_SECONDS = 20;
    private static final double TOLERANCE = 0.002;
    private static final double WEAK_KIDNEY = 0.1;
    private static final long KIDNEY_DURATION = 30_000;
    private static final int RECOVERY_OBSERVATION = 20;
    private static final double RECOVERY_DIVISOR = 3;

    @GameTest(maxTicks = TIMEOUT)
    public void sourceKindRangeAndMilkFilterRealSeeds(GameTestHelper context) {
        // 两名施药者各自下引；错误药种、超距与牛奶分别隔离，最后隔墙只催本人同种。
        WitchBrewGameTest.ready(context, first -> WitchBrewGameTest.ready(context, second -> {
            second.setPos(first.getX() + SIDE, first.getY(), first.getZ());
            var near = WitchBrewGameTest.target(context, first, 0, TARGET_DISTANCE);
            var far = WitchBrewGameTest.target(context, first, SIDE, TARGET_DISTANCE);
            var milk = WitchBrewGameTest.target(context, first, -SIDE, TARGET_DISTANCE);
            fan(context, first, BrewKind.ARMOR_ROT);
            fan(context, second, BrewKind.ARMOR_ROT);
            context.runAfterDelay(FAN_EFFECT, () -> {
                context.assertTrue(has(first, near) && has(second, near), "两来源没有分别下残药");
                context.assertTrue(has(first, milk), "牛奶测试没有真实残药");
                milk.removeEffect(BrewingEffects.ARMOR_ROT);
                ClearAllStatusEffectsConsumeEffect.INSTANCE.apply(context.getLevel(),
                        new ItemStack(Items.MILK_BUCKET), milk);
                context.assertTrue(!has(first, milk) && !has(second, milk), "无可见药效时牛奶没清残药");
                far.setPos(first.getX(), first.getY(), first.getZ() + OUT_OF_RANGE);
            });
            context.runAfterDelay(FAN_FINISHED, () -> {
                mist(context, first, BrewKind.INSECT_LURE);
                context.runAfterDelay(MIST_EFFECT, () -> context.assertTrue(has(first, near), "错误药种催掉残药"));
            });
            context.runAfterDelay(MIST_READY, () -> {
                var wall = first.blockPosition().south();
                context.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
                context.getLevel().setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState());
                mist(context, first, BrewKind.ARMOR_ROT);
                context.runAfterDelay(MIST_EFFECT, () -> {
                    context.assertTrue(!has(first, near) && has(second, near), "催发混用了别人的残药");
                    context.assertTrue(has(first, far), "噀催越过 16 米");
                    List.of(near, far, milk).forEach(LivingEntity::discard);
                    BrewingTestPlayers.close(second);
                    WitchBrewGameTest.finish(context, first);
                });
            });
        }));
    }

    @GameTest(maxTicks = TIMEOUT)
    public void realKidneysScaleResidueHalfLife(GameTestHelper context) {
        // 改变真实器官吞吐后，同一泼引的残药按各自肾脏档衰减。
        WitchBrewGameTest.ready(context, player -> {
            var ordinary = WitchBrewGameTest.target(context, player, -SIDE, TARGET_DISTANCE);
            var weak = WitchBrewGameTest.target(context, player, SIDE, TARGET_DISTANCE);
            weakenKidneys(weak);
            fan(context, player, BrewKind.DEPLETION);
            context.runAfterDelay(FAN_EFFECT, () -> {
                double ordinaryDose = dose(player, ordinary);
                double weakDose = dose(player, weak);
                double ordinaryHalfLife = halfLife(ordinary);
                double weakHalfLife = halfLife(weak);
                context.assertTrue(weakHalfLife > ordinaryHalfLife, "肾脏夹具没有形成档位差");
                context.runAfterDelay(HALF_LIFE_TICKS, () -> {
                    assertDecay(context, dose(player, ordinary) / ordinaryDose, ordinaryHalfLife, HALF_LIFE_TICKS);
                    assertDecay(context, dose(player, weak) / weakDose, weakHalfLife, HALF_LIFE_TICKS);
                    ordinary.discard();
                    weak.discard();
                    WitchBrewGameTest.finish(context, player);
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void formalRecoveryCastTriplesResidueDecay(GameTestHelper context) {
        // 目标亲自学调息并施放；世界宿主必须读到恢复类活动，再把半衰期除以三。
        WitchBrewGameTest.ready(context, source -> WitchBrewGameTest.ready(context, target -> {
            target.setPos(source.getX(), source.getY(), source.getZ() + TARGET_DISTANCE);
            fan(context, source, BrewKind.ARMOR_ROT);
            context.runAfterDelay(FAN_EFFECT, () -> {
                double before = dose(source, target);
                TechniqueFabric.service().learn(target.getUUID(), "meridian_basic_techniques:breath_volume");
                var reference = TechniqueReference.newBuilder()
                        .setId("meridian_basic_techniques:breath")
                        .setVersion(1)
                        .build();
                var request = CastRequest.newBuilder()
                        .setBodyId(target.getStringUUID())
                        .setCastId(UUID.randomUUID().toString())
                        .setTechnique(reference)
                        .build();
                context.assertTrue(TechniqueFabric.service().cast(target.getUUID(), request).hasBinding(), "调息起手失败");
                context.runAfterDelay(RECOVERY_OBSERVATION, () -> {
                    assertDecay(context, dose(source, target) / before,
                            HALF_LIFE_SECONDS / RECOVERY_DIVISOR, RECOVERY_OBSERVATION);
                    BrewingTestPlayers.close(target);
                    WitchBrewGameTest.finish(context, source);
                });
            });
        }));
    }

    private static void weakenKidneys(LivingEntity target) {
        // 通过可信身体活动安装器官吞吐修正，药效仍只能读取正式 inspect。
        var binding = BrewBodyHost.inspect(target).orElseThrow();
        long now = binding.body().getSettledAt().getElapsedMillis();
        var activity = BodyActivity.newBuilder();
        var start = CurvePoint.newBuilder()
                .setOffsetMillis(0)
                .setValue(1)
                .build();
        var end = CurvePoint.newBuilder()
                .setOffsetMillis(KIDNEY_DURATION)
                .setValue(1)
                .build();
        for (var organ : binding.body().getStructure().getOrgansList()) {
            if (organ.getKind().endsWith("_kidney")) {
                var modifier = CapabilityModifier.newBuilder()
                        .setId(BrewBodyProgress.KEY + "/test_kidney/" + organ.getId())
                        .setSource(BrewBodyProgress.KEY)
                        .setOrganId(organ.getId())
                        .setStartMillis(now)
                        .setStrengthPeak(1)
                        .setBurstPeak(1)
                        .setEndurancePeak(1)
                        .setRecoveryPeak(1)
                        .setThroughputPeak(WEAK_KIDNEY)
                        .addCurve(start)
                        .addCurve(end)
                        .build();
                activity.addAddModifiers(modifier);
            }
        }
        BrewBodyHost.activity(binding, activity.build());
    }

    private static double halfLife(LivingEntity target) {
        // 预期采用正式身体器官事实，指数衰减由独立公式核对。
        var body = BrewBodyHost.inspect(target).orElseThrow().body();
        return HALF_LIFE_SECONDS * BrewBodyFacts.detox(body, BrewThresholds.DEFAULT).tier().durationMultiplier();
    }

    private static void assertDecay(GameTestHelper context, double ratio, double halfLife, int ticks) {
        // 同一观察阶段的首尾比值避免把起效后一 tick 的自然衰减误当成初始剂量损失。
        double expected = Math.pow(0.5, ticks * SECONDS_PER_TICK / halfLife);
        context.assertTrue(Math.abs(ratio - expected) < TOLERANCE, "残药衰减不符：" + ratio + " != " + expected);
    }

    private static double dose(ServerPlayer source, LivingEntity target) {
        // 同一瓶的所有沾药部位等速衰减，任选真实存在的一份即可核对。
        return BrewResidues.snapshots().stream()
                .filter(value -> value.key().source().equals(source.getUUID())
                        && value.key().target().equals(target.getUUID()))
                .mapToDouble(value -> value.dose()).findFirst().orElseThrow();
    }

    private static boolean has(ServerPlayer source, LivingEntity target) {
        // 查来源与目标联合身份，不能用全服非空冒充本人的残药。
        return BrewResidues.snapshots().stream().anyMatch(value -> value.key().source().equals(source.getUUID())
                && value.key().target().equals(target.getUUID()));
    }

    private static void fan(GameTestHelper context, ServerPlayer player, BrewKind kind) {
        // 初始残药只从生产泼引获得。
        WitchBrewGameTest.hold(player, kind, BrewQuality.COMMON, BrewUse.SPLASH);
        WitchBrewGameTest.cast(context, player, "fling_seed");
    }

    private static void mist(GameTestHelper context, ServerPlayer player, BrewKind kind) {
        // 手里实际药种由正式 inspect 读取。
        WitchBrewGameTest.hold(player, kind, BrewQuality.COMMON, BrewUse.SPLASH);
        WitchBrewGameTest.cast(context, player, "spray_trigger");
    }
}
