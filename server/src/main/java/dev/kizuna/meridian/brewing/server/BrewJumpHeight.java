package dev.kizuna.meridian.brewing.server;

/** 按原版逐 tick 的重力与空气阻力，把目标跳跃高度换算成起跳速度。 */
public final class BrewJumpHeight {
    private static final double AIR_DRAG = 0.98;
    private static final int SEARCH_STEPS = 40;
    private static final int MAX_ASCENT_TICKS = 1_000;
    private static final double BISECTION_DIVISOR = 2;

    private BrewJumpHeight() {
        // 单纯换算起跳速度，不维护玩家运动状态。
    }

    public static float power(float original, double gravity, double heightMultiplier) {
        // 高度与初速度并非线性关系；用相同离散运动规则求出缩短后的顶点。
        if (original <= 0 || gravity <= 0 || heightMultiplier == 1) {
            return original;
        }
        double target = height(original, gravity) * heightMultiplier;
        double low = 0;
        double high = original;
        for (int iteration = 0; iteration < SEARCH_STEPS; iteration++) {
            double middle = (low + high) / BISECTION_DIVISOR;
            if (height(middle, gravity) > target) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return (float) ((low + high) / BISECTION_DIVISOR);
    }

    private static double height(double initialVelocity, double gravity) {
        // 起跳后的每 tick 先移动，再扣重力并乘空气阻力，直到上升结束。
        double height = 0;
        double velocity = initialVelocity;
        for (int tick = 0; tick < MAX_ASCENT_TICKS && velocity > 0; tick++) {
            height += velocity;
            velocity = (velocity - gravity) * AIR_DRAG;
        }
        return height;
    }
}
