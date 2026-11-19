package dev.kizuna.meridian.brewing.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/**
 * 炉窗和步骤演示共用的底件：深色半透明底板、细亮边、标题下一条青色细线，全用纯色矩形画，不用贴图。
 *
 * <p>色值取自界面改版的琉璃样稿，样稿按两倍像素画，这里的尺寸都折成界面坐标。悬停和不可用两种状态样稿里没有，
 * 沿用样稿的白色叠加，只改不透明度。</p>
 */
final class Glass {
    static final int PANEL = 0xA80A1012;
    static final int PANEL_EDGE = 0x38FFFFFF;
    static final int PANEL_SHADOW = 0x40000000;
    static final int RULE = 0x1FFFFFFF;
    static final int ACCENT = 0xFF5FC3A6;
    static final int ACCENT_TEXT = 0xFF7FE0C2;
    static final int TITLE = 0xFFFFFFFF;
    static final int TEXT = 0xFFE6EFEC;
    static final int DIM = 0xFF9FB1AD;
    static final int FAINT = 0x809FB1AD;
    static final int WARNING = 0xFFF0864A;
    static final int WELL = 0x59000000;
    static final int WELL_EDGE = 0x14FFFFFF;
    static final int TRACK = 0x24FFFFFF;
    static final int BUTTON = 0x0FFFFFFF;
    static final int BUTTON_EDGE = 0x40FFFFFF;
    static final int HOVER = 0x33FFFFFF;
    static final int HOVER_EDGE = 0x73FFFFFF;
    static final int SELECTED = 0x595FC3A6;
    static final int DISABLED_EDGE = 0x1FFFFFFF;
    /** 凹槽、按钮那一圈细边的粗细。 */
    static final int EDGE = 1;
    /** 物品图标离所在行、所在格左边的距离。 */
    static final int ICON_INSET = 2;
    /** 底板四周留的边，标题和各块内容都从这里起。 */
    static final int PADDING = 6;
    /** 标题栏占的高度：标题一行字，下面一条横线。 */
    static final int TITLE_BAR = 16;
    /** 炉窗和独立演示用同一块底板；界面比例 2、窗口 1280×720 时四周都留得出世界，比例 3 时刚好放满。 */
    static final int WINDOW_WIDTH = 404;
    static final int WINDOW_HEIGHT = 240;
    /** 底板比正中高一些，底下留给动作栏的提示。 */
    private static final int WINDOW_LIFT = 14;
    private static final int CONTENT_GAP = 4;
    private static final int SHADOW_DROP = 3;
    private static final int TITLE_INSET = 2;
    private static final int MARKER_WIDTH = 2;

    /** 按钮和列表行的四种样子。 */
    enum Face { IDLE, HOVERED, SELECTED, DISABLED }

    /** 一块矩形，坐标是界面坐标。 */
    record Box(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean contains(double pointX, double pointY) {
            return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
        }
    }

    private Glass() {
        // 只有静态入口。
    }

    /** 底板在屏幕上的位置：水平居中，竖直方向比正中略高；屏幕太矮时贴顶。 */
    static Box window(int screenWidth, int screenHeight) {
        int top = Math.max(0, (screenHeight - WINDOW_HEIGHT) / 2 - WINDOW_LIFT);
        return new Box((screenWidth - WINDOW_WIDTH) / 2, top, WINDOW_WIDTH, WINDOW_HEIGHT);
    }

    /** 底板里标题栏以下、四边留白以内的那一块。 */
    static Box content(Box window) {
        int top = window.y() + PADDING + TITLE_BAR + CONTENT_GAP;
        return new Box(window.x() + PADDING, top, window.width() - PADDING * 2, window.bottom() - PADDING - top);
    }

    static void panel(GuiGraphicsExtractor graphics, Box box) {
        // 底板下方压一条浅影，再画半透明底和一圈细亮边；后面的世界照常透出来。
        graphics.fill(box.x(), box.bottom(), box.right(), box.bottom() + SHADOW_DROP, PANEL_SHADOW);
        graphics.fill(box.x(), box.y(), box.right(), box.bottom(), PANEL);
        graphics.outline(box.x(), box.y(), box.width(), box.height(), PANEL_EDGE);
    }

    static void title(GuiGraphicsExtractor graphics, Font font, Component title, Box panel) {
        // 标题白字；横线通栏，标题正下方那一段换成青色。
        int left = panel.x() + PADDING;
        int width = panel.width() - PADDING * 2;
        int line = panel.y() + PADDING + TITLE_BAR - 1;
        FormattedCharSequence shown = clip(font, title, width - TITLE_INSET);
        graphics.text(font, shown, left + TITLE_INSET, panel.y() + PADDING + 1, TITLE, false /* dropShadow */);
        graphics.fill(left, line, left + width, line + 1, RULE);
        graphics.fill(left, line, left + font.width(shown) + TITLE_INSET * 2, line + 1, ACCENT);
    }

    static void well(GuiGraphicsExtractor graphics, Box box) {
        graphics.fill(box.x(), box.y(), box.right(), box.bottom(), WELL);
        graphics.outline(box.x(), box.y(), box.width(), box.height(), WELL_EDGE);
    }

    static void button(GuiGraphicsExtractor graphics, Box box, Face face) {
        int fill = switch (face) {
            case IDLE, DISABLED -> BUTTON;
            case HOVERED -> HOVER;
            case SELECTED -> SELECTED;
        };
        int edge = switch (face) {
            case IDLE -> BUTTON_EDGE;
            case HOVERED -> HOVER_EDGE;
            case SELECTED -> ACCENT;
            case DISABLED -> DISABLED_EDGE;
        };
        graphics.fill(box.x(), box.y(), box.right(), box.bottom(), fill);
        graphics.outline(box.x(), box.y(), box.width(), box.height(), edge);
    }

    static void row(GuiGraphicsExtractor graphics, Box box, Face face) {
        // 列表行平时不画底；悬停铺一层白，选中铺青色。
        if (face == Face.HOVERED) {
            graphics.fill(box.x(), box.y(), box.right(), box.bottom(), HOVER);
        }
        if (face == Face.SELECTED) {
            graphics.fill(box.x(), box.y(), box.right(), box.bottom(), SELECTED);
        }
    }

    /** 行左边立一条青线：药方列表里标「下一方」，步骤列表里标炉子真正走到的那一步。 */
    static void marker(GuiGraphicsExtractor graphics, Box box) {
        graphics.fill(box.x(), box.y(), box.x() + MARKER_WIDTH, box.bottom(), ACCENT);
    }

    static void track(GuiGraphicsExtractor graphics, Box box, double fraction) {
        // 进度槽：先铺底，再按比例填青色；比例夹在 0 到 1 之间。
        double clamped = Math.max(0, Math.min(1, fraction));
        int filled = (int) Math.round(box.width() * clamped);
        graphics.fill(box.x(), box.y(), box.right(), box.bottom(), TRACK);
        graphics.fill(box.x(), box.y(), box.x() + filled, box.bottom(), ACCENT);
    }

    static void text(GuiGraphicsExtractor graphics, Font font, FormattedCharSequence text, int x, int y, int color) {
        graphics.text(font, text, x, y, color, false /* dropShadow */);
    }

    static void textRight(GuiGraphicsExtractor graphics, Font font, FormattedCharSequence text, int right, int y,
                          int color) {
        graphics.text(font, text, right - font.width(text), y, color, false /* dropShadow */);
    }

    /** 一行放不下时截断，末尾补省略号。 */
    static FormattedCharSequence clip(Font font, FormattedText text, int width) {
        if (font.width(text) <= width) {
            return Language.getInstance().getVisualOrder(text);
        }
        FormattedText head = font.substrByWidth(text, width - font.width(CommonComponents.ELLIPSIS));
        return Language.getInstance().getVisualOrder(FormattedText.composite(head, CommonComponents.ELLIPSIS));
    }

    /** 按宽度折行，最多留 {@code maxLines} 行；超出的在最后一行末尾补省略号。 */
    static List<FormattedCharSequence> wrap(Font font, FormattedText text, int width, int maxLines) {
        List<FormattedCharSequence> lines = font.split(text, width);
        if (lines.size() <= maxLines) {
            return lines;
        }
        // 给省略号留出位置后重新折一遍，免得补上以后超宽。
        FormattedCharSequence ellipsis = CommonComponents.ELLIPSIS.getVisualOrderText();
        List<FormattedCharSequence> tight = font.split(text, width - font.width(ellipsis));
        List<FormattedCharSequence> shown = new ArrayList<>(tight.subList(0, maxLines - 1));
        shown.add(FormattedCharSequence.composite(tight.get(maxLines - 1), ellipsis));
        return shown;
    }
}
