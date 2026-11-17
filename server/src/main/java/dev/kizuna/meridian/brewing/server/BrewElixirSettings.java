package dev.kizuna.meridian.brewing.server;

import dev.kizuna.meridian.core.CapabilityModifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;

/**
 * 五行丹露的数：四档品质各给多少本行真元、丹毒留多久、丹毒在身再服时这一次打几折、丹毒最多几级。
 * 都在 {@code meridian-brewing.properties} 的 {@code elixir.*} 里。
 */
public record BrewElixirSettings(Map<BrewQuality, Double> qi, double toxinSeconds, double repeatFactor,
                                 int maxToxinLevel, Toxins toxins) {
    private static final String QI = "elixir.qi.";
    private static final String TOXIN_SECONDS = "elixir.toxin_seconds";
    private static final String REPEAT_FACTOR = "elixir.repeat_factor";
    private static final String MAX_TOXIN_LEVEL = "elixir.max_toxin_level";
    private static final String FIRE_BURN_LEVEL = "elixir.fire.burn_level";
    private static final String FIRE_BURN_INTERVAL = "elixir.fire.burn_interval_seconds";
    private static final String FIRE_BURN_HP = "elixir.fire.burn_hp";
    private static final String EARTH_SPRINT_LEVEL = "elixir.earth.sprint_level";

    /** 火行丹毒从指定等级起，按间隔造成胸口热伤。 */
    public record Burn(int level, double intervalSeconds, double hp) {
        public Burn {
            // 灼伤等级至少一级，间隔和伤害必须是有限正数。
            if (level < 1 || !Double.isFinite(intervalSeconds) || intervalSeconds <= 0
                    || !Double.isFinite(hp) || hp <= 0) {
                throw new IllegalArgumentException("炼药配置数值无效：elixir.fire.*");
            }
        }
    }

    public record Toxins(Map<BrewKind, List<Double>> multipliers, Burn burn, int blockedSprintLevel) {
        public Toxins {
            // 五行逐级强度共用读表规则；火行增加消耗，其他四行降低能力。
            var copied = new EnumMap<BrewKind, List<Double>>(BrewKind.class);
            for (BrewKind kind : BrewKind.elixirKinds()) {
                List<Double> levels = List.copyOf(multipliers.get(kind));
                if (levels.isEmpty() || levels.stream().anyMatch(value -> !validMultiplier(kind, value))) {
                    throw new IllegalArgumentException("炼药配置数值无效：elixir." + kind.elementPath());
                }
                copied.put(kind, levels);
            }
            multipliers = Map.copyOf(copied);
            if (burn == null || blockedSprintLevel < 1) {
                throw new IllegalArgumentException("炼药配置数值无效：elixir.*");
            }
        }

        public double multiplier(BrewKind kind, int level) {
            // 无丹毒保持原值，超出配置表的等级沿用最后一档。
            if (level <= 0) {
                return 1;
            }
            List<Double> values = multipliers.get(kind);
            return values.get(Math.min(level, values.size()) - 1);
        }
    }

    private static boolean validMultiplier(BrewKind kind, double value) {
        // 金毒走身体能力修正，加载时就遵守其下限；饥饿消耗不能因丹毒而减少。
        if (!Double.isFinite(value)) {
            return false;
        }
        if (kind == BrewKind.FIRE_ELIXIR) {
            return value >= 1;
        }
        if (kind == BrewKind.METAL_ELIXIR) {
            return value >= CapabilityModifier.MIN_MULTIPLIER && value <= 1;
        }
        return value > 0 && value <= 1;
    }

    public BrewElixirSettings {
        // 四档都要有、都为正；丹毒时长为正；折扣在 0 到 1 之间；最高级至少 1。
        qi = Map.copyOf(qi);
        boolean amounts = qi.size() == BrewQuality.values().length
                && qi.values().stream().allMatch(amount -> Double.isFinite(amount) && amount > 0);
        if (!amounts || !Double.isFinite(toxinSeconds) || toxinSeconds <= 0
                || !(repeatFactor >= 0 && repeatFactor <= 1) || maxToxinLevel < 1 || toxins == null) {
            throw new IllegalArgumentException("炼药配置数值无效：elixir.*");
        }
    }

    public static BrewElixirSettings load(Path path) {
        // 与其他炼药规则共用配置文件及缺键提示。
        return BrewConfig.load(path, "丹露", BrewElixirSettings::read);
    }

    public static BrewElixirSettings read(Properties properties, BiConsumer<String, String> missing) {
        // 有键就按键读，非法值照常拒绝；缺键交给调用方提示，值取默认资源。
        var qi = new EnumMap<BrewQuality, Double>(BrewQuality.class);
        for (BrewQuality quality : BrewQuality.values()) {
            String key = QI + quality.name().toLowerCase(Locale.ROOT);
            qi.put(quality, Double.parseDouble(BrewConfig.value(properties, key, missing)));
        }
        return new BrewElixirSettings(qi,
                Double.parseDouble(BrewConfig.value(properties, TOXIN_SECONDS, missing)),
                Double.parseDouble(BrewConfig.value(properties, REPEAT_FACTOR, missing)),
                Integer.parseInt(BrewConfig.value(properties, MAX_TOXIN_LEVEL, missing)),
                toxins(properties, missing));
    }

    private static Toxins toxins(Properties properties, BiConsumer<String, String> missing) {
        // 各行只声明作用维度，逐级数值统一从配置读取。
        var multipliers = new EnumMap<BrewKind, List<Double>>(BrewKind.class);
        for (BrewKind kind : BrewKind.elixirKinds()) {
            String dimension = switch (kind) {
                case FIRE_ELIXIR -> "exhaustion";
                case METAL_ELIXIR -> "intake";
                case WOOD_ELIXIR -> "attack_speed";
                case WATER_ELIXIR -> "movement_speed";
                case EARTH_ELIXIR -> "jump_height";
                default -> throw new IllegalArgumentException("不是丹露：" + kind);
            };
            String key = "elixir." + kind.elementPath() + "." + dimension;
            List<Double> values = Arrays.stream(BrewConfig.value(properties, key, missing).split(","))
                    .map(text -> Double.parseDouble(text.trim())).toList();
            multipliers.put(kind, values);
        }
        var burn = new Burn(
                Integer.parseInt(BrewConfig.value(properties, FIRE_BURN_LEVEL, missing)),
                Double.parseDouble(BrewConfig.value(properties, FIRE_BURN_INTERVAL, missing)),
                Double.parseDouble(BrewConfig.value(properties, FIRE_BURN_HP, missing)));
        int blockedSprintLevel = Integer.parseInt(BrewConfig.value(properties, EARTH_SPRINT_LEVEL, missing));
        return new Toxins(multipliers, burn, blockedSprintLevel);
    }

    /**
     * 这一次服下给多少本行真元。{@code toxinLevel} 是服之前身上这一行丹毒的级数（没有是 0）：
     * 没有丹毒给足量；有丹毒打折；已经到最高级，这一次一点不给。
     */
    public double dose(BrewQuality quality, int toxinLevel) {
        // 只读取同一行的丹毒等级，满级后不再入账。
        double full = qi.get(quality);
        if (toxinLevel <= 0) {
            return full;
        }
        if (toxinLevel >= maxToxinLevel) {
            return 0;
        }
        return full * repeatFactor;
    }

    /** 服下以后丹毒是几级：没有的挂一级，有的升一级，到顶不再升。 */
    public int nextToxinLevel(int toxinLevel) {
        // 每瓶升一级并在配置上限停止。
        return Math.min(maxToxinLevel, Math.max(0, toxinLevel) + 1);
    }
}
