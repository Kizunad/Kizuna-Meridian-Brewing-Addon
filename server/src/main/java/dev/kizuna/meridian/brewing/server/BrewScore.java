package dev.kizuna.meridian.brewing.server;

/** 一瓶药在舀出时的可审计分数和品质。 */
public record BrewScore(
        int ingredientScore,
        int soakScore,
        int heatScore,
        int timingScore,
        int collectionScore,
        int scorchPenalty,
        int totalScore,
        BrewQuality quality,
        CatalystRequirement catalystRequirement) {
}
