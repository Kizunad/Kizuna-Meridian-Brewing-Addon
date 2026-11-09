package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.BrewThresholds;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import dev.kizuna.meridian.human.fabric.HumanControl;
import dev.kizuna.meridian.human.fabric.HumanStaggerLock;
import dev.kizuna.meridian.human.fabric.HumanDebugHost;
import dev.kizuna.meridian.human.fabric.HumanGameTestAccess;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.network.chat.Component;

/** Brewing、Threatening、Human 的正式会话与玩家网络门禁回归。 */
public final class BrewingCharmGameTest {
    private static final int TIMEOUT = 300;
    private static final int HEIGHT = 3;
    private static final int INTENT_DELAY = 6;
    private static final int SHORTENED_END = 101;
    private static final int MEMORY_EXPIRED = 101;
    private static final long IMMUNITY_MILLIS = 10_000;
    private static final double HIT_HP = 1;
    private static final double OUT_OF_RANGE = 17;
    private static final double LETHAL_HP = 10_000;
    private static final int CAST_DELAY = 45;
    private static final int CONTROL_EXPIRED = 121;
    private static final double CAST_QI = 100;
    private static final double QI_DENSITY = 0.01;
    private static final double TARGET_HP = 200;
    private static final double TARGET_OFFSET = 2;
    private static final String VOLUME = "meridian_door_breaker:upper";

    @GameTest(maxTicks = TIMEOUT)
    public void playerTargetPriorityAndMemoryExpiry(GameTestHelper context) {
        // 从真正伤害回执建立记忆：亲手命中优先于受击，过期后回到正瞄准施药者的最近敌人。
        ServerPlayer caster = BrewingTestPlayers.create(context);
        Villager subject = villager(context, 1);
        Villager attacked = villager(context, 2);
        Villager attacker = villager(context, 3);
        var hunter = context.spawn(EntityTypes.PILLAGER, new BlockPos(2, HEIGHT, 1));
        hunter.setNoAi(true /* noAi */);
        context.assertTrue(BrewCharmTargets.opponent(caster, subject) == null, "无敌人时没有跟随");
        hunter.setTarget(caster);
        context.assertTrue(BrewCharmTargets.opponent(caster, subject) == hunter, "第三步没有选当前敌对目标");
        hit(caster, attacker);
        context.assertTrue(BrewCharmTargets.opponent(caster, subject) == attacker, "受击记忆没有优先于当前敌人");
        hit(attacked, caster);
        context.assertTrue(BrewCharmTargets.opponent(caster, subject) == attacked, "亲手命中没有第一优先级");
        context.runAfterDelay(MEMORY_EXPIRED, () -> {
            context.assertTrue(BrewCharmTargets.opponent(caster, subject) == hunter, "五秒旧命中没有过期");
            hunter.setTarget(null);
            context.assertTrue(BrewCharmTargets.opponent(caster, subject) == null, "四步兜底没有回到跟随");
            close(caster, List.of(subject, attacked, attacker, hunter));
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void allyHitWakesAndStartsTenSecondImmunity(GameTestHelper context) {
        // 施药者亲手命中立即结束控制，提前醒按此刻重新写十秒免疫。
        ServerPlayer caster = BrewingTestPlayers.create(context);
        Villager subject = villager(context, 2);
        var subjectId = subject.getUUID();
        charm(subject, caster);
        context.assertTrue(BrewCharmSessions.controlled(subjectId), "弱档未开始控制");
        hit(subject, caster);
        context.assertTrue(!BrewCharmSessions.controlled(subjectId), "盟友伤害未立即醒");
        var body = BrewBodyHost.inspect(subject).orElseThrow().body();
        long remaining = BrewBodyProgress.read(body).getCharmImmunityUntilMillis()
                - body.getSettledAt().getElapsedMillis();
        context.assertTrue(remaining == IMMUNITY_MILLIS, "免疫不是从实际醒来算十秒");
        charm(subject, caster);
        context.assertTrue(!BrewCharmSessions.controlled(subjectId), "免疫期被重复控制");
        close(caster, List.of(subject));
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void otherHitShortensControlByOneSecond(GameTestHelper context) {
        // 陌生人的一次有效伤害不立即醒，六秒控制会在五秒结束。
        ServerPlayer caster = BrewingTestPlayers.create(context);
        Villager subject = villager(context, 2);
        var subjectId = subject.getUUID();
        Villager stranger = villager(context, 3);
        charm(subject, caster);
        hit(subject, stranger);
        context.assertTrue(BrewCharmSessions.controlled(subjectId), "外人伤害立即叫醒");
        context.runAfterDelay(SHORTENED_END, () -> {
            context.assertTrue(!BrewCharmSessions.controlled(subjectId), "外人一击没有扣掉一秒");
            close(caster, List.of(subject, stranger));
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void casterDeathEndsControl(GameTestHelper context) {
        // 用正式直接伤害杀死施药者，受控者无需再挨打即醒。
        ServerPlayer caster = BrewingTestPlayers.create(context);
        Villager subject = villager(context, 2);
        charm(subject, caster);
        VanillaDamageFabric.damageDirect(new VanillaDamageFabric.DirectDamage(caster,
                caster.damageSources().fellOutOfWorld(), "head", LETHAL_HP)).orElseThrow();
        context.runAfterDelay(1, () -> {
            context.assertTrue(!BrewCharmSessions.controlled(subject.getUUID()), "施药者死亡没有醒");
            close(caster, List.of(subject));
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void leavingSixteenMetersEndsControl(GameTestHelper context) {
        // 距离按真实实体坐标判断，超出半径后下个输入 tick 就结束。
        ServerPlayer caster = BrewingTestPlayers.create(context);
        Villager subject = villager(context, 2);
        charm(subject, caster);
        caster.setPos(subject.getX() + OUT_OF_RANGE, subject.getY(), subject.getZ());
        context.runAfterDelay(1, () -> {
            context.assertTrue(!BrewCharmSessions.controlled(subject.getUUID()), "超距未结束控制");
            close(caster, List.of(subject));
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void playerSessionSendsIntentsAndRejectsOwnActions(GameTestHelper context) {
        // 真正玩家会话通过网络发送逐 tick 意图；主动施放、热键栏、丢弃、放挖与容器都拒绝。
        ServerPlayer caster = BrewingTestPlayers.create(context);
        ServerPlayer subject = BrewingTestPlayers.create(context);
        var subjectId = subject.getUUID();
        subject.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        charm(subject, caster);
        context.assertTrue(HumanControl.controlled(subjectId), "Human 未开启受控会话");
        assertActionsBlocked(context, subject);
        context.runAfterDelay(INTENT_DELAY, () -> {
            var intents = BrewingTestPlayers.controlIntents(subject);
            context.assertTrue(intents.size() >= INTENT_DELAY - 1, "意图没有逐 tick 发到玩家通道");
            long sequence = 0;
            for (var intent : intents) {
                long receivedSequence = intent.getSequence();
                context.assertTrue(receivedSequence > sequence && !intent.getEnded(), "意图序号未单调递增");
                sequence = receivedSequence;
            }
            BrewingTestPlayers.close(subject);
            context.assertTrue(!HumanControl.controlled(subjectId), "下线仍受控");
            ServerPlayer rejoined = BrewingTestPlayers.rejoin(context, subject);
            context.runAfterDelay(1, () -> {
                context.assertTrue(!HumanControl.controlled(rejoined.getUUID())
                        && !rejoined.hasEffect(BrewingEffects.CHARM_CONTROL), "重登续接了旧控制");
                BrewingTestPlayers.close(rejoined);
                BrewingTestPlayers.close(caster);
                context.succeed();
            });
        });
    }

    private static void assertActionsBlocked(GameTestHelper context, ServerPlayer subject) {
        // 使用真实包处理与原版挖掘入口，不能仅断言门禁布尔值。
        var reference = TechniqueReference.newBuilder()
                .setId("meridian_test:unknown")
                .setVersion(1)
                .build();
        var cast = CastRequest.newBuilder()
                .setBodyId(BrewingTestPlayers.body(subject).getId())
                .setCastId("player-controlled-rejected")
                .setTechnique(reference)
                .build();
        var outcome = TechniqueFabric.service().cast(subject.getUUID(), cast);
        context.assertTrue(outcome.getFrameworkFailure() == FailureCode.ACCESS_DENIED, "玩家自己的施放未拒绝");
        subject.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(1));
        context.assertTrue(subject.getInventory().getSelectedSlot() == 0, "受控玩家切换了快捷栏");
        BlockPos position = subject.blockPosition();
        var drop = new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.DROP_ITEM,
                position, Direction.DOWN, 0);
        subject.connection.handlePlayerAction(drop);
        context.assertTrue(subject.getMainHandItem().is(Items.STONE), "受控玩家丢出了物品");
        BlockPos stone = position.below();
        subject.level().setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        context.assertTrue(!subject.gameMode.destroyBlock(stone), "受控玩家挖掉方块");
        assertPlacementBlocked(context, subject, stone);
        var menu = new SimpleMenuProvider((id, inventory, player) -> ChestMenu.threeRows(id, inventory),
                Component.literal("control-test"));
        context.assertTrue(subject.openMenu(menu).isEmpty(), "受控玩家打开容器");
    }

    private static void assertPlacementBlocked(GameTestHelper context, ServerPlayer subject, BlockPos floor) {
        // 经原版放置包尝试把手持石头放到相邻空格，服务器门禁必须在物品消费前拒绝。
        var level = subject.level();
        BlockPos support = floor.east();
        level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        BlockPos placement = support.above();
        level.setBlockAndUpdate(placement, Blocks.AIR.defaultBlockState());
        var hit = new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false /* inside */);
        subject.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 1));
        context.assertTrue(level.getBlockState(placement).isAir(), "受控玩家放下了方块");
        context.assertTrue(subject.getMainHandItem().getCount() == 1, "拒绝放置仍消耗物品");
    }

    @GameTest(maxTicks = TIMEOUT)
    public void playerControlDelegatesLearnedTechnique(GameTestHelper context) {
        // 准备真实身体的开脉与库存，服务端 NPC 脑选择玩家已学的正式式并实际扣费打击。
        ServerPlayer caster = BrewingTestPlayers.create(context);
        ServerPlayer subject = BrewingTestPlayers.create(context);
        prepareTechnique(context, subject);
        var target = context.spawn(EntityTypes.COW, new BlockPos(1, HEIGHT, 1));
        target.setPos(subject.position().add(0, 0, TARGET_OFFSET));
        target.setNoAi(true /* noAi */);
        target.setNoGravity(true /* noGravity */);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(TARGET_HP);
        target.setHealth((float) TARGET_HP);
        target.hurtServer(context.getLevel(), target.damageSources().playerAttack(caster), (float) HIT_HP);
        target.setDeltaMovement(Vec3.ZERO);
        float before = target.getHealth();
        double qi = BodyProtobuf.fromMessage(BrewingTestPlayers.body(subject)).totalQi();
        charm(subject, caster);
        // 上卷的破门起手需 1600 ms；观察窗口必须覆盖选择器选中该式时的首次打击。
        context.runAfterDelay(CAST_DELAY, () -> {
            var body = BodyProtobuf.fromMessage(BrewingTestPlayers.body(subject));
            var progress = TechniqueState.read(body);
            context.assertTrue(progress.hasActiveCast() || !progress.getCooldownsMap().isEmpty(), "未代发已学功法");
            context.assertTrue(body.totalQi() < qi, "代发没有消耗玩家自己的真元");
            context.assertTrue(target.getHealth() < before, "服务端代发功法没有真实打击：距离="
                    + subject.distanceTo(target) + "，视线=" + subject.hasLineOfSight(target)
                    + "，当前式=" + progress.getActiveCast().getRequest().getTechnique()
                    + "，目标=" + BrewCharmTargets.opponent(caster, subject));
            BrewingTestPlayers.close(subject);
            close(caster, List.of(target));
            context.succeed();
        });
    }

    private static void prepareTechnique(GameTestHelper context, ServerPlayer subject) {
        // 管理入口只准备资源，学习、选式、绑定和费用仍全部走正式功法服务。
        HumanGameTestAccess.openMeridians(subject, "all");
        var inventory = DebugQi.newBuilder()
                .setMode(DebugQiMode.SET_INVENTORY)
                .setAmount(CAST_QI)
                .setDensity(QI_DENSITY)
                .build();
        context.assertTrue(HumanDebugHost.debugQi(subject, inventory) == FailureCode.FAILURE_CODE_UNSPECIFIED,
                "准备真实功法库存失败");
        subject.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        subject.setOnGround(true /* onGround */);
        TechniqueFabric.service().learn(subject.getUUID(), VOLUME);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void playerCharmUsesConfiguredControlMode(GameTestHelper context) {
        // 同一回归分别在默认与关闭玩家控制的全新进程运行，实际加载配置决定预期分支。
        ServerPlayer caster = BrewingTestPlayers.create(context);
        ServerPlayer subject = BrewingTestPlayers.create(context);
        var subjectId = subject.getUUID();
        var config = BrewThresholds.load(FabricLoader.getInstance().getConfigDir()
                .resolve("meridian-brewing.properties"));
        charm(subject, caster);
        if (config.playerCharm() == BrewThresholds.PlayerCharm.CONTROL) {
            context.assertTrue(HumanControl.controlled(subjectId), "默认配置没有控制弱档玩家");
        } else {
            context.assertTrue(!HumanControl.controlled(subjectId) && HumanStaggerLock.isLocked(subjectId),
                    "关闭玩家控制后弱档没有降为魅惑僵直");
        }
        BrewingTestPlayers.close(subject);
        BrewingTestPlayers.close(caster);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void naturalExpiryClearsPlayerIntentAndStartsImmunity(GameTestHelper context) {
        // 自然到期发送结束意图，清服务端门禁并从醒来时刻开始十秒免疫。
        ServerPlayer caster = BrewingTestPlayers.create(context);
        ServerPlayer subject = BrewingTestPlayers.create(context);
        var subjectId = subject.getUUID();
        charm(subject, caster);
        context.runAfterDelay(CONTROL_EXPIRED, () -> {
            context.assertTrue(!HumanControl.controlled(subjectId), "自然到期没有归还玩家");
            var intents = BrewingTestPlayers.controlIntents(subject);
            context.assertTrue(intents.stream().anyMatch(intent -> intent.getEnded()), "自然醒没有发送结束意图");
            charm(subject, caster);
            context.assertTrue(!HumanControl.controlled(subjectId), "自然醒的免疫没有生效");
            subject.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(1));
            context.assertTrue(subject.getInventory().getSelectedSlot() == 1, "结束后快捷栏门禁仍残留");
            BrewingTestPlayers.close(subject);
            BrewingTestPlayers.close(caster);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void petsAndScoreboardTeammatesAreProtectedAndWakeSubjects(GameTestHelper context) {
        // 原版主人和队伍事实同时约束主动攻击与醒的伤害归属。
        ServerPlayer caster = BrewingTestPlayers.create(context);
        ServerPlayer teammate = BrewingTestPlayers.create(context);
        var level = context.getLevel();
        var scoreboard = level.getScoreboard();
        var team = scoreboard.addPlayerTeam("charm_" + caster.getId());
        scoreboard.addPlayerToTeam(caster.getScoreboardName(), team);
        scoreboard.addPlayerToTeam(teammate.getScoreboardName(), team);
        var pet = context.spawn(EntityTypes.WOLF, new BlockPos(1, HEIGHT, 1));
        pet.tame(caster);
        pet.setNoAi(true /* noAi */);
        var subject = context.spawn(EntityTypes.VINDICATOR, new BlockPos(2, HEIGHT, 1));
        var subjectId = subject.getUUID();
        subject.setNoAi(true /* noAi */);
        subject.setNoGravity(true /* noGravity */);
        charm(subject, caster);
        context.assertTrue(BrewCharmSessions.controlled(subjectId), "盟友保护测试没有开启控制");
        context.assertTrue(!subject.doHurtTarget(level, caster), "受控者攻击施药者");
        context.assertTrue(!subject.doHurtTarget(level, pet), "受控者攻击施药者宠物");
        context.assertTrue(!subject.doHurtTarget(level, teammate), "受控者攻击施药者队友");
        hit(subject, pet);
        context.assertTrue(!BrewCharmSessions.controlled(subjectId), "施药者宠物伤害未叫醒");
        Villager second = villager(context, 3);
        charm(second, caster);
        hit(second, teammate);
        context.assertTrue(!BrewCharmSessions.controlled(second.getUUID()), "原版队伍玩家伤害未叫醒");
        scoreboard.removePlayerTeam(team);
        BrewingTestPlayers.close(teammate);
        close(caster, List.of(subject, second, pet));
        context.succeed();
    }

    private static Villager villager(GameTestHelper context, int x) {
        // 正式健康宿主认领身体，关闭 AI 只用于隔离药效生命周期断言。
        Villager entity = context.spawn(EntityTypes.VILLAGER, new BlockPos(x, HEIGHT, 1));
        entity.setNoAi(true /* noAi */);
        entity.setNoGravity(true /* noGravity */);
        BrewBodyHost.inspect(entity).orElseThrow(); // 校验真实身体认领，失败会抛出异常。
        return entity;
    }

    private static void charm(LivingEntity subject, LivingEntity caster) {
        // 使用正式药效入口，仅固定命中主体，品质与脑力判定仍真实执行。
        var application = new BrewBodyEffects.Application(BrewKind.CHARM, BrewQuality.SUPERIOR,
                BrewUse.SPLASH, subject.position(), caster);
        BrewBodyEffects.apply(subject, application);
    }

    private static void hit(LivingEntity target, LivingEntity attacker) {
        // 指定轻伤确保一次真实伤害通知，避免无敌帧吞掉测试命中。
        var source = target.damageSources().mobAttack(attacker);
        VanillaDamageFabric.damageDirect(new VanillaDamageFabric.DirectDamage(target, source,
                "right_upper_arm", HIT_HP)).orElseThrow();
    }

    private static void close(ServerPlayer caster, List<? extends Mob> entities) {
        // 退出触发正式控制结束，再移除测试生物。
        BrewingTestPlayers.close(caster);
        entities.forEach(Mob::discard);
    }
}
