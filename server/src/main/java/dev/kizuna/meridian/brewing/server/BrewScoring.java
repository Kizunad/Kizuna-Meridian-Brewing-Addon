package dev.kizuna.meridian.brewing.server;

import java.util.Optional;

/** 按旧稿五项规则计算炼药结果；分数只在服务端生成品质时使用。 */
public final class BrewScoring {
    public static final int INGREDIENT_MAX = 25;
    public static final int SOAK_MAX = 10;
    public static final int HEAT_MAX = 30;
    public static final int TIMING_MAX = 20;
    public static final int COLLECTION_MAX = 15;
    public static final int SCORCH_PENALTY = 10;
    public static final int SCRAP_THRESHOLD = 20;
    public static final double RAW_INGREDIENT_MULTIPLIER = 0.6;
    public static final double PREPARED_INGREDIENT_MULTIPLIER = 1.0;
    public static final double SOAK_SECONDS = 10.0;
    public static final double ACCESSORY_IN_WINDOW_SCORE = 12.0;
    public static final double ACCESSORY_NEAR_WINDOW_SCORE = 6.0;
    public static final double CATALYST_IN_WINDOW_SCORE = 8.0;
    public static final double CATALYST_NEAR_WINDOW_SCORE = 4.0;
    public static final double NEAR_WINDOW_SECONDS = 4.0;
    private static final int SCORE_MAX = 100;

    private BrewScoring() {
        // 评分是无状态纯规则。
    }

    public static BrewScore calculate(BrewScoreInput input) {
        // 焦锅扣分单独记录，避免把火分和焦罚混在一起而无法解释结果。
        int scorchPenalty = input.scorchCount() * SCORCH_PENALTY;
        int rawTotal = input.ingredientScore() + input.soakScore() + input.heatScore()
                + input.timingScore() + input.collectionScore() - scorchPenalty;
        int totalScore = clamp(rawTotal, 0, SCORE_MAX);
        Optional<BrewQuality> quality = Optional.empty();
        if (totalScore >= SCRAP_THRESHOLD) {
            quality = Optional.of(quality(totalScore, input.catalystRequirement()));
        }
        return new BrewScore(input.ingredientScore(), input.soakScore(), input.heatScore(),
                input.timingScore(), input.collectionScore(), scorchPenalty, totalScore,
                quality, input.catalystRequirement());
    }

    public static boolean isScrap(BrewScoreInput input) {
        // 药渣由总分低于品质下限决定，不允许客户端指定结果。
        return total(input) < SCRAP_THRESHOLD;
    }

    public static int total(BrewScoreInput input) {
        // 对外提供同一套总分计算，测试和熬药状态机不会重复实现扣焦逻辑。
        return clamp(input.ingredientScore() + input.soakScore() + input.heatScore()
                + input.timingScore() + input.collectionScore()
                - input.scorchCount() * SCORCH_PENALTY, 0, SCORE_MAX);
    }

    public static int ingredientScore(IngredientBatch primary, IngredientBatch accessory,
                                      IngredientBatch catalyst, long currentGameDay) {
        if (primary == null || currentGameDay < 0) {
            throw new IllegalArgumentException("料分输入不完整");
        }
        // 没下辅料时它在平均里算 0，分母照旧；引药可以不下，不下就只平均主料和辅料两味。
        double totalMultiplier = primary.scoreMultiplier(currentGameDay);
        if (accessory != null) {
            totalMultiplier += PREPARED_INGREDIENT_MULTIPLIER;
        }
        int ingredientCount = 2;
        if (catalyst != null) {
            totalMultiplier += PREPARED_INGREDIENT_MULTIPLIER;
            ingredientCount++;
        }
        return (int) Math.round(INGREDIENT_MAX * totalMultiplier / ingredientCount);
    }

    public static int timingScore(TimingWindow accessoryWindow, Double accessorySeconds,
                                  TimingWindow catalystWindow, Double catalystSeconds) {
        if (accessoryWindow == null || catalystWindow == null) {
            throw new IllegalArgumentException("时分输入不完整");
        }
        int score = 0;
        if (accessorySeconds != null) {
            score = windowScore(accessoryWindow, accessorySeconds,
                    ACCESSORY_IN_WINDOW_SCORE, ACCESSORY_NEAR_WINDOW_SCORE);
        }
        if (catalystSeconds != null) {
            if (!Double.isFinite(catalystSeconds)) {
                throw new IllegalArgumentException("引药时间不是有限值");
            }
            score += windowScore(catalystWindow, catalystSeconds,
                    CATALYST_IN_WINDOW_SCORE, CATALYST_NEAR_WINDOW_SCORE);
        }
        return score;
    }

    private static int windowScore(TimingWindow window, double seconds, double inWindow, double nearWindow) {
        if (window.contains(seconds)) {
            return (int) inWindow;
        }
        if (window.distanceTo(seconds) <= NEAR_WINDOW_SECONDS) {
            return (int) nearWindow;
        }
        return 0;
    }

    private static BrewQuality quality(int totalScore, CatalystRequirement catalystRequirement) {
        CatalystState catalystState = CatalystState.ABSENT;
        if (catalystRequirement == CatalystRequirement.INCLUDED) {
            catalystState = CatalystState.PRESENT;
        }
        return BrewQuality.fromScore(totalScore, catalystState);
    }

    private static int clamp(int value, int minimum, int maximum) {
        // 分数边界固定在 0 到 100，避免焦罚造成负数品质。
        return Math.max(minimum, Math.min(maximum, value));
    }
}
