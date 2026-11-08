package dev.kizuna.meridian.brewing.server;

/** 药方中一味辅料或引药的投料时窗，单位为熬药开始后的秒数。 */
public record TimingWindow(double startSeconds, double endSeconds) {
    public TimingWindow {
        // 时窗必须从非负时刻开始且保持顺序。
        if (!Double.isFinite(startSeconds) || !Double.isFinite(endSeconds)
                || startSeconds < 0 || startSeconds >= endSeconds) {
            throw new IllegalArgumentException("非法投料时窗：" + startSeconds + "-" + endSeconds);
        }
    }

    public boolean contains(double elapsedSeconds) {
        // 时窗采用闭区间，服务端 tick 落在边界时仍可投料。
        return elapsedSeconds >= startSeconds && elapsedSeconds <= endSeconds;
    }

    public double distanceTo(double elapsedSeconds) {
        // 早晚偏差用于评分，不把时窗外的投料时间裁剪成时窗内。
        if (elapsedSeconds < startSeconds) {
            return startSeconds - elapsedSeconds;
        }
        if (elapsedSeconds > endSeconds) {
            return elapsedSeconds - endSeconds;
        }
        return 0;
    }
}
