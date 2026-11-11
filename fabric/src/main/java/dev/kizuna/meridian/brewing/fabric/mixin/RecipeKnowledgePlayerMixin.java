package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.LegacyRecipeHolder;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 旧版把已读药方写在玩家存档的 addon_progress 里。这里只读一次，挂在实体上等登录后并进身体；
 * 并入之前存档照原样写回，免得身体暂停时这份记录丢掉；并入以后不再写玩家存档。
 */
@Mixin(Player.class)
public abstract class RecipeKnowledgePlayerMixin implements LegacyRecipeHolder {
    @Unique
    private static final String LEGACY_PROGRESS = "addon_progress";

    @Unique
    private Set<BrewKind> meridianBrewing$legacyRecipes = EnumSet.noneOf(BrewKind.class);

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void meridianBrewing$read(ValueInput input, CallbackInfo callback) {
        // 旧格式是每种药方一个布尔值；没有这段的新存档读出空集合。
        ValueInput recipes = input.childOrEmpty(LEGACY_PROGRESS).childOrEmpty(RecipeKnowledge.PROGRESS_KEY);
        EnumSet<BrewKind> legacy = EnumSet.noneOf(BrewKind.class);
        for (BrewKind kind : BrewKind.values()) {
            if (recipes.getBooleanOr(kind.path(), false /* defaultKnown */)) {
                legacy.add(kind);
            }
        }
        meridianBrewing$legacyRecipes = legacy;
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void meridianBrewing$write(ValueOutput output, CallbackInfo callback) {
        // 还没并进身体的旧记录原样写回；已经并入的玩家这里什么都不写。
        if (meridianBrewing$legacyRecipes.isEmpty()) {
            return;
        }
        ValueOutput recipes = output.child(LEGACY_PROGRESS).child(RecipeKnowledge.PROGRESS_KEY);
        for (BrewKind kind : meridianBrewing$legacyRecipes) {
            recipes.putBoolean(kind.path(), true);
        }
    }

    @Override
    public Set<BrewKind> meridianBrewing$legacyRecipes() {
        // 返回副本，并入流程只能经 setter 清空。
        return Set.copyOf(meridianBrewing$legacyRecipes);
    }

    @Override
    public void meridianBrewing$setLegacyRecipes(Set<BrewKind> recipes) {
        // 并入成功后传空集合，下次存档就不再写旧格式。
        meridianBrewing$legacyRecipes = recipes.isEmpty() ? EnumSet.noneOf(BrewKind.class) : EnumSet.copyOf(recipes);
    }
}
