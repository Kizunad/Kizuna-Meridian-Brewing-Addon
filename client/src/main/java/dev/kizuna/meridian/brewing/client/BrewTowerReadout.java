package dev.kizuna.meridian.brewing.client;

import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewRecipe;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewTowerController;
import dev.kizuna.meridian.brewing.server.TowerNeed;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 药塔窗口怎么读整数表，不碰渲染。
 *
 * <p>整数表是逐项同步的：任何两项都可能一新一旧，比如缺料掩码里还留着上一张泼型药方的「缺火药」，选中的已经是
 * 只能喝的药方。所以这里每一项都单独读得出来，不拿一项去对另一项：缺的料按药方单独查料名和件数，不去对开工清单；
 * 认不得的位不画，认不得的序号按没有或第一项算。</p>
 */
final class BrewTowerReadout {
    private static final BrewKind[] KINDS = BrewKind.values();
    private static final TowerNeed[] NEEDS = TowerNeed.values();

    private BrewTowerReadout() {
    }

    /** 缺的一样；木桶里的料带着料名和件数，其余（箱子、燃料、火种、真元）没有。 */
    record Shown(TowerNeed need, Optional<BrewTowerController.Requirement> item) {
    }

    /** 整数表里的枚举序号对应哪一项；序号认不得（两端版本不一样才会有）时按第一项。 */
    static <T> T pick(T[] values, int index) {
        if (index < 0 || index >= values.length) {
            return values[0];
        }
        return values[index];
    }

    /** 整数表里选中的药方；没选（-1）或序号不认得时为空。 */
    static Optional<BrewKind> kind(int selected) {
        if (selected < 0 || selected >= KINDS.length) {
            return Optional.empty();
        }
        return Optional.of(KINDS[selected]);
    }

    /** 按开工前查的顺序列出掩码里的每一样；没有选中的药方时什么都不列。 */
    static List<Shown> shown(int mask, int selected) {
        Optional<BrewKind> kind = kind(selected);
        if (kind.isEmpty()) {
            return List.of();
        }
        BrewRecipe recipe = BrewRecipes.require(kind.orElseThrow());
        var shown = new ArrayList<Shown>();
        for (TowerNeed need : NEEDS) {
            if ((mask & need.bit()) != 0) {
                shown.add(new Shown(need, BrewTowerController.stocked(recipe, need)));
            }
        }
        return shown;
    }
}
