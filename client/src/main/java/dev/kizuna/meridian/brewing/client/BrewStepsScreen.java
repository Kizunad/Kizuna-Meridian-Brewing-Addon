package dev.kizuna.meridian.brewing.client;

import dev.kizuna.meridian.brewing.protocol.pb.BrewTimeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 药方书末页「炼法」打开的独立演示：没有炉子，只有本机的演示游标，画法和炉窗里的步骤视图是同一套
 * （见 {@link BrewStepsPane}）。「返回」或 Esc 回到来时的那本书，书还停在原来那一页。
 */
public final class BrewStepsScreen extends Screen {
    private static final int LEFT_BUTTON = 0;
    private final BrewTimeline timeline;
    private final Screen parent;
    private BrewStepsPane pane;

    BrewStepsScreen(BrewTimeline timeline, Screen parent) {
        super(Component.translatable(BrewNames.SCREEN + "furnace.steps"));
        this.timeline = timeline;
        this.parent = parent;
    }

    @Override
    protected void init() {
        // 底板和炉窗一样大、摆在同一处；窗口尺寸变了会重进这里，演示从头再放。
        pane = new BrewStepsPane(Glass.content(Glass.window(width, height)), timeline);
        pane.buttons(this::onClose).forEach(this::addRenderableWidget);
    }

    @Override
    public void tick() {
        pane.tick();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // 和炉窗一样不压暗世界，只画半透明底板；字幕照原版在背景这一层登记。
        Glass.Box panel = Glass.window(width, height);
        Glass.panel(graphics, panel);
        Glass.title(graphics, font, pane.title(), panel);
        pane.wells(graphics);
        minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        pane.draw(graphics, font, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 点某一行就把演示游标挪过去；其余交给按钮。
        if (event.button() == LEFT_BUTTON && pane.clicked(event.x(), event.y())) {
            AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return pane.scrolled(mouseX, mouseY, scrollY);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    public BrewStepsPane pane() {
        return pane;
    }
}
