package dev.kizuna.meridian.brewing.server;

/** 投入巫釜的一份物品及其炮制日期。 */
public record IngredientBatch(String itemId, PreparationKind preparation, long preparedGameDay) {
    private static final long UNPREPARED_DAY = -1L;

    public IngredientBatch {
        if (itemId == null || itemId.isBlank() || preparation == null) {
            throw new IllegalArgumentException("投料信息不完整");
        }
        if (preparation == PreparationKind.RAW && preparedGameDay != UNPREPARED_DAY) {
            throw new IllegalArgumentException("生料不能带炮制日期");
        }
        if (preparation == PreparationKind.PREPARED && preparedGameDay < 0) {
            throw new IllegalArgumentException("炮制料必须带制成日");
        }
    }

    public static IngredientBatch raw(String itemId) {
        return new IngredientBatch(itemId, PreparationKind.RAW, UNPREPARED_DAY);
    }

    public static IngredientBatch prepared(String itemId, long preparedGameDay) {
        return new IngredientBatch(itemId, PreparationKind.PREPARED, preparedGameDay);
    }

    public double scoreMultiplier(long currentGameDay) {
        if (preparation == PreparationKind.RAW) {
            return BrewScoring.RAW_INGREDIENT_MULTIPLIER;
        }
        return BrewScoring.PREPARED_INGREDIENT_MULTIPLIER
                * IngredientFreshness.multiplier(preparedGameDay, currentGameDay);
    }
}
