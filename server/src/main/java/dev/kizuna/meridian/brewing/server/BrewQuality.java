package dev.kizuna.meridian.brewing.server;

import java.nio.file.Path;

/** 巫药的四档品质，品质组件保存 ordinal 值。 */
public enum BrewQuality {
    POOR(0, 20, 39, "劣质"),
    COMMON(1, 40, 59, "寻常"),
    FINE(2, 60, 79, "精良"),
    SUPERIOR(3, 80, Integer.MAX_VALUE, "优质");

    private final int componentValue;

    public static BrewQuality portableAutomaticLimit(Path config) {
        return BrewConfig.load(config, "便携锅自动品质", (properties, missing) ->
                valueOf(BrewConfig.value(properties, "portable.auto_max_quality", missing)));
    }
    private final int minimumScore;
    private final int maximumScore;
    private final String displayName;

    BrewQuality(int componentValue, int minimumScore, int maximumScore, String displayName) {
        // 组件值与目录显示名同时固定，避免序号和显示顺序分离。
        this.componentValue = componentValue;
        this.minimumScore = minimumScore;
        this.maximumScore = maximumScore;
        this.displayName = displayName;
    }

    public int componentValue() {
        // 0 到 3 是物品数据组件的稳定编码。
        return componentValue;
    }

    public int minimumScore() {
        // 质量边界来自炼药五项总分。
        return minimumScore;
    }

    public int maximumScore() {
        // 仅用于验证配置和报告中的品质区间。
        return maximumScore;
    }

    public String displayName() {
        // 提示文本由本仓提供，客户端只负责显示语言键。
        return displayName;
    }

    /** 物品组件里的这个值是不是某一档品质；不认得的值不算巫药，见 {@link #fromComponentValue}。 */
    public static boolean isComponentValue(int value) {
        // 和 fromComponentValue 查同一张表，只是不认得时返回 false 而不抛。
        for (BrewQuality quality : values()) {
            if (quality.componentValue == value) {
                return true;
            }
        }
        return false;
    }

    public static BrewQuality fromComponentValue(int value) {
        // 非法组件值不能静默降级为某一档。
        for (BrewQuality quality : values()) {
            if (quality.componentValue == value) {
                return quality;
            }
        }
        throw new IllegalArgumentException("未知巫药品质组件值：" + value);
    }

    static BrewQuality fromScore(int score, CatalystState catalystState) {
        // 优质必须同时满足分数和引药条件，其余档位只看总分。
        if (score < POOR.minimumScore) {
            throw new IllegalArgumentException("低于药渣阈值的分数不能直接映射品质：" + score);
        }
        if (score >= SUPERIOR.minimumScore && catalystState == CatalystState.PRESENT) {
            return SUPERIOR;
        }
        if (score >= FINE.minimumScore) {
            return FINE;
        }
        if (score >= COMMON.minimumScore) {
            return COMMON;
        }
        return POOR;
    }
}
