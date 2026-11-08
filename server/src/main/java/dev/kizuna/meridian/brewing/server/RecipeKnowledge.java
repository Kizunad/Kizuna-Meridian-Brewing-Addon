package dev.kizuna.meridian.brewing.server;

import java.util.EnumSet;
import java.util.Set;

/** 药方读过记录对应的 addon_progress 载荷。 */
public final class RecipeKnowledge {
    public static final String PROGRESS_KEY = "meridian_brewing:recipes";
    private final EnumSet<BrewKind> known = EnumSet.noneOf(BrewKind.class);

    public void learn(BrewKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("药方身份不能为空");
        }
        known.add(kind);
    }

    public boolean knows(BrewKind kind) {
        return kind != null && known.contains(kind);
    }

    public Set<BrewKind> known() {
        return Set.copyOf(known);
    }
}
