package dev.kizuna.meridian.brewing.server;

/** 一锅药的耗元账：这锅一共要多少，已经从环境和施法者自身各付了多少（元）。 */
public record BrewQiTally(double total, double ambient, double self) {
    public BrewQiTally {
        // 三个数都是有限非负数；付出的不会超过总数太多，只容浮点尾差。
        if (!(total >= 0) || !(ambient >= 0) || !(self >= 0)
                || !Double.isFinite(total) || !Double.isFinite(ambient) || !Double.isFinite(self)) {
            throw new IllegalArgumentException("耗元账必须是有限非负数");
        }
    }

    public static BrewQiTally start(double total) {
        // 起熬时还没付任何一份。
        return new BrewQiTally(total, 0, 0);
    }

    public double paid() {
        return ambient + self;
    }

    public double nextShare() {
        // 每推进一秒付总数的 1/30；前面付少了不补，付够了就停。
        double share = total / BrewCauldron.BREW_SECONDS;
        return Math.max(0, Math.min(share, total - paid()));
    }

    public BrewQiTally pay(double fromAmbient, double fromSelf) {
        // 这一秒实际取到的两份记进账里。
        return new BrewQiTally(total, ambient + fromAmbient, self + fromSelf);
    }
}
