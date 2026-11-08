package dev.kizuna.meridian.brewing.server;

/** 铜釜的四级氧化状态。 */
public enum CopperOxidation {
    UNAFFECTED(0, 0.20),
    EXPOSED(1, 0.15),
    WEATHERED(2, 0.10),
    OXIDIZED(3, 0.07);

    private final int level;
    private final double convergencePerSecond;

    CopperOxidation(int level, double convergencePerSecond) {
        this.level = level;
        this.convergencePerSecond = convergencePerSecond;
    }

    public int level() {
        return level;
    }

    public double convergencePerSecond() {
        return convergencePerSecond;
    }

    public CopperOxidation next() {
        CopperOxidation[] states = values();
        int nextLevel = Math.min(level + 1, states.length - 1);
        return states[nextLevel];
    }

    public CopperOxidation previous() {
        CopperOxidation[] states = values();
        int previousLevel = Math.max(level - 1, 0);
        return states[previousLevel];
    }
}
