package dev.kizuna.meridian.brewing.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import dev.kizuna.meridian.origin.fabric.OriginEntrypoint;
import dev.kizuna.meridian.origin.fabric.OriginRegistrar;
import dev.kizuna.meridian.origin.fabric.OriginTalentType;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ExtraCodecs;

/**
 * 出身附属的登记入口：一次性天赋 {@code meridian_brewing:starting_recipes}，选定出身时把写着的药方并进玩家会的药方。
 * 参数 {@code recipes} 是药方路径（如 {@code qi_gather}），至少一张；认不出的药方在读出身数据包时就拒。
 */
public final class BrewOrigins implements OriginEntrypoint {
    private static final Codec<BrewKind> RECIPE = Codec.STRING.comapFlatMap(BrewOrigins::recipe, BrewKind::path);
    static final Codec<StartingRecipes> PARAMS = RecordCodecBuilder.create(instance -> instance.group(
            ExtraCodecs.nonEmptyList(RECIPE.listOf()).fieldOf("recipes").forGetter(StartingRecipes::recipes))
            .apply(instance, StartingRecipes::new));
    static final OriginTalentType<StartingRecipes> STARTING_RECIPES = OriginTalentType.oneShot(
            Identifier.parse("meridian_brewing:starting_recipes"), PARAMS, BrewOrigins::grant);

    @Override
    public void register(OriginRegistrar registrar) {
        // 出身附属读数据包前收齐各附属的类型。
        registrar.talent(STARTING_RECIPES);
    }

    private static DataResult<BrewKind> recipe(String path) {
        // 药方按路径认，和羊皮纸、指令用的是同一个名字；认不出就让这个出身读取失败。
        return RecipeKnowledge.kind(path).map(DataResult::success)
                .orElseGet(() -> DataResult.error(() -> "没有这张药方：" + path));
    }

    static void grant(ServerPlayer player, StartingRecipes params) {
        // 并进玩家已会的药方，原来会的不动；身体这一刻读不到就抛，出身附属下次进服再发。
        BrewRecipeBook.Lookup current = BrewRecipeBook.lookup(player);
        requireBound(current);
        EnumSet<BrewKind> known = EnumSet.noneOf(BrewKind.class);
        known.addAll(current.known());
        known.addAll(params.recipes());
        requireBound(BrewRecipeBook.replace(player, known));
    }

    private static void requireBound(BrewRecipeBook.Lookup lookup) {
        // 身体暂停或还没建好时不算发成。
        if (lookup.presence() != BrewBodyHost.Presence.BOUND) {
            throw new IllegalStateException("读不到身体，开局的药方等下次进服再记：" + lookup.presence());
        }
    }

    /** 出身 JSON 里这一条天赋的参数：开局就会的几张药方。 */
    public record StartingRecipes(List<BrewKind> recipes) {
        // 药方按出身 JSON 里写的顺序存。
        public StartingRecipes {
            // 列表定下以后不再变。
            recipes = List.copyOf(recipes);
        }
    }
}
