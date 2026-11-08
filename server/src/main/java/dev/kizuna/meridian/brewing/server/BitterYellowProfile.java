package dev.kizuna.meridian.brewing.server;

/** 苦黄膏一档品质的治疗、药封和反胃参数。 */
public record BitterYellowProfile(
        double durationSeconds,
        double healingPerSecond,
        double totalHealing,
        double tempoMultiplier,
        double strengthMultiplier,
        double nauseaSeconds) {
}
