package dev.kizuna.meridian.brewing.server;

/**
 * 一锅药的耗元账：这锅一共要多少（元），容许欠几秒的份，已经推进了几秒，从环境和施法者自身各付了多少。
 *
 * <p>每推进一秒应付到总数的 1/30 乘已推进秒数；这一秒付不满时差额记作欠付，之后每秒连本带欠一起要。
 * 欠付超过 {@code graceSeconds} 秒的份才断锅；熬成时还欠着的（不超过宽限）不再追。</p>
 */
public record BrewQiTally(double total, double graceSeconds, int seconds, double ambient, double self) {
    /** 浮点尾差；比这小的欠付按付清算。 */
    private static final double TOLERANCE = 1e-9;

    public BrewQiTally {
        // 数额都是有限非负数，秒数不为负。
        if (!finiteNonNegative(total) || !finiteNonNegative(graceSeconds)
                || !finiteNonNegative(ambient) || !finiteNonNegative(self) || seconds < 0) {
            throw new IllegalArgumentException("耗元账必须是有限非负数");
        }
    }

    public static BrewQiTally start(double total, double graceSeconds) {
        // 起熬时还没推进、没付任何一份。
        return new BrewQiTally(total, graceSeconds, 0, 0, 0);
    }

    public double paid() {
        return ambient + self;
    }

    /** 一秒的份额：总数的 1/30。 */
    public double share() {
        return total / BrewCauldron.BREW_SECONDS;
    }

    /** 推进到现在应付的，不超过总数。 */
    public double due() {
        return Math.min(total, share() * seconds);
    }

    /** 欠付：应付减已付，不为负。 */
    public double arrears() {
        return Math.max(0, due() - paid());
    }

    /** 容许欠付的上限：宽限秒数乘一秒的份额。 */
    public double graceQi() {
        return graceSeconds * share();
    }

    public boolean owing() {
        return arrears() > TOLERANCE;
    }

    /** 欠付超过宽限：调用方中断这锅。 */
    public boolean beyondGrace() {
        return arrears() > graceQi() + TOLERANCE;
    }

    public double nextPayment() {
        // 下一秒推进完应付到的数减已付：这一秒的份额加前面欠的，付够总数就停。
        double dueAfter = Math.min(total, share() * (seconds + 1));
        return Math.max(0, dueAfter - paid());
    }

    public BrewQiTally pay(double fromAmbient, double fromSelf) {
        // 推进一秒，这一秒实际取到的两份记进账里；取到零也算推进过。
        return new BrewQiTally(total, graceSeconds, seconds + 1, ambient + fromAmbient, self + fromSelf);
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }
}
