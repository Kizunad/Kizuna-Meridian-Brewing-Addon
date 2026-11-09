package dev.kizuna.meridian.brewing.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 肾脏和脑力档位由服务端配置统一决定。 */
public record BrewThresholds(Kidneys kidneys, Brain brain, PlayerCharm playerCharm) {
    public enum PlayerCharm { CONTROL, STAGGER }
    public static final BrewThresholds DEFAULT = defaults();

    public static BrewThresholds load(Path path) {
        // 初次启动安装可编辑配置，损坏配置明确报错，不能偷偷使用旧档位。
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                try (InputStream defaults = resource()) {
                    Files.copy(defaults, path);
                }
            }
            try (InputStream input = Files.newInputStream(path)) {
                return read(input);
            }
        } catch (IOException invalid) {
            throw new IllegalStateException("读取炼药档位配置失败", invalid);
        }
    }

    private static BrewThresholds defaults() {
        // 单测与新安装使用同一份资源，数值不在代码中重复维护。
        try (InputStream input = resource()) {
            return read(input);
        } catch (IOException invalid) {
            throw new IllegalStateException("读取炼药默认档位失败", invalid);
        }
    }

    private static InputStream resource() {
        // 默认资源属于必装内容，缺失直接中止初始化。
        InputStream input = BrewThresholds.class.getResourceAsStream("/meridian-brewing.properties");
        if (input == null) {
            throw new IllegalStateException("缺少炼药默认档位配置");
        }
        return input;
    }

    private static BrewThresholds read(InputStream input) throws IOException {
        // 两组边界分别严格递增，避免同一数值同时匹配互相矛盾的档位。
        var properties = new Properties();
        properties.load(input);
        var kidneys = new Kidneys(number(properties, "kidney.ordinary"), number(properties, "kidney.good"),
                number(properties, "kidney.excellent"), number(properties, "kidney.harmless"));
        var brain = new Brain(number(properties, "brain.stagger"), number(properties, "brain.ignore"));
        String controls = properties.getProperty("charm_controls_players", "true");
        PlayerCharm playerCharm;
        if (controls.equals("true")) {
            playerCharm = PlayerCharm.CONTROL;
        } else if (controls.equals("false")) {
            playerCharm = PlayerCharm.STAGGER;
        } else {
            throw new IllegalArgumentException("charm_controls_players 必须为 true 或 false");
        }
        return new BrewThresholds(kidneys, brain, playerCharm);
    }

    private static double number(Properties properties, String key) {
        // 所有必需字段都必须是有限非负数，缺字段不隐式选择默认玩法。
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("炼药配置缺少字段：" + key);
        }
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number) || number < 0) {
            throw new IllegalArgumentException("炼药配置数值无效：" + key);
        }
        return number;
    }

    public record Kidneys(double ordinary, double good, double excellent, double harmless) {
        public Kidneys {
            // 四条下界从常到无害递增，最低档涵盖其余非负排毒力。
            if (!(ordinary < good && good < excellent && excellent < harmless)) {
                throw new IllegalArgumentException("肾脏档位界线必须严格递增");
            }
        }
    }

    public record Brain(double stagger, double ignore) {
        public Brain {
            // 强档必须高于良好档。
            if (!(stagger < ignore)) {
                throw new IllegalArgumentException("脑力档位界线必须严格递增");
            }
        }
    }
}
