package dev.kizuna.meridian.brewing.client;

import dev.kizuna.meridian.brewing.fabric.BrewTooltipLines;
import dev.kizuna.meridian.brewing.fabric.PreparedIngredientItem;
import java.util.List;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 把巫药的品质和药效、炮制药材的天数插在物品名下面。 */
final class BrewTooltips {
    private static final int BELOW_NAME = 1;

    private BrewTooltips() {
    }

    static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            List<Component> brewLines = BrewTooltipLines.lines(stack);
            if (!brewLines.isEmpty()) {
                lines.addAll(Math.min(BELOW_NAME, lines.size()), brewLines);
            }
            // 炮制药材的天数要对着现在的游戏日算；还没进世界时不写。
            var level = Minecraft.getInstance().level;
            if (level != null) {
                List<Component> prepared = BrewTooltipLines.preparedLines(stack, PreparedIngredientItem.gameDay(level));
                lines.addAll(Math.min(BELOW_NAME, lines.size()), prepared);
            }
        });
    }
}
