package dev.kizuna.meridian.brewing.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.kizuna.meridian.core.CapabilityModifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

public final class BrewElixirSettingsTest {
    private static final double TOLERANCE = 1e-9;
    private static final List<Double> EXPECTED_QI = List.of(0.6, 1.0, 1.5, 2.4);
    private static final int TOXIN_SECONDS = 300;
    private static final double REPEAT_FACTOR = 0.5;
    private static final int MAX_LEVEL = 3;
    private static final int SECOND_LEVEL = 2;
    private static final int BURN_INTERVAL = 20;
    private static final int CONFIG_KEYS = 16;
    private static final double FINE_QI = 1.5;
    private static final double REPEATED_FINE_QI = 0.75;
    private static final double CUSTOM_REPEATED_QI = 0.5;
    private static final List<Double> FIRE_STRENGTH = List.of(1.5, 2.5, 2.5);
    private static final List<Double> METAL_STRENGTH = List.of(0.8, 0.6, 0.4);
    private static final List<Double> SPEED_STRENGTH = List.of(0.9, 0.8, 0.7);
    private static final List<Double> EARTH_STRENGTH = List.of(0.8, 0.6, 0.6);
    private static final double CUSTOM_PENALTY = 0.75;
    private static final String METAL_INTAKE = "elixir.metal.intake";

    @Test
    public void defaultsGiveFourDosesAndFiveMinuteToxin() {
        // 随包的数：劣质 0.6、寻常 1.0、精良 1.5、优质 2.4；丹毒 300 秒、再服打五折、最多三级。默认资源不缺键。
        var missing = new ArrayList<String>();
        var settings = BrewElixirSettings.read(new Properties(), (key, fallback) -> missing.add(key));
        for (BrewQuality quality : BrewQuality.values()) {
            assertEquals(EXPECTED_QI.get(quality.ordinal()), settings.qi().get(quality), TOLERANCE);
        }
        assertEquals(TOXIN_SECONDS, settings.toxinSeconds(), TOLERANCE);
        assertEquals(REPEAT_FACTOR, settings.repeatFactor(), TOLERANCE);
        assertEquals(MAX_LEVEL, settings.maxToxinLevel());
        // 火行丹毒：一、二、三级饥饿消耗 1.5、2.5、2.5 倍；三级起每 20 秒胸口受 1 点热伤。
        assertEquals(FIRE_STRENGTH, settings.toxins().multipliers().get(BrewKind.FIRE_ELIXIR));
        assertEquals(METAL_STRENGTH, settings.toxins().multipliers().get(BrewKind.METAL_ELIXIR));
        assertEquals(SPEED_STRENGTH, settings.toxins().multipliers().get(BrewKind.WOOD_ELIXIR));
        assertEquals(SPEED_STRENGTH, settings.toxins().multipliers().get(BrewKind.WATER_ELIXIR));
        assertEquals(EARTH_STRENGTH, settings.toxins().multipliers().get(BrewKind.EARTH_ELIXIR));
        assertEquals(MAX_LEVEL, settings.toxins().burn().level());
        assertEquals(BURN_INTERVAL, settings.toxins().burn().intervalSeconds(), TOLERANCE);
        assertEquals(1, settings.toxins().burn().hp(), TOLERANCE);
        // 空的用户文件十六个键全缺，各提示一次，值取默认资源。
        assertEquals(CONFIG_KEYS, missing.size());
    }

    @Test
    public void repeatedDosesHalveThenStopAndToxinClimbsToTheCap() {
        // 没有丹毒给足量、挂一级；一级、二级在身各打五折、升一级；三级在身一点不给，还是三级。
        var settings = BrewElixirSettings.read(new Properties(), (key, fallback) -> { });
        assertEquals(FINE_QI, settings.dose(BrewQuality.FINE, 0), TOLERANCE);
        assertEquals(1, settings.nextToxinLevel(0));
        assertEquals(REPEATED_FINE_QI, settings.dose(BrewQuality.FINE, 1), TOLERANCE);
        assertEquals(SECOND_LEVEL, settings.nextToxinLevel(1));
        assertEquals(REPEATED_FINE_QI, settings.dose(BrewQuality.FINE, SECOND_LEVEL), TOLERANCE);
        assertEquals(MAX_LEVEL, settings.nextToxinLevel(SECOND_LEVEL));
        assertEquals(0, settings.dose(BrewQuality.FINE, MAX_LEVEL), TOLERANCE);
        assertEquals(MAX_LEVEL, settings.nextToxinLevel(MAX_LEVEL));
    }

    @Test
    public void userValuesAreUsedAndBadOnesRejected() {
        // 用户写了就用用户的；量不为正、折扣出了 0 到 1、最高级小于 1 都在加载时拒绝。
        var custom = new Properties();
        custom.setProperty("elixir.qi.fine", "2.0");
        custom.setProperty("elixir.repeat_factor", "0.25");
        var settings = BrewElixirSettings.read(custom, (key, fallback) -> { });
        assertEquals(CUSTOM_REPEATED_QI, settings.dose(BrewQuality.FINE, 1), TOLERANCE);
        for (List<String> bad : List.of(List.of("elixir.qi.poor", "0"), List.of("elixir.repeat_factor", "1.5"),
                List.of("elixir.max_toxin_level", "0"), List.of("elixir.toxin_seconds", "-1"),
                List.of("elixir.fire.exhaustion", "0.5"), List.of("elixir.fire.burn_hp", "0"),
                List.of("elixir.fire.burn_hp", "Infinity"), List.of("elixir.metal.intake", "0"),
                List.of("elixir.wood.attack_speed", "NaN"), List.of("elixir.water.movement_speed", "1.1"),
                List.of("elixir.earth.jump_height", "-0.2"), List.of("elixir.earth.sprint_level", "0"))) {
            var properties = new Properties();
            properties.setProperty(bad.get(0), bad.get(1));
            var failure = assertThrows(bad.toString(), IllegalArgumentException.class,
                    () -> BrewElixirSettings.read(properties, (key, fallback) -> { }));
            assertTrue(failure.getMessage(), failure.getMessage().contains("elixir"));
        }
    }

    @Test
    public void metalIntakeRejectsValuesBelowTheBodyModifierMinimum() {
        // 配置加载就拒绝无法提交的金毒倍率，下限本身仍是合法值。
        var invalid = new Properties();
        invalid.setProperty(METAL_INTAKE, "0.8,0.6,0.01");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> BrewElixirSettings.read(invalid, (key, fallback) -> { }));
        String message = failure.getMessage();
        assertTrue(message, message.contains("elixir.metal"));
        var boundary = new Properties();
        boundary.setProperty(METAL_INTAKE, Double.toString(CapabilityModifier.MIN_MULTIPLIER));
        var settings = BrewElixirSettings.read(boundary, (key, fallback) -> { });
        assertEquals(CapabilityModifier.MIN_MULTIPLIER,
                settings.toxins().multiplier(BrewKind.METAL_ELIXIR, MAX_LEVEL), TOLERANCE);
    }

    @Test
    public void eachPenaltyUsesItsOwnConfiguredValues() {
        // 四行强度分别读取配置，级数超过列表时用最后一档；没有丹毒保持原值。
        var custom = new Properties();
        custom.setProperty("elixir.metal.intake", "0.75");
        custom.setProperty("elixir.wood.attack_speed", "0.75");
        custom.setProperty("elixir.water.movement_speed", "0.75");
        custom.setProperty("elixir.earth.jump_height", "0.75");
        var settings = BrewElixirSettings.read(custom, (key, fallback) -> { });
        for (BrewKind kind : List.of(BrewKind.METAL_ELIXIR, BrewKind.WOOD_ELIXIR,
                BrewKind.WATER_ELIXIR, BrewKind.EARTH_ELIXIR)) {
            assertEquals(CUSTOM_PENALTY, settings.toxins().multiplier(kind, MAX_LEVEL), TOLERANCE);
            assertEquals(1, settings.toxins().multiplier(kind, 0), TOLERANCE);
        }
    }
}
