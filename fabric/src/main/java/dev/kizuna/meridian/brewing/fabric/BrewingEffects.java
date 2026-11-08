package dev.kizuna.meridian.brewing.fabric;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/** 六种药效及魅惑两档控制状态的注册表入口。 */
final class BrewingEffects {
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
        DEPLETION = register("depletion", MobEffectCategory.HARMFUL, 0xA08A4A);
        ARMOR_ROT = register("armor_rot", MobEffectCategory.HARMFUL, 0x5E9C7A);
        INSECT_LURE = register("insect_lure", MobEffectCategory.HARMFUL, 0xE0A030);
        QI_GATHER = register("qi_gather", MobEffectCategory.BENEFICIAL, 0x4FA3A0);
        BITTER_YELLOW = register("bitter_yellow", MobEffectCategory.BENEFICIAL, 0xC9A227);
        CHARM_STAGGER = register("charm_stagger", MobEffectCategory.HARMFUL, 0xC77DBA);
        CHARM_CONTROL = register("charm_control", MobEffectCategory.HARMFUL, 0xC77DBA);
    }

    private static Holder.Reference<MobEffect> register(String path, MobEffectCategory category, int color) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        return Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, id,
                new BrewStatusEffect(category, color));
    }

    private static final class BrewStatusEffect extends MobEffect {
        private BrewStatusEffect(MobEffectCategory category, int color) {
            super(category, color);
        }
    }
}
