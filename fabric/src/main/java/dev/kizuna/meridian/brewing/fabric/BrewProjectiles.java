package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewEffectProfiles;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.human.server.HumanProjectileBallistics;
import dev.kizuna.meridian.human.server.HumanProjectileBallistics.Vector;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewPartGroup;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LaunchProjectile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.UseBrew;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 无散布抛射在出手时冻结瞄准分段，原版药瓶负责飞行、碰撞与碎瓶声。 */
public final class BrewProjectiles {
    private static final double BASE_SPEED = 0.5;
    private static final double HEAD_START = 0.8;
    private static final double TORSO_START = 0.45;
    private static final double TARGET_MARGIN = 0.1;
    private static final Map<UUID, Impact> impacts = new HashMap<>();

    private BrewProjectiles() {
        // 只保存仍在飞行的功法药瓶元数据。
    }

    static void initialize() {
        // 普通手扔药瓶不登记；实体离开世界后释放本次飞行身份。
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> impacts.remove(entity.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> impacts.clear());
    }

    static void launch(LivingEntity actor, ItemStack stack, LaunchProjectile settings, String targetId) {
        // 瞄准读取当前视线，落点取目标脚下或最远视线点；速度由离散弹道反解，不调用带散布的 shoot。
        var level = (ServerLevel) actor.level();
        Aim aim = aim(actor, settings.getLob().getMaximumDistance().getValue(), targetId);
        var potion = new ThrownSplashPotion(level, actor, stack.copyWithCount(1));
        Vec3 displacement = aim.feet().subtract(potion.position());
        var profile = new HumanProjectileBallistics.LobProfile(settings.getLob().getElevation().getValue(),
                BASE_SPEED * settings.getLob().getMaximumSpeedMultiplier().getValue());
        // 药瓶先扣重力再乘阻力才位移；把箭矢积分的位移除以阻力并补回首 tick 重力。
        Vec3 arrowDisplacement = displacement.scale(1 / HumanProjectileBallistics.AIR_DRAG);
        var shot = HumanProjectileBallistics.lob(vector(arrowDisplacement), profile, new Vector(0, 0, 0));
        Vector velocity = shot.velocity();
        potion.setDeltaMovement(new Vec3(velocity.x(),
                velocity.y() + HumanProjectileBallistics.GRAVITY, velocity.z()));
        impacts.put(potion.getUUID(), new Impact(settings.getBrew(), aim.group()));
        if (!level.addFreshEntity(potion)) {
            impacts.remove(potion.getUUID());
            throw new IllegalStateException("功法药瓶未能加入世界");
        }
    }

    private static Aim aim(LivingEntity actor, double range, String targetId) {
        // 方块先截断视线，再找准星首先穿过的生物碰撞箱，不能隔墙锁定实体。
        Vec3 eye = actor.getEyePosition();
        Vec3 end = eye.add(actor.getLookAngle().scale(range));
        var hit = actor.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, actor));
        Vec3 visibleEnd = hit.getLocation();
        LivingEntity selected = selectedTarget(actor, targetId, range);
        if (selected != null) {
            var intersection = selected.getBoundingBox().inflate(TARGET_MARGIN).clip(eye, visibleEnd);
            double height = TORSO_START;
            if (intersection.isPresent()) {
                height = (intersection.get().y - selected.getY()) / selected.getBbHeight();
            }
            return new Aim(selected.position(), group(height));
        }
        var candidates = actor.level().getEntitiesOfClass(LivingEntity.class, new AABB(eye, visibleEnd).inflate(1),
                        entity -> entity != actor && entity.isAlive() && entity.distanceToSqr(actor) <= range * range)
                .stream().sorted(Comparator.comparingDouble(entity -> entity.distanceToSqr(eye))).toList();
        for (LivingEntity target : candidates) {
            var intersection = target.getBoundingBox().inflate(TARGET_MARGIN).clip(eye, visibleEnd);
            if (intersection.isPresent()) {
                double height = (intersection.get().y - target.getY()) / target.getBbHeight();
                return new Aim(target.position(), group(height));
            }
        }
        return new Aim(visibleEnd, BrewPartGroup.BREW_PART_GROUP_UNSPECIFIED);
    }

    private static LivingEntity selectedTarget(LivingEntity actor, String targetId, double range) {
        // 显式目标仍须在射程和视线内，客户端目标身份不赋予穿墙或超距权限。
        if (targetId.isEmpty()) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(targetId);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        var level = (ServerLevel) actor.level();
        if (level.getEntity(id) instanceof LivingEntity target && target != actor && target.isAlive()
                && target.distanceToSqr(actor) <= range * range && actor.hasLineOfSight(target)) {
            return target;
        }
        return null;
    }

    private static BrewPartGroup group(double height) {
        // 出手时把准星高度离散成三段，落地时不会被目标的新姿势覆盖。
        if (height >= HEAD_START) {
            return BrewPartGroup.HEAD_NECK;
        }
        if (height >= TORSO_START) {
            return BrewPartGroup.TORSO;
        }
        return BrewPartGroup.LEGS;
    }

    static BrewPartGroup aimedGroup(UUID projectile) {
        // 只读飞行记录，实际命中仍消费同一份出手分段。
        return impacts.get(projectile).group();
    }

    public static boolean impact(ThrownSplashPotion potion, ItemStack stack) {
        // 只有登记过的功法药瓶走定向与下引链，普通喷溅继续走原始药效入口。
        Impact impact = impacts.remove(potion.getUUID());
        if (impact == null) {
            return false;
        }
        var level = (ServerLevel) potion.level();
        BrewKind kind = BrewItemStacks.kind(stack);
        var quality = BrewItemStacks.quality(stack);
        double radius = radius(stack);
        Vec3 position = potion.position();
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                potion.getBoundingBox().inflate(radius), entity -> entity.isAlive()
                        && entity.distanceToSqr(position) <= radius * radius)) {
            var application = new BrewBodyEffects.Application(kind, quality, BrewUse.THROW,
                    position, potion.getOwner());
            BrewTechniqueUse.applySeed(target, application, impact.use(), impact.group());
        }
        if (kind == BrewKind.DEPLETION) {
            WitheringService.enqueue(level, position, radius, potion.getOwner());
        }
        if (kind == BrewKind.INSECT_LURE) {
            BrewingRuntime.spawnSilverfish(level, position, quality, potion.getOwner(), 1);
        }
        return true;
    }

    private static double radius(ItemStack stack) {
        // 半径属于药瓶品质，功法只控制落点和部位偏向。
        var quality = BrewItemStacks.quality(stack);
        return switch (BrewItemStacks.kind(stack)) {
            case DEPLETION -> BrewEffectProfiles.depletion(quality).radius();
            case ARMOR_ROT -> BrewEffectProfiles.armorRot(quality).radius();
            case INSECT_LURE -> BrewEffectProfiles.insectLure(quality).radius();
            case CHARM -> BrewEffectProfiles.charm(quality).radius();
            default -> BrewingRuntime.DEFAULT_SPLASH_RADIUS;
        };
    }

    private static Vector vector(Vec3 value) {
        // 弹道库只接收纯坐标，不引用实体或世界。
        return new Vector(value.x, value.y, value.z);
    }

    private record Aim(Vec3 feet, BrewPartGroup group) { }
    private record Impact(UseBrew use, BrewPartGroup group) { }
}
