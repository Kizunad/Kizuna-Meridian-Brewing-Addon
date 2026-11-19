package dev.kizuna.meridian.brewing.client;

import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** 琉璃样式的按钮：点击、键盘和旁白都沿用原版按钮，只换画法；选中与否每帧向所属画面问。 */
final class GlassButton extends Button {
    private static final int LABEL_MARGIN = 3;
    private static final BooleanSupplier NEVER = () -> false;
    private final BooleanSupplier selected;

    GlassButton(Glass.Box box, Component message, OnPress onPress) {
        this(box, message, onPress, NEVER);
    }

    GlassButton(Glass.Box box, Component message, OnPress onPress, BooleanSupplier selected) {
        super(box.x(), box.y(), box.width(), box.height(), message, onPress, DEFAULT_NARRATION);
        this.selected = selected;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // 字居中，放不下就截断；不可用时字和边都淡下去。
        Font font = Minecraft.getInstance().font;
        Glass.Box box = new Glass.Box(getX(), getY(), getWidth(), getHeight());
        Glass.button(graphics, box, face());
        FormattedCharSequence label = Glass.clip(font, getMessage(), getWidth() - LABEL_MARGIN * 2);
        int x = getX() + (getWidth() - font.width(label)) / 2;
        int y = getY() + (getHeight() - font.lineHeight) / 2 + 1;
        int color = active ? Glass.TEXT : Glass.FAINT;
        Glass.text(graphics, font, label, x, y, color);
    }

    Glass.Face face() {
        if (!active) {
            return Glass.Face.DISABLED;
        }
        if (selected.getAsBoolean()) {
            return Glass.Face.SELECTED;
        }
        return isHoveredOrFocused() ? Glass.Face.HOVERED : Glass.Face.IDLE;
    }
}
