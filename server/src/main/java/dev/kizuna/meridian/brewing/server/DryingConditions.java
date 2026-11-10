package dev.kizuna.meridian.brewing.server;

/** 晒药匾是否具备露天晒料条件。 */
public record DryingConditions(DaylightPhase daylight, DryWeather weather, boolean exposedToSky) {
    public DryingConditions {
        if (daylight == null || weather == null) {
            throw new IllegalArgumentException("晒料条件不完整");
        }
    }

    public boolean canDry() {
        return daylight == DaylightPhase.DAY && weather == DryWeather.CLEAR && exposedToSky;
    }
}
