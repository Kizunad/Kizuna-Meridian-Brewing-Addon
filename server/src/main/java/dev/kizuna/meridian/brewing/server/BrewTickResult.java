package dev.kizuna.meridian.brewing.server;

/** 巫釜一次服务端推进后的可观察快照。 */
public record BrewTickResult(
        BrewCauldronState state,
        double temperature,
        int waterUnits,
        double brewElapsedSeconds,
        int scorchCount) {
}
