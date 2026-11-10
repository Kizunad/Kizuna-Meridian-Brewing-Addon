package dev.kizuna.meridian.brewing.client;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class DeadWoodTintTest {
    private static final Path ASSETS =
            Path.of(System.getProperty("brewing.fabricResources"), "assets", "meridian_brewing");

    @Test
    public void itemTintsMatchBlockTints() throws IOException {
        // 手里、物品栏里的枯木与放在地上的颜色一致：原木两档，木头只有侧面一档。
        for (DeadWoodTint tint : DeadWoodTint.values()) {
            assertEquals(tint.log(), List.of(tint.side(), tint.end()), itemTints(tint.log()));
            assertEquals(tint.wood(), List.of(tint.side()), itemTints(tint.wood()));
        }
    }

    @Test
    public void deadWoodModelsUseTintedParents() throws IOException {
        for (DeadWoodTint tint : DeadWoodTint.values()) {
            assertEquals("meridian_brewing:block/dead_log", parent(tint.log()));
            assertEquals("meridian_brewing:block/dead_wood", parent(tint.wood()));
        }
    }

    private static List<Integer> itemTints(String path) throws IOException {
        JsonObject model = read("items/" + path + ".json").getAsJsonObject("model");
        List<Integer> tints = new ArrayList<>();
        model.getAsJsonArray("tints").forEach(tint -> tints.add(tint.getAsJsonObject().get("value").getAsInt()));
        return tints;
    }

    private static String parent(String path) throws IOException {
        return read("models/block/" + path + ".json").get("parent").getAsString();
    }

    private static JsonObject read(String path) throws IOException {
        return JsonParser.parseString(Files.readString(ASSETS.resolve(path))).getAsJsonObject();
    }
}
