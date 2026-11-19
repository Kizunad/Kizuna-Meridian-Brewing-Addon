package dev.kizuna.meridian.brewing.client;

import dev.kizuna.meridian.brewing.fabric.FurnaceMenu;
import dev.kizuna.meridian.brewing.protocol.pb.BrewTimeline;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceChoice;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceRequest;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceView;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import dev.kizuna.meridian.brewing.server.VesselKind;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

/**
 * 炼药窗口：空手点成形的大炉打开。左边列出自己读过、这座炉能炼的药方和饮用 / 泼洒两个剂型钮，
 * 右边是炉子的状态（见 {@link FurnaceStatus}），右下角「取回原料」和「看炼法」。没有物品格，也不画背包。
 *
 * <p>内容全部来自服务端发的 {@code FurnaceView}，每次变化整份换掉，画面不往前推算。点药方、换剂型、取回原料、
 * 看炼法都只发请求，带上手里这一份的选方版本号；版本过期时服务端只回一份新的状态，画面跟着刷新。</p>
 *
 * <p>「看炼法」把窗口换成步骤演示（见 {@link BrewStepsPane}），菜单照旧开着，「返回」或 Esc 切回来。
 * 演示拿到以后，选方版本或批号一变就向服务端再要一份。</p>
 */
public final class FurnaceScreen extends AbstractContainerScreen<FurnaceMenu> {
    private static final String KEY = BrewNames.SCREEN + "furnace.";
    private static final int GAP = 4;
    private static final int LIST_WIDTH = 128;
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_TEXT_X = 21;
    private static final int ROW_TEXT_Y = 5;
    private static final int FORM_HEIGHT = 14;
    /** 两个剂型钮离凹槽边、彼此之间的间隔。 */
    private static final int FORM_INSET = 2;
    private static final int BUTTON_HEIGHT = 16;
    private static final int STEPS_WIDTH = 72;
    private static final int RECLAIM_WIDTH = 88;
    private static final int SCROLLBAR = 2;
    private static final int HINT_LINES = 6;
    private static final int LINE_STEP = 10;
    private static final int LEFT_BUTTON = 0;
    private static final int NONE = -1;
    private static final long NOT_REQUESTED = -1;
    private FurnaceView view;
    /** 还没选方时手上点着的剂型；选了方以后剂型钮跟服务端记下的下一方走。 */
    private BrewForm preferred = BrewForm.DRINK;
    private BrewStepsPane pane;
    private List<GlassButton> paneButtons = List.of();
    private long requestedSteps = NOT_REQUESTED;
    private boolean wantsSteps;
    private int scroll;
    private GlassButton drinkButton;
    private GlassButton splashButton;
    private GlassButton stepsButton;
    private GlassButton reclaimButton;

    /** 画面上现在有什么，客户端场景拿它核对；和画的是同一份判断。 */
    public record Shown(List<String> recipes, Optional<String> highlighted, boolean concealed, int doses,
                        List<String> missing, boolean reclaim, boolean steps, boolean awaitingWorker) {}

    FurnaceScreen(FurnaceMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, Glass.WINDOW_WIDTH, Glass.WINDOW_HEIGHT);
    }

    static void register() {
        // Fabric 的事件登记以后撤不掉，所以只登记一次，收到的状态和步骤只转给此刻开着的炉窗；窗口一关就没人收了。
        MenuScreens.register(FurnaceMenu.type(), FurnaceScreen::new);
        FurnaceClientData.VIEW.register(update -> open().ifPresent(screen -> screen.accept(update)));
        FurnaceClientData.STEPS.register(steps -> open().ifPresent(screen -> screen.accept(steps)));
    }

    private static Optional<FurnaceScreen> open() {
        if (Minecraft.getInstance().gui.screen() instanceof FurnaceScreen screen) {
            return Optional.of(screen);
        }
        return Optional.empty();
    }

    @Override
    protected void init() {
        // 原版把窗口摆在正中，这里换到和独立演示相同的位置；窗口尺寸变了会重进这里，按钮照新位置重摆，正在看的演示从头再放。
        super.init();
        topPos = Glass.window(width, height).y();
        Glass.Box list = listWell();
        int formY = list.bottom() - FORM_INSET - FORM_HEIGHT;
        int formWidth = (list.width() - FORM_INSET * 3) / 2;
        int splashX = list.x() + FORM_INSET * 2 + formWidth;
        drinkButton = formButton(new Glass.Box(list.x() + FORM_INSET, formY, formWidth, FORM_HEIGHT), BrewForm.DRINK);
        splashButton = formButton(new Glass.Box(splashX, formY, formWidth, FORM_HEIGHT), BrewForm.SPLASH);
        Glass.Box content = Glass.content(panel());
        int buttonY = content.bottom() - BUTTON_HEIGHT;
        int stepsX = content.right() - STEPS_WIDTH;
        stepsButton = addRenderableWidget(new GlassButton(
                new Glass.Box(stepsX, buttonY, STEPS_WIDTH, BUTTON_HEIGHT),
                Component.translatable(KEY + "steps"), pressed -> openSteps()));
        reclaimButton = addRenderableWidget(new GlassButton(
                new Glass.Box(stepsX - GAP - RECLAIM_WIDTH, buttonY, RECLAIM_WIDTH, BUTTON_HEIGHT),
                Component.translatable(KEY + "reclaim"), pressed -> send(request().setReclaim(true /* reclaim */))));
        Optional<BrewTimeline> shown = steps().map(BrewStepsPane::timeline);
        paneButtons = List.of();
        pane = null;
        FurnaceClientData.view(menu.containerId).ifPresent(this::accept);
        shown.filter(steps -> wantsSteps).ifPresent(this::showSteps);
        refresh();
    }

    private GlassButton formButton(Glass.Box box, BrewForm kind) {
        Component label = BrewNames.form(kind.name());
        return addRenderableWidget(new GlassButton(box, label, pressed -> chooseForm(kind), () -> form() == kind));
    }

    private void accept(FurnaceView update) {
        // 只认自己这一个菜单的状态。演示对不上现在的选方版本或批号就再要一份。
        if (update.getMenu() != menu.containerId) {
            return;
        }
        view = update;
        scroll = Math.max(0, Math.min(scroll, update.getCatalog().getVisibleCount() - rows()));
        if (!offersSteps()) {
            closeSteps();
        } else if (wantsSteps && !stepsCurrent()) {
            requestSteps();
        }
        if (pane != null) {
            pane.follow(update.getClock().getCurrentStep());
        }
        refresh();
    }

    private void accept(BrewTimeline steps) {
        // 药方书要来的步骤菜单号是 0，不归炉窗；已经点了返回、或者不是现在这一版选方的也不要。
        if (steps.getMenu() != menu.containerId || !wantsSteps || view == null) {
            return;
        }
        var orders = view.getOrders();
        if (steps.getVersion() != orders.getVersion() || steps.getBatch() != orders.getBatch()) {
            return;
        }
        showSteps(steps);
        refresh();
    }

    private void showSteps(BrewTimeline steps) {
        paneButtons.forEach(this::removeWidget);
        pane = new BrewStepsPane(Glass.content(panel()), steps);
        paneButtons = pane.buttons(this::closeSteps);
        paneButtons.forEach(this::addRenderableWidget);
        if (view != null) {
            pane.follow(view.getClock().getCurrentStep());
        }
    }

    private void openSteps() {
        wantsSteps = true;
        requestSteps();
    }

    private void requestSteps() {
        // 同一版选方只要一次；服务端嫌版本旧时会回一份新状态，那时版本变了再要。
        long version = view.getOrders().getVersion();
        if (requestedSteps == version) {
            return;
        }
        requestedSteps = version;
        send(request().setFurnaceSteps(true /* furnaceSteps */));
    }

    private void closeSteps() {
        wantsSteps = false;
        requestedSteps = NOT_REQUESTED;
        paneButtons.forEach(this::removeWidget);
        paneButtons = List.of();
        pane = null;
        refresh();
    }

    private boolean stepsCurrent() {
        if (pane == null) {
            return false;
        }
        var orders = view.getOrders();
        BrewTimeline shown = pane.timeline();
        return shown.getVersion() == orders.getVersion() && shown.getBatch() == orders.getBatch();
    }

    private void chooseForm(BrewForm wanted) {
        // 已经选了方就连方带新剂型重发一次，服务端答应了剂型钮才跟着变；还没选方时只记在本机，点药方时带上。
        preferred = wanted;
        if (view != null && view.getOrders().hasNext()) {
            select(view.getOrders().getNext().getRecipe(), wanted);
        }
    }

    private void select(String recipe, BrewForm wanted) {
        // 这张方做不成想要的剂型时，按它做得成的那一种发，不发一个明知会被拒的请求。
        BrewForm sent = wanted;
        Optional<BrewKind> kind = RecipeKnowledge.kind(recipe);
        if (kind.isPresent() && !supports(kind.orElseThrow(), wanted)) {
            sent = other(wanted);
        }
        FurnaceChoice choice = FurnaceChoice.newBuilder()
                .setRecipe(recipe)
                .setForm(sent.name())
                .build();
        send(request().setSelect(choice));
    }

    private FurnaceRequest.Builder request() {
        // 每个请求都带菜单号和手里这份状态的选方版本号。
        return FurnaceRequest.newBuilder()
                .setMenu(menu.containerId)
                .setVersion(view.getOrders().getVersion());
    }

    private void send(FurnaceRequest.Builder request) {
        FurnaceClientData.request(request.build());
    }

    private static boolean supports(BrewKind kind, BrewForm wanted) {
        BrewUse use = wanted == BrewForm.DRINK ? BrewUse.DRINK : BrewUse.SPLASH;
        return kind.supports(use);
    }

    private static BrewForm other(BrewForm current) {
        return current == BrewForm.DRINK ? BrewForm.SPLASH : BrewForm.DRINK;
    }

    @Override
    protected void containerTick() {
        if (pane != null) {
            pane.tick();
        }
    }

    private void refresh() {
        // 两个视图共用一块底板：看步骤时把选方这一边的按钮藏起来。下一方做不成的剂型按不动。
        boolean status = pane == null;
        Optional<BrewKind> next = nextKind();
        drinkButton.visible = status;
        splashButton.visible = status;
        drinkButton.active = next.isEmpty() || supports(next.orElseThrow(), BrewForm.DRINK);
        splashButton.active = next.isEmpty() || supports(next.orElseThrow(), BrewForm.SPLASH);
        stepsButton.visible = status;
        stepsButton.active = offersSteps();
        reclaimButton.visible = status && offersReclaim();
    }

    private Optional<BrewKind> nextKind() {
        if (view == null || !view.getOrders().hasNext()) {
            return Optional.empty();
        }
        return RecipeKnowledge.kind(view.getOrders().getNext().getRecipe());
    }

    /** 服务端肯给步骤的时候才亮：看得见的锅里那一方或下一方。 */
    private boolean offersSteps() {
        return view != null && !view.getClock().getCurrentStep().isEmpty();
    }

    /** 锅里有没开熬的料、自己也认得那张方，服务端才收取回请求。 */
    private boolean offersReclaim() {
        return view != null && view.getClock().getReclaimable();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // 不照原版把整个世界压暗，只画一块半透明底板和几块凹槽，按钮和内容压在凹槽上面；字幕照原版在背景这一层登记。
        Glass.Box panel = panel();
        Glass.panel(graphics, panel);
        if (pane == null) {
            Glass.title(graphics, font, batchTitle(), panel);
            Glass.well(graphics, listWell());
            Glass.well(graphics, statusWell());
        } else {
            Glass.title(graphics, font, pane.title(), panel);
            pane.wells(graphics);
        }
        minecraft.gui.hud.extractDeferredSubtitles();
    }

    private Component batchTitle() {
        // 菜单名保留实际方块名称，产量和套数按目录中的炉型查同一张规则表。
        if (view != null) {
            String vessel = view.getCatalog().getVessel();
            for (var kind : VesselKind.values()) {
                if (kind.name().equals(vessel)) {
                    var batch = kind.batch();
                    return Component.translatable(KEY + "batch_title", title, batch.sets(), batch.bottles());
                }
            }
        }
        return title;
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);
        if (pane != null) {
            pane.draw(graphics, font, mouseX, mouseY);
            return;
        }
        recipes(graphics, mouseX, mouseY);
        if (view != null) {
            FurnaceStatus.draw(graphics, font, statusBox(), view, new ScreenPosition(mouseX, mouseY));
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // 标题画在底板的标题栏里，没有背包也就没有背包标题。
    }

    private void recipes(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // 药方一行一张：图标、药名；下一方整行铺青色、左边立青线，鼠标停着的铺白。一张都没有时写一句提示。
        Glass.Box well = listWell();
        List<String> recipes = catalog();
        if (recipes.isEmpty()) {
            hint(graphics, well);
            return;
        }
        Optional<String> next = highlighted();
        int last = Math.min(recipes.size(), scroll + rows());
        for (int index = scroll; index < last; index++) {
            String recipe = recipes.get(index);
            Glass.Box row = rowBox(index);
            boolean chosen = next.isPresent() && next.orElseThrow().equals(recipe);
            Glass.row(graphics, row, rowFace(chosen, row.contains(mouseX, mouseY)));
            if (chosen) {
                Glass.marker(graphics, row);
            }
            graphics.item(BrewNames.potion(recipe), row.x() + Glass.ICON_INSET, row.y() + 1);
            int nameWidth = row.width() - ROW_TEXT_X - Glass.ICON_INSET;
            FormattedCharSequence name = Glass.clip(font, BrewNames.recipe(recipe), nameWidth);
            Glass.text(graphics, font, name, row.x() + ROW_TEXT_X, row.y() + ROW_TEXT_Y, Glass.TEXT);
        }
        scrollbar(graphics, well, recipes.size());
    }

    private static Glass.Face rowFace(boolean chosen, boolean hovered) {
        if (chosen) {
            return Glass.Face.SELECTED;
        }
        return hovered ? Glass.Face.HOVERED : Glass.Face.IDLE;
    }

    private void hint(GuiGraphicsExtractor graphics, Glass.Box well) {
        int y = well.y() + GAP;
        Component hint = Component.translatable(KEY + "empty");
        for (FormattedCharSequence line : Glass.wrap(font, hint, well.width() - GAP * 2, HINT_LINES)) {
            Glass.text(graphics, font, line, well.x() + GAP, y, Glass.DIM);
            y += LINE_STEP;
        }
    }

    private void scrollbar(GuiGraphicsExtractor graphics, Glass.Box well, int count) {
        // 药方比能放的行数多时，右边一条细槽标出现在卷到哪。
        int rows = rows();
        if (count <= rows) {
            return;
        }
        int height = rows * ROW_HEIGHT;
        int x = well.right() - 1 - SCROLLBAR;
        int top = well.y() + 1 + height * scroll / count;
        int bottom = well.y() + 1 + height * (scroll + rows) / count;
        graphics.fill(x, well.y() + 1, x + SCROLLBAR, well.y() + 1 + height, Glass.TRACK);
        graphics.fill(x, top, x + SCROLLBAR, bottom, Glass.ACCENT);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 步骤行和药方行不是原版控件，自己判点中了哪一行；其余交给按钮。
        if (event.button() == LEFT_BUTTON && rowClicked(event.x(), event.y())) {
            AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean rowClicked(double mouseX, double mouseY) {
        if (pane != null) {
            return pane.clicked(mouseX, mouseY);
        }
        int index = recipeAt(mouseX, mouseY);
        if (index == NONE) {
            return false;
        }
        select(catalog().get(index), form());
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (pane != null) {
            return pane.scrolled(mouseX, mouseY, scrollY);
        }
        if (!listWell().contains(mouseX, mouseY)) {
            return false;
        }
        int wanted = scroll - (int) Math.signum(scrollY);
        scroll = Math.max(0, Math.min(Math.max(0, catalog().size() - rows()), wanted));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // 看步骤时按 Esc 先回到炉窗，再按才关窗。
        if (pane != null && event.isEscape()) {
            closeSteps();
            return true;
        }
        return super.keyPressed(event);
    }

    private Glass.Box panel() {
        return new Glass.Box(leftPos, topPos, Glass.WINDOW_WIDTH, Glass.WINDOW_HEIGHT);
    }

    private Glass.Box listWell() {
        Glass.Box content = Glass.content(panel());
        return new Glass.Box(content.x(), content.y(), LIST_WIDTH, content.height());
    }

    /** 状态区的凹槽：列表右边，到按钮那一排上方为止。 */
    private Glass.Box statusWell() {
        Glass.Box content = Glass.content(panel());
        int x = content.x() + LIST_WIDTH + GAP;
        return new Glass.Box(x, content.y(), content.right() - x, content.height() - BUTTON_HEIGHT - GAP);
    }

    private Glass.Box statusBox() {
        Glass.Box well = statusWell();
        return new Glass.Box(well.x() + GAP, well.y() + GAP / 2, well.width() - GAP * 2, well.height() - GAP);
    }

    /** 列表在剂型钮上方能放几行。 */
    private int rows() {
        return (listWell().height() - Glass.EDGE * 2 - FORM_HEIGHT - GAP) / ROW_HEIGHT;
    }

    private Glass.Box rowBox(int index) {
        Glass.Box well = listWell();
        int y = well.y() + 1 + (index - scroll) * ROW_HEIGHT;
        return new Glass.Box(well.x() + 1, y, well.width() - Glass.EDGE * 2 - SCROLLBAR - 1, ROW_HEIGHT);
    }

    private int recipeAt(double mouseX, double mouseY) {
        int last = Math.min(catalog().size(), scroll + rows());
        for (int index = scroll; index < last; index++) {
            if (rowBox(index).contains(mouseX, mouseY)) {
                return index;
            }
        }
        return NONE;
    }

    private List<String> catalog() {
        if (view == null) {
            return List.of();
        }
        return view.getCatalog().getVisibleList();
    }

    private Optional<String> highlighted() {
        if (view == null || !view.getOrders().hasNext()) {
            return Optional.empty();
        }
        return Optional.of(view.getOrders().getNext().getRecipe());
    }

    public Shown shown() {
        // 没收到状态时什么都没有；材料和缺项在看不到的时候算没有。
        if (view == null) {
            return new Shown(List.of(), Optional.empty(), false /* concealed */, 0, List.of(),
                    false /* reclaim */, false /* steps */, false /* awaitingWorker */);
        }
        boolean concealed = FurnaceStatus.concealed(view);
        int doses = 0;
        List<String> missing = List.of();
        if (!concealed) {
            doses = view.getClock().getDosesCount();
            missing = FurnaceStatus.missing(view).stream().map(Component::getString).toList();
        }
        return new Shown(catalog(), highlighted(), concealed, doses, missing, reclaimButton.visible,
                stepsButton.active, FurnaceStatus.awaitingWorker(view));
    }

    /** 当前批次过窗的药名和处理办法。 */
    public Optional<Component> missedDose() {
        if (view == null || FurnaceStatus.concealed(view)) {
            return Optional.empty();
        }
        return FurnaceStatus.missed(view.getClock());
    }

    /** 正在看的步骤演示；在炉窗这一面时为空。 */
    public Optional<BrewStepsPane> steps() {
        return Optional.ofNullable(pane);
    }

    /** 剂型钮现在亮的是哪一种：选了方就是服务端记下的那一种，没选方是手上点着的。 */
    public BrewForm form() {
        if (view != null && view.getOrders().hasNext()) {
            return BrewForm.valueOf(view.getOrders().getNext().getForm());
        }
        return preferred;
    }

    /** 列表里某张方那一行的中心，界面坐标；这张方不在可见的几行里时为空。 */
    public Optional<ScreenPosition> recipeCenter(String recipe) {
        int index = catalog().indexOf(recipe);
        if (index < scroll || index >= scroll + rows()) {
            return Optional.empty();
        }
        Glass.Box row = rowBox(index);
        return Optional.of(new ScreenPosition(row.x() + row.width() / 2, row.y() + row.height() / 2));
    }
}
