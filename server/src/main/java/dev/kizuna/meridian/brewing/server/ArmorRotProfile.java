package dev.kizuna.meridian.brewing.server;

/** 蚀甲水一档品质的效果表。 */
public record ArmorRotProfile(
        double radius,
        double durationSeconds,
        int minimumParts,
        int maximumParts,
        double durabilityPercentPerSecond,
        double armorMultiplier) {
}
