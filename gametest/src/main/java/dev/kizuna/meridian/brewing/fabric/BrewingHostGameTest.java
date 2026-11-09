package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BitterHealing;
import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import dev.kizuna.meridian.human.fabric.HumanStaggerLock;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.meridian.vanilla.VanillaDamageBridge;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** 真实宿主的僵直类别、无敌保护和类人泼药回归。 */
public final class BrewingHostGameTest {
    private static final int TIMEOUT = 200;
    private static final int EFFECT_TICKS = 25;
    private static final int HIT_DELAY_TICKS = 5;
    private static final int CHARM_END_TICKS = 17;
    private static final int FALL_STEPS = 15;
    private static final int AIR_HEIGHT = 4;
    private static final int SPAWN_HEIGHT = 3;
    private static final double HALF_SPEED = 0.5;
    private static final double EPSILON = 1e-8;
    private static final double WOUND_HP = 5;
    private static final double LETHAL_HP = 10_000;
    private static final float HEAVY_HIT = 4;
    private static final long MILLIS_PER_TICK = 50;
    private static final String ARM = "right_upper_arm";

    @GameTest(maxTicks = TIMEOUT)
    public void depletionRespectsCreativeSpectatorAndSurvival(GameTestHelper context) {
        // 同一个真实玩家切换模式，泼药只在生存时扣身体血量。
        ServerPlayer player = BrewingTestPlayers.create(context);
        double before = totalHp(BrewingTestPlayers.body(player));
        player.setGameMode(GameType.CREATIVE);
        splash(player, BrewKind.DEPLETION);
        context.assertTrue(totalHp(BrewingTestPlayers.body(player)) == before, "创造模式被枯竭扣血");
        player.setGameMode(GameType.SPECTATOR);
        splash(player, BrewKind.DEPLETION);
        context.assertTrue(totalHp(BrewingTestPlayers.body(player)) == before, "旁观模式被枯竭扣血");
        player.setGameMode(GameType.SURVIVAL);
        splash(player, BrewKind.DEPLETION);
        context.assertTrue(totalHp(BrewingTestPlayers.body(player)) < before, "生存模式未承受枯竭伤害");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void directDamageHonorsInvulnerabilityTagsAndRejectsDeadTargets(GameTestHelper context) {
        // 普通来源尊重实体无敌，原版越过无敌的来源仍能伤害创造玩家，死体不再提交事务。
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.setInvulnerable(true /* invulnerable */);
        context.assertTrue(damage(player, player.damageSources().generic()).isEmpty(), "普通来源绕过实体无敌");
        player.setInvulnerable(false /* invulnerable */);
        player.setGameMode(GameType.CREATIVE);
        double before = totalHp(BrewingTestPlayers.body(player));
        context.assertTrue(damage(player, player.damageSources().fellOutOfWorld()).isPresent(), "越过无敌标签被拒绝");
        context.assertTrue(totalHp(BrewingTestPlayers.body(player)) < before, "越过无敌来源没有扣身体血量");
        var lethal = new VanillaDamageFabric.DirectDamage(player, player.damageSources().fellOutOfWorld(),
                "head", LETHAL_HP);
        VanillaDamageFabric.damageDirect(lethal).orElseThrow();
        context.assertTrue(player.isDeadOrDying(), "测试目标没有真正死亡");
        long revision = BrewingTestPlayers.body(player).getRevision();
        context.assertTrue(damage(player, player.damageSources().fellOutOfWorld()).isEmpty(), "死体仍接受直接伤害");
        context.assertTrue(BrewingTestPlayers.body(player).getRevision() == revision, "死体伤害改变了身体");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void heavyHitSlowsButAllowsAttackAndItemUse(GameTestHelper context) {
        // 重击走原版伤害管线；攻击回调与原版攻击、盾使用在受击僵直中仍可执行。
        ServerPlayer player = BrewingTestPlayers.create(context);
        var sheep = context.spawn(EntityTypes.SHEEP, new BlockPos(1, SPAWN_HEIGHT, 1));
        sheep.setNoAi(true /* noAi */);
        double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
        player.hurtServer(context.getLevel(), player.damageSources().mobAttack(sheep), HEAVY_HIT);
        context.assertTrue(TechniqueFabric.service().staggerRemainingMillis(player.getUUID()) > 0, "重击没有僵直");
        context.assertTrue(Math.abs(player.getAttributeValue(Attributes.MOVEMENT_SPEED) - speed * HALF_SPEED)
                < EPSILON, "受击没有减半移动");
        context.assertTrue(AttackEntityCallback.EVENT.invoker().interact(player, player.level(),
                InteractionHand.MAIN_HAND, sheep, null) != InteractionResult.FAIL, "受击僵直拦住攻击");
        float hp = sheep.getHealth();
        player.attack(sheep);
        context.assertTrue(sheep.getHealth() < hp, "受击僵直中无法实际攻击");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHIELD));
        player.startUsingItem(InteractionHand.MAIN_HAND);
        context.assertTrue(player.isUsingItem(), "受击僵直拦住盾使用");
        sheep.discard();
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void charmDeadlineSurvivesHeavyHit(GameTestHelper context) {
        // 下等魅惑使普通玩家定身；期间真实重击只写自己的受击期限。
        ServerPlayer player = BrewingTestPlayers.create(context);
        charm(player);
        long remaining = charmRemaining(player);
        context.assertTrue(remaining > 0 && HumanStaggerLock.isLocked(player.getUUID()), "魅惑未产生独立定身");
        context.assertTrue(player.getAttributeValue(Attributes.JUMP_STRENGTH) == 0, "魅惑没有锁住跳跃属性");
        player.jumpFromGround();
        context.assertTrue(player.getDeltaMovement().y == 0, "魅惑期间仍可起跳");
        context.runAfterDelay(HIT_DELAY_TICKS, () -> {
            var attacker = context.spawn(EntityTypes.SHEEP, new BlockPos(1, SPAWN_HEIGHT, 1));
            player.hurtServer(context.getLevel(), player.damageSources().mobAttack(attacker), HEAVY_HIT);
            attacker.discard();
            context.assertTrue(TechniqueFabric.service().staggerRemainingMillis(player.getUUID(),
                    TechniqueService.StaggerKind.HIT) > 0, "定身期间重击没有写受击期限");
            context.assertTrue(charmRemaining(player) == remaining - HIT_DELAY_TICKS * MILLIS_PER_TICK,
                    "受击改变了魅惑定身期限");
            context.assertTrue(player.getAttributeValue(Attributes.MOVEMENT_SPEED) == 0, "重击把定身降成减速");
        });
        context.runAfterDelay(CHARM_END_TICKS, () -> {
            context.assertTrue(charmRemaining(player) == 0 && !HumanStaggerLock.isLocked(player.getUUID()),
                    "魅惑未按原期限结束");
            context.assertTrue(player.getAttributeValue(Attributes.JUMP_STRENGTH) > 0, "魅惑结束未恢复跳跃");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void airborneCharmFallsToGround(GameTestHelper context) {
        // 内存客户端以原版 travel 推进重力，定身只限制主动运动，不锁住竖直位移。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BlockPos floor = context.absolutePos(new BlockPos(1, 1, 1));
        context.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        player.setPos(floor.getX(), floor.getY() + AIR_HEIGHT, floor.getZ());
        player.setNoGravity(false /* noGravity */);
        charm(player);
        fall(context, player, floor, FALL_STEPS);
    }

    private static void fall(GameTestHelper context, ServerPlayer player, BlockPos floor, int steps) {
        // 每个 tick 只推进一次原版物理，仍在魅惑期限内落地才算通过。
        player.travel(Vec3.ZERO);
        if (player.onGround()) {
            context.assertTrue(player.getY() <= floor.getY() + 1, "玩家没有落到指定地面");
            context.assertTrue(HumanStaggerLock.isLocked(player.getUUID()), "落地前魅惑已经结束");
            BrewingTestPlayers.close(player);
            context.succeed();
            return;
        }
        context.assertTrue(steps > 0, "魅惑使空中玩家悬停");
        context.runAfterDelay(1, () -> fall(context, player, floor, steps - 1));
    }

    @GameTest(maxTicks = TIMEOUT)
    public void humanoidDepletion(GameTestHelper context) {
        // 枯竭一次伤害与持续能力都落到 Threatening 身体。
        humanoidSplash(context, BrewKind.DEPLETION);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void humanoidArmorRot(GameTestHelper context) {
        // 蚀甲进度由类人活动宿主保存并参与状态周期。
        humanoidSplash(context, BrewKind.ARMOR_ROT);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void humanoidQiGather(GameTestHelper context) {
        // 聚气为实际两肺登记器官曲线。
        humanoidSplash(context, BrewKind.QI_GATHER);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void humanoidBitterYellow(GameTestHelper context) {
        // 苦黄先命中带伤四肢，再经健康和活动宿主结算。
        humanoidSplash(context, BrewKind.BITTER_YELLOW);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void humanoidInsectLure(GameTestHelper context) {
        // 招虫维持原本的世界状态，不虚构身体能力修正。
        humanoidSplash(context, BrewKind.INSECT_LURE);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void humanoidCharm(GameTestHelper context) {
        // 魅惑判定及免疫进度可写类人身体，控制器不在此测试范围。
        humanoidSplash(context, BrewKind.CHARM);
    }

    private static void humanoidSplash(GameTestHelper context, BrewKind kind) {
        // 完整装配认领真实村民，六种药各用独立实体，避免药效互相干扰。
        Villager target = context.spawn(EntityTypes.VILLAGER, new BlockPos(1, SPAWN_HEIGHT, 1));
        target.setNoAi(true /* noAi */);
        target.setNoGravity(true /* noGravity */);
        BodySnapshot body = BrewBodyHost.inspect(target).orElseThrow().body();
        context.assertTrue(body.getId().startsWith("meridian_humanoid:entity/"), "村民未由 Threatening 认领");
        if (kind == BrewKind.BITTER_YELLOW) {
            woundLimbs(target, body);
        }
        double before = totalHp(BrewBodyHost.inspect(target).orElseThrow().body());
        splash(target, kind);
        assertHumanoidEffect(context, target, kind);
        context.runAfterDelay(EFFECT_TICKS, () -> {
            BodySnapshot after = BrewBodyHost.inspect(target).orElseThrow().body();
            if (kind == BrewKind.DEPLETION) {
                context.assertTrue(totalHp(after) < before, "类人枯竭没有部位伤害");
            } else if (kind == BrewKind.BITTER_YELLOW) {
                context.assertTrue(totalHp(after) > before, "类人苦黄没有治疗受伤四肢");
            }
            target.removeAllEffects();
            context.assertTrue(BrewBodyHost.inspect(target).orElseThrow().body().getModifiersCount() == 0,
                    "类人清药遗漏了能力修正");
            target.discard();
            context.succeed();
        });
    }

    private static void assertHumanoidEffect(GameTestHelper context, Villager target, BrewKind kind) {
        // 按药的真实职责检查已提交的进度、能力或世界图标。
        BodySnapshot body = BrewBodyHost.inspect(target).orElseThrow().body();
        var progress = BrewBodyProgress.read(body);
        if (kind == BrewKind.INSECT_LURE) {
            context.assertTrue(target.hasEffect(BrewingEffects.INSECT_LURE), "类人未获得招虫状态");
        } else if (kind == BrewKind.CHARM) {
            context.assertTrue(progress.getCharmImmunityUntilMillis() > body.getSettledAt().getElapsedMillis(),
                    "类人魅惑未写入免疫进度");
        } else {
            context.assertTrue(progress.getDosesList().stream().anyMatch(dose -> dose.getKind().equals(kind.name())),
                    "类人药效未写入身体剂量：" + kind);
            if (kind != BrewKind.ARMOR_ROT) {
                context.assertTrue(body.getModifiersCount() > 0, "类人药效未写入能力修正：" + kind);
            }
        }
    }

    private static void woundLimbs(LivingEntity target, BodySnapshot body) {
        // 所有可被苦黄抽中的肢体先留有真实伤势，避免随机选到满血部位。
        for (var part : body.getPartStatesList()) {
            if (BitterHealing.isLimb(part.getId())) {
                var damage = new VanillaDamageFabric.DirectDamage(target, target.damageSources().generic(),
                        part.getId(), WOUND_HP);
                VanillaDamageFabric.damageDirect(damage).orElseThrow();
            }
        }
    }

    private static void splash(LivingEntity target, BrewKind kind) {
        // 从正式范围命中入口进入，不直接调用活动宿主代替药水逻辑。
        ItemStack stack;
        if (kind == BrewKind.QI_GATHER) {
            // 聚气无普通喷溅配方；命令构造的同种药水组件也必须安全经过范围命中入口。
            stack = PotionContents.createItemStack(Items.SPLASH_POTION, BrewingPotions.potion(kind));
            stack.set(BrewingComponents.QUALITY, BrewQuality.SUPERIOR.componentValue());
        } else {
            stack = BrewItemStacks.create(kind, BrewQuality.SUPERIOR, BrewUse.SPLASH);
        }
        BrewingRuntime.splash((ServerLevel) target.level(), stack, target.position(), null);
    }

    private static void charm(ServerPlayer player) {
        // 普通人体下等魅惑落在僵直档，经真实药效判定写入服务。
        var application = new BrewBodyEffects.Application(BrewKind.CHARM, BrewQuality.POOR,
                BrewUse.SPLASH, player.position(), null);
        BrewBodyEffects.apply(player, application);
    }

    private static long charmRemaining(ServerPlayer player) {
        // 单独读魅惑期限，避免把受击剩余时间误当定身时间。
        return TechniqueFabric.service().staggerRemainingMillis(player.getUUID(), TechniqueService.StaggerKind.CHARM);
    }

    private static Optional<VanillaDamageBridge.CommitResult> damage(ServerPlayer player, DamageSource source) {
        // 指定肢体剂量使无敌断言不受随机命中影响。
        return VanillaDamageFabric.damageDirect(new VanillaDamageFabric.DirectDamage(player, source, ARM, WOUND_HP));
    }

    private static double totalHp(BodySnapshot body) {
        // 只汇总权威部位账户，原版显示血量不能代替身体断言。
        return body.getPartStatesList().stream().mapToDouble(part -> part.getHp()).sum();
    }
}
