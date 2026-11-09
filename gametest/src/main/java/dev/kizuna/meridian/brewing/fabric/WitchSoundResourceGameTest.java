package dev.kizuna.meridian.brewing.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;

/** 发行包的每个声音和字幕都以实际资源及原版注册表核对。 */
public final class WitchSoundResourceGameTest {
    @GameTest
    public void everySoundResolvesToVanillaAndBothSubtitlesExist(GameTestHelper context) throws IOException {
        // 模组容器读取最终打包资源，不能从源码目录绕过资源装配。
        JsonObject sounds = read("sounds.json");
        JsonObject chinese = read("lang/zh_cn.json");
        JsonObject english = read("lang/en_us.json");
        for (var entry : sounds.entrySet()) {
            JsonObject event = entry.getValue().getAsJsonObject();
            String subtitle = event.get("subtitle").getAsString();
            context.assertTrue(chinese.has(subtitle) && english.has(subtitle), "字幕缺失：" + subtitle);
            for (var sound : event.getAsJsonArray("sounds")) {
                var reference = sound.getAsJsonObject();
                Identifier id = Identifier.parse(reference.get("name").getAsString());
                context.assertTrue(reference.get("type").getAsString().equals("event")
                        && id.getNamespace().equals("minecraft") && BuiltInRegistries.SOUND_EVENT.containsKey(id),
                        "没有对应的原版声音：" + id);
            }
        }
        var mod = FabricLoader.getInstance().getModContainer("meridian_witch").orElseThrow();
        for (var root : mod.getRootPaths()) {
            try (var files = Files.walk(root)) {
                context.assertTrue(files.noneMatch(path -> path.toString().endsWith(".class")), "内容包包含 Java 类");
            }
        }
        try (var reader = Files.newBufferedReader(mod.findPath("fabric.mod.json").orElseThrow())) {
            context.assertTrue(!JsonParser.parseReader(reader).getAsJsonObject().has("entrypoints"),
                    "内容包含 Java 入口");
        }
        context.succeed();
    }

    private static JsonObject read(String file) throws IOException {
        // 所有读取来自实际加载的内容模组。
        var mod = FabricLoader.getInstance().getModContainer("meridian_witch").orElseThrow();
        var path = mod.findPath("assets/meridian_witch/" + file).orElseThrow();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
