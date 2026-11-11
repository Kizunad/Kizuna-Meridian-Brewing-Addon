package dev.kizuna.meridian.brewing.server;

import java.util.Optional;

/** 一瓶药在舀出时的可审计分数和品质；总分不到药渣线时没有品质。 */
public record BrewScore(
        int ingredientScore,
        int soakScore,
        int heatScore,
        int timingScore,
        int collectionScore,
        int scorchPenalty,
        int totalScore,
        Optional<BrewQuality> quality,
        CatalystRequirement catalystRequirement) {
}
