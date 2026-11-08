package dev.kizuna.meridian.brewing.server;

/** 聚气汤一档品质的吐纳倍率和肺虚时长。 */
public record QiGatherProfile(
        double durationSeconds,
        double intakeMultiplier,
        double exhaustionDurationSeconds) {
}
