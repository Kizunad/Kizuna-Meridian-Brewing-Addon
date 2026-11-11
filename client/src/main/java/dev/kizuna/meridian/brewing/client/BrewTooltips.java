package dev.kizuna.meridian.brewing.client;

import dev.kizuna.meridian.brewing.fabric.BrewTooltipLines;
import java.util.List;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;

/** 把巫药的品质和药效插在物品名下面。 */
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
        });
    }
}
