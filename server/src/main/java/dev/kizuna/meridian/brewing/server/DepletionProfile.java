package dev.kizuna.meridian.brewing.server;

/** 枯竭药水一档品质的效果表。 */
public record DepletionProfile(
        double radius,
        double durationSeconds,
        int minimumParts,
        int maximumParts,
        double muscleEnduranceMultiplier,
        double partDamageHp,
        double tempoMultiplier) {
}
