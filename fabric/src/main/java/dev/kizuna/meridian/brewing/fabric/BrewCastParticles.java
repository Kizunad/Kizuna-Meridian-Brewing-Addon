package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** 行药诀用药时冒这种药本色的原版药水粒子；玩家和女巫施放走同一处，旁边的人都看得见。 */
final class BrewCastParticles {
    private static final int SINGLE = 1;
    private static final double NO_SPREAD = 0;
    private static final double NO_SPEED = 0;
    private static final float FAINT_ALPHA = 0.35F;
    private static final double FULL_TURN = Math.PI * 2;
    private static final double HALVES = 2;
    private static final int RING_POINTS = 16;
    private static final double RING_RADIUS = 0.6;
    private static final double WAIST_HEIGHT = 0.5;
    private static final double CHEST_HEIGHT = 0.7;
    private static final int RELEASE_COUNT = 10;
    private static final double RELEASE_SPREAD = 0.1;
    private static final double HAND_REACH = 0.5;
    private static final double HAND_DROP = 0.4;
    private static final int FAN_RAYS = 7;
    private static final int FAN_STEPS = 4;
    private static final int FAN_COUNT = 2;
    private static final double FAN_SPREAD = 0.15;
    private static final int MIST_STEPS = 8;
    private static final double MIST_LENGTH = 3;
    private static final double MIST_SPREAD_PER_METER = 0.12;
    private static final int MIST_COUNT = 3;
    private static final double MOUTH_REACH = 0.3;
    private static final double MOUTH_DROP = 0.15;

    private BrewCastParticles() {
        // 只发一次性的粒子包，不留状态。
    }

    static void ring(LivingEntity actor, BrewKind kind) {
        // 服药、敷药起效时腰间淡淡一圈，透明度压低，不挡视线。
        int faint = ARGB.color(FAINT_ALPHA, kind.colorRgb());
        var particle = ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, faint);
        ServerLevel level = level(actor);
        double y = actor.getY() + actor.getBbHeight() * WAIST_HEIGHT;
        for (int point = 0; point < RING_POINTS; point++) {
            double angle = FULL_TURN * point / RING_POINTS;
            double x = actor.getX() + Math.cos(angle) * RING_RADIUS;
            double z = actor.getZ() + Math.sin(angle) * RING_RADIUS;
            level.sendParticles(particle, x, y, z, SINGLE, NO_SPREAD, NO_SPREAD, NO_SPREAD, NO_SPEED);
        }
    }

    static void release(LivingEntity actor, BrewKind kind) {
        // 掷引出手那一下在手边溅一小团；药瓶落地碎开另有原版喷溅药水的粒子。
        Vec3 hand = actor.getEyePosition().add(actor.getLookAngle().scale(HAND_REACH)).subtract(0, HAND_DROP, 0);
        level(actor).sendParticles(opaque(kind), hand.x, hand.y, hand.z, RELEASE_COUNT,
                RELEASE_SPREAD, RELEASE_SPREAD, RELEASE_SPREAD, NO_SPEED);
    }

    static void fan(LivingEntity actor, BrewKind kind, double range, double fanAngleDegrees) {
        // 泼引照目录的射程和扇角，在胸口高度铺开一片扇面，泼到哪里粒子就到哪里。
        var particle = opaque(kind);
        ServerLevel level = level(actor);
        Vec3 forward = actor.getLookAngle().multiply(1, 0, 1).normalize();
        double fanAngle = Math.toRadians(fanAngleDegrees);
        double y = actor.getY() + actor.getBbHeight() * CHEST_HEIGHT;
        for (int ray = 0; ray < FAN_RAYS; ray++) {
            double offset = fanAngle * ray / (FAN_RAYS - 1) - fanAngle / HALVES;
            Vec3 direction = forward.yRot((float) offset);
            for (int step = 1; step <= FAN_STEPS; step++) {
                Vec3 point = direction.scale(range * step / FAN_STEPS);
                level.sendParticles(particle, actor.getX() + point.x, y, actor.getZ() + point.z, FAN_COUNT,
                        FAN_SPREAD, FAN_SPREAD, FAN_SPREAD, NO_SPEED);
            }
        }
    }

    static void mist(LivingEntity actor, BrewKind kind) {
        // 噀催从嘴前顺着视线喷出一道药雾，越远散得越开；催发本身隔墙也算，粒子只画嘴前这一段。
        var particle = opaque(kind);
        ServerLevel level = level(actor);
        Vec3 look = actor.getLookAngle();
        Vec3 mouth = actor.getEyePosition().add(look.scale(MOUTH_REACH)).subtract(0, MOUTH_DROP, 0);
        for (int step = 1; step <= MIST_STEPS; step++) {
            double distance = MIST_LENGTH * step / MIST_STEPS;
            Vec3 point = mouth.add(look.scale(distance));
            double spread = distance * MIST_SPREAD_PER_METER;
            level.sendParticles(particle, point.x, point.y, point.z, MIST_COUNT, spread, spread, spread, NO_SPEED);
        }
    }

    private static ColorParticleOption opaque(BrewKind kind) {
        // 泼、掷、噀用这种药的本色，不透明。
        return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, ARGB.opaque(kind.colorRgb()));
    }

    private static ServerLevel level(LivingEntity actor) {
        // 用药处理器只在服务端运行，施放者所在世界一定是服务端世界。
        return (ServerLevel) actor.level();
    }
}
