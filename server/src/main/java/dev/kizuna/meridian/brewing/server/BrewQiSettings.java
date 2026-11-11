package dev.kizuna.meridian.brewing.server;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.BiConsumer;

/** 熬药耗真元的配置：开关和每锅基数（元/锅），数值都是占位。 */
public record BrewQiSettings(boolean enabled, double costPerBrew) {
    static final String ENABLED = "qi.enabled";
    static final String COST_PER_BREW = "qi.cost_per_brew";
    private static final String DEFAULT_RESOURCE = "/meridian-brewing.properties";
    private static final Logger LOGGER = System.getLogger("meridian_brewing_config");

    public BrewQiSettings {
        // 基数是有限非负数；零等于这锅不耗元。
        if (!Double.isFinite(costPerBrew) || costPerBrew < 0) {
            throw new IllegalArgumentException("炼药配置数值无效：" + COST_PER_BREW);
        }
    }

    public static BrewQiSettings load(Path path) {
        // 文件由档位配置先装好；旧导出的文件没有这两个键时用 JAR 里的默认值，启动时每个键提示一次。
        try (InputStream input = Files.newInputStream(path)) {
            var properties = new Properties();
            properties.load(input);
            return read(properties, (key, fallback) -> LOGGER.log(Level.WARNING,
                    "炼药配置 " + path + " 缺少 " + key + "，先用默认值 " + fallback
                            + "；删掉这个文件重进可得到带新键的完整配置"));
        } catch (IOException invalid) {
            throw new IllegalStateException("读取炼药耗元配置失败", invalid);
        }
    }

    public static BrewQiSettings read(Properties properties, BiConsumer<String, String> missing) {
        // 有键就按键读，非法值照常拒绝；缺键交给调用方提示，值取默认资源。
        Properties defaults = defaults();
        String enabled = value(properties, defaults, ENABLED, missing);
        boolean on;
        if (enabled.equals("true")) {
            on = true;
        } else if (enabled.equals("false")) {
            on = false;
        } else {
            throw new IllegalArgumentException(ENABLED + " 必须为 true 或 false");
        }
        double cost = Double.parseDouble(value(properties, defaults, COST_PER_BREW, missing));
        return new BrewQiSettings(on, cost);
    }

    public double brewCost(VesselKind vessel) {
        // 每锅基数乘釜的系数；关掉耗元时这锅不要真元。
        if (!enabled) {
            return 0;
        }
        return costPerBrew * vessel.qiCost();
    }

    private static String value(Properties properties, Properties defaults, String key,
                                BiConsumer<String, String> missing) {
        // 默认资源属于必装内容，两边都没有这个键说明 JAR 坏了。
        String value = properties.getProperty(key);
        if (value != null) {
            return value.trim();
        }
        String fallback = defaults.getProperty(key);
        if (fallback == null) {
            throw new IllegalStateException("默认炼药配置缺少字段：" + key);
        }
        missing.accept(key, fallback.trim());
        return fallback.trim();
    }

    private static Properties defaults() {
        // 和档位配置读同一份默认资源，数值不在代码里重复。
        try (InputStream input = BrewQiSettings.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("缺少炼药默认配置");
            }
            var properties = new Properties();
            properties.load(input);
            return properties;
        } catch (IOException invalid) {
            throw new IllegalStateException("读取炼药默认配置失败", invalid);
        }
    }
}
