package dev.kizuna.meridian.brewing.server;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.function.BiConsumer;

/**
 * 药塔的配置：出药品质上限（默认精良，优质只能手熬），以及火室每格燃料能烧几秒，数值都是占位。
 * 一批几瓶、几套料、耗元系数跟着 {@link VesselKind#TOWER} 走，不再单列。
 */
public record BrewTowerSettings(BrewQuality qualityCap, double coalBlockSeconds, double logSeconds,
                                double lavaSeconds, double fireSeconds) {
    static final String QUALITY_CAP = "tower.quality_cap";
    static final String COAL_BLOCK_SECONDS = "tower.fuel.coal_block_seconds";
    static final String LOG_SECONDS = "tower.fuel.log_seconds";
    static final String LAVA_SECONDS = "tower.fuel.lava_seconds";
    static final String FIRE_SECONDS = "tower.fuel.fire_seconds";

    public BrewTowerSettings {
        // 品质上限必须给；每种燃料至少能烧一点，不然点着就灭。
        if (qualityCap == null) {
            throw new IllegalArgumentException("炼药配置数值无效：" + QUALITY_CAP);
        }
        requirePositive(coalBlockSeconds, COAL_BLOCK_SECONDS);
        requirePositive(logSeconds, LOG_SECONDS);
        requirePositive(lavaSeconds, LAVA_SECONDS);
        requirePositive(fireSeconds, FIRE_SECONDS);
    }

    public static BrewTowerSettings load(Path path) {
        return BrewConfig.load(path, "药塔", BrewTowerSettings::read);
    }

    public static BrewTowerSettings read(Properties properties, BiConsumer<String, String> missing) {
        // 品质写枚举名（POOR / COMMON / FINE / SUPERIOR），大小写都认；秒数写小数。
        String cap = BrewConfig.value(properties, QUALITY_CAP, missing).toUpperCase(Locale.ROOT);
        return new BrewTowerSettings(BrewQuality.valueOf(cap),
                seconds(properties, COAL_BLOCK_SECONDS, missing),
                seconds(properties, LOG_SECONDS, missing),
                seconds(properties, LAVA_SECONDS, missing),
                seconds(properties, FIRE_SECONDS, missing));
    }

    /** 这种燃料一格能烧几秒。 */
    public double secondsFor(TowerFuel fuel) {
        return switch (fuel) {
            case COAL_BLOCK -> coalBlockSeconds;
            case LOG -> logSeconds;
            case LAVA -> lavaSeconds;
            case FIRE -> fireSeconds;
        };
    }

    /** 熬出来的品质压到上限以内。 */
    public BrewQuality cap(BrewQuality quality) {
        if (quality.compareTo(qualityCap) > 0) {
            return qualityCap;
        }
        return quality;
    }

    private static double seconds(Properties properties, String key, BiConsumer<String, String> missing) {
        return Double.parseDouble(BrewConfig.value(properties, key, missing));
    }

    private static void requirePositive(double seconds, String key) {
        if (!Double.isFinite(seconds) || seconds <= 0) {
            throw new IllegalArgumentException("炼药配置数值无效：" + key);
        }
    }
}
