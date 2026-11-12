package dev.kizuna.meridian.brewing.server;

/**
 * 火室一格：点着时烧的是什么、还剩几秒。没点着时 {@code fuel} 为空、秒数为 0；
 * 点着后不一定每秒都在烧，药塔只让够档数的那几格烧，剩下的秒数留着。
 */
public record FuelCell(TowerFuel fuel, double remainingSeconds) {
    public static final FuelCell UNLIT = new FuelCell(null, 0);

    public FuelCell {
        // 点着的格子一定知道烧的是什么，秒数为正；没点着的格子两样都空。
        if (!Double.isFinite(remainingSeconds) || remainingSeconds < 0) {
            throw new IllegalArgumentException("燃料秒数必须是有限非负数");
        }
        if ((fuel == null) != (remainingSeconds == 0)) {
            throw new IllegalArgumentException("燃料格要么没点着，要么点着且剩余秒数为正");
        }
    }

    public boolean lit() {
        return fuel != null;
    }

    /** 烧 {@code seconds} 秒；烧完就是没点着。 */
    public FuelCell burn(double seconds) {
        // 剩下不到一步的也算这一步烧完。
        double left = remainingSeconds - seconds;
        if (left <= 0) {
            return UNLIT;
        }
        return new FuelCell(fuel, left);
    }
}
