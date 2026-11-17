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
    private static final BrewKind[] KINDS = BrewKind.towerKinds();
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

    /** 缺料一行里一样东西摆在哪：{@code x} 是相对底板的横坐标，{@code row} 是第几行（从 0 起）。 */
    record Spot(int x, int row) {
    }

    /**
     * 悬停「缺燃料」写几档。{@code synced} 是服务端同步来的、塔这一秒实际在等的档数
     * （泡料、保温只要 1 档，中途换了药方也还是这一批的）；还没同步到（0）或认不得时，按选中药方的稳定档数写。
     */
    static int fuelLevel(int synced, BrewRecipe recipe) {
        if (synced < 1 || synced > BrewTowerController.FUEL_CELLS) {
            return BrewTowerController.stableLevel(recipe);
        }
        return synced;
    }

    /**
     * 缺料一行怎么排：从 {@code left} 起一样接一样往右，哪一样的右边会超过 {@code right} 就另起一行、回到
     * {@code left}。{@code widths} 是每一样占的宽度（图标一格，文字按字宽）。一行七个图标以内位置和不换行时一样。
     */
    static List<Spot> flow(List<Integer> widths, int left, int right) {
        var spots = new ArrayList<Spot>();
        int x = left;
        int row = 0;
        for (int width : widths) {
            if (x > left && x + width > right) {
                x = left;
                row++;
            }
            spots.add(new Spot(x, row));
            x += width;
        }
        return spots;
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
