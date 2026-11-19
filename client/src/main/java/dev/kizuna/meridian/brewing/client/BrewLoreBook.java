package dev.kizuna.meridian.brewing.client;

import dev.kizuna.meridian.brewing.fabric.BrewLore;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * 收到「翻开哪一篇」后，按本机语言取出正文，排成原版书页，用原版的书本界面翻看；药方的书在末页多一颗
 * 「看炼法」（见 {@link RecipeBookScreen}）。
 *
 * <p>页宽、页高和原版 {@link BookViewScreen} 一样：每页 114 像素宽、128 像素高，按字体行高算能放几行。
 * 第一页先写加粗的篇名（太长时按加粗字宽折成几行），空一行再接正文；段落之间的空行落到页首时不占行；
 * 逗号、句号这类句读不落在行首。</p>
 */
final class BrewLoreBook {
    private static final int PAGE_WIDTH = 114;
    private static final int PAGE_HEIGHT = 128;
    private static final String PARAGRAPH_BREAK = "\n";
    private static final Style TITLE_STYLE = Style.EMPTY.withBold(true);
    /** 不能放在行首的句读。 */
    private static final String NO_LINE_START = "，。、；：！？）》」』…";

    private BrewLoreBook() {
        // 只有静态入口。
    }

    static void register() {
        // 收包回调已经在客户端主线程上，可以直接换界面。
        ClientPlayNetworking.registerGlobalReceiver(BrewLore.OpenPayload.TYPE,
                (payload, context) -> open(context.client(), payload.document()));
    }

    private static void open(Minecraft client, String document) {
        // 认不出的篇名不翻开，免得服务端版本更新后客户端翻出一本空书。
        Optional<Component> title = BrewLore.title(document);
        if (title.isEmpty()) {
            return;
        }
        StringSplitter splitter = client.font.getSplitter();
        String text = Component.translatable(BrewLore.textKey(document)).getString().strip();
        List<Component> lines = new ArrayList<>(titleLines(splitter, title.orElseThrow()));
        lines.add(Component.empty());
        for (String line : lines(splitter, text)) {
            lines.add(Component.literal(line));
        }
        int linesPerPage = PAGE_HEIGHT / client.font.lineHeight;
        List<Component> pages = paginate(lines, linesPerPage);
        BookViewScreen.BookAccess book = new BookViewScreen.BookAccess(pages);
        // 药方的书在末页多一颗「看炼法」；总纲不是哪一张方，照旧用原版界面。
        if (RecipeKnowledge.kind(document).isPresent()) {
            client.gui.setScreen(new RecipeBookScreen(book, document));
        } else {
            client.gui.setScreen(new BookViewScreen(book));
        }
    }

    private static List<Component> titleLines(StringSplitter splitter, Component title) {
        // 篇名加粗后字更宽，按加粗的字宽折行，折出几行就占几行。
        List<Component> lines = new ArrayList<>();
        for (FormattedText line : splitter.splitLines(title.getString(), PAGE_WIDTH, TITLE_STYLE)) {
            lines.add(Component.literal(line.getString()).withStyle(TITLE_STYLE));
        }
        return lines;
    }

    private static List<String> lines(StringSplitter splitter, String text) {
        // 语言文件里一段一行，段与段之间空一行；每段按页宽折行，空行原样留着。
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split(PARAGRAPH_BREAK, -1)) {
            if (paragraph.isBlank()) {
                lines.add("");
                continue;
            }
            lines.addAll(wrap(splitter, paragraph));
        }
        return lines;
    }

    private static List<String> wrap(StringSplitter splitter, String paragraph) {
        // 每次按页宽折出一行；下一行若以句读开头，这一行就少放一个字，让那个字带着句读折下去。
        List<String> lines = new ArrayList<>();
        String rest = paragraph;
        while (!rest.isEmpty()) {
            String first = splitter.splitLines(rest, PAGE_WIDTH, Style.EMPTY).getFirst().getString();
            if (first.isEmpty() || !rest.startsWith(first)) {
                throw new IllegalStateException("折行结果不是这一段的开头：" + first);
            }
            int cut = first.length();
            while (cut > 1 && cut < rest.length() && NO_LINE_START.indexOf(rest.charAt(cut)) >= 0) {
                cut--;
            }
            lines.add(rest.substring(0, cut));
            rest = rest.substring(cut).stripLeading();
        }
        return lines;
    }

    private static List<Component> paginate(List<Component> lines, int linesPerPage) {
        // 一行一行往页里放，每页装满就换页；页首的空行跳过。
        List<Component> pages = new ArrayList<>();
        MutableComponent page = Component.empty();
        int used = 0;
        for (Component line : lines) {
            if (used == linesPerPage) {
                pages.add(page);
                page = Component.empty();
                used = 0;
            }
            if (used == 0 && line.getString().isEmpty()) {
                continue;
            }
            if (used > 0) {
                page.append(PARAGRAPH_BREAK);
            }
            page.append(line);
            used++;
        }
        pages.add(page);
        return List.copyOf(pages);
    }
}
