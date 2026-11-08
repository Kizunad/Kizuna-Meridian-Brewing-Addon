package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.ArmorRotProfile;
import dev.kizuna.meridian.brewing.server.BitterYellowProfile;
import dev.kizuna.meridian.brewing.server.BrewEffectProfiles;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.CharmProfile;
import dev.kizuna.meridian.brewing.server.DepletionProfile;
import dev.kizuna.meridian.brewing.server.InsectLureProfile;
import dev.kizuna.meridian.brewing.server.QiGatherProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 将物品组件映射到原版状态效果，并维护需要世界参与的持续行为。 */
public final class BrewingRuntime {
    private static final int TICKS_PER_SECOND = 20;
    private static final int MIN_EFFECT_TICKS = 1;
    private static final double DEFAULT_SPLASH_RADIUS = 3.0;
    private static final int SILVERFISH_SEARCH_RADIUS = 4;

    private BrewingRuntime() {
        // 药效服务只提供静态世界接线。
    }

    static void register() {
        WitheringService.register();
        ServerTickEvents.END_SERVER_TICK.register(WitheringService::tick);
    }

    public static boolean consume(Level level, LivingEntity entity, ItemStack stack) {
        if (!BrewItemStacks.isBrew(stack)) {
            return false;
        }
        BrewKind kind = BrewItemStacks.kind(stack);
        BrewQuality quality = BrewItemStacks.quality(stack);
        ResolvedEffect effect = resolve(kind, quality);
        applyDrink(entity, effect);
        return true;
    }

    public static void splash(ServerLevel level, ItemStack stack, Vec3 position, Entity source) {
        if (!BrewItemStacks.isBrew(stack)) {
            return;
        }
        BrewKind kind = BrewItemStacks.kind(stack);
        BrewQuality quality = BrewItemStacks.quality(stack);
        ResolvedEffect effect = resolve(kind, quality);
        double radius = effect.radius();
        AABB bounds = new AABB(position, position).inflate(radius);
        double radiusSquared = radius * radius;
        for (Entity entity : level.getEntities((Entity) null, bounds,
                value -> value instanceof LivingEntity living
                        && living.distanceToSqr(position) <= radiusSquared)) {
            if (entity instanceof LivingEntity target) {
                applySplashToTarget(level, target, effect);
            }
        }
        if (kind == BrewKind.DEPLETION) {
            WitheringService.enqueue(level, position, radius, source);
        }
        if (kind == BrewKind.INSECT_LURE) {
            spawnSilverfish(level, position, effect, source);
        }
    }

    private static void applyDrink(LivingEntity entity, ResolvedEffect effect) {
        if (effect.kind() == BrewKind.BITTER_YELLOW) {
            BitterData data = (BitterData) effect.data();
            entity.heal((float) data.drink().totalHealing());
        }
        addEffect(entity, effect.data().status(), effect.data().duration(BrewUse.DRINK),
                effect.quality());
    }

    private static void applySplashToTarget(ServerLevel level, LivingEntity target,
                                            ResolvedEffect effect) {
        switch (effect.kind()) {
            case DEPLETION -> applyDepletion(level, target, effect);
            case ARMOR_ROT -> applyArmorRot(target, effect);
            case INSECT_LURE, QI_GATHER -> addEffect(target, effect.data().status(),
                    effect.data().duration(BrewUse.SPLASH), effect.quality());
            case BITTER_YELLOW -> applyBitterSplash(target, effect);
            case CHARM -> addEffect(target, effect.data().status(),
                    effect.data().duration(BrewUse.SPLASH), effect.quality());
        }
    }

    private static void applyDepletion(ServerLevel level, LivingEntity target,
                                       ResolvedEffect effect) {
        DepletionData data = (DepletionData) effect.data();
        target.hurtServer(level, level.damageSources().magic(),
                (float) data.profile().partDamageHp());
        addEffect(target, effect.data().status(), effect.data().duration(BrewUse.SPLASH),
                effect.quality());
    }

    private static void applyArmorRot(LivingEntity target, ResolvedEffect effect) {
        ArmorData data = (ArmorData) effect.data();
        BrewingEffects.damageArmor(target, data.profile());
        addEffect(target, effect.data().status(), effect.data().duration(BrewUse.SPLASH),
                effect.quality());
    }

    private static void applyBitterSplash(LivingEntity target, ResolvedEffect effect) {
        BitterData data = (BitterData) effect.data();
        target.heal((float) data.splash().healingPerSecond());
        addEffect(target, effect.data().status(), effect.data().duration(BrewUse.SPLASH),
                effect.quality());
    }

    private static void addEffect(LivingEntity target, Holder<MobEffect> effect,
                                  double durationSeconds, BrewQuality quality) {
        int durationTicks = Math.max(MIN_EFFECT_TICKS,
                (int) Math.round(durationSeconds * TICKS_PER_SECOND));
        target.addEffect(new MobEffectInstance(effect, durationTicks, quality.componentValue(),
                false /* ambient */, true /* visible */, true /* showIcon */));
    }

    private static void spawnSilverfish(ServerLevel level, Vec3 position,
                                        ResolvedEffect effect, Entity source) {
        InsectData data = (InsectData) effect.data();
        for (int index = 0; index < data.profile().silverfishCount(); index++) {
            BlockPos spawnPos = findStoneSpawn(level, BlockPos.containing(position), source);
            if (spawnPos == null) {
                return;
            }
            EntityTypes.SILVERFISH.spawn(level, spawnPos, EntitySpawnReason.MOB_SUMMONED);
        }
    }

    private static BlockPos findStoneSpawn(ServerLevel level, BlockPos origin, Entity source) {
        int searchAttempts = SILVERFISH_SEARCH_RADIUS * SILVERFISH_SEARCH_RADIUS;
        int coordinateRange = SILVERFISH_SEARCH_RADIUS * 2 + 1;
        for (int attempt = 0; attempt < searchAttempts; attempt++) {
            int offsetX = level.getRandom().nextInt(coordinateRange) - SILVERFISH_SEARCH_RADIUS;
            int offsetZ = level.getRandom().nextInt(coordinateRange) - SILVERFISH_SEARCH_RADIUS;
            BlockPos candidate = origin.offset(offsetX, 0, offsetZ);
            if (!level.getBlockState(candidate).is(BlockTags.BASE_STONE_OVERWORLD)
                    || !level.getBlockState(candidate.above()).isAir()) {
                continue;
            }
            if (source instanceof Player player && !level.mayInteract(player, candidate)) {
                continue;
            }
            return candidate.above();
        }
        return null;
    }

    private static ResolvedEffect resolve(BrewKind kind, BrewQuality quality) {
        return switch (kind) {
            case DEPLETION -> new ResolvedEffect(kind, quality,
                    new DepletionData(BrewEffectProfiles.depletion(quality)));
            case ARMOR_ROT -> new ResolvedEffect(kind, quality,
                    new ArmorData(BrewEffectProfiles.armorRot(quality)));
            case INSECT_LURE -> new ResolvedEffect(kind, quality,
                    new InsectData(BrewEffectProfiles.insectLure(quality)));
            case QI_GATHER -> new ResolvedEffect(kind, quality,
                    new QiData(BrewEffectProfiles.qiGather(quality)));
            case BITTER_YELLOW -> new ResolvedEffect(kind, quality,
                    new BitterData(BrewEffectProfiles.bitterYellow(quality, BrewUse.DRINK),
                            BrewEffectProfiles.bitterYellow(quality, BrewUse.SPLASH)));
            case CHARM -> charmEffect(kind, quality);
        };
    }

    private static ResolvedEffect charmEffect(BrewKind kind, BrewQuality quality) {
        CharmProfile profile = BrewEffectProfiles.charm(quality);
        Holder<MobEffect> status = BrewingEffects.CHARM_STAGGER;
        if (quality == BrewQuality.SUPERIOR) {
            status = BrewingEffects.CHARM_CONTROL;
        }
        return new ResolvedEffect(kind, quality, new CharmData(profile, status));
    }

    private record ResolvedEffect(BrewKind kind, BrewQuality quality, EffectData data) {
        private double radius() {
            return data.radius();
        }
    }

    private interface EffectData {
        double radius();

        double duration(BrewUse use);

        Holder<MobEffect> status();
    }

    private record DepletionData(DepletionProfile profile) implements EffectData {
        @Override
        public double radius() {
            return profile.radius();
        }

        @Override
        public double duration(BrewUse use) {
            return profile.durationSeconds();
        }

        @Override
        public Holder<MobEffect> status() {
            return BrewingEffects.DEPLETION;
        }
    }

    private record ArmorData(ArmorRotProfile profile) implements EffectData {
        @Override
        public double radius() {
            return profile.radius();
        }

        @Override
        public double duration(BrewUse use) {
            return profile.durationSeconds();
        }

        @Override
        public Holder<MobEffect> status() {
            return BrewingEffects.ARMOR_ROT;
        }
    }

    private record InsectData(InsectLureProfile profile) implements EffectData {
        @Override
        public double radius() {
            return profile.radius();
        }

        @Override
        public double duration(BrewUse use) {
            return profile.durationSeconds();
        }

        @Override
        public Holder<MobEffect> status() {
            return BrewingEffects.INSECT_LURE;
        }
    }

    private record QiData(QiGatherProfile profile) implements EffectData {
        @Override
        public double radius() {
            return DEFAULT_SPLASH_RADIUS;
        }

        @Override
        public double duration(BrewUse use) {
            return profile.durationSeconds();
        }

        @Override
        public Holder<MobEffect> status() {
            return BrewingEffects.QI_GATHER;
        }
    }

    private record BitterData(BitterYellowProfile drink, BitterYellowProfile splash)
            implements EffectData {
        @Override
        public double radius() {
            return DEFAULT_SPLASH_RADIUS;
        }

        @Override
        public double duration(BrewUse use) {
            if (use == BrewUse.DRINK) {
                return drink.durationSeconds();
            }
            return splash.durationSeconds();
        }

        @Override
        public Holder<MobEffect> status() {
            return BrewingEffects.BITTER_YELLOW;
        }
    }

    private record CharmData(CharmProfile profile, Holder<MobEffect> status) implements EffectData {
        @Override
        public double radius() {
            return profile.radius();
        }

        @Override
        public double duration(BrewUse use) {
            if (status == BrewingEffects.CHARM_CONTROL) {
                return profile.controlSeconds();
            }
            return profile.staggerSeconds();
        }
    }
}
