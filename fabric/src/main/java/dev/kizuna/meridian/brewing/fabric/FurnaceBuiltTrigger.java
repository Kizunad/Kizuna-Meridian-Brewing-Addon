package dev.kizuna.meridian.brewing.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.MinMaxBounds;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 进度触发器 {@code meridian_brewing:furnace_built}：玩家守着一座整座对上的药炉时触发，报这座药炉的阶数。
 *
 * <p>玩家空手查看已成形的药炉时触发。条件 {@code tier} 是阶数范围，省略就是任意一阶。</p>
 */
final class FurnaceBuiltTrigger extends SimpleCriterionTrigger<FurnaceBuiltTrigger.Instance> {
    static final Identifier ID = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "furnace_built");
    static final FurnaceBuiltTrigger TRIGGER = new FurnaceBuiltTrigger();

    private FurnaceBuiltTrigger() {
        // 全局只有一个实例，由注册表持有。
    }

    static void register() {
        // 触发器类型在注册表冻结前登记，进度文件按 ID 引用。
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, ID, TRIGGER);
    }

    @Override
    public Codec<Instance> codec() {
        // 进度文件里的条件格式。
        return Instance.CODEC;
    }

    void trigger(ServerPlayer player, int tier) {
        // 认出整座药炉时调用；原版只检查这名玩家还没完成的进度。
        trigger(player, instance -> instance.tier().matches(tier));
    }

    /** 一条进度条件：可选的玩家谓词和药炉阶数范围。 */
    record Instance(Optional<ContextAwarePredicate> player, MinMaxBounds.Ints tier)
            implements SimpleCriterionTrigger.SimpleInstance {
        static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                MinMaxBounds.Ints.CODEC.optionalFieldOf("tier", MinMaxBounds.Ints.ANY).forGetter(Instance::tier))
                .apply(instance, Instance::new));
    }
}
