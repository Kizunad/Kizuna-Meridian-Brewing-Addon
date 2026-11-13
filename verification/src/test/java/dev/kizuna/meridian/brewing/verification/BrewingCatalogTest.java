package dev.kizuna.meridian.brewing.verification;

import static org.junit.Assert.assertEquals;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationDuration;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import java.util.List;
import org.junit.Test;

/** 验证炼药目录资源能够由 Technique Framework 的有界解析器读取。 */
public final class BrewingCatalogTest {
    private static final int EXPECTED_DEFINITION_COUNT = 1;
    private static final int EXPECTED_BOOK_COUNT = 1;
    private static final int EXPECTED_VOLUME_COUNT = 1;
    private static final String BREW_CLIP = "meridian_brewing:witch_brew";

    @Test
    public void loadsTheMinimalBrewingCatalog() throws Exception {
        var catalog = BrewingCatalog.load();
        assertEquals("meridian_brewing", catalog.getNamespace());
        assertEquals(EXPECTED_DEFINITION_COUNT, catalog.getDefinitionsCount());
        assertEquals(EXPECTED_BOOK_COUNT, catalog.getBooksCount());
        assertEquals(EXPECTED_VOLUME_COUNT, catalog.getVolumesCount());
        assertEquals("meridian_brewing:brew", catalog.getDefinitions(0).getId());
        assertEquals("meridian_brewing:brewing", catalog.getBooks(0).getId());
        assertEquals("meridian_brewing:brewing_basics", catalog.getVolumes(0).getId());
    }

    @Test
    public void brewPlaysTheWholeBrewingClipFromCastStartUntilRecoveryEnds() throws Exception {
        // 熬药 32 秒一段动作照五脏诀、行药诀的写法挂在起手 0 毫秒；副手下料的叠加动作由炼药代码播，不进目录。
        var definition = BrewingCatalog.load().getDefinitions(0);
        var presentations = definition.getEffectsList().stream().filter(TechniqueEffect::hasPresentation).toList();
        PresentationEffect expected = PresentationEffect.newBuilder()
                .setId(BREW_CLIP)
                .setDuration(PresentationDuration.UNTIL_RECOVERY_END)
                .setStartMillis(0)
                .build();
        assertEquals(List.of(expected), presentations.stream().map(TechniqueEffect::getPresentation).toList());
        assertEquals(0, presentations.getFirst().getAtMillis());
    }
}
