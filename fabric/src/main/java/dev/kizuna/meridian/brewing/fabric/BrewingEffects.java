package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BrewEffectProfiles;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewPotency;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.InsectLureProfile;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/** 巫药、丹毒及魅惑两档控制状态的注册表入口和持续行为。 */
final class BrewingEffects {
    private static final int TICKS_PER_SECOND = 20;
    private static final BrewEffectKind NO_TICK = BrewEffectKind.NO_TICK;

    static Holder.Reference<MobEffect> DEPLETION;
    static Holder.Reference<MobEffect> ARMOR_ROT;
    static Holder.Reference<MobEffect> INSECT_LURE;
    static Holder.Reference<MobEffect> QI_GATHER;
    static Holder.Reference<MobEffect> BITTER_YELLOW;
    static Holder.Reference<MobEffect> CHARM_STAGGER;
    static Holder.Reference<MobEffect> CHARM_CONTROL;
    private static final Map<BrewKind, Holder.Reference<MobEffect>> TOXINS = new EnumMap<>(BrewKind.class);

    private BrewingEffects() {
        // 状态效果只注册一次。
    }

    static void register() {
        // 药效周期由状态效果驱动，身体进度只提供该状态的目标与曲线。
        DEPLETION = register("depletion", MobEffectCategory.HARMFUL, 0xA08A4A, BrewEffectKind.DEPLETION);
        ARMOR_ROT = register("armor_rot", MobEffectCategory.HARMFUL, 0x5E9C7A,
                BrewEffectKind.ARMOR_ROT);
        INSECT_LURE = register("insect_lure", MobEffectCategory.HARMFUL, 0xE0A030,
                BrewEffectKind.INSECT_LURE);
        QI_GATHER = register("qi_gather", MobEffectCategory.BENEFICIAL, 0x4FA3A0, BrewEffectKind.QI_GATHER);
        BITTER_YELLOW = register("bitter_yellow", MobEffectCategory.BENEFICIAL, 0xC9A227,
                BrewEffectKind.BITTER_YELLOW);
        CHARM_STAGGER = register("charm_stagger", MobEffectCategory.HARMFUL, 0xC77DBA,
                NO_TICK);
        CHARM_CONTROL = register("charm_control", MobEffectCategory.HARMFUL, 0xC77DBA,
                NO_TICK);
        for (BrewKind kind : BrewKind.elixirKinds()) {
            Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, kind.elementPath() + "_toxin");
            TOXINS.put(kind, Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, id, new BrewToxinEffect(kind)));
        }
    }

    private static Holder.Reference<MobEffect> register(String path, MobEffectCategory category,
                                                         int color, BrewEffectKind behavior) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        return Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, id,
                new BrewStatusEffect(category, color, behavior));
    }

    static Holder<MobEffect> status(BrewKind kind) {
        // 状态身份集中管理，生命周期和用药入口共用同一注册对象。
        return switch (kind) {
            case DEPLETION -> DEPLETION;
            case ARMOR_ROT -> ARMOR_ROT;
            case QI_GATHER -> QI_GATHER;
            case BITTER_YELLOW -> BITTER_YELLOW;
            case INSECT_LURE -> INSECT_LURE;
            case CHARM -> CHARM_CONTROL;
            case FIRE_ELIXIR, METAL_ELIXIR, WOOD_ELIXIR, WATER_ELIXIR, EARTH_ELIXIR -> TOXINS.get(kind);
        };
    }

    static Optional<BrewKind> kind(Holder<MobEffect> effect) {
        // 魅惑有两个图标，但只有一个来源种类。
        if (effect.equals(CHARM_STAGGER)) {
            return Optional.of(BrewKind.CHARM);
        }
        for (BrewKind kind : BrewKind.values()) {
            // 丹毒不是药效本身：它被移除时没有身体里的剂量要撤，不算进来源种类。
            if (!kind.elixir() && effect.equals(status(kind))) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    private static void attractInsects(ServerLevel level, LivingEntity target, BrewQuality quality) {
        // 功法催发的气味距离随剩余剂量减弱，普通喷溅没有身体剂量、或身体被宿主暂停读不到剂量时使用原值。
        InsectLureProfile profile = BrewEffectProfiles.insectLure(quality);
        double strength = BrewBodyHost.observe(target).found().map(binding ->
                BrewBodyProgress.read(binding.body()).getDosesList().stream()
                        .filter(dose -> dose.getKind().equals(BrewKind.INSECT_LURE.name()))
                        .mapToDouble(BrewPotency::strength).max().orElse(1)).orElse(1.0);
        double distance = profile.hearingDistance() * strength;
        AABB bounds = target.getBoundingBox().inflate(distance);
        List<Entity> entities = level.getEntities((Entity) null, bounds,
                entity -> entity instanceof Mob mob
                        && mob.getType().builtInRegistryHolder().is(BrewingTags.SCENT_SEEKERS)
                        && entity.distanceToSqr(target) <= distance * distance);
        for (Entity entity : entities) {
            if (entity instanceof Mob insect) {
                insect.setTarget(target);
            }
        }
    }

    private enum BrewEffectKind {
        NO_TICK,
        DEPLETION,
        ARMOR_ROT,
        QI_GATHER,
        BITTER_YELLOW,
        INSECT_LURE
    }

    private static final class BrewStatusEffect extends MobEffect {
        private final BrewEffectKind behavior;

        private BrewStatusEffect(MobEffectCategory category, int color, BrewEffectKind behavior) {
            super(category, color);
            this.behavior = behavior;
        }

        @Override
        public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
            // 物品蚀甲和身体持续效果在状态仍存在时结算。
            BrewQuality quality = BrewQuality.fromComponentValue(amplifier);
            if (behavior != BrewEffectKind.NO_TICK && behavior != BrewEffectKind.INSECT_LURE) {
                BrewEffectLifecycle.tick(entity, BrewKind.valueOf(behavior.name()), quality);
            }
            if (behavior == BrewEffectKind.INSECT_LURE) {
                if (entity.isInWaterOrRain()) {
                    entity.removeEffect(BrewingEffects.INSECT_LURE);
                } else {
                    attractInsects(level, entity, quality);
                }
            }
            return true;
        }

        @Override
        public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
            // 每秒结算一次，时间不足一秒的尾段由移除时清理修正。
            if (behavior != BrewEffectKind.NO_TICK) {
                return duration % TICKS_PER_SECOND == 0;
            }
            return false;
        }
    }
}
