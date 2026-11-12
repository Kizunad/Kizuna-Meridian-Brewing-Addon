package dev.kizuna.meridian.brewing.server;

import java.nio.file.Path;
import java.util.Properties;
import java.util.function.BiConsumer;

/** 熬药结构的配置：外框最多可以缺几格还算有效（D6 默认 0，服主想放宽可以改）。 */
public record BrewStructureSettings(int frameTolerance) {
    static final String FRAME_TOLERANCE = "structure.frame_tolerance";

    public BrewStructureSettings {
        if (frameTolerance < 0) {
            throw new IllegalArgumentException("炼药配置数值无效：" + FRAME_TOLERANCE);
        }
    }

    public static BrewStructureSettings load(Path path) {
        return BrewConfig.load(path, "结构", BrewStructureSettings::read);
    }

    public static BrewStructureSettings read(Properties properties, BiConsumer<String, String> missing) {
        // 容差是非负整数；写成小数或负数照常拒绝。
        return new BrewStructureSettings(Integer.parseInt(BrewConfig.value(properties, FRAME_TOLERANCE, missing)));
    }
}
