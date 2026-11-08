package dev.kizuna.meridian.brewing.server;

/** 一次结构检查的纯数据快照。 */
public record BrewStructure(
        HeatSourceKind heatSource,
        int hearthStoneCount,
        SmokeVentState smokeVent,
        HerbState herb) {
    public static final int REQUIRED_HEARTH_STONES = 4;
    public static final double COMPLETE_STRUCTURE_MAXIMUM = 100.0;
    public static final double BLOCKED_STRUCTURE_MAXIMUM = 70.0;

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
