package dev.kizuna.meridian.brewing.server;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * 读 {@code meridian-brewing.properties} 里后加的那些键：用户文件里有就用，旧导出的文件缺键时
 * 用 JAR 里的默认值，并在启动时每个键提示一次，不改用户的文件。
 */
final class BrewConfig {
    private static final String DEFAULT_RESOURCE = "/meridian-brewing.properties";
    private static final Logger LOGGER = System.getLogger("meridian_brewing_config");

    private BrewConfig() {
    }

    /** 读用户配置文件，交给 {@code reader} 按键取值；缺的键打一条 WARNING。 */
    static <T> T load(Path path, String what, BiFunction<Properties, BiConsumer<String, String>, T> reader) {
        // 文件由档位配置先装好，这里只读。
        try (InputStream input = Files.newInputStream(path)) {
            var properties = new Properties();
            properties.load(input);
            return reader.apply(properties, (key, fallback) -> LOGGER.log(Level.WARNING,
                    "炼药配置 " + path + " 缺少 " + key + "，先用默认值 " + fallback
                            + "；删掉这个文件重进可得到带新键的完整配置"));
        } catch (IOException invalid) {
            throw new IllegalStateException("读取炼药" + what + "配置失败", invalid);
        }
    }

    /** 取一个键：用户文件有就用，没有就报给 {@code missing} 并取默认资源里的值。 */
    static String value(Properties properties, String key, BiConsumer<String, String> missing) {
        // 默认资源属于必装内容，两边都没有这个键说明 JAR 坏了。
        String value = properties.getProperty(key);
        if (value != null) {
            return value.trim();
        }
        String fallback = defaults().getProperty(key);
        if (fallback == null) {
            throw new IllegalStateException("默认炼药配置缺少字段：" + key);
        }
        missing.accept(key, fallback.trim());
        return fallback.trim();
    }

    private static Properties defaults() {
        // 和档位配置读同一份默认资源，数值不在代码里重复。
        try (InputStream input = BrewConfig.class.getResourceAsStream(DEFAULT_RESOURCE)) {
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
