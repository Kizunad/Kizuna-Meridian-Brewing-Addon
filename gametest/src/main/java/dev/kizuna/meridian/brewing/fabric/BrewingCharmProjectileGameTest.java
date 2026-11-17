package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmCommand;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmEndReason;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmSession;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 真实药瓶与箭矢飞行命中，按出手和控制的先后验证醒的规则。 */
public final class BrewingCharmProjectileGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_brewing/charm_projectile_test");
    private static final String STRUCTURE = "meridian_brewing_test:witch_range";
    private static final int TIMEOUT = 80;
    private static final int CONTROL_DELAY = 3;
    private static final int CHECK_DELAY = 40;
    private static final int HEIGHT = 3;
    private static final int CASTER_X = 2;
    private static final int STRANGER_X = 3;
    private static final int CASTER_Z = 2;
    private static final int SUBJECT_Z = 10;
    private static final double PROJECTILE_SPEED = 0.5;
    private static final double TARGET_HEIGHT = 0.5;
    private static final double ARROW_DAMAGE = 1;
    private static final long SHORTENED_TICKS = 20;
    private static final long NOT_OBSERVED_TICK = -1;
    private static final Map<UUID, Scene> scenes = new HashMap<>();
    private static boolean observingDamage;

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void allyPotionLaunchedBeforeCharmDoesNotWake(GameTestHelper context) {
        // 药瓶还在飞时开始控制，稍后真实掉血也不结束或缩短会话。
        observe(context, Kind.POTION, Timing.BEFORE, Outcome.HELD, Shooter.CASTER);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void allyPotionLaunchedAfterCharmWakes(GameTestHelper context) {
        // 控住后才扔出的伤害药照常叫醒，结束原因必须是盟友伤害。
        observe(context, Kind.POTION, Timing.AFTER, Outcome.WOKEN, Shooter.CASTER);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void allyArrowLaunchedBeforeCharmDoesNotWake(GameTestHelper context) {
        // 箭矢和药瓶使用同一出手边界，真实命中不能空过。
        observe(context, Kind.ARROW, Timing.BEFORE, Outcome.HELD, Shooter.CASTER);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void allyArrowLaunchedAfterCharmWakes(GameTestHelper context) {
        // 控制以后射出的箭仍按原规则立即叫醒。
        observe(context, Kind.ARROW, Timing.AFTER, Outcome.WOKEN, Shooter.CASTER);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void allyArrowLaunchedBeforeCharmInSameTickDoesNotWake(GameTestHelper context) {
        // 即使世界时钟相同，先离手的箭也属于控制前的出手。
        observe(context, Kind.ARROW, Timing.SAME_TICK_BEFORE, Outcome.HELD, Shooter.CASTER);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void allyArrowLaunchedAfterCharmInSameTickWakes(GameTestHelper context) {
        // 同一 tick 里后离手的箭不能借飞行年龄被误认为控制前出手。
        observe(context, Kind.ARROW, Timing.SAME_TICK_AFTER, Outcome.WOKEN, Shooter.CASTER);
    }

    @GameTest(maxTicks = TIMEOUT, structure = STRUCTURE)
    public void strangerArrowLaunchedBeforeCharmStillShortens(GameTestHelper context) {
        // 外人预先射出的箭仍扣一秒，不能套用施药者一方的豁免。
        observe(context, Kind.ARROW, Timing.BEFORE, Outcome.SHORTENED, Shooter.STRANGER);
    }

    private static void observe(GameTestHelper context, Kind kind, Timing timing, Outcome outcome, Shooter shooter) {
        // 固定无散布弹道，所有命中均由原版实体 tick 产生，不直接调用命中或伤害入口。
        var scene = new Scene(context, kind, timing, shooter);
        switch (timing) {
            case BEFORE -> {
                scene.launch();
                context.runAfterDelay(CONTROL_DELAY, scene::charm);
            }
            case AFTER -> {
                scene.charm();
                context.runAfterDelay(CONTROL_DELAY, scene::launch);
            }
            case SAME_TICK_BEFORE -> {
                scene.launch();
                scene.charm();
            }
            case SAME_TICK_AFTER -> {
                scene.charm();
                scene.launch();
            }
        }
        context.runAfterDelay(CHECK_DELAY, () -> {
            try {
                scene.verify(outcome);
            } finally {
                scene.close();
            }
            context.succeed();
        });
    }

    public static void recordCommand(CharmCommand command) {
        // 仅测试 Mixin 在正式控制器确认后调用，捕获真实会话命令而不替换控制器。
        UUID subject = UUID.fromString(command.getSession().getSubjectUuid());
        Scene scene = scenes.get(subject);
        if (scene != null) {
            scene.commands.add(command);
        }
    }

    private static void watch(Scene scene) {
        // 全进程只安装一个只读伤害观察者，并按目标身份隔离同时运行的场景。
        if (!observingDamage) {
            VanillaDamageFabric.registerDamageObserver(event -> {
                Scene found = scenes.get(event.target().getUUID());
                if (found != null && event.source().getDirectEntity() == found.projectile) {
                    found.hits.add(new Hit(found.tick(), event));
                }
            });
            observingDamage = true;
        }
        scenes.put(scene.subject.getUUID(), scene);
    }

    private enum Kind { POTION, ARROW }
    private enum Timing { BEFORE, AFTER, SAME_TICK_BEFORE, SAME_TICK_AFTER }
    private enum Outcome { HELD, WOKEN, SHORTENED }
    private enum Shooter { CASTER, STRANGER }
    private record Hit(long tick, VanillaDamageFabric.DamageEvent event) { }

    private static final class Scene {
        private final GameTestHelper context;
        private final Kind kind;
        private final Timing timing;
        private final Villager caster;
        private final Villager subject;
        private final Villager shooter;
        private final float healthBefore;
        private final List<CharmCommand> commands = new ArrayList<>();
        private final List<Hit> hits = new ArrayList<>();
        private Projectile projectile;
        private long releasedTick;

        private Scene(GameTestHelper context, Kind kind, Timing timing, Shooter side) {
            // 双方都由真实身体宿主认领；关闭 AI 和重力只固定靶位，不替代飞行碰撞。
            this.context = context;
            this.kind = kind;
            this.timing = timing;
            caster = villager(new BlockPos(CASTER_X, HEIGHT, CASTER_Z));
            subject = villager(new BlockPos(CASTER_X, HEIGHT, SUBJECT_Z));
            if (side == Shooter.STRANGER) {
                shooter = villager(new BlockPos(STRANGER_X, HEIGHT, CASTER_Z));
            } else {
                shooter = caster;
            }
            healthBefore = subject.getHealth();
            watch(this);
        }

        private Villager villager(BlockPos position) {
            // 身体必须已绑定，否则无法收到任务要求的 Damage 正式通知。
            Villager entity = context.spawn(EntityTypes.VILLAGER, position);
            entity.setNoAi(true /* noAi */);
            entity.setNoGravity(true /* noGravity */);
            BrewBodyHost.inspect(entity).orElseThrow(); // 校验宿主已认领，失败时抛出异常。
            return entity;
        }

        private void launch() {
            // 生成后持续飞行到靶位，不能用直接调用伤害的方式伪造落地。
            releasedTick = tick();
            projectile = createProjectile();
            Vec3 aim = subject.position().add(0, subject.getBbHeight() * TARGET_HEIGHT, 0);
            Vec3 velocity = aim.subtract(projectile.position()).normalize().scale(PROJECTILE_SPEED);
            projectile.setNoGravity(true /* noGravity */);
            projectile.setDeltaMovement(velocity);
            context.assertTrue(context.getLevel().addFreshEntity(projectile), "投射物没有进入世界");
        }

        private Projectile createProjectile() {
            // 原版伤害药和普通箭均保留正式施药者归属，箭的伤害固定为不会致命的轻伤。
            var level = context.getLevel();
            if (kind == Kind.POTION) {
                ItemStack stack = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HARMING);
                return new ThrownSplashPotion(level, shooter, stack);
            }
            var arrow = new Arrow(level, shooter, new ItemStack(Items.ARROW), null);
            arrow.setBaseDamage(ARROW_DAMAGE);
            return arrow;
        }

        private void charm() {
            // 命中前调用正式魅惑药效入口；控制开始时投射物必须仍未造成伤害。
            context.assertTrue(hits.isEmpty(), "控制前投射物已经命中");
            if (projectile != null) {
                context.assertTrue(projectile.isAlive(), "控制前投射物已经消失");
            }
            var application = new BrewBodyEffects.Application(BrewKind.CHARM, BrewQuality.SUPERIOR,
                    BrewUse.SPLASH, subject.position(), caster);
            BrewBodyEffects.apply(subject, application);
            context.assertTrue(BrewCharmSessions.controlled(subject.getUUID()), "魅惑没有开始");
        }

        private void verify(Outcome outcome) {
            // 同时核对正式伤害通知与实际血量，未命中时即使会话状态符合也必须失败。
            CharmSession started = started();
            log(started);
            context.assertTrue(hits.size() == 1, "没有恰好一次真实投射物伤害通知：" + hits.size());
            Hit hit = hits.getFirst();
            context.assertTrue(hit.event().attacker() == shooter, "伤害施加者不是实际出手者");
            context.assertTrue(hit.event().input().damageAfterEffects() > 0, "命中没有造成有效伤害");
            context.assertTrue(subject.getHealth() < healthBefore, "投射物命中没有实际掉血");
            context.assertTrue(hit.tick() > started.getStartedTick() && hit.tick() > releasedTick,
                    "命中没有发生在离手并控住以后");
            long startedTick = started.getStartedTick();
            switch (timing) {
                case BEFORE -> context.assertTrue(releasedTick < startedTick, "不是先出手后控制");
                case AFTER -> context.assertTrue(releasedTick > startedTick, "不是先控制后出手");
                default -> context.assertTrue(releasedTick == startedTick, "没有覆盖同一 tick 的出手边界");
            }
            verifyOutcome(outcome, started);
        }

        private void verifyOutcome(Outcome outcome, CharmSession started) {
            // 检查真实 END 原因；豁免命中不能被误走成外人缩时，外人命中则必须仍缩一秒。
            var endings = commands.stream().filter(command -> command.getAction() == CharmCommand.Action.END).toList();
            boolean controlled = BrewCharmSessions.controlled(subject.getUUID());
            if (outcome == Outcome.WOKEN) {
                context.assertTrue(!controlled, "控制后出手的投射物没有叫醒");
                context.assertTrue(endings.size() == 1
                        && endings.getFirst().getReason() == CharmEndReason.ALLY_DAMAGE, "结束原因不是 ALLY_DAMAGE");
                return;
            }
            context.assertTrue(controlled && endings.isEmpty(), "控制前出手的投射物结束了会话");
            long fullEnd = started.getEndsTick();
            long expectedEnd;
            if (outcome == Outcome.SHORTENED) {
                expectedEnd = fullEnd - SHORTENED_TICKS;
            } else {
                expectedEnd = fullEnd;
            }
            long actualEnd = commands.getLast().getSession().getEndsTick();
            context.assertTrue(actualEnd == expectedEnd, "投射物命中后的控制期限错误：" + actualEnd);
        }

        private CharmSession started() {
            // 校验捕获了正式 START；缺少命令观察接线时直接抛出异常，不能凭状态空过。
            return commands.stream().filter(command -> command.getAction() == CharmCommand.Action.START)
                    .map(CharmCommand::getSession).findFirst().orElseThrow();
        }

        private void log(CharmSession started) {
            // 留出手、控制、落地 tick 与血量，供客户端场景复摆及负对照核对。
            long impactTick;
            double damage;
            if (!hits.isEmpty()) {
                Hit hit = hits.getFirst();
                impactTick = hit.tick();
                damage = hit.event().input().damageAfterEffects();
            } else {
                impactTick = NOT_OBSERVED_TICK;
                damage = 0;
            }
            var reasons = commands.stream().filter(command -> command.getAction() == CharmCommand.Action.END)
                    .map(CharmCommand::getReason).toList();
            LOGGER.info("CHARM_PRELAUNCH kind={} timing={} shooter={} subject={} releasedTick={} startedTick={} "
                            + "impactTick={} damage={} healthBefore={} healthAfter={} controlled={} "
                            + "endsBefore={} endsAfter={} reasons={}",
                    kind, timing, shooter.getUUID(), subject.getUUID(), releasedTick, started.getStartedTick(),
                    impactTick, damage, healthBefore, subject.getHealth(),
                    BrewCharmSessions.controlled(subject.getUUID()), started.getEndsTick(),
                    commands.getLast().getSession().getEndsTick(), reasons);
        }

        private long tick() {
            // 和魅惑协议使用同一个主世界时钟。
            return context.getLevel().getServer().overworld().getGameTime();
        }

        private void close() {
            // 先停止观察再卸载生物，清理产生的 UNLOADED 不参与本次命中的结束原因断言。
            scenes.remove(subject.getUUID());
            if (projectile != null) {
                projectile.discard();
            }
            subject.discard();
            caster.discard();
            if (shooter != caster) {
                shooter.discard();
            }
        }
    }
}
