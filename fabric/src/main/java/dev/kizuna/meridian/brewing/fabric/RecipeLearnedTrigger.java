package dev.kizuna.meridian.brewing.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import java.util.Optional;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 进度触发器 {@code meridian_brewing:recipe_learned}：玩家读药方羊皮纸、头一回把这张药方记进身体时触发。
 *
 * <p>条件写 {@code "recipe": "<药方路径>"}（如 {@code qi_gather}）只认这一张，省略就是任意一张。已经学过的再读不触发。</p>
 */
final class RecipeLearnedTrigger extends SimpleCriterionTrigger<RecipeLearnedTrigger.Instance> {
    static final Identifier ID = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "recipe_learned");
    static final RecipeLearnedTrigger TRIGGER = new RecipeLearnedTrigger();
    private static final Codec<BrewKind> KIND_CODEC = Codec.STRING.comapFlatMap(RecipeLearnedTrigger::kind,
            BrewKind::path);

    private RecipeLearnedTrigger() {
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

    void trigger(ServerPlayer player, BrewKind learned) {
        // 药方写进身体那一刻调用；原版只检查这名玩家还没完成的进度。
        trigger(player, instance -> instance.matches(learned));
    }

    private static DataResult<BrewKind> kind(String path) {
        // 药方路径写错时进度文件加载失败，不会静默变成永远不成立。
        return RecipeKnowledge.kind(path)
                .map(DataResult::success)
                .orElseGet(() -> DataResult.error(() -> "没有这张药方：" + path));
    }

    /** 一条进度条件：可选的玩家谓词和药方。 */
    record Instance(Optional<ContextAwarePredicate> player, Optional<BrewKind> recipe)
            implements SimpleCriterionTrigger.SimpleInstance {
        static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                KIND_CODEC.optionalFieldOf("recipe").forGetter(Instance::recipe)).apply(instance, Instance::new));

        boolean matches(BrewKind learned) {
            // 没写药方时任意一张都算。
            return recipe.map(learned::equals).orElse(true);
        }
    }
}
