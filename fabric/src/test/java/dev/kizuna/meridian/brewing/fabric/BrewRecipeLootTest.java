package dev.kizuna.meridian.brewing.fabric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.gson.JsonParser;
import dev.kizuna.meridian.brewing.server.BrewKind;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.Test;

/** 小屋药方池的等概率规则直接检查权重，不从有限抽样中的最小张数反推。 */
public final class BrewRecipeLootTest {
    private static final String TABLE = "/data/meridian_brewing/loot_table/chests/witch_hut.json";

    @Test
    public void everyRegisteredRecipeOccursExactlyOnceWithEqualWeight() throws Exception {
        // 读取发布资源，漏条目、重复条目和不等权重都会明确失败。
        var stream = BrewRecipeLootTest.class.getResourceAsStream(TABLE);
        assertNotNull(stream);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var table = JsonParser.parseReader(reader).getAsJsonObject();
            var pool = table.getAsJsonArray("pools").get(0).getAsJsonObject();
            var weights = new TreeMap<String, Integer>();
            for (var value : pool.getAsJsonArray("entries")) {
                var entry = value.getAsJsonObject();
                assertEquals("meridian_brewing:brew_recipe", entry.get("name").getAsString());
                var function = entry.getAsJsonArray("functions").get(0).getAsJsonObject();
                String kind = function.getAsJsonObject("components")
                        .get("meridian_brewing:recipe_kind").getAsString();
                assertEquals("重复药方：" + kind, null, weights.put(kind, entry.get("weight").getAsInt()));
            }
            Set<String> expected = Arrays.stream(BrewKind.values()).map(BrewKind::path).collect(Collectors.toSet());
            assertEquals(expected, weights.keySet());
            assertEquals(Set.of(1), Set.copyOf(weights.values()));
        }
    }
}
