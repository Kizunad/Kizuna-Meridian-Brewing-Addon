package dev.kizuna.meridian.brewing.verification;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.server.TechniqueCatalogManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 炼药功法目录的有界文本解析入口。执行算子仍由上游功法库提供。 */
public final class BrewingCatalog {
    public static final String RESOURCE = "/data/meridian_brewing/technique_catalog.txtpb";

    private BrewingCatalog() {
        // 目录工具只提供静态验证入口。
    }

    public static TechniqueCatalog load() throws IOException {
        var stream = BrewingCatalog.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IOException("找不到炼药功法目录：" + RESOURCE);
        }
        try (stream) {
            byte[] bytes = stream.readNBytes(TechniqueCatalogManifest.MAX_BYTES + 1);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return TechniqueCatalogManifest.parseText(
                    "meridian_brewing:" + RESOURCE.substring(1), text);
        }
    }
}
