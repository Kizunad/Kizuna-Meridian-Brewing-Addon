package dev.kizuna.meridian.brewing.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 十二篇讲解的正文：中文、英文语言文件里都有，都不是空的。中文是老药婆讲古的世界内文本，
 * 不许出现分数、倍率、按键、界面这些机制说法，也不许夹英文。
 */
public class BrewLoreTextTest {
    private static final String LANG_DIRECTORY = "/assets/meridian_brewing/lang/";
    private static final int DOCUMENT_COUNT = 12;
    private static final List<String> MECHANIC_WORDS = List.of(
            "分数", "总分", "满分", "扣分", "得分", "倍率", "机制", "判定", "时窗", "动作栏", "窗口",
            "右键", "左键", "主手", "副手", "×", "%");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]");

    @Test
    public void everyDocumentHasTextInBothLanguages() throws IOException {
        // 六张巫药方、五张丹露方加总纲，每一篇在两份语言文件里都要有正文。
        List<String> documents = BrewLore.documents();
        assertEquals(DOCUMENT_COUNT, documents.size());
        for (String language : List.of("zh_cn", "en_us")) {
            JsonObject lang = lang(language);
            for (String document : documents) {
                String key = BrewLore.textKey(document);
                assertTrue(language + " 缺讲解 " + key, lang.has(key));
                assertTrue(language + " 的讲解是空的：" + key, !lang.get(key).getAsString().isBlank());
            }
        }
    }

    @Test
    public void chineseLoreUsesNoMechanicWords() throws IOException {
        // 中文正文照用户定的规矩：找出每一篇里的机制说法和英文字母，一处都不许有。
        JsonObject lang = lang("zh_cn");
        List<String> found = new ArrayList<>();
        for (String document : BrewLore.documents()) {
            String text = lang.get(BrewLore.textKey(document)).getAsString();
            for (String word : MECHANIC_WORDS) {
                if (text.contains(word)) {
                    found.add(document + "：" + word);
                }
            }
            if (LATIN.matcher(text).find()) {
                found.add(document + "：英文字母");
            }
        }
        assertTrue("讲解里有机制说法：" + found, found.isEmpty());
    }

    private static JsonObject lang(String language) throws IOException {
        // 读打进 JAR 的语言文件本身，和游戏里取的是同一份。
        InputStream stream = BrewLoreTextTest.class.getResourceAsStream(LANG_DIRECTORY + language + ".json");
        assertNotNull("没有语言文件 " + language, stream);
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
