package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.protocol.pb.BrewEffectMessages.BrewDose;
import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import dev.kizuna.meridian.human.fabric.HumanDebugHost;
import dev.kizuna.meridian.human.fabric.HumanGameTestAccess;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewPartGroup;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.consume_effects.ClearAllStatusEffectsConsumeEffect;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** 生产行药诀目录由真实玩家学卷，再经过 TechniqueService 与真实身体宿主施放。 */
public final class WitchBrewGameTest {
    private static final int TIMEOUT = 220;
    private static final int LOGIN_TICKS = 3;
    private static final int IMBIBE_EFFECT = 65;
    private static final int ANOINT_EFFECT = 53;
    private static final int FAN_EFFECT = 17;
    private static final int FAN_FINISHED = 37;
    private static final int MIST_EFFECT = 33;
    private static final int LOB_LANDED = 90;
    private static final double QI = 100;
    private static final double DENSITY = 1;
    private static final double WOUND = 5;
    private static final double TOLERANCE = 0.000001;
    private static final long COMMON_DRINK_DURATION = 30_000;
    private static final long COMMON_NAUSEA = 4_000;
    private static final double SUPERIOR_STRENGTH = 1.2;
    private static final double ANOINT_STRENGTH = 1.5;
    private static final long ANOINT_DURATION = 20_000;
    private static final double TARGET_DISTANCE = 2;
    private static final int FAN_TARGETS = 5;
    private static final int FAN_LIMIT = 4;
    private static final double SPACING = 0.15;
    private static final float LOOK_SOUTH = 0;
    private static final long STAGGER_MILLIS = 500;
    private static final double STAGGER_SPEED = 0.5;
    private static final double TRIGGER_DURATION = 0.5;
    private static final int SPAWN_HEIGHT = 3;
    private static final String PREFIX = "meridian_witch:";

    @GameTest(maxTicks = TIMEOUT)
    public void imbibePromotesStrengthExtendsDurationAndHalvesNausea(GameTestHelper context) {
        // 寻常药的持续与代价按原品质，治疗强度按精良；效果时才在原手位留下空瓶。
        ready(context, player -> {
            wound(player, "right_upper_arm");
            hold(player, BrewKind.BITTER_YELLOW, BrewQuality.COMMON, BrewUse.DRINK);
            long started = cast(context, player, "imbibe");
            context.assertTrue(player.getMainHandItem().is(Items.POTION), "起手提前消耗药瓶");
            context.runAfterDelay(IMBIBE_EFFECT, () -> {
                BrewDose dose = dose(player, BrewKind.BITTER_YELLOW);
                context.assertTrue(dose.getStrengthQuality() == BrewQuality.FINE.componentValue(), "服药未升档强度");
                context.assertTrue(dose.getEndsMillis() - dose.getStartedMillis() == COMMON_DRINK_DURATION,
                        "服药持续未按原品质乘 1.5");
                long nausea = BrewBodyProgress.read(BrewingTestPlayers.body(player)).getNauseaUntilMillis();
                context.assertTrue(nausea - dose.getStartedMillis() == COMMON_NAUSEA, "苦黄膏反胃未减半");
                context.assertTrue(player.getMainHandItem().is(Items.GLASS_BOTTLE), "服药没有原手位空瓶");
                context.assertTrue(BrewResidues.snapshots().stream()
                        .noneMatch(value -> value.key().source().equals(player.getUUID())), "服药错误留下残药");
                WitchSoundAssertions.timeline(context, player, "imbibe", started);
                finish(context, player);
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void superiorImbibeScalesIntakeGainAndCreativeKeepsBottle(GameTestHelper context) {
        // 优质封顶只把超出一的摄入增益乘 1.2，创造玩家保留实际药瓶。
        ready(context, player -> {
            player.setGameMode(GameType.CREATIVE);
            hold(player, BrewKind.QI_GATHER, BrewQuality.SUPERIOR, BrewUse.DRINK);
            cast(context, player, "imbibe");
            context.runAfterDelay(IMBIBE_EFFECT, () -> {
                BrewDose dose = dose(player, BrewKind.QI_GATHER);
                context.assertTrue(Math.abs(dose.getStrengthMultiplier() - SUPERIOR_STRENGTH) < TOLERANCE,
                        "优质强度没有乘 1.2");
                var modifiers = BrewingTestPlayers.body(player).getModifiersList();
                context.assertTrue(modifiers.stream().filter(modifier -> modifier.hasOrganId())
                        .filter(modifier -> modifier.getStartMillis() == dose.getStartedMillis())
                        .allMatch(modifier -> Math.abs(modifier.getIntakePeak() - (1 + SUPERIOR_STRENGTH)) < TOLERANCE),
                        "聚气错误放大基础摄入");
                context.assertTrue(player.getMainHandItem().is(Items.POTION), "创造模式扣了药瓶");
                finish(context, player);
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void anointOnlyHealsWorseLimbGroupWithoutNausea(GameTestHelper context) {
        // 腿组伤重，敷药只给腿组药封；两臂保持无药封。
        ready(context, player -> {
            wound(player, "right_thigh");
            hold(player, BrewKind.BITTER_YELLOW, BrewQuality.COMMON, BrewUse.DRINK);
            long started = cast(context, player, "anoint");
            context.runAfterDelay(ANOINT_EFFECT, () -> {
                BrewDose dose = dose(player, BrewKind.BITTER_YELLOW);
                context.assertTrue(dose.getPartIdsList().stream().allMatch(part ->
                        BrewPartSelection.matches(part, BrewPartGroup.LEGS)),
                        "外敷药封波及另一组");
                context.assertTrue(dose.getStrengthMultiplier() == ANOINT_STRENGTH, "外敷强度未乘 1.5");
                context.assertTrue(dose.getEndsMillis() - dose.getStartedMillis() == ANOINT_DURATION,
                        "外敷误用了喷溅持续");
                context.assertTrue(BrewBodyProgress.read(BrewingTestPlayers.body(player)).getNauseaUntilMillis() == 0,
                        "外敷造成反胃");
                WitchSoundAssertions.timeline(context, player, "anoint_legs", started);
                finish(context, player);
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void anointArmsUsesOnlyArmSounds(GameTestHelper context) {
        // 两臂伤重时只播两次抹药，不播腿部拍药声。
        ready(context, player -> {
            wound(player, "right_upper_arm");
            hold(player, BrewKind.BITTER_YELLOW, BrewQuality.COMMON, BrewUse.DRINK);
            long started = cast(context, player, "anoint");
            context.runAfterDelay(ANOINT_EFFECT, () -> {
                WitchSoundAssertions.timeline(context, player, "anoint_arms", started);
                context.assertTrue(BrewResidues.snapshots().stream()
                        .noneMatch(value -> value.key().source().equals(player.getUUID())), "敷药错误留下残药");
                finish(context, player);
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void wrongHandsRejectBeforeConsumptionAndInterruptedWarmupKeepsBottle(GameTestHelper context) {
        // 起手的失败原因明确，受击打断不能提前消费未来效果事件的药瓶。
        ready(context, player -> {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
            rejected(context, player, "imbibe", "要拿一瓶饮用的巫药");
            rejected(context, player, "lob_seed", "要喷溅型的巫药");
            hold(player, BrewKind.BITTER_YELLOW, BrewQuality.COMMON, BrewUse.DRINK);
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.STONE));
            rejected(context, player, "anoint", "敷药要空出副手");
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            cast(context, player, "imbibe");
            TechniqueFabric.service().stagger(player.getUUID(), new TechniqueService.Stagger(
                    STAGGER_MILLIS, STAGGER_SPEED));
            context.runAfterDelay(IMBIBE_EFFECT, () -> {
                context.assertTrue(player.getMainHandItem().is(Items.POTION), "被打断后药瓶仍被消费");
                context.assertTrue(BrewBodyProgress.read(BrewingTestPlayers.body(player)).getDosesCount() == 0,
                        "被打断后药效仍落地");
                finish(context, player);
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void fanLimitsTargetsAndMistTriggersSameSourceThroughWallThenMilkClears(GameTestHelper context) {
        // 五个正面目标只中四个，随后隔墙催同种残药，清除后不能再次催发。
        ready(context, player -> {
            var targets = new ArrayList<Villager>();
            for (int index = 0; index < FAN_TARGETS; index++) {
                targets.add(target(context, player, index * SPACING, TARGET_DISTANCE));
            }
            hold(player, BrewKind.ARMOR_ROT, BrewQuality.COMMON, BrewUse.SPLASH);
            long started = cast(context, player, "fling_seed");
            context.runAfterDelay(FAN_EFFECT, () -> {
                long affected = targets.stream().filter(target -> target.hasEffect(BrewingEffects.ARMOR_ROT)).count();
                context.assertTrue(affected == FAN_LIMIT, "泼引没有限制最多四个目标：" + affected);
                context.assertTrue(player.getMainHandItem().is(Items.GLASS_BOTTLE), "泼引没有留下空瓶");
                context.assertTrue(!BrewResidues.snapshots().isEmpty(), "泼引没有下残药");
                WitchSoundAssertions.timeline(context, player, "fling_seed", started);
                var affectedTarget = targets.stream().filter(target -> target.hasEffect(BrewingEffects.ARMOR_ROT))
                        .findFirst().orElseThrow();
                WitchSoundAssertions.effectAt(context, player, affectedTarget, "potion.armor_rot");
                WitchSoundAssertions.effectAt(context, player, affectedTarget, "residue.plant");
            });
            context.runAfterDelay(FAN_FINISHED, () -> triggerAcrossWall(context, player, targets));
        });
    }

    private static void triggerAcrossWall(GameTestHelper context, ServerPlayer player, List<Villager> targets) {
        // 墙建立在两次施放之间，催发应忽略视线且仅消费施药者自己的残药。
        BlockPos wall = player.blockPosition().south();
        context.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        context.getLevel().setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState());
        hold(player, BrewKind.ARMOR_ROT, BrewQuality.POOR, BrewUse.SPLASH);
        long started = cast(context, player, "spray_trigger");
        context.runAfterDelay(MIST_EFFECT, () -> {
            context.assertTrue(BrewResidues.snapshots().stream()
                    .noneMatch(value -> value.key().source().equals(player.getUUID())), "催发没有清除同源残药");
            for (Villager target : targets) {
                var body = BrewBodyHost.inspect(target).orElseThrow().body();
                for (BrewDose dose : BrewBodyProgress.read(body).getDosesList()) {
                    context.assertTrue(dose.getStrengthMultiplier() < 1, "催发没有减半强度并乘剩余剂量");
                    context.assertTrue(dose.getDurationMultiplier() == TRIGGER_DURATION, "催发持续没有减半");
                }
                ClearAllStatusEffectsConsumeEffect.INSTANCE.apply(context.getLevel(),
                        new ItemStack(Items.MILK_BUCKET), target);
            }
            context.assertTrue(BrewResidues.snapshots().stream()
                    .noneMatch(value -> targets.stream()
                            .anyMatch(target -> target.getUUID().equals(value.key().target()))),
                    "牛奶没有清残药");
            targets.forEach(Villager::discard);
            WitchSoundAssertions.timeline(context, player, "spray_trigger", started);
            finish(context, player);
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void lobProducesRealProjectileAndSeedsOnlyOnImpact(GameTestHelper context) {
        // 使用真实药瓶弹道撞地，出手后主手空；普通手扔入口不会登记这种残药。
        ready(context, player -> {
            Villager target = target(context, player, 0, TARGET_DISTANCE);
            BlockPos ground = target.blockPosition().below();
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    context.getLevel().setBlockAndUpdate(ground.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                }
            }
            hold(player, BrewKind.ARMOR_ROT, BrewQuality.COMMON, BrewUse.SPLASH);
            long started = cast(context, player, "lob_seed");
            context.runAfterDelay(LOB_LANDED, () -> {
                context.assertTrue(player.getMainHandItem().isEmpty(), "掷引没有掷出手里药瓶");
                context.assertTrue(BrewResidues.snapshots().stream().anyMatch(value ->
                        value.key().target().equals(target.getUUID()) && value.key().source().equals(player.getUUID())),
                        "真实药瓶落地没有留下同源残药");
                WitchSoundAssertions.timeline(context, player, "lob_seed", started);
                target.discard();
                finish(context, player);
            });
        });
    }

    static void ready(GameTestHelper context, Consumer<ServerPlayer> action) {
        // 玩家登录完成后准备境界与真元，书卷学习与施放均使用正式服务。
        ServerPlayer player = BrewingTestPlayers.create(context);
        context.runAfterDelay(LOGIN_TICKS, () -> {
            HumanGameTestAccess.openMeridians(player, "all");
            HumanGameTestAccess.enterTrueQi(player);
            var qi = DebugQi.newBuilder()
                    .setMode(DebugQiMode.SET_INVENTORY)
                    .setAmount(QI)
                    .setDensity(DENSITY)
                    .build();
            context.assertTrue(HumanDebugHost.debugQi(player, qi) == FailureCode.FAILURE_CODE_UNSPECIFIED,
                    "准备真元失败");
            TechniqueFabric.service().learn(player.getUUID(), PREFIX + "guard");
            TechniqueFabric.service().learn(player.getUUID(), PREFIX + "seed");
            player.setYRot(LOOK_SOUTH);
            player.setXRot(0);
            action.accept(player);
        });
    }

    static long cast(GameTestHelper context, ServerPlayer player, String technique) {
        // 成功必须带真实绑定，避免后续效果断言掩盖起手费用或持物失败。
        long started = player.level().getGameTime();
        var result = TechniqueFabric.service().cast(player.getUUID(), request(player, technique));
        context.assertTrue(result.hasBinding() && result.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                "行药诀起手失败：" + result);
        return started;
    }

    static void rejected(GameTestHelper context, ServerPlayer player, String technique, String reason) {
        // 拒绝原因由正式起手入口返回，不直接调用内部持物判定。
        var result = TechniqueFabric.service().cast(player.getUUID(), request(player, technique));
        context.assertTrue(!result.hasBinding() && result.getReason().equals(reason), "失败原因不符：" + result);
    }

    private static CastRequest request(ServerPlayer player, String technique) {
        // 精确引用取生产注册表，测试不能覆盖目录的费用与事件时刻。
        var definition = TechniqueFabric.service().registry().findLatest(PREFIX + technique).orElseThrow();
        var reference = TechniqueReference.newBuilder()
                .setId(definition.getId())
                .setVersion(definition.getVersion())
                .build();
        return CastRequest.newBuilder()
                .setBodyId(player.getStringUUID())
                .setCastId(UUID.randomUUID().toString())
                .setTechnique(reference)
                .build();
    }

    private static BrewDose dose(ServerPlayer player, BrewKind kind) {
        // 读取唯一身体账本里的实际药效记录。
        return BrewBodyProgress.read(BrewingTestPlayers.body(player)).getDosesList().stream()
                .filter(dose -> dose.getKind().equals(kind.name())).findFirst().orElseThrow();
    }

    static void hold(ServerPlayer player, BrewKind kind, BrewQuality quality, BrewUse use) {
        // 实际组件物品经过宿主 inspect，不把用药事实注入测试替身。
        player.setItemInHand(InteractionHand.MAIN_HAND, BrewItemStacks.create(kind, quality, use));
    }

    private static void wound(ServerPlayer player, String part) {
        // 真实身体伤势用于比较敷药部位和治疗量。
        VanillaDamageFabric.damageDirect(new VanillaDamageFabric.DirectDamage(player,
                player.damageSources().generic(), part, WOUND)).orElseThrow();
    }

    static Villager target(GameTestHelper context, ServerPlayer player, double x, double z) {
        // 静止目标只隔离几何和用药，身体仍由正式类人宿主认领。
        var target = context.spawn(EntityTypes.VILLAGER, new BlockPos(1, SPAWN_HEIGHT, 1));
        target.setNoAi(true /* noAi */);
        target.setNoGravity(true /* noGravity */);
        target.setPos(player.getX() + x, player.getY(), player.getZ() + z);
        BrewBodyHost.inspect(target).orElseThrow(); // 校验实体已获得真实身体，缺失即抛异常。
        return target;
    }

    static void finish(GameTestHelper context, ServerPlayer player) {
        // 退出正式连接使尚在收势中的施放清理，再完成本用例。
        BrewingTestPlayers.close(player);
        context.succeed();
    }
}
