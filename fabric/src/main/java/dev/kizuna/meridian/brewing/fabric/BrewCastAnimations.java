package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewPartGroup;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.playeranimation.animation.PlayerAnimationAction;
import dev.kizuna.playeranimation.mobs.MobAnimationServer;
import dev.kizuna.playeranimation.server.ServerPlayerAnimations;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** 目录里写不下的两段动作：敷药按选中的部位组挑一段，熬药时副手每下一次料叠一段左臂。 */
final class BrewCastAnimations {
    private static final long MILLIS_PER_TICK = 50;
    private static final Map<BrewPartGroup, PlayerAnimationAction> ANOINT_CLIPS = Map.of(
            BrewPartGroup.ARMS, PlayerAnimationAction.WITCH_ANOINT_ARMS,
            BrewPartGroup.LEGS, PlayerAnimationAction.WITCH_ANOINT_LEGS);
    private static final Map<CastKey, String> playing = new HashMap<>();

    private BrewCastAnimations() {
        // 只记这一次施放自己起播的那段，停播时不碰别处播的动作。
    }

    static String anointClip(BrewPartGroup group) {
        // 敷药只有两臂、两腿两组；别的组没有动作，说明选组规则和剪辑对不上。
        PlayerAnimationAction action = ANOINT_CLIPS.get(group);
        if (action == null) {
            throw new IllegalArgumentException("敷药没有这一组的动作：" + group);
        }
        return action.id();
    }

    static void startAnoint(LivingEntity actor, BrewPartGroup group, EffectIntent intent) {
        // 起播 tick 按效果的权威时刻回推，和 Human、Threatening 播目录表现同一算法，迟到几 tick 也不错位。
        String clip = anointClip(group);
        long startTick = startTick(actor, intent);
        if (actor instanceof ServerPlayer player) {
            ServerPlayerAnimations.playFrom(player, clip, startTick);
        } else {
            MobAnimationServer.playFrom(actor, clip, startTick);
        }
        playing.put(new CastKey(actor.getUUID(), intent.getCastId()), clip);
    }

    private static long startTick(LivingEntity actor, EffectIntent intent) {
        // 权威时钟是主世界 tick 折成的毫秒；施放者所在维度的 tick 用来对齐客户端。
        long nowMillis = actor.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        long latenessTicks = Math.max(0, nowMillis - intent.getScheduledMillis()) / MILLIS_PER_TICK;
        return Math.max(0, actor.level().getGameTime() - latenessTicks);
    }

    static void end(LivingEntity actor, UUID actorId, String castId) {
        // 收势结束、打断、下线都走施放结束；施放者已不在世界里时只丢掉记录，PA 自己会清它的状态。
        String clip = playing.remove(new CastKey(actorId, castId));
        if (clip == null || actor == null) {
            return;
        }
        if (actor instanceof ServerPlayer player) {
            ServerPlayerAnimations.stop(player, clip);
        } else {
            MobAnimationServer.stop(actor, clip);
        }
    }

    static void feedOffHand(ServerPlayer player) {
        // 熬药中副手下料、添水、加炭、拨灰成功一次，叠一段 0.6 秒的左臂动作，熬药那段照播。
        ServerPlayerAnimations.play(player, PlayerAnimationAction.WITCH_BREW_FEED_OFF);
    }

    static void clear() {
        // 停服时丢掉未结束施放的记录。
        playing.clear();
    }

    private record CastKey(UUID actor, String cast) { }
}
