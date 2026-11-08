package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.ArmorRotProfile;
import dev.kizuna.meridian.brewing.server.BrewEffectProfiles;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.InsectLureProfile;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/** 六种药效及魅惑两档控制状态的注册表入口和持续行为。 */
final class BrewingEffects {
    private static final int TICKS_PER_SECOND = 20;
    private static final double COPPER_ARMOR_FACTOR = 1.0;
    private static final BrewEffectKind NO_TICK = BrewEffectKind.NO_TICK;

    static Holder.Reference<MobEffect> DEPLETION;
    static Holder.Reference<MobEffect> ARMOR_ROT;
    static Holder.Reference<MobEffect> INSECT_LURE;
    static Holder.Reference<MobEffect> QI_GATHER;
    static Holder.Reference<MobEffect> BITTER_YELLOW;
    static Holder.Reference<MobEffect> CHARM_STAGGER;
    static Holder.Reference<MobEffect> CHARM_CONTROL;

    private BrewingEffects() {
        // 状态效果只注册一次。
    }

    static void register() {
        DEPLETION = register("depletion", MobEffectCategory.HARMFUL, 0xA08A4A, NO_TICK);
        ARMOR_ROT = register("armor_rot", MobEffectCategory.HARMFUL, 0x5E9C7A,
                BrewEffectKind.ARMOR_ROT);
        INSECT_LURE = register("insect_lure", MobEffectCategory.HARMFUL, 0xE0A030,
                BrewEffectKind.INSECT_LURE);
        QI_GATHER = register("qi_gather", MobEffectCategory.BENEFICIAL, 0x4FA3A0, NO_TICK);
        BITTER_YELLOW = register("bitter_yellow", MobEffectCategory.BENEFICIAL, 0xC9A227,
                NO_TICK);
        CHARM_STAGGER = register("charm_stagger", MobEffectCategory.HARMFUL, 0xC77DBA,
                NO_TICK);
        CHARM_CONTROL = register("charm_control", MobEffectCategory.HARMFUL, 0xC77DBA,
                NO_TICK);
    }

    private static Holder.Reference<MobEffect> register(String path, MobEffectCategory category,
                                                         int color, BrewEffectKind behavior) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        return Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, id,
                new BrewStatusEffect(category, color, behavior));
    }

    static void damageArmor(LivingEntity target, ArmorRotProfile profile) {
        for (EquipmentSlot slot : armorSlots()) {
            ItemStack stack = target.getItemBySlot(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }
            double materialFactor = armorMaterialFactor(stack);
            if (materialFactor == 0.0) {
                continue;
            }
            int damage = Math.max(1, (int) Math.ceil(
                    stack.getMaxDamage() * profile.durabilityPercentPerSecond() * materialFactor));
            stack.hurtAndBreak(damage, target, slot);
        }
    }

    private static List<EquipmentSlot> armorSlots() {
        return List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET);
    }

    private static double armorMaterialFactor(ItemStack stack) {
        if (stack.is(BrewingTags.ARMOR_ROT_IMMUNE)) {
            return 0.0;
        }
        if (stack.is(BrewingTags.ARMOR_ROT_HALF)) {
            return 0.5;
        }
        if (stack.is(BrewingTags.COPPER_ARMOR)) {
            return COPPER_ARMOR_FACTOR;
        }
        return 1.0;
    }

    private static void attractInsects(ServerLevel level, LivingEntity target, BrewQuality quality) {
        InsectLureProfile profile = BrewEffectProfiles.insectLure(quality);
        AABB bounds = target.getBoundingBox().inflate(profile.hearingDistance());
        List<Entity> entities = level.getEntities((Entity) null, bounds,
                entity -> entity instanceof Mob mob
                        && mob.getType().builtInRegistryHolder().is(BrewingTags.SCENT_SEEKERS));
        for (Entity entity : entities) {
            if (entity instanceof Mob insect) {
                insect.setTarget(target);
            }
        }
    }

    private enum BrewEffectKind {
        NO_TICK,
        ARMOR_ROT,
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
            BrewQuality quality = BrewQuality.fromComponentValue(amplifier);
            if (behavior == BrewEffectKind.ARMOR_ROT) {
                damageArmor(entity, BrewEffectProfiles.armorRot(quality));
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
            if (behavior == BrewEffectKind.ARMOR_ROT
                    || behavior == BrewEffectKind.INSECT_LURE) {
                return duration % TICKS_PER_SECOND == 0;
            }
            return false;
        }
    }
}
