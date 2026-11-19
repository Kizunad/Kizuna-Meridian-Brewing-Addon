package dev.kizuna.meridian.brewing.client;

import dev.kizuna.meridian.brewing.protocol.pb.FurnaceChoice;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceClock;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceDose;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceOrders;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceView;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.VesselKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * 炉窗右半边的状态区：下一方、锅里这一方、阶段和当前步骤、各味药材、火候、余燃、水和余瓶、已熬时间和时窗、缺什么。
 *
 * <p>只照服务端发来的那一份画，不往前推算。别人在炼而自己没读过那张方时，服务端只给忙碌和阶段，
 * 这里就只写「炉子在忙」和阶段，不画空的材料表。辅料、引药要等开熬后的时窗才能投，
 * 服务端的当前步骤走到那一味之前不列进「缺」。</p>
 */
final class FurnaceStatus {
    private static final String KEY = BrewNames.SCREEN + "furnace.";
    private static final String AUTOMATIC = "AUTOMATIC_FURNACE";
    private static final Set<String> TIMED_SLOTS = Set.of("ACCESSORY", "CATALYST");
    private static final Set<String> PLAIN_MISSING = Set.of("recipe", "water", "fire", "payer", "qi");
    private static final List<String> LABELS = List.of("next", "actual", "phase", "heat", "missing");
    private static final double FULL_DEGREES = 100;
    private static final int LINE_STEP = 12;
    private static final int TOP_INSET = 2;
    private static final int LABEL_GAP = 6;
    private static final int PAIR_GAP = 4;
    private static final int DOSE_COLUMNS = 2;
    private static final int DOSE_HEIGHT = 18;
    private static final int DOSE_TEXT_X = 21;
    private static final int DOSE_TEXT_Y = 5;
    private static final int DOSE_INSET = 4;
    private static final int BLOCK_GAP = 6;
    private static final int TRACK_HEIGHT = 3;
    private static final int TRACK_STEP = 9;
    private static final int SUPPLY_COLUMNS = 3;
    private static final int MISSING_LINES = 2;
    private static final int NOTICE_BACKGROUND = 0xFF21292A;
    private static final int BUSY_GAP = 4;
    private final GuiGraphicsExtractor graphics;
    private final Font font;
    private final Glass.Box box;
    private final FurnaceView view;
    private final int valueX;
    private int y;

    private FurnaceStatus(GuiGraphicsExtractor graphics, Font font, Glass.Box box, FurnaceView view) {
        // 各行的值左对齐在同一列，列的位置按当前语言里最长的那个标签定。
        this.graphics = graphics;
        this.font = font;
        this.box = box;
        this.view = view;
        int widest = 0;
        for (String label : LABELS) {
            widest = Math.max(widest, font.width(Component.translatable(KEY + label)));
        }
        valueX = box.x() + widest + LABEL_GAP;
        y = box.y() + TOP_INSET;
    }

    static void draw(GuiGraphicsExtractor graphics, Font font, Glass.Box box, FurnaceView view,
                     ScreenPosition mouse) {
        new FurnaceStatus(graphics, font, box, view).draw(mouse);
    }

    /** 服务端只给了忙碌和阶段、没给当前步骤，就是别人的方而自己没读过。 */
    static boolean concealed(FurnaceView view) {
        return view.getOrders().getBusy() && view.getClock().getCurrentStep().isEmpty();
    }

    /** 三阶炉要铜傀儡照料；服务端没报工人时显示在等。 */
    static boolean awaitingWorker(FurnaceView view) {
        return view.getCatalog().getVessel().equals(AUTOMATIC) && !view.getClock().hasWorker()
                && !concealed(view);
    }

    /** 「缺」这一行列出的名字，顺序照服务端给的；同一项只列一次。 */
    static List<Component> missing(FurnaceView view) {
        FurnaceClock clock = view.getClock();
        Map<String, Component> names = new LinkedHashMap<>();
        for (String token : clock.getMissingList()) {
            missingName(clock, token).ifPresent(name -> names.putIfAbsent(token, name));
        }
        return List.copyOf(names.values());
    }

    private static Optional<Component> missingName(FurnaceClock clock, String token) {
        // 缺的是某一味药就写物品名；其余几种（药方、水、火、选方的人、真元）各有一句短话；
        // 服务端附带的整句提示和上面几项重复，不再列。
        for (FurnaceDose dose : clock.getDosesList()) {
            if (!dose.getSlot().equals(token)) {
                continue;
            }
            if (TIMED_SLOTS.contains(token) && !token.equals(clock.getCurrentStep())) {
                return Optional.empty();
            }
            return Optional.of(BrewNames.item(dose.getItem()).getHoverName());
        }
        if (PLAIN_MISSING.contains(token)) {
            return Optional.of(Component.translatable(KEY + "missing." + token));
        }
        return Optional.empty();
    }

    private void draw(ScreenPosition mouse) {
        var orders = view.getOrders();
        var clock = view.getClock();
        line("next", next(orders));
        if (concealed(view)) {
            busy();
            return;
        }
        line("actual", actual(orders));
        line("phase", phase(clock));
        if (awaitingWorker(view)) {
            text(Component.translatable(KEY + "worker"), box.x(), box.width(), Glass.WARNING);
            y += LINE_STEP;
        }
        doses(mouse);
        heat();
        supplies();
        elapsed();
        missed(clock).ifPresent(warning -> {
            // 比例三时动作栏位于炉窗后面，用实底隔开手熬的逐秒进度和过窗提示。
            graphics.fill(box.x(), y - 1, box.right(), y + font.lineHeight + 1, NOTICE_BACKGROUND);
            text(warning, box.x(), box.width(), Glass.WARNING);
            y += LINE_STEP;
        });
        missing();
    }

    private static Component phase(FurnaceClock clock) {
        Component phase = BrewNames.phase(clock.getPhase());
        if (clock.getCurrentStep().isEmpty()) {
            return phase;
        }
        if (clock.getPhase().equals("BREWING")) {
            String mode = clock.getManual() ? "manual" : "automatic";
            phase = Component.translatable(KEY + mode);
        }
        return Component.translatable(KEY + "pair", phase, BrewNames.step(clock.getCurrentStep()));
    }

    static Optional<Component> missed(FurnaceClock clock) {
        // 同一批的两味定时料共用熬法；过窗的药名并排放，给完整的一句提示留出空间。
        var names = new ArrayList<Component>();
        String message = "closed";
        for (var dose : clock.getDosesList()) {
            if (dose.getMissed()) {
                names.add(BrewNames.item(dose.getItem()).getHoverName());
                if (dose.getLateAllowed()) {
                    message = "late";
                }
            }
        }
        if (names.isEmpty()) {
            return Optional.empty();
        }
        var items = ComponentUtils.formatList(names, Component.translatable(KEY + "separator"));
        return Optional.of(Component.translatable(KEY + message, items));
    }

    private static Component next(FurnaceOrders orders) {
        if (!orders.hasNext()) {
            return Component.translatable(KEY + "unchosen");
        }
        return choice(orders.getNext());
    }

    private static Component actual(FurnaceOrders orders) {
        if (!orders.hasActual()) {
            return Component.translatable(KEY + "none");
        }
        return choice(orders.getActual());
    }

    private static Component choice(FurnaceChoice choice) {
        // 药名和剂型，例如「苦黄膏 · 饮用」。
        return Component.translatable(KEY + "pair", BrewNames.recipe(choice.getRecipe()),
                BrewNames.form(choice.getForm()));
    }

    private void busy() {
        y += BUSY_GAP;
        text(Component.translatable(KEY + "busy"), box.x(), box.width(), Glass.TITLE);
        y += LINE_STEP + BUSY_GAP;
        line("phase", BrewNames.phase(view.getClock().getPhase()));
    }

    private void line(String label, Component value) {
        text(Component.translatable(KEY + label), box.x(), valueX - box.x(), Glass.DIM);
        text(value, valueX, box.right() - valueX, Glass.TEXT);
        y += LINE_STEP;
    }

    private void text(Component text, int x, int width, int color) {
        Glass.text(graphics, font, Glass.clip(font, text, width), x, y, color);
    }

    private void doses(ScreenPosition mouse) {
        // 各味药材排成两列：图标、名字、已投/需投；投够的数字变青色。鼠标停上去说明这是哪一味。
        List<FurnaceDose> doses = view.getClock().getDosesList();
        if (doses.isEmpty()) {
            return;
        }
        int rows = (doses.size() + DOSE_COLUMNS - 1) / DOSE_COLUMNS;
        Glass.Box well = new Glass.Box(box.x(), y + 1, box.width(), rows * DOSE_HEIGHT + Glass.EDGE * 2);
        Glass.well(graphics, well);
        int cellWidth = (well.width() - Glass.EDGE * 2) / DOSE_COLUMNS;
        for (int index = 0; index < doses.size(); index++) {
            int cellX = well.x() + 1 + index % DOSE_COLUMNS * cellWidth;
            int cellY = well.y() + 1 + index / DOSE_COLUMNS * DOSE_HEIGHT;
            dose(doses.get(index), new Glass.Box(cellX, cellY, cellWidth, DOSE_HEIGHT), mouse);
        }
        y = well.bottom() + BLOCK_GAP;
    }

    private void dose(FurnaceDose dose, Glass.Box cell, ScreenPosition mouse) {
        ItemStack stack = BrewNames.item(dose.getItem());
        graphics.item(stack, cell.x() + Glass.ICON_INSET, cell.y() + 1);
        String count = dose.getAdded() + "/" + dose.getRequired();
        int countX = cell.right() - DOSE_INSET - font.width(count);
        int countColor = dose.getAdded() >= dose.getRequired() ? Glass.ACCENT_TEXT : Glass.TEXT;
        graphics.text(font, count, countX, cell.y() + DOSE_TEXT_Y, countColor, false /* dropShadow */);
        int nameX = cell.x() + DOSE_TEXT_X;
        FormattedCharSequence name = Glass.clip(font, stack.getHoverName(), countX - DOSE_INSET - nameX);
        Glass.text(graphics, font, name, nameX, cell.y() + DOSE_TEXT_Y, Glass.TEXT);
        if (cell.contains(mouse.x(), mouse.y())) {
            Component tip = Component.translatable(KEY + "dose_tip", BrewNames.slot(dose.getSlot()),
                    stack.getHoverName());
            if (dose.getMissed()) {
                String message = dose.getLateAllowed() ? "late" : "closed";
                tip = Component.translatable(KEY + message, stack.getHoverName());
            }
            graphics.setTooltipForNextFrame(font, tip, mouse.x(), mouse.y());
        }
    }

    private void heat() {
        // 火种、火档和锅温写成一行，下面一条槽按 0 到 100 度填。
        FurnaceClock clock = view.getClock();
        Component value = Component.translatable(KEY + "heat_value", BrewNames.fire(clock.getFire()),
                clock.getGrade(), BrewNames.number(Math.rint(clock.getTemperature())));
        line("heat", value);
        Glass.Box track = new Glass.Box(valueX, y, box.right() - valueX, TRACK_HEIGHT);
        Glass.track(graphics, track, clock.getTemperature() / FULL_DEGREES);
        y += TRACK_STEP;
    }

    private void supplies() {
        // 余燃、水、余瓶三项排成一行；水写「现有/装满」。
        FurnaceClock clock = view.getClock();
        int column = box.width() / SUPPLY_COLUMNS;
        if (clock.getContinuousFire()) {
            text(Component.translatable(KEY + "continuous"), box.x(), column, Glass.TEXT);
        } else {
            pair("fuel", Component.translatable(KEY + "seconds", clock.getFuel()), box.x(), column);
        }
        pair("water", Component.literal(water(clock.getWater())), box.x() + column, column);
        pair("bottles", Component.literal(Integer.toString(clock.getBottles())), box.x() + column * 2, column);
        y += LINE_STEP;
    }

    private String water(int units) {
        // 装满是多少按炉型查规则表；认不出的炉型只写现有的。
        for (VesselKind kind : VesselKind.values()) {
            if (kind.name().equals(view.getCatalog().getVessel())) {
                return units + "/" + kind.waterCapacity();
            }
        }
        return Integer.toString(units);
    }

    private void elapsed() {
        // 已熬多少秒和当前时窗；下面一条槽按整锅的熬制时长填，时窗那一段在槽下方画一条亮线。
        FurnaceClock clock = view.getClock();
        int column = box.width() / SUPPLY_COLUMNS;
        Component seconds = Component.translatable(KEY + "seconds", BrewNames.number(Math.floor(clock.getElapsed())));
        pair("elapsed", seconds, box.x(), column);
        boolean windowed = clock.getWindowEnd() > 0;
        if (windowed) {
            Component window = Component.translatable(KEY + "window_value",
                    BrewNames.number(clock.getWindowStart()), BrewNames.number(clock.getWindowEnd()));
            pair("window", window, box.x() + column, column * 2);
        }
        y += LINE_STEP;
        Glass.Box track = new Glass.Box(box.x(), y, box.width(), TRACK_HEIGHT);
        Glass.track(graphics, track, clock.getElapsed() / BrewCauldron.BREW_SECONDS);
        if (windowed) {
            int from = track.x() + (int) (track.width() * clock.getWindowStart() / BrewCauldron.BREW_SECONDS);
            int to = track.x() + (int) (track.width() * clock.getWindowEnd() / BrewCauldron.BREW_SECONDS);
            graphics.fill(from, track.bottom(), to, track.bottom() + 1, Glass.TEXT);
        }
        y += TRACK_STEP;
    }

    private void pair(String label, Component value, int x, int width) {
        Component name = Component.translatable(KEY + label);
        int nameWidth = font.width(name);
        Glass.text(graphics, font, name.getVisualOrderText(), x, y, Glass.DIM);
        int valueLeft = x + nameWidth + PAIR_GAP;
        Glass.text(graphics, font, Glass.clip(font, value, x + width - PAIR_GAP - valueLeft), valueLeft, y,
                Glass.TEXT);
    }

    private void missing() {
        // 缺的东西用顿号连成一句，最多折两行；什么都不缺就不画这一行。
        List<Component> names = missing(view);
        if (names.isEmpty()) {
            return;
        }
        Component separator = Component.translatable(KEY + "separator");
        Component joined = ComponentUtils.formatList(names, separator);
        text(Component.translatable(KEY + "missing"), box.x(), valueX - box.x(), Glass.WARNING);
        int lines = Math.max(1, Math.min(MISSING_LINES, (box.bottom() - y) / LINE_STEP));
        for (FormattedCharSequence line : Glass.wrap(font, joined, box.right() - valueX, lines)) {
            Glass.text(graphics, font, line, valueX, y, Glass.TEXT);
            y += LINE_STEP;
        }
    }
}
