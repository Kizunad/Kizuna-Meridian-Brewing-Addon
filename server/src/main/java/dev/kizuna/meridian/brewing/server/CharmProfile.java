package dev.kizuna.meridian.brewing.server;

/** 魅惑药水每一档的范围、压制和生命周期参数。 */
public record CharmProfile(
        double radius,
        double suppression,
        double staggerSeconds,
        double controlSeconds,
        double immunitySeconds) {
}
