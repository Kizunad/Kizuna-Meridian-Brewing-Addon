package dev.kizuna.meridian.brewing.server;

/** 五项炼药分数的输入，所有数值均是已经按规则折算后的分值。 */
public record BrewScoreInput(
        int ingredientScore,
        int soakScore,
        int heatScore,
        int timingScore,
        int collectionScore,
        int scorchCount,
        CatalystRequirement catalystRequirement) {
    public BrewScoreInput {
        // 每一项先限制在各自的满分内，焦锅扣分单独保留以便审计。
        if (ingredientScore < 0 || ingredientScore > BrewScoring.INGREDIENT_MAX) {
            throw new IllegalArgumentException("料分超出范围：" + ingredientScore);
        }
        if (soakScore < 0 || soakScore > BrewScoring.SOAK_MAX) {
            throw new IllegalArgumentException("浸分超出范围：" + soakScore);
        }
        if (heatScore < 0 || heatScore > BrewScoring.HEAT_MAX) {
            throw new IllegalArgumentException("火分超出范围：" + heatScore);
        }
        if (timingScore < 0 || timingScore > BrewScoring.TIMING_MAX) {
            throw new IllegalArgumentException("时分超出范围：" + timingScore);
        }
        if (collectionScore < 0 || collectionScore > BrewScoring.COLLECTION_MAX) {
            throw new IllegalArgumentException("收分超出范围：" + collectionScore);
        }
        if (scorchCount < 0 || catalystRequirement == null) {
            throw new IllegalArgumentException("炼药输入不完整");
        }
    }
}
