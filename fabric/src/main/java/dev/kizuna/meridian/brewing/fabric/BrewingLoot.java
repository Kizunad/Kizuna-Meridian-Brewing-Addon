package dev.kizuna.meridian.brewing.fabric;

import com.mojang.serialization.MapCodec;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import java.util.Set;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetComponentsFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemKilledByPlayerCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;

/**
 * 修炼者女巫的掉落：被玩家打死、身体已认领且是真炁境界的女巫，独立地抽药方和熬药卷轴。
 *
 * <p>只往原版自带的 {@code entities/witch} 加池；数据包替换过的表不动。概率是占位值。</p>
 */
final class BrewingLoot {
    static final Identifier WITCH_TABLE = Identifier.withDefaultNamespace("entities/witch");
    static final Identifier CULTIVATOR_ID = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "cultivator");
    static final Identifier SCROLL_ID = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID,
            "brewing_basics_scroll");
    static final float RECIPE_CHANCE = 0.10F;
    static final float SCROLL_CHANCE = 0.08F;

    private BrewingLoot() {
    }

    static void register() {
        Registry.register(BuiltInRegistries.LOOT_CONDITION_TYPE, CULTIVATOR_ID, CultivatorCondition.CODEC);
        LootTableEvents.MODIFY.register((key, table, source, registries) -> {
            if (source.isBuiltin() && key.identifier().equals(WITCH_TABLE)) {
                addRecipePool(table);
                addScrollPool(table);
            }
        });
    }

    private static void addRecipePool(LootTable.Builder table) {
        LootPool.Builder pool = cultivatorPool(RECIPE_CHANCE);
        Item recipe = BrewingItems.item("brew_recipe");
        for (BrewKind kind : BrewKind.values()) {
            var recipeKind = SetComponentsFunction.setComponent(BrewingComponents.RECIPE_KIND, kind.path());
            pool.add(LootItem.lootTableItem(recipe).apply(recipeKind));
        }
        table.withPool(pool);
    }

    private static void addScrollPool(LootTable.Builder table) {
        // 熬药卷轴是功法库按目录生成的物品，缺了说明功法目录没装上。
        Item scroll = BuiltInRegistries.ITEM.getOptional(SCROLL_ID)
                .orElseThrow(() -> new IllegalStateException("功法库没有生成熬药卷轴：" + SCROLL_ID));
        LootPool.Builder pool = cultivatorPool(SCROLL_CHANCE);
        pool.add(LootItem.lootTableItem(scroll));
        table.withPool(pool);
    }

    private static LootPool.Builder cultivatorPool(float chance) {
        // 先比便宜的条件，最后才去身体宿主那里读境界。
        LootPool.Builder pool = LootPool.lootPool();
        pool.when(LootItemKilledByPlayerCondition.killedByPlayer());
        pool.when(LootItemRandomChanceCondition.randomChance(chance));
        pool.when(() -> CultivatorCondition.INSTANCE);
        return pool;
    }

    /** 战利品条件：这只实体有已认领、没暂停的身体，并且是真炁境界。 */
    record CultivatorCondition() implements LootItemCondition {
        static final CultivatorCondition INSTANCE = new CultivatorCondition();
        static final MapCodec<CultivatorCondition> CODEC = MapCodec.unit(INSTANCE);

        @Override
        public MapCodec<CultivatorCondition> codec() {
            return CODEC;
        }

        @Override
        public Set<ContextKey<?>> getReferencedContextParams() {
            return Set.of(LootContextParams.THIS_ENTITY);
        }

        @Override
        public boolean test(LootContext context) {
            if (!(context.getOptionalParameter(LootContextParams.THIS_ENTITY) instanceof LivingEntity entity)) {
                return false;
            }
            return BrewBodyHost.observe(entity).found()
                    .map(binding -> binding.body().getRealm() == Realm.TRUE_QI)
                    .orElse(false);
        }
    }
}
