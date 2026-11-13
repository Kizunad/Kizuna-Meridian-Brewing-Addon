package dev.kizuna.meridian.brewing.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmCommand;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmEndReason;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmReply;
import dev.kizuna.meridian.brewing.protocol.pb.CharmMessages.CharmSession;
import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.CharmController;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;

/**
 * 唯一负责醒的规则与时钟；控制器仅执行当前会话，不另开药效计时器。
 *
 * <p>选敌每 {@link #SELECT_TICKS} 个 tick 一次；有人挨打、有活物死了，下一 tick 就重选。对手换了才向控制器发
 * UPDATE，挨打缩时照旧当场发。</p>
 */
public final class BrewCharmSessions {
    private static final long TICKS_PER_SECOND = 20;
    private static final long SELECT_TICKS = 5;
    private static final long IMMUNITY_MILLIS = 10_000;
    /** 玩家被魅惑接管时动作栏那句话的语言键。 */
    public static final String TAKEN_MESSAGE = "message.meridian_brewing.charm.taken";
    private static final Identifier STOP_PHASE = Identifier.parse("meridian_brewing:end_control");
    private static final Map<UUID, Active> active = new HashMap<>();
    private static CharmController controller;
    private static boolean opponentsStale;

    private BrewCharmSessions() {
        // 活动会话不持久化，下线或卸载即结束。
    }

    static void initialize() {
        // 服务启动后发现唯一必装控制器；死亡、卸载和断线都走同一个结束入口。
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var controllers = FabricLoader.getInstance().getEntrypoints(CharmController.ENTRYPOINT,
                    CharmController.class);
            if (controllers.size() != 1) {
                throw new IllegalStateException("魅惑必须装配唯一控制器");
            }
            controller = controllers.getFirst();
        });
        VanillaDamageFabric.registerDamageObserver(BrewCharmSessions::damaged);
        registerLifecycle();
    }

    private static void registerLifecycle() {
        // 控制期限、离线与实体消失分别从正式世界事件进入同一个结束路径。
        ServerLivingEntityEvents.AFTER_DAMAGE.register((target, source, base, taken, blocked) -> {
            // 无身体目标不会产生 Damage 身体回执，补记原版实际命中，不能重复记录已绑定目标。
            if (taken > 0 && VanillaDamageFabric.inspectBody(target).isEmpty()
                    && source.getEntity() instanceof LivingEntity attacker) {
                BrewCharmTargets.hit(target, attacker);
                opponentsStale = true;
            }
        });
        ServerTickEvents.START_SERVER_TICK.register(BrewCharmSessions::tick);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            // 死的可能正是谁的对手，下一 tick 重选。
            opponentsStale = true;
            leave(entity, CharmEndReason.SUBJECT_DIED);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof LivingEntity living) {
                leave(living, CharmEndReason.UNLOADED);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            leave(handler.player, CharmEndReason.DISCONNECTED);
            BrewCharmTargets.forget(handler.player.getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(STOP_PHASE, Event.DEFAULT_PHASE);
        ServerLifecycleEvents.SERVER_STOPPING.register(STOP_PHASE, server -> {
            // 控制先结束，功法服务和身体宿主在后续阶段才停止，保证清理仍能得到正式回执。
            List.copyOf(active.values()).forEach(session -> end(session.subject(), CharmEndReason.SERVER_STOPPED));
            BrewCharmTargets.clear();
        });
    }

    static void start(LivingEntity subject, LivingEntity caster, double seconds, BrewQuality quality) {
        // 身份与初始期限固定；只有醒的规则可以缩短期限，重复中药不叠加控制。
        UUID subjectId = subject.getUUID();
        var level = subject.level();
        if (active.containsKey(subjectId) || !caster.isAlive() || caster.level() != level
                || caster.distanceToSqr(subject) > BrewCharmTargets.RANGE * BrewCharmTargets.RANGE) {
            return;
        }
        long tick = level.getServer().overworld().getGameTime();
        var session = CharmSession.newBuilder()
                .setSessionId(UUID.randomUUID().toString())
                .setSubjectUuid(subject.getStringUUID())
                .setCasterUuid(caster.getStringUUID())
                .setStartedTick(tick)
                .setEndsTick(tick + Math.round(seconds * TICKS_PER_SECOND))
                .build();
        var updated = withOpponent(session, subject, caster);
        send(CharmCommand.Action.START, updated, CharmEndReason.CHARM_END_UNSPECIFIED);
        active.put(subjectId, new Active(subject, caster, updated, tick));
        BrewBodyEffects.addStatus(subject, BrewingEffects.CHARM_CONTROL, seconds, quality);
        if (subject instanceof ServerPlayer player) {
            // 玩家的身体接下来不听自己使唤：动作栏写明是谁下的药、多久、怎么醒。
            player.sendOverlayMessage(Component.translatable(TAKEN_MESSAGE, caster.getDisplayName(),
                    String.valueOf(Math.round(seconds)), String.valueOf(Math.round(BrewCharmTargets.RANGE))));
        }
    }

    public static boolean controlled(UUID subject) {
        // 供可信世界门禁读取，不推进或创建会话。
        return active.containsKey(subject);
    }

    private static void tick(MinecraftServer server) {
        // 输入与 AI 运行前先终止无效会话；到了选敌的点（或刚有人挨打、死了）再重选，对手换了才发给控制器。
        long tick = server.overworld().getGameTime();
        BrewCharmTargets.prune(tick);
        boolean hitOrDeath = opponentsStale;
        opponentsStale = false;
        for (Active value : List.copyOf(active.values())) {
            LivingEntity subject = value.subject();
            CharmEndReason reason = ended(value, tick);
            if (reason != CharmEndReason.CHARM_END_UNSPECIFIED) {
                end(subject, reason);
            } else if (hitOrDeath || tick - value.selectedTick() >= SELECT_TICKS) {
                reselect(value, tick);
            }
        }
    }

    private static void reselect(Active value, long tick) {
        // 四步选敌照旧；选出来和当前一样就只记下选过的时刻，不发 UPDATE。
        LivingEntity subject = value.subject();
        LivingEntity caster = value.caster();
        CharmSession current = value.session();
        CharmSession next = withOpponent(current, subject, caster);
        if (!next.getOpponentUuid().equals(current.getOpponentUuid())) {
            send(CharmCommand.Action.UPDATE, next, CharmEndReason.CHARM_END_UNSPECIFIED);
        }
        active.put(subject.getUUID(), new Active(subject, caster, next, tick));
    }

    private static CharmEndReason ended(Active value, long tick) {
        // 以当前实体状态判断，不能把死亡施药者的 UUID 当成仍有效的控制来源。
        LivingEntity subject = value.subject();
        LivingEntity caster = value.caster();
        if (!subject.isAlive()) {
            return CharmEndReason.SUBJECT_DIED;
        }
        if (!caster.isAlive()) {
            return CharmEndReason.CASTER_DIED;
        }
        if (caster.level() != subject.level()
                || caster.distanceToSqr(subject) > BrewCharmTargets.RANGE * BrewCharmTargets.RANGE) {
            return CharmEndReason.OUT_OF_RANGE;
        }
        if (tick >= value.session().getEndsTick()) {
            return CharmEndReason.EXPIRED;
        }
        return CharmEndReason.CHARM_END_UNSPECIFIED;
    }

    private static CharmSession withOpponent(CharmSession session, LivingEntity subject, LivingEntity caster) {
        // 阵营与四步优先级只在 Brewing 求值，控制器不能自行换成仇恨目标。
        LivingEntity opponent = BrewCharmTargets.opponent(caster, subject);
        String opponentId = "";
        if (opponent != null) {
            opponentId = opponent.getStringUUID();
        }
        return session.toBuilder().setOpponentUuid(opponentId).build();
    }

    private static void damaged(VanillaDamageFabric.DamageEvent event) {
        // 只有别人造成的有效伤害才缩时并记忆；环境伤害不改变受惑期限。
        if (event.input().damageAfterEffects() <= 0 || event.attacker() == null) {
            return;
        }
        LivingEntity target = event.target();
        LivingEntity attacker = event.attacker();
        UUID targetId = target.getUUID();
        BrewCharmTargets.hit(target, attacker);
        opponentsStale = true;
        Active value = active.get(targetId);
        if (value == null) {
            return;
        }
        LivingEntity caster = value.caster();
        if (BrewCharmTargets.sameSide(caster, attacker)) {
            end(target, CharmEndReason.ALLY_DAMAGE);
            return;
        }
        CharmSession previous = value.session();
        long shortened = previous.getEndsTick() - TICKS_PER_SECOND;
        var session = previous.toBuilder().setEndsTick(shortened).build();
        active.put(targetId, new Active(value.subject(), caster, session, value.selectedTick()));
        long tick = target.level().getServer().overworld().getGameTime();
        if (shortened <= tick) {
            end(target, CharmEndReason.EXPIRED);
        } else {
            send(CharmCommand.Action.UPDATE, session, CharmEndReason.CHARM_END_UNSPECIFIED);
            var effect = target.getEffect(BrewingEffects.CHARM_CONTROL);
            if (effect != null) {
                BrewQuality quality = BrewQuality.fromComponentValue(effect.getAmplifier());
                BrewBodyEffects.addStatus(target, BrewingEffects.CHARM_CONTROL,
                        (double) (shortened - tick) / TICKS_PER_SECOND, quality);
            }
        }
    }

    static void end(LivingEntity subject, CharmEndReason reason) {
        // 控制器确认后再撤活动身份；交接失败保留会话供重试，效果移除不会重入结束。
        UUID subjectId = subject.getUUID();
        Active value = active.get(subjectId);
        if (value == null) {
            return;
        }
        send(CharmCommand.Action.END, value.session(), reason);
        active.remove(subjectId);
        immunity(subject);
        subject.removeEffect(BrewingEffects.CHARM_CONTROL);
    }

    static void immunity(LivingEntity subject) {
        // 免疫沿用唯一身体进度，以实际醒来的身体时刻计算；身体被宿主暂停时不记免疫。
        var found = BrewBodyHost.observe(subject).found();
        if (found.isEmpty()) {
            return;
        }
        var binding = found.get();
        var body = binding.body();
        if (body.getVitality().getDead()) {
            return;
        }
        long until = body.getSettledAt().getElapsedMillis() + IMMUNITY_MILLIS;
        var state = BrewBodyProgress.read(body).toBuilder().setCharmImmunityUntilMillis(until).build();
        var activity = BodyActivity.newBuilder();
        BrewBodyProgress.write(activity, state);
        BrewBodyHost.activity(binding, activity.build());
    }

    private static void leave(LivingEntity entity, CharmEndReason reason) {
        // 主体或施药者任意一方离开都结束，退出时不保存可恢复控制。
        end(entity, reason);
        CharmEndReason casterReason = reason;
        if (reason == CharmEndReason.SUBJECT_DIED) {
            casterReason = CharmEndReason.CASTER_DIED;
        }
        for (Active value : List.copyOf(active.values())) {
            if (value.caster() == entity) {
                end(value.subject(), casterReason);
            }
        }
    }

    private static void send(CharmCommand.Action action, CharmSession session, CharmEndReason reason) {
        // 二进制响应必须确认同一会话；缺接线或拒绝都不能显示虚假的成功控制。
        var command = CharmCommand.newBuilder()
                .setAction(action)
                .setSession(session)
                .setReason(reason)
                .build();
        try {
            var reply = CharmReply.parseFrom(controller.exchange(CharmController.CALLER, command.toByteArray()));
            if (!reply.getSessionId().equals(session.getSessionId()) || !reply.getAccepted()) {
                throw new IllegalStateException("魅惑控制器拒绝会话：" + reply.getRejection());
            }
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalStateException("魅惑控制器响应无效", invalid);
        }
    }

    private record Active(LivingEntity subject, LivingEntity caster, CharmSession session, long selectedTick) { }
}
