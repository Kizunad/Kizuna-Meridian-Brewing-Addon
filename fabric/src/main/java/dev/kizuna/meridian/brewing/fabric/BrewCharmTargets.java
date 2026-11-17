package dev.kizuna.meridian.brewing.fabric;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Enemy;

/**
 * 魅惑的阵营与选敌规则，控制器复用同一份世界事实。
 *
 * <p>受控者替施药者打谁，依次是：施药者正在打的（玩家是最近亲手命中的，生物是当前目标）、最近伤过施药者的、
 * 16 格内正盯着施药者的敌对生物；都没有就跟随。受控者自己、施药者一方、被同一个施药者魅惑着的都不算数。</p>
 */
public final class BrewCharmTargets {
    public static final double RANGE = 16;
    private static final long MEMORY_TICKS = 100;
    private static final Map<UUID, Hit> outgoing = new HashMap<>();
    private static final Map<UUID, Hit> incoming = new HashMap<>();

    private BrewCharmTargets() {
        // 只保存短期伤害身份，不强引用离线玩家或实体。
    }

    public static boolean sameSide(LivingEntity caster, Entity candidate) {
        // 主人与召唤物共享阵营；生物同伴由原版同盟关系决定，玩家仍遵循计分板队伍。
        if (candidate == caster) {
            return true;
        }
        if (candidate instanceof OwnableEntity owned && owned.getOwner() == caster) {
            return true;
        }
        if (caster instanceof Mob && candidate instanceof Mob) {
            return caster.isAlliedTo(candidate);
        }
        return candidate instanceof Player && caster.getTeam() != null && caster.isAlliedTo(candidate);
    }

    static void hit(LivingEntity target, LivingEntity attacker) {
        // 来源实体已由 Damage 解出箭主或功法施放者，宠物不算主人的亲手命中。
        long tick = target.level().getServer().overworld().getGameTime();
        UUID targetId = target.getUUID();
        if (attacker == null) {
            return;
        }
        UUID attackerId = attacker.getUUID();
        if (attacker instanceof ServerPlayer) {
            outgoing.put(attackerId, new Hit(targetId, tick));
        }
        // 谁最近伤过谁对生物也记：生物施药时同样要问「最近谁伤过施药者」。五秒过期，每 tick 清。
        incoming.put(targetId, new Hit(attackerId, tick));
    }

    public static LivingEntity opponent(LivingEntity caster, LivingEntity subject) {
        // 严格按施药者正在打的、最近伤过施药者的、正盯着施药者的敌对生物、跟随的顺序挑选。
        UUID casterId = caster.getUUID();
        LivingEntity engaged = remembered(caster, subject, outgoing.get(casterId));
        if (caster instanceof Mob mob && valid(caster, subject, mob.getTarget())) {
            engaged = mob.getTarget();
        }
        if (engaged != null) {
            return engaged;
        }
        LivingEntity attacker = remembered(caster, subject, incoming.get(casterId));
        if (attacker != null) {
            return attacker;
        }
        return caster.level().getEntitiesOfClass(Mob.class, caster.getBoundingBox().inflate(RANGE),
                        mob -> mob.getTarget() == caster && caster.distanceToSqr(mob) <= RANGE * RANGE
                                && valid(caster, subject, mob) && hostile(mob))
                .stream().min(Comparator.comparingDouble(caster::distanceToSqr)).orElse(null);
    }

    private static boolean hostile(Mob mob) {
        // 第三优先级只收原版怪物与已开脉修炼者，普通动物的临时目标不算敌军。
        if (mob instanceof Enemy) {
            return true;
        }
        var binding = BrewBodyHost.inspect(mob);
        if (binding.isEmpty()) {
            return false;
        }
        var body = binding.get().body();
        return body.getRealm() == Realm.TRUE_QI || body.getMeridianStatesList().stream()
                .anyMatch(state -> state.getOpening().getStage() == MeridianOpeningStage.OPENED);
    }

    private static LivingEntity remembered(LivingEntity caster, LivingEntity subject, Hit hit) {
        // 超过五秒、死亡、换维度或变成盟友的记录都不能继续作为攻击目标。
        var level = (ServerLevel) caster.level();
        if (hit == null || level.getServer().overworld().getGameTime() - hit.tick() > MEMORY_TICKS) {
            return null;
        }
        Entity entity = level.getEntity(hit.target());
        if (entity instanceof LivingEntity living && valid(caster, subject, living)) {
            return living;
        }
        return null;
    }

    private static boolean valid(LivingEntity caster, LivingEntity subject, LivingEntity target) {
        // 本次受控者不能自伤，也不能代打旁观者、死体、施药者一方，或者被同一个施药者魅惑着的另一只。
        return target != null && target != subject && target.isAlive() && !target.isRemoved()
                && !target.isSpectator() && target.level() == caster.level() && !sameSide(caster, target)
                && !BrewCharmSessions.charmedBy(caster, target);
    }

    static void forget(UUID player) {
        // 下线后丢弃进攻与防守记忆，重登不能沿用上一连接的选敌。
        outgoing.remove(player);
        incoming.remove(player);
    }

    static void prune(long tick) {
        // 长期开服不保留过期玩家身份。
        outgoing.values().removeIf(hit -> tick - hit.tick() > MEMORY_TICKS);
        incoming.values().removeIf(hit -> tick - hit.tick() > MEMORY_TICKS);
    }

    static void clear() {
        // 同进程开新世界时不继承旧服的伤害记忆。
        outgoing.clear();
        incoming.clear();
    }

    private record Hit(UUID target, long tick) { }
}
