package dev.kizuna.meridian.brewing.verification;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 验证炼药目录资源能够由 Technique Framework 的有界解析器读取。 */
public final class BrewingCatalogTest {
    private static final int EXPECTED_DEFINITION_COUNT = 1;
    private static final int EXPECTED_BOOK_COUNT = 1;
    private static final int EXPECTED_VOLUME_COUNT = 1;

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
}
