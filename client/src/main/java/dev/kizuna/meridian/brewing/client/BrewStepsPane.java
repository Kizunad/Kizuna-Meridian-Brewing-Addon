package dev.kizuna.meridian.brewing.client;

import dev.kizuna.meridian.brewing.protocol.pb.BrewStep;
import dev.kizuna.meridian.brewing.protocol.pb.BrewTimeline;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * 步骤演示：上面一格放演示游标停着的那一步（大图标、整句话、火档），下面列出全部步骤。炉窗的子视图和药方书打开的
 * 独立画面都画这一块。
 *
 * <p>演示游标只在本机走：每一步停 {@link #STEP_TICKS} 刻，走到最后一步停住；能暂停、重播，点某一行就跳到那一步。
 * 炉子真正走到哪一步由服务端说了算，经 {@link #follow} 传进来，在那一行左边立一条青线、右边写「炉里」，
 * 和演示游标（整行铺青色、右边写「演示」）分开画。</p>
 */
public final class BrewStepsPane {
    /** 演示游标在每一步停留的刻数。 */
    public static final int STEP_TICKS = 50;
    private static final String KEY = BrewNames.SCREEN + "steps.";
    private static final String PLAY = KEY + "play";
    private static final String PAUSE = KEY + "pause";
    private static final String REAL = KEY + "real";
    private static final String DEMO = KEY + "demo";
    private static final int NONE = -1;
    private static final int STAGE_HEIGHT = 38;
    private static final int GAP = 4;
    private static final int ROW_HEIGHT = 16;
    private static final int BUTTON_HEIGHT = 16;
    private static final int BUTTON_WIDTH = 56;
    private static final int INSET = 3;
    private static final int ICON = 16;
    private static final float STAGE_ICON_SCALE = 2;
    private static final int STAGE_TEXT_X = INSET + ICON * 2 + INSET * 2;
    private static final int STAGE_LINES = 2;
    private static final int LINE_STEP = 10;
    private static final int NUMBER_WIDTH = 16;
    private static final int NUMBER_GAP = 2;
    private static final int ROW_TEXT_X = NUMBER_WIDTH + ICON + 4;
    private static final int ROW_TEXT_Y = 4;
    private static final int FOOT_GAP = 8;
    private static final int SCROLLBAR = 2;
    private static final int PROGRESS_HEIGHT = 2;
    private static final int PIP_WIDTH = 2;
    private static final int PIP_HEIGHT = 7;
    private static final int PIP_STEP = 3;
    private static final int FIRE_GRADES = 4;
    private final Glass.Box box;
    private final BrewTimeline timeline;
    private final List<Component> sentences;
    private final int rows;
    private GlassButton playButton;
    private int cursor;
    private int elapsed;
    private boolean playing = true;
    private int scroll;
    private int live = NONE;

    BrewStepsPane(Glass.Box box, BrewTimeline timeline) {
        // 句子进来时拼好一次；列表能放几行由这块区域的高度定。
        this.box = box;
        this.timeline = timeline;
        List<Component> lines = new ArrayList<>();
        for (BrewStep step : timeline.getStepsList()) {
            lines.add(BrewStepText.sentence(step, timeline));
        }
        sentences = List.copyOf(lines);
        rows = (box.height() - STAGE_HEIGHT - GAP * 2 - BUTTON_HEIGHT - Glass.EDGE * 2) / ROW_HEIGHT;
    }

    /** 标题：药名、剂型、炉型，例如「苦黄膏 · 饮用 · 一阶药炉」。 */
    Component title() {
        var choice = timeline.getChoice();
        return Component.translatable(KEY + "title", BrewNames.recipe(choice.getRecipe()),
                BrewNames.form(choice.getForm()), BrewNames.vessel(timeline.getVessel()));
    }

    /** 底下一排按钮：左边「返回」，右边「播放 / 暂停」和「重播」。 */
    List<GlassButton> buttons(Runnable back) {
        int y = box.bottom() - BUTTON_HEIGHT;
        int replayX = box.right() - BUTTON_WIDTH;
        int playX = replayX - GAP - BUTTON_WIDTH;
        var backButton = new GlassButton(new Glass.Box(box.x(), y, BUTTON_WIDTH, BUTTON_HEIGHT),
                Component.translatable(KEY + "back"), pressed -> back.run());
        playButton = new GlassButton(new Glass.Box(playX, y, BUTTON_WIDTH, BUTTON_HEIGHT),
                playLabel(), pressed -> togglePlay());
        var replayButton = new GlassButton(new Glass.Box(replayX, y, BUTTON_WIDTH, BUTTON_HEIGHT),
                Component.translatable(KEY + "replay"), pressed -> replay());
        return List.of(backButton, playButton, replayButton);
    }

    void tick() {
        // 播放时每刻走一格，一步停够了就到下一步；最后一步停够了自己停下。
        if (!playing || sentences.isEmpty()) {
            return;
        }
        elapsed++;
        if (elapsed < STEP_TICKS) {
            return;
        }
        if (cursor == sentences.size() - 1) {
            elapsed = STEP_TICKS;
            pause();
            return;
        }
        jump(cursor + 1);
    }

    /** 炉子真正走到的那一步，传协议里的步骤名；空串表示没有（独立演示，或者这一步不在列表里）。 */
    void follow(String currentStep) {
        live = NONE;
        for (int step = 0; step < timeline.getStepsCount(); step++) {
            if (timeline.getSteps(step).getAction().equals(currentStep)) {
                live = step;
            }
        }
    }

    private void togglePlay() {
        // 走完以后再点播放，等于重播。
        if (playing) {
            pause();
        } else if (finished()) {
            replay();
        } else {
            resume();
        }
    }

    private void replay() {
        jump(0);
        resume();
    }

    private void pause() {
        playing = false;
        playButton.setMessage(playLabel());
    }

    private void resume() {
        playing = true;
        playButton.setMessage(playLabel());
    }

    private Component playLabel() {
        return Component.translatable(playing ? PAUSE : PLAY);
    }

    private boolean finished() {
        return cursor == sentences.size() - 1 && elapsed >= STEP_TICKS;
    }

    private void jump(int step) {
        // 换到某一步并从头计时；这一步不在列表可见的几行里就把列表卷过去。
        cursor = step;
        elapsed = 0;
        if (cursor < scroll) {
            scroll = cursor;
        }
        if (cursor >= scroll + rows) {
            scroll = cursor - rows + 1;
        }
    }

    boolean clicked(double mouseX, double mouseY) {
        int row = rowAt(mouseX, mouseY);
        if (row == NONE) {
            return false;
        }
        jump(row);
        return true;
    }

    boolean scrolled(double mouseX, double mouseY, double amount) {
        if (!list().contains(mouseX, mouseY)) {
            return false;
        }
        int wanted = scroll - (int) Math.signum(amount);
        scroll = Math.max(0, Math.min(Math.max(0, sentences.size() - rows), wanted));
        return true;
    }

    /** 两块凹槽的底，画在背景那一层，按钮和内容都压在它上面。 */
    void wells(GuiGraphicsExtractor graphics) {
        Glass.well(graphics, new Glass.Box(box.x(), box.y(), box.width(), STAGE_HEIGHT));
        Glass.well(graphics, list());
    }

    void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        if (sentences.isEmpty()) {
            return;
        }
        stage(graphics, font);
        Glass.Box list = list();
        int last = Math.min(sentences.size(), scroll + rows);
        for (int step = scroll; step < last; step++) {
            row(graphics, font, step, mouseX, mouseY);
        }
        scrollbar(graphics, list);
    }

    private void stage(GuiGraphicsExtractor graphics, Font font) {
        // 游标停着的那一步：两倍大的图标，整句话最多折两行；下面一行是火档、炉子真正走到第几步和演示放到第几步，
        // 底边一条细槽是整段演示走了多少。
        Glass.Box stage = new Glass.Box(box.x(), box.y(), box.width(), STAGE_HEIGHT);
        BrewStep step = timeline.getSteps(cursor);
        graphics.pose().pushMatrix();
        graphics.pose().translate(stage.x() + INSET, stage.y() + INSET);
        graphics.pose().scale(STAGE_ICON_SCALE, STAGE_ICON_SCALE);
        graphics.item(BrewStepText.icon(step), 0, 0);
        graphics.pose().popMatrix();
        int textX = stage.x() + STAGE_TEXT_X;
        int textWidth = stage.right() - INSET - textX;
        int y = stage.y() + INSET;
        for (FormattedCharSequence line : Glass.wrap(font, sentences.get(cursor), textWidth, STAGE_LINES)) {
            Glass.text(graphics, font, line, textX, y, Glass.TITLE);
            y += LINE_STEP;
        }
        Component count = Component.translatable(KEY + "count", cursor + 1, sentences.size());
        int footY = stage.bottom() - INSET - font.lineHeight;
        Glass.textRight(graphics, font, count.getVisualOrderText(), stage.right() - INSET, footY, Glass.DIM);
        int footX = textX;
        if (!step.getFire().isEmpty()) {
            pips(graphics, footX, footY, step.getGrade());
            footX += FIRE_GRADES * PIP_STEP + FOOT_GAP;
        }
        if (live != NONE) {
            Component at = Component.translatable(KEY + "real_at", live + 1);
            Glass.text(graphics, font, at.getVisualOrderText(), footX, footY, Glass.ACCENT_TEXT);
        }
        double total = (double) sentences.size() * STEP_TICKS;
        double done = (double) cursor * STEP_TICKS + elapsed;
        Glass.track(graphics, new Glass.Box(stage.x() + 1, stage.bottom() - 1 - PROGRESS_HEIGHT,
                stage.width() - Glass.EDGE * 2, PROGRESS_HEIGHT), done / total);
    }

    private static void pips(GuiGraphicsExtractor graphics, int x, int y, int grade) {
        // 调火的步骤画四格火档，亮几格就是几档。
        for (int pip = 0; pip < FIRE_GRADES; pip++) {
            int color = pip < grade ? Glass.ACCENT : Glass.TRACK;
            int left = x + pip * PIP_STEP;
            graphics.fill(left, y, left + PIP_WIDTH, y + PIP_HEIGHT, color);
        }
    }

    private void row(GuiGraphicsExtractor graphics, Font font, int step, int mouseX, int mouseY) {
        // 一行一步：序号、图标、一句话；一句话放不下就截断，鼠标停上去显示整句。
        Glass.Box row = rowBox(step);
        boolean hovered = row.contains(mouseX, mouseY);
        Glass.row(graphics, row, rowFace(step, hovered));
        boolean real = step == live;
        if (real) {
            Glass.marker(graphics, row);
        }
        int textY = row.y() + ROW_TEXT_Y;
        String number = Integer.toString(step + 1);
        graphics.text(font, number, row.x() + NUMBER_WIDTH - font.width(number) - NUMBER_GAP, textY, Glass.DIM,
                false /* dropShadow */);
        ItemStack icon = BrewStepText.icon(timeline.getSteps(step));
        graphics.item(icon, row.x() + NUMBER_WIDTH, row.y());
        int tagWidth = tag(graphics, font, row, step);
        int textWidth = row.width() - ROW_TEXT_X - tagWidth - INSET;
        Component sentence = sentences.get(step);
        int color = real ? Glass.ACCENT_TEXT : Glass.TEXT;
        Glass.text(graphics, font, Glass.clip(font, sentence, textWidth), row.x() + ROW_TEXT_X, textY, color);
        if (hovered && font.width(sentence) > textWidth) {
            graphics.setTooltipForNextFrame(font, sentence, mouseX, mouseY);
        }
    }

    private Glass.Face rowFace(int step, boolean hovered) {
        if (step == cursor) {
            return Glass.Face.SELECTED;
        }
        return hovered ? Glass.Face.HOVERED : Glass.Face.IDLE;
    }

    private int tag(GuiGraphicsExtractor graphics, Font font, Glass.Box row, int step) {
        // 行尾的小字：炉子真正走到的那一步写「炉里」，演示游标所在的那一步写「演示」，两者落在同一行时只写「炉里」。
        boolean real = step == live;
        if (!real && step != cursor) {
            return 0;
        }
        Component tag = Component.translatable(real ? REAL : DEMO);
        int color = real ? Glass.ACCENT_TEXT : Glass.DIM;
        Glass.textRight(graphics, font, tag.getVisualOrderText(), row.right() - INSET, row.y() + ROW_TEXT_Y, color);
        return font.width(tag) + INSET;
    }

    private void scrollbar(GuiGraphicsExtractor graphics, Glass.Box list) {
        // 步骤比能放的行数多时，右边一条细槽标出现在卷到哪。
        int count = sentences.size();
        if (count <= rows) {
            return;
        }
        int height = list.height() - Glass.EDGE * 2;
        int x = list.right() - 1 - SCROLLBAR;
        int top = list.y() + 1 + height * scroll / count;
        int bottom = list.y() + 1 + height * (scroll + rows) / count;
        graphics.fill(x, list.y() + 1, x + SCROLLBAR, list.bottom() - 1, Glass.TRACK);
        graphics.fill(x, top, x + SCROLLBAR, bottom, Glass.ACCENT);
    }

    private Glass.Box list() {
        return new Glass.Box(box.x(), box.y() + STAGE_HEIGHT + GAP, box.width(), rows * ROW_HEIGHT + Glass.EDGE * 2);
    }

    private Glass.Box rowBox(int step) {
        Glass.Box list = list();
        int y = list.y() + 1 + (step - scroll) * ROW_HEIGHT;
        return new Glass.Box(list.x() + 1, y, list.width() - Glass.EDGE * 2 - SCROLLBAR - 1, ROW_HEIGHT);
    }

    private int rowAt(double mouseX, double mouseY) {
        int last = Math.min(sentences.size(), scroll + rows);
        for (int step = scroll; step < last; step++) {
            if (rowBox(step).contains(mouseX, mouseY)) {
                return step;
            }
        }
        return NONE;
    }

    /** 这份演示是哪一批、哪一版选方的；炉窗用它判断要不要换一份。 */
    public BrewTimeline timeline() {
        return timeline;
    }

    public List<Component> sentences() {
        return sentences;
    }

    /** 演示游标停在第几步，从 0 数。 */
    public int demoStep() {
        return cursor;
    }

    /** 炉子真正走到第几步，从 0 数；没有就是 -1。 */
    public int realStep() {
        return live;
    }

    public boolean playing() {
        return playing;
    }

    /** 列表里某一步那一行的中心，界面坐标；这一步卷在外面时没有。 */
    public Optional<ScreenPosition> rowCenter(int step) {
        if (step < scroll || step >= scroll + rows) {
            return Optional.empty();
        }
        Glass.Box row = rowBox(step);
        return Optional.of(new ScreenPosition(row.x() + row.width() / 2, row.y() + row.height() / 2));
    }
}
