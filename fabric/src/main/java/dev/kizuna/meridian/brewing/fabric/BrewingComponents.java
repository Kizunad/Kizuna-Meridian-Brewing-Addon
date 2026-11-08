package dev.kizuna.meridian.brewing.fabric;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;

/** 炼药物品使用的数据组件。 */
final class BrewingComponents {
    static final Identifier QUALITY_ID = Identifier.fromNamespaceAndPath(
            BrewingFabric.MOD_ID, "quality");
    static final Identifier PREPARED_DAY_ID = Identifier.fromNamespaceAndPath(
            BrewingFabric.MOD_ID, "prepared_day");
    static final Identifier RECIPE_KIND_ID = Identifier.fromNamespaceAndPath(
            BrewingFabric.MOD_ID, "recipe_kind");
    static DataComponentType<Integer> QUALITY;
    static DataComponentType<Long> PREPARED_DAY;
    static DataComponentType<String> RECIPE_KIND;

    private BrewingComponents() {
        // 组件只通过模组初始化注册。
    }

    static void register() {
        DataComponentType.Builder<Integer> builder = DataComponentType.builder();
        builder.persistent(Codec.INT);
        builder.networkSynchronized(ByteBufCodecs.VAR_INT);
        QUALITY = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, QUALITY_ID, builder.build());

        DataComponentType.Builder<Long> dayBuilder = DataComponentType.builder();
        dayBuilder.persistent(Codec.LONG);
        dayBuilder.networkSynchronized(ByteBufCodecs.VAR_LONG);
        PREPARED_DAY = Registry.register(
                BuiltInRegistries.DATA_COMPONENT_TYPE, PREPARED_DAY_ID, dayBuilder.build());

        DataComponentType.Builder<String> recipeBuilder = DataComponentType.builder();
        recipeBuilder.persistent(Codec.STRING);
        recipeBuilder.networkSynchronized(ByteBufCodecs.STRING_UTF8);
        RECIPE_KIND = Registry.register(
                BuiltInRegistries.DATA_COMPONENT_TYPE, RECIPE_KIND_ID, recipeBuilder.build());
    }
}
