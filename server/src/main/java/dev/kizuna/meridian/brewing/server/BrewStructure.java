package dev.kizuna.meridian.brewing.server;

import java.util.EnumSet;
import java.util.Set;

/** 一次结构检查的纯数据快照。 */
public record BrewStructure(
        HeatSourceKind heatSource,
        int hearthStoneCount,
        SmokeVentState smokeVent,
        HerbState herb) {
    public static final int REQUIRED_HEARTH_STONES = 4;
    public static final double COMPLETE_STRUCTURE_MAXIMUM = 100.0;
    public static final double BLOCKED_STRUCTURE_MAXIMUM = 70.0;
    private static final Set<BrewDirection> REQUIRED_DIRECTIONS =
            Set.copyOf(EnumSet.allOf(BrewDirection.class));

    public BrewStructure {
        if (heatSource == null || smokeVent == null || herb == null
                || hearthStoneCount < 0 || hearthStoneCount > REQUIRED_HEARTH_STONES) {
            throw new IllegalArgumentException("结构快照不完整");
        }
    }

    public boolean accepts(FireKind expectedFire) {
        if (expectedFire == null || heatSource.fireKind() != expectedFire || herb == HerbState.MISSING) {
            return false;
        }
        return !heatSource.needsHearthStones()
                || (smokeVent == SmokeVentState.OPEN && hearthStoneCount == REQUIRED_HEARTH_STONES);
    }

    public static BrewStructure fromHearthDirections(HeatSourceKind heatSource,
                                                     Set<BrewDirection> hearthDirections,
                                                     SmokeVentState smokeVent,
                                                     HerbState herb) {
        if (hearthDirections == null) {
            throw new IllegalArgumentException("灶石方向不能为空");
        }
        EnumSet<BrewDirection> directions = EnumSet.noneOf(BrewDirection.class);
        directions.addAll(hearthDirections);
        directions.retainAll(REQUIRED_DIRECTIONS);
        return new BrewStructure(heatSource, directions.size(), smokeVent, herb);
    }

    public double maximumTemperature() {
        boolean hearthMissing = heatSource.needsHearthStones()
                && hearthStoneCount < REQUIRED_HEARTH_STONES;
        boolean ventBlocked = smokeVent == SmokeVentState.BLOCKED;
        if (heatSource == HeatSourceKind.NONE) {
            return heatSource.equilibriumTemperature();
        }
        if (hearthMissing || ventBlocked) {
            return BLOCKED_STRUCTURE_MAXIMUM;
        }
        return COMPLETE_STRUCTURE_MAXIMUM;
    }
}
