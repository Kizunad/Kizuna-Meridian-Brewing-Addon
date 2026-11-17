package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;

/** 巫药和五行丹露的原版 Potion 身份；品质留在物品数据组件。 */
final class BrewingPotions {
    private static final int DEFAULT_STATUS_DURATION_TICKS = 20;
    private static final Map<BrewKind, Holder.Reference<Potion>> POTIONS = new EnumMap<>(BrewKind.class);

    private BrewingPotions() {
        // 药水身份只在模组初始化时登记。
    }

    static void register() {
        POTIONS.put(BrewKind.DEPLETION, register(BrewKind.DEPLETION, BrewingEffects.DEPLETION));
        POTIONS.put(BrewKind.BITTER_YELLOW, register(BrewKind.BITTER_YELLOW, BrewingEffects.BITTER_YELLOW));
        POTIONS.put(BrewKind.ARMOR_ROT, register(BrewKind.ARMOR_ROT, BrewingEffects.ARMOR_ROT));
        POTIONS.put(BrewKind.INSECT_LURE, register(BrewKind.INSECT_LURE, BrewingEffects.INSECT_LURE));
        POTIONS.put(BrewKind.QI_GATHER, register(BrewKind.QI_GATHER, BrewingEffects.QI_GATHER));
        POTIONS.put(BrewKind.CHARM, register(BrewKind.CHARM, BrewingEffects.CHARM_CONTROL));
        for (BrewKind kind : BrewKind.elixirKinds()) {
            POTIONS.put(kind, register(kind, BrewingEffects.status(kind)));
        }
    }

    static Holder<Potion> potion(BrewKind kind) {
        Holder.Reference<Potion> potion = POTIONS.get(kind);
        if (potion == null) {
            throw new IllegalArgumentException("未登记巫药：" + kind);
        }
        return potion;
    }

    static Map<BrewKind, Holder.Reference<Potion>> all() {
        return Map.copyOf(POTIONS);
    }

    private static Holder.Reference<Potion> register(BrewKind kind, Holder<MobEffect> effect) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, kind.path());
        Potion potion = new Potion("meridian_brewing_" + kind.path(),
                new MobEffectInstance(effect, DEFAULT_STATUS_DURATION_TICKS));
        return Registry.registerForHolder(BuiltInRegistries.POTION, id, potion);
    }
}
