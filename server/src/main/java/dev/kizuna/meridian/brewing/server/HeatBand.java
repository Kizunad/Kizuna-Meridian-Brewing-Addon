package dev.kizuna.meridian.brewing.server;

/** 药方要求的温度带，单位为摄氏度。 */
public record HeatBand(double minimumDegrees, double maximumDegrees) {
    public HeatBand {
        // 温度带必须有正宽度，避免火候分数出现除零或反向区间。
        if (!Double.isFinite(minimumDegrees) || !Double.isFinite(maximumDegrees)
                || minimumDegrees < 0 || maximumDegrees > 100 || minimumDegrees >= maximumDegrees) {
            throw new IllegalArgumentException("非法火候带：" + minimumDegrees + "-" + maximumDegrees);
        }
    }

    public boolean contains(double temperature) {
        // 上下沿都属于可熬区间，便于温度取整后的边界判定。
        return temperature >= minimumDegrees && temperature <= maximumDegrees;
    }
}
