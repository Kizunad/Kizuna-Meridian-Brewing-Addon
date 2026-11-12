package dev.kizuna.meridian.brewing.client;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewTowerMenu;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewRecipe;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewTowerController;
import dev.kizuna.meridian.brewing.server.FireKind;
import dev.kizuna.meridian.brewing.server.HeatBand;
import dev.kizuna.meridian.brewing.server.TowerNeed;
import dev.kizuna.meridian.brewing.server.TowerPhase;
import dev.kizuna.meridian.brewing.server.TowerStatus;
import dev.kizuna.meridian.brewing.server.VesselKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 三阶药塔的窗口：上排 6 张药方（主料图标，没学会的盖灰点不了）和「停」，下排形态、单批 / 连续两个切换钮，
 * 再往下是状态、温度条、火、燃料、进度、铜箱子和真元，最后一行是开工还缺的东西。
 *
 * <p>内容全部来自 {@link BrewTowerMenu} 的整数表，按钮只发原版 {@code clickMenuButton} 的编号；没学会的药方
 * 在这里点不了只是提示，服务端照样会拒。缺料一行按 {@link BrewTowerController#requirements} 同一张表画。
 * 底板照原版容器的灰色画法，不用自制贴图（ui-neutral-chrome）。</p>
 */
final class BrewTowerScreen extends AbstractContainerScreen<BrewTowerMenu> {
    private static final BrewKind[] KINDS = BrewKind.values();
    private static final TowerStatus[] STATUSES = TowerStatus.values();
    private static final BrewForm[] FORMS = BrewForm.values();
    private static final FireKind[] FIRES = FireKind.values();
    private static final TowerNeed[] NEEDS = TowerNeed.values();
    private static final String KEY = "screen.meridian_brewing.tower.";
    private static final String MESSAGE = "message.meridian_brewing.";
    private static final Identifier TILE = Identifier.withDefaultNamespace("container/stonecutter/recipe");
    private static final Identifier TILE_SELECTED =
            Identifier.withDefaultNamespace("container/stonecutter/recipe_selected");
    private static final Identifier TILE_HIGHLIGHTED =
            Identifier.withDefaultNamespace("container/stonecutter/recipe_highlighted");
    private static final Identifier CHEST_ICON = Identifier.withDefaultNamespace("waxed_copper_chest");
    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 166;
    private static final int MARGIN = 8;
    private static final int TILE_Y = 18;
    private static final int TILE_WIDTH = 16;
    private static final int TILE_HEIGHT = 18;
    private static final int TILE_ICON_OFFSET = 1;
    private static final int BUTTON_HEIGHT = 20;
    private static final int STOP_X = 112;
    private static final int STOP_WIDTH = 56;
    private static final int TOGGLE_Y = 40;
    private static final int TOGGLE_WIDTH = 78;
    private static final int MODE_X = 90;
    private static final int STATUS_Y = 66;
    private static final int LINE_STEP = 11;
    private static final int BAR_Y = 88;
    private static final int BAR_HEIGHT = 5;
    private static final int FIRE_Y = 98;
    private static final int NEED_Y = 140;
    private static final int NEED_STEP = 18;
    private static final int NEED_GAP = 2;
    private static final int TEXT_ICON_OFFSET = 4;
    private static final double BAR_MIN_DEGREES = 20;
    private static final double BAR_MAX_DEGREES = 100;
    private static final double TENTHS = 10.0;
    private static final long BREW_SECONDS = Math.round(BrewCauldron.BREW_SECONDS);
    private static final int NONE = -1;
    private static final int LEFT_BUTTON = 0;
    private static final float CLICK_PITCH = 1.0f;
    private static final int LABEL_COLOR = 0xFF404040;
    private static final int WARNING_COLOR = 0xFFAA0000;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int EDGE_COLOR = 0xFF000000;
    private static final int LIGHT_COLOR = 0xFFFFFFFF;
    private static final int SHADOW_COLOR = 0xFF555555;
    private static final int INSET_COLOR = 0xFF8B8B8B;
    private static final int INSET_SHADOW_COLOR = 0xFF373737;
    private static final int BAND_COLOR = 0xFF5D9E3A;
    private static final int MARKER_COLOR = 0xFFFFFFFF;
    private static final int UNKNOWN_OVERLAY = 0xB0C6C6C6;
    private static final int BEVEL = 2;
    private static final int EDGE = 1;
    private Button stopButton;
    private Button formButton;
    private Button modeButton;

    BrewTowerScreen(BrewTowerMenu menu, Inventory inventory, Component title) {
        // 没有物品栏，底板大小照切石机；标题照原版位置。
        super(menu, inventory, title, IMAGE_WIDTH, IMAGE_HEIGHT);
    }

    @Override
    protected void init() {
        // 原版先算好 leftPos、topPos，按钮跟着底板摆；字和能不能按每 tick 按整数表刷新。
        super.init();
        stopButton = button(Component.translatable(KEY + "stop"), STOP_X, TILE_Y, STOP_WIDTH,
                pressed -> send(BrewTowerMenu.BUTTON_STOP));
        formButton = button(formLabel(BrewForm.DRINK), MARGIN, TOGGLE_Y, TOGGLE_WIDTH, pressed -> toggleForm());
        modeButton = button(modeLabel(), MODE_X, TOGGLE_Y, TOGGLE_WIDTH, pressed -> toggleMode());
        containerTick();
    }

    private Button button(Component text, int x, int y, int width, Button.OnPress press) {
        // 原版按钮贴图；坐标按底板左上角算。
        Button.Builder builder = Button.builder(text, press);
        builder.bounds(leftPos + x, topPos + y, width, BUTTON_HEIGHT);
        return addRenderableWidget(builder.build());
    }

    @Override
    protected void containerTick() {
        // 形态钮显示这一批实际出的形态，药方只认一种时按不动；停只在塔在干活或锅里有东西时能按。
        BrewForm shown = shownForm();
        formButton.setMessage(formLabel(shown));
        formButton.active = selected().map(BrewTowerScreen::bothForms).orElse(true);
        modeButton.setMessage(modeLabel());
        boolean idle = menu.data(BrewTowerMenu.DATA_PHASE) == TowerPhase.IDLE.ordinal();
        stopButton.active = menu.data(BrewTowerMenu.DATA_ARMED) == BrewTowerMenu.FLAG_ON || !idle;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // 原版先把世界压暗，再画灰底板、药方格和温度条的凹槽。
        super.extractBackground(graphics, mouseX, mouseY, a);
        panel(graphics, new Box(leftPos, topPos, imageWidth, imageHeight));
        int tilesWidth = TILE_WIDTH * KINDS.length;
        inset(graphics, new Box(leftPos + MARGIN, topPos + TILE_Y, tilesWidth, TILE_HEIGHT));
        inset(graphics, new Box(leftPos + MARGIN, topPos + BAR_Y, barWidth(), BAR_HEIGHT));
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // 坐标已平移到底板左上角；只画标题，这个窗口没有「物品栏」那一行。
        graphics.text(font, title, titleLabelX, titleLabelY, LABEL_COLOR, false);
        extractTiles(graphics, mouseX, mouseY);
        extractStatus(graphics);
        extractNeeds(graphics);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // 药方格写名字、火和火带，没学会的写怎么学；缺料图标写缺什么。
        super.extractTooltip(graphics, mouseX, mouseY);
        Optional<BrewKind> tile = tileAt(mouseX, mouseY);
        if (tile.isPresent()) {
            graphics.setComponentTooltipForNextFrame(font, tileTooltip(tile.orElseThrow()), mouseX, mouseY);
            return;
        }
        List<NeedIcon> icons = needIcons();
        for (int index = 0; index < icons.size(); index++) {
            if (isHovering(needX(index), NEED_Y, NEED_STEP, NEED_STEP, mouseX, mouseY)) {
                graphics.setComponentTooltipForNextFrame(font, List.of(icons.get(index).tooltip()), mouseX, mouseY);
                return;
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 左键点到学会的药方就选它（选了塔就开工）；没学会的点了不发包。
        Optional<BrewKind> tile = tileAt(event.x(), event.y());
        if (event.button() == LEFT_BUTTON && tile.isPresent()) {
            BrewKind kind = tile.orElseThrow();
            if (known(kind)) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, CLICK_PITCH));
                send(BrewTowerMenu.BUTTON_FIRST_RECIPE + kind.ordinal());
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void extractTiles(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // 选中的用原版「已选」底，鼠标下的用「高亮」底；没学会的在图标上盖一层灰。
        Optional<BrewKind> chosen = selected();
        Optional<BrewKind> hovered = tileAt(mouseX, mouseY);
        for (BrewKind kind : KINDS) {
            int x = tileX(kind);
            Identifier sprite = TILE;
            if (chosen.equals(Optional.of(kind))) {
                sprite = TILE_SELECTED;
            } else if (hovered.equals(Optional.of(kind)) && known(kind)) {
                sprite = TILE_HIGHLIGHTED;
            }
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, TILE_Y, TILE_WIDTH, TILE_HEIGHT);
            graphics.item(primary(kind), x, TILE_Y + TILE_ICON_OFFSET);
            if (!known(kind)) {
                graphics.fill(x, TILE_Y, x + TILE_WIDTH, TILE_Y + TILE_HEIGHT, UNKNOWN_OVERLAY);
            }
        }
    }

    private void extractStatus(GuiGraphicsExtractor graphics) {
        // 一行一样：状态、温度和火带、温度条、火力和燃料、本批进度、铜箱子和真元。
        TowerStatus status = STATUSES[menu.data(BrewTowerMenu.DATA_STATUS)];
        Component statusName = Component.translatable(KEY + "status." + status.name().toLowerCase(Locale.ROOT));
        line(graphics, Component.translatable(KEY + "status", statusName), STATUS_Y);
        line(graphics, temperatureLine(), STATUS_Y + LINE_STEP);
        extractBar(graphics);
        line(graphics, fireLine(), FIRE_Y);
        String progress = tenths(menu.data(BrewTowerMenu.DATA_PROGRESS_TENTHS));
        Component batch = Component.translatable(KEY + "batch", menu.data(BrewTowerMenu.DATA_BOTTLES), progress,
                BREW_SECONDS);
        line(graphics, batch, FIRE_Y + LINE_STEP);
        Component chest = Component.translatable(KEY + "chest", menu.data(BrewTowerMenu.DATA_CHEST_FREE),
                menu.data(BrewTowerMenu.DATA_QI_PERCENT));
        line(graphics, chest, FIRE_Y + LINE_STEP * 2);
    }

    private Component temperatureLine() {
        // 有药方时带上火带；都没选时只写温度。
        String temperature = tenths(menu.data(BrewTowerMenu.DATA_TEMPERATURE_TENTHS));
        int bandMin = menu.data(BrewTowerMenu.DATA_BAND_MIN);
        if (bandMin == NONE) {
            return Component.translatable(KEY + "temperature", temperature);
        }
        int bandMax = menu.data(BrewTowerMenu.DATA_BAND_MAX);
        return Component.translatable(KEY + "temperature_band", temperature, bandMin, bandMax);
    }

    private Component fireLine() {
        // 火力档、火种、火室里点着的燃料还能烧几秒。
        Component fire = fireName(FIRES[menu.data(BrewTowerMenu.DATA_FIRE)]);
        return Component.translatable(KEY + "fire", menu.data(BrewTowerMenu.DATA_LEVEL), fire,
                menu.data(BrewTowerMenu.DATA_FUEL_SECONDS));
    }

    private void extractBar(GuiGraphicsExtractor graphics) {
        // 条上 20°–100°：火带涂绿，当前温度画一道白线。
        int bandMin = menu.data(BrewTowerMenu.DATA_BAND_MIN);
        if (bandMin != NONE) {
            int left = barX(bandMin);
            int right = barX(menu.data(BrewTowerMenu.DATA_BAND_MAX));
            graphics.fill(left, BAR_Y, right, BAR_Y + BAR_HEIGHT, BAND_COLOR);
        }
        int marker = barX(menu.data(BrewTowerMenu.DATA_TEMPERATURE_TENTHS) / TENTHS);
        graphics.fill(marker, BAR_Y, marker + EDGE, BAR_Y + BAR_HEIGHT, MARKER_COLOR);
    }

    private void extractNeeds(GuiGraphicsExtractor graphics) {
        // 没选药方时提示先选；缺的东西一样一个图标，物品带数量，环境真元没有物品就写字。
        if (selected().isEmpty()) {
            line(graphics, Component.translatable(KEY + "pick"), NEED_Y + TEXT_ICON_OFFSET);
            return;
        }
        List<NeedIcon> icons = needIcons();
        if (icons.isEmpty()) {
            return;
        }
        graphics.text(font, needsLabel(), MARGIN, NEED_Y + TEXT_ICON_OFFSET, LABEL_COLOR, false);
        for (int index = 0; index < icons.size(); index++) {
            NeedIcon icon = icons.get(index);
            int x = needX(index);
            if (icon.stack().isEmpty()) {
                Component qi = Component.translatable(KEY + "need.qi");
                graphics.text(font, qi, x, NEED_Y + TEXT_ICON_OFFSET, WARNING_COLOR, false);
                continue;
            }
            graphics.item(icon.stack(), x, NEED_Y);
            graphics.itemDecorations(font, icon.stack(), x, NEED_Y);
        }
    }

    private List<NeedIcon> needIcons() {
        // 按开工前查的顺序；物品类的从药方表取料名和件数，其余按缺的那一项给图标。
        int mask = menu.data(BrewTowerMenu.DATA_NEEDS);
        Optional<BrewKind> kind = selected();
        if (mask == 0 || kind.isEmpty()) {
            return List.of();
        }
        BrewRecipe recipe = BrewRecipes.require(kind.orElseThrow());
        var icons = new ArrayList<NeedIcon>();
        for (TowerNeed need : NEEDS) {
            if ((mask & need.bit()) != 0) {
                icons.add(needIcon(need, recipe));
            }
        }
        return icons;
    }

    private NeedIcon needIcon(TowerNeed need, BrewRecipe recipe) {
        // 提示文字和服务端写进动作栏的是同一批语言键。
        return switch (need) {
            case CHEST -> new NeedIcon(stack(CHEST_ICON, 1),
                    Component.translatable(MESSAGE + "tower.need.chest", VesselKind.TOWER.waterCapacity()));
            case FUEL -> new NeedIcon(new ItemStack(Items.COAL_BLOCK),
                    Component.translatable(MESSAGE + "tower.need.fuel", BrewTowerController.stableLevel(recipe)));
            case FIRE_STARTER -> starterIcon(recipe.fireKind());
            case AMBIENT_QI -> new NeedIcon(ItemStack.EMPTY, Component.translatable(KEY + "need.qi_tooltip"));
            default -> itemIcon(need, recipe);
        };
    }

    private NeedIcon starterIcon(FireKind fire) {
        // 图标用第一种能换火的东西，提示里写全。
        String first = BrewTowerController.starters(fire).getFirst();
        String key = MESSAGE + "tower.need.starter." + fire.name().toLowerCase(Locale.ROOT);
        return new NeedIcon(stack(Identifier.parse(first), 1), Component.translatable(key));
    }

    private NeedIcon itemIcon(TowerNeed need, BrewRecipe recipe) {
        // 主料、底料、火药、辅料、玻璃瓶：药方表里写着件数。
        BrewForm form = FORMS[menu.data(BrewTowerMenu.DATA_FORM)];
        for (BrewTowerController.Requirement requirement : BrewTowerController.requirements(recipe, form)) {
            if (requirement.need() == need) {
                ItemStack stack = stack(BrewItemStacks.recipeItemId(requirement.itemId()), requirement.count());
                Component tooltip = Component.translatable(MESSAGE + "tower.need.item", stack.getHoverName(),
                        requirement.count());
                return new NeedIcon(stack, tooltip);
            }
        }
        throw new IllegalStateException("药塔缺料项不在药方表里：" + need);
    }

    private List<Component> tileTooltip(BrewKind kind) {
        // 名字一行；学会的写火和火带，没学会的写怎么学。
        Component name = Component.translatable("potion.meridian_brewing_" + kind.path());
        if (!known(kind)) {
            return List.of(name, Component.translatable(KEY + "unknown"));
        }
        BrewRecipe recipe = BrewRecipes.require(kind);
        HeatBand band = recipe.heatBand();
        Component fire = Component.translatable(KEY + "recipe_fire", fireName(recipe.fireKind()),
                Math.round(band.minimumDegrees()), Math.round(band.maximumDegrees()));
        return List.of(name, fire);
    }

    private Optional<BrewKind> tileAt(double mouseX, double mouseY) {
        // 6 格一排，鼠标落在哪格就是哪张药方。
        for (BrewKind kind : KINDS) {
            if (isHovering(tileX(kind), TILE_Y, TILE_WIDTH, TILE_HEIGHT, mouseX, mouseY)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    private void toggleForm() {
        // 喝换泼、泼换喝；药方只认一种时按钮按不动，走不到这里。
        int button = BrewTowerMenu.BUTTON_SPLASH;
        if (shownForm() == BrewForm.SPLASH) {
            button = BrewTowerMenu.BUTTON_DRINK;
        }
        send(button);
    }

    private void toggleMode() {
        // 单批换连续、连续换单批。
        int button = BrewTowerMenu.BUTTON_CONTINUOUS;
        if (continuous()) {
            button = BrewTowerMenu.BUTTON_ONCE;
        }
        send(button);
    }

    private void send(int button) {
        // 和切石机一样只发编号，服务端照做或不认；结果随整数表同步回来。
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }

    private BrewForm shownForm() {
        // 选了药方就按药方能出的形态显示；没选时显示窗口里选的。
        BrewForm chosen = FORMS[menu.data(BrewTowerMenu.DATA_FORM)];
        return selected().map(kind -> BrewTowerController.formFor(kind, chosen)).orElse(chosen);
    }

    private Optional<BrewKind> selected() {
        // 没选是 -1。
        int index = menu.data(BrewTowerMenu.DATA_SELECTED);
        if (index == NONE) {
            return Optional.empty();
        }
        return Optional.of(KINDS[index]);
    }

    private boolean known(BrewKind kind) {
        // 看窗口这人学会的药方，第 i 位对应第 i 张。
        return (menu.data(BrewTowerMenu.DATA_KNOWN) & (1 << kind.ordinal())) != 0;
    }

    private void line(GuiGraphicsExtractor graphics, Component text, int y) {
        // 原版容器标签的颜色，不带阴影。
        graphics.text(font, text, MARGIN, y, LABEL_COLOR, false);
    }

    private Component needsLabel() {
        // 缺料行的开头。
        return Component.translatable(KEY + "needs");
    }

    private int needX(int index) {
        // 「缺：」后面一格一个图标。
        return MARGIN + font.width(needsLabel()) + NEED_GAP + index * NEED_STEP;
    }

    private int barWidth() {
        // 温度条和文字同宽。
        return imageWidth - MARGIN * 2;
    }

    private int barX(double degrees) {
        // 20° 在最左，100° 在最右，超出的贴边。
        double clamped = Math.max(BAR_MIN_DEGREES, Math.min(BAR_MAX_DEGREES, degrees));
        double fraction = (clamped - BAR_MIN_DEGREES) / (BAR_MAX_DEGREES - BAR_MIN_DEGREES);
        return MARGIN + (int) Math.round(fraction * barWidth());
    }

    private static int tileX(BrewKind kind) {
        // 药方格从左往右按 BrewKind 的顺序排。
        return MARGIN + kind.ordinal() * TILE_WIDTH;
    }

    private static boolean bothForms(BrewKind kind) {
        // 两样都能出的药方才让玩家切形态。
        return BrewTowerController.formFor(kind, BrewForm.DRINK) != BrewTowerController.formFor(kind, BrewForm.SPLASH);
    }

    private static ItemStack primary(BrewKind kind) {
        // 药方格的图标是它的主料（晒过的那种）。
        String primary = BrewRecipes.require(kind).ingredients().preparedPrimary();
        return stack(BrewItemStacks.recipeItemId(primary), 1);
    }

    private static ItemStack stack(Identifier itemId, int count) {
        // 只拿来画图标和数量。
        return new ItemStack(BuiltInRegistries.ITEM.getValue(itemId), count);
    }

    private static Component formLabel(BrewForm form) {
        // 「形态：喝」或「形态：泼」。
        return Component.translatable(KEY + "form." + form.name().toLowerCase(Locale.ROOT));
    }

    private boolean continuous() {
        // 塔现在是连续熬还是只熬一批。
        return menu.data(BrewTowerMenu.DATA_CONTINUOUS) == BrewTowerMenu.FLAG_ON;
    }

    private Component modeLabel() {
        // 「只熬一批」或「连续熬」。
        if (continuous()) {
            return Component.translatable(KEY + "mode.continuous");
        }
        return Component.translatable(KEY + "mode.once");
    }

    private static Component fireName(FireKind fire) {
        // 和火盆提示同一组火种名。
        return Component.translatable(MESSAGE + "fire." + fire.name().toLowerCase(Locale.ROOT));
    }

    private static String tenths(int tenths) {
        // 整数表里是十分之一，显示一位小数。
        return String.format(Locale.ROOT, "%.1f", tenths / TENTHS);
    }

    private static void panel(GuiGraphicsExtractor graphics, Box box) {
        // 原版容器底板：黑边去角，左上白高光、右下深灰阴影，中间浅灰。
        int left = box.left();
        int top = box.top();
        int right = box.right();
        int bottom = box.bottom();
        graphics.fill(left + EDGE, top, right - EDGE, top + EDGE, EDGE_COLOR);
        graphics.fill(left + EDGE, bottom - EDGE, right - EDGE, bottom, EDGE_COLOR);
        graphics.fill(left, top + EDGE, left + EDGE, bottom - EDGE, EDGE_COLOR);
        graphics.fill(right - EDGE, top + EDGE, right, bottom - EDGE, EDGE_COLOR);
        graphics.fill(left + EDGE, top + EDGE, right - EDGE, bottom - EDGE, LIGHT_COLOR);
        graphics.fill(left + EDGE + BEVEL, top + EDGE + BEVEL, right - EDGE, bottom - EDGE, SHADOW_COLOR);
        graphics.fill(left + EDGE + BEVEL, top + EDGE + BEVEL, right - EDGE - BEVEL, bottom - EDGE - BEVEL,
                PANEL_COLOR);
    }

    private static void inset(GuiGraphicsExtractor graphics, Box box) {
        // 原版物品格的凹槽：外一圈左上深灰、右下白，里面中灰。
        int left = box.left() - EDGE;
        int top = box.top() - EDGE;
        int right = box.right() + EDGE;
        int bottom = box.bottom() + EDGE;
        graphics.fill(left, top, right, bottom, LIGHT_COLOR);
        graphics.fill(left, top, right - EDGE, bottom - EDGE, INSET_SHADOW_COLOR);
        graphics.fill(box.left(), box.top(), box.right(), box.bottom(), INSET_COLOR);
    }

    /** 缺的一样东西：图标（环境真元没有物品，是空的）和悬停提示。 */
    private record NeedIcon(ItemStack stack, Component tooltip) {
    }

    /** 屏幕坐标里的一块矩形。 */
    private record Box(int left, int top, int width, int height) {
        int right() {
            return left + width;
        }

        int bottom() {
            return top + height;
        }
    }
}
