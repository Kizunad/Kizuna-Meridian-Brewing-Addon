package dev.kizuna.meridian.brewing.server;

import java.nio.file.Path;
import java.util.Properties;
import java.util.function.BiConsumer;

/** 熬药耗真元的配置：开关、每锅基数（元/锅）和容许欠付的秒数，数值都是占位。 */
public record BrewQiSettings(boolean enabled, double costPerBrew, double graceSeconds) {
    static final String ENABLED = "qi.enabled";
    static final String COST_PER_BREW = "qi.cost_per_brew";
    static final String GRACE_SECONDS = "qi.grace_seconds";

    public BrewQiSettings {
        // 基数和宽限都是有限非负数；基数为零等于这锅不耗元，宽限为零等于一付不满就断锅。
        if (!Double.isFinite(costPerBrew) || costPerBrew < 0) {
            throw new IllegalArgumentException("炼药配置数值无效：" + COST_PER_BREW);
        }
        if (!Double.isFinite(graceSeconds) || graceSeconds < 0) {
            throw new IllegalArgumentException("炼药配置数值无效：" + GRACE_SECONDS);
        }
    }

    public static BrewQiSettings load(Path path) {
        return BrewConfig.load(path, "耗元", BrewQiSettings::read);
    }

    public static BrewQiSettings read(Properties properties, BiConsumer<String, String> missing) {
        // 有键就按键读，非法值照常拒绝；缺键交给调用方提示，值取默认资源。
        String enabled = BrewConfig.value(properties, ENABLED, missing);
        boolean on;
        if (enabled.equals("true")) {
            on = true;
        } else if (enabled.equals("false")) {
            on = false;
        } else {
            throw new IllegalArgumentException(ENABLED + " 必须为 true 或 false");
        }
        double cost = Double.parseDouble(BrewConfig.value(properties, COST_PER_BREW, missing));
        double grace = Double.parseDouble(BrewConfig.value(properties, GRACE_SECONDS, missing));
        return new BrewQiSettings(on, cost, grace);
    }

    public double brewCost(VesselKind vessel) {
        // 每锅基数乘釜的系数；关掉耗元时这锅不要真元。
        if (!enabled) {
            return 0;
        }
        return costPerBrew * vessel.qiCost();
    }
}
