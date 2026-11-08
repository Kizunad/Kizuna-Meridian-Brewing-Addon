package dev.kizuna.meridian.brewing.server;

/** 招虫水一档品质的效果表。 */
public record InsectLureProfile(
        double radius,
        double durationSeconds,
        double hearingDistance,
        int silverfishCount) {
}
