package dev.kizuna.meridian.brewing.server;

/** 巫釜的一锅药在生命周期中的阶段。 */
public enum BrewCauldronState {
    EMPTY,
    WATER,
    RAW,
    BREWING,
    BREWED,
    SCORCHED
}
