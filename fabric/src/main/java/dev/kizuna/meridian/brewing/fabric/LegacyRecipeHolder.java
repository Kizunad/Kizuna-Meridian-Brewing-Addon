package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import java.util.Set;

/** 旧版玩家存档里的已读药方，读档后挂在玩家实体上，等并进身体。 */
public interface LegacyRecipeHolder {
    Set<BrewKind> meridianBrewing$legacyRecipes();

    void meridianBrewing$setLegacyRecipes(Set<BrewKind> recipes);
}
