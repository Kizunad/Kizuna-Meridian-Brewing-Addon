package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BitterHealing;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** 由实体碰撞箱与朝向估计部位位置，按距离加权无放回抽取。 */
final class BrewPartSelection {
    private static final double DISTANCE_OFFSET = 0.5;
    private static final double SIDE_OFFSET = 0.3;
    private static final double HEAD_HEIGHT = 0.9;
    private static final double ARM_HEIGHT = 0.65;
    private static final double TORSO_HEIGHT = 0.55;
    private static final double THIGH_HEIGHT = 0.35;
    private static final double LEG_HEIGHT = 0.2;
    private static final double FOOT_HEIGHT = 0.05;
    private static final float QUARTER_TURN = 90;

    enum Scope { ALL, WOUNDED_LIMBS }

    private BrewPartSelection() {
        // 抽取结果是本次用药事实，保存进身体药效进度。
    }

    record Splash(LivingEntity target, Vec3 impact, int minimum, int maximum, Scope scope) { }

    static List<String> select(BodySnapshot body, Splash splash) {
        // 先跳过失能部位；苦黄膏只从可治疗的四肢中选。
        var remaining = new ArrayList<String>();
        for (var part : body.getPartStatesList()) {
            if (part.getHp() <= 0) {
                continue;
            }
            if (splash.scope() == Scope.WOUNDED_LIMBS
                    && (!BitterHealing.isLimb(part.getId()) || BitterHealing.wound(body, part.getId()) == 0)) {
                continue;
            }
            remaining.add(part.getId());
        }
        int count = splash.minimum() + splash.target().getRandom().nextInt(splash.maximum() - splash.minimum() + 1);
        if (splash.target().distanceToSqr(splash.impact()) > 1) {
            count = Math.max(1, count - 1);
        }
        var selected = new ArrayList<String>();
        while (!remaining.isEmpty() && selected.size() < count) {
            String part = draw(remaining, splash);
            remaining.remove(part);
            selected.add(part);
        }
        return List.copyOf(selected);
    }

    private static String draw(List<String> parts, Splash splash) {
        // 近处权重大；每次删除已选部位后重新计算总权重。
        double total = parts.stream().mapToDouble(part -> weight(part, splash)).sum();
        double roll = splash.target().getRandom().nextDouble() * total;
        for (String part : parts) {
            roll -= weight(part, splash);
            if (roll <= 0) {
                return part;
            }
        }
        return parts.getLast();
    }

    private static double weight(String part, Splash splash) {
        // 左右随实体朝向旋转，不能按世界坐标把右臂永远放在东边。
        LivingEntity target = splash.target();
        double side = 0;
        if (part.startsWith("left_")) {
            side = SIDE_OFFSET;
        } else if (part.startsWith("right_")) {
            side = -SIDE_OFFSET;
        }
        Vec3 lateral = Vec3.directionFromRotation(0, target.getYRot() + QUARTER_TURN)
                .scale(target.getBbWidth() * side);
        Vec3 point = target.position().add(lateral).add(0, target.getBbHeight() * height(part), 0);
        return 1 / (DISTANCE_OFFSET + point.distanceTo(splash.impact()));
    }

    private static double height(String part) {
        // 高度是碰撞箱估计值；实际命中仍以保存的部位身份为准。
        if (part.equals("head") || part.equals("neck")) {
            return HEAD_HEIGHT;
        }
        if (part.endsWith("_foot")) {
            return FOOT_HEIGHT;
        }
        if (part.endsWith("_lower_leg")) {
            return LEG_HEIGHT;
        }
        if (part.endsWith("_thigh")) {
            return THIGH_HEIGHT;
        }
        if (part.endsWith("_arm") || part.endsWith("_forearm") || part.endsWith("_hand")) {
            return ARM_HEIGHT;
        }
        return TORSO_HEIGHT;
    }
}
