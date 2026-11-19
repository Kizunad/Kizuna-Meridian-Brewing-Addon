package dev.kizuna.meridian.brewing.client;

import dev.kizuna.meridian.brewing.protocol.pb.BrewStep;
import dev.kizuna.meridian.brewing.protocol.pb.BrewTimeline;
import java.util.Locale;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 把一步炼法的结构化字段拼成一句给人看的操作说明，例如「开熬后第 6 到 10 秒，投入蜂蜜瓶 2」。
 *
 * <p>一句话分两半：什么时候（按触发条件）和做什么（按动作），各查一条语言键再拼起来。每条语言键拿到同一组参数，
 * 起熬方式随本批演示下发，同一口釜可手熬或自动开熬。</p>
 */
final class BrewStepText {
    private static final String KEY = BrewNames.SCREEN + "steps.";
    private static final String WHEN = KEY + "when.";
    private static final String DO = KEY + "do.";
    private static final String OTHER_ITEM = DO + "other_item";
    private static final String OTHER_FIRE = DO + "other_fire";
    private static final String START = "START";
    private static final String SOUL_FIRE = "SOUL_FIRE";

    private BrewStepText() {
        // 只有静态入口。
    }

    static Component sentence(BrewStep step, BrewTimeline timeline) {
        return Component.translatable(KEY + "sentence", when(step), what(step, timeline));
    }

    private static Component when(BrewStep step) {
        // 参数依次是起、止秒数和温度下、上限；认不出的条件用一句不带数字的话。
        String key = WHEN + lower(step.getCondition());
        if (!Language.getInstance().has(key)) {
            return Component.translatable(WHEN + "other");
        }
        return Component.translatable(key, BrewNames.number(step.getFromSeconds()),
                BrewNames.number(step.getToSeconds()), BrewNames.number(step.getLowTemperature()),
                BrewNames.number(step.getHighTemperature()));
    }

    private static Component what(BrewStep step, BrewTimeline timeline) {
        // 参数依次是物品名、件数、火种、火档、止秒数和温度下、上限；认不出的动作按有没有物品退到两句通用说法。
        String key = actionKey(step, timeline);
        Component item = icon(step).getHoverName();
        Component fire = Component.empty();
        if (!step.getFire().isEmpty()) {
            fire = BrewNames.fire(step.getFire());
        }
        return Component.translatable(key, item, step.getCount(), fire, step.getGrade(),
                BrewNames.number(step.getToSeconds()), BrewNames.number(step.getLowTemperature()),
                BrewNames.number(step.getHighTemperature()));
    }

    private static String actionKey(BrewStep step, BrewTimeline timeline) {
        String action = step.getAction();
        if (action.equals(START) && timeline.getManual()) {
            return DO + "start_by_hand";
        }
        String key = DO + lower(action);
        if (Language.getInstance().has(key)) {
            return key;
        }
        return step.getItem().isEmpty() ? OTHER_FIRE : OTHER_ITEM;
    }

    /** 这一步的图标：投料、添水、收药画那件物品，调火画对应火种的营火。 */
    static ItemStack icon(BrewStep step) {
        if (!step.getItem().isEmpty()) {
            return BrewNames.item(step.getItem());
        }
        if (step.getFire().equals(SOUL_FIRE)) {
            return new ItemStack(Items.SOUL_CAMPFIRE);
        }
        return new ItemStack(Items.CAMPFIRE);
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
