package dev.kizuna.meridian.brewing.server;

/** 炮制药材随游戏日衰减的药性曲线。 */
public final class IngredientFreshness {
    public static final long FULL_DAYS = 3L;
    public static final long DECAY_END_DAY = 10L;
    public static final double MINIMUM_MULTIPLIER = 0.6;

    private IngredientFreshness() {
        // 药性曲线是无状态的纯规则。
    }

    public static double multiplier(long preparedGameDay, long currentGameDay) {
        long age = ageDays(preparedGameDay, currentGameDay);
        if (age <= FULL_DAYS) {
            return 1.0;
        }
        if (age >= DECAY_END_DAY) {
            return MINIMUM_MULTIPLIER;
        }
        double decayDays = DECAY_END_DAY - FULL_DAYS - 1;
        double elapsedDecay = age - FULL_DAYS;
        double decayRange = 1.0 - MINIMUM_MULTIPLIER;
        return 1.0 - decayRange * elapsedDecay / decayDays;
    }

    public static long ageDays(long preparedGameDay, long currentGameDay) {
        if (preparedGameDay < 0 || currentGameDay < preparedGameDay) {
            throw new IllegalArgumentException("炮制日期不能晚于当前日期");
        }
        return currentGameDay - preparedGameDay;
    }
}
