package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.ArmorRotProfile;
import dev.kizuna.meridian.brewing.server.BitterYellowProfile;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewEffectProfiles;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewModifiers;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.CharmProfile;
import dev.kizuna.meridian.brewing.server.DepletionProfile;
import dev.kizuna.meridian.brewing.server.InsectLureProfile;
import dev.kizuna.meridian.brewing.server.QiGatherProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 巫药物品提示：品质一行，再写这一档的药效。数字都从 {@link BrewEffectProfiles} 取，语言文件里只有句式。
 * 药方羊皮纸写它记的是哪一味药。
 *
 * <p>原版那行「(0:01)」是登记用的显示效果，巫药物品已经用 {@code tooltip_display} 藏掉。</p>
 */
public final class BrewTooltipLines {
    private static final String PREFIX = "tooltip.meridian_brewing.";
    private static final int DECIMALS = 2;
    private static final double PERCENT = 100.0;

    private BrewTooltipLines() {
    }

    /** 不是巫药也不是药方时返回空表。 */
    public static List<Component> lines(ItemStack stack) {
        // 药方和巫药各写各的；别的物品不加行。
        if (stack.getItem() instanceof RecipeScrollItem) {
            return List.of(recipeLine(stack).withStyle(ChatFormatting.GRAY));
        }
        if (!BrewItemStacks.isBrew(stack)) {
            return List.of();
        }
        BrewKind kind = BrewItemStacks.kind(stack);
        BrewQuality quality = BrewItemStacks.quality(stack);
        BrewUse use = BrewUse.DRINK;
        if (stack.is(Items.SPLASH_POTION)) {
            use = BrewUse.SPLASH;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(PREFIX + "quality", BrewTexts.quality(quality))
                .withStyle(ChatFormatting.GRAY));
        for (MutableComponent line : effectLines(kind, quality, use)) {
            lines.add(line.withStyle(ChatFormatting.GRAY));
        }
        return List.copyOf(lines);
    }

    private static MutableComponent recipeLine(ItemStack stack) {
        // 认不出的药方照读的时候那样写字迹模糊。
        return RecipeScrollItem.kind(stack)
                .map(kind -> line("recipe", BrewTexts.component(BrewCauldron.recipeName(kind))))
                .orElseGet(() -> line("recipe.illegible"));
    }

    private static List<MutableComponent> effectLines(BrewKind kind, BrewQuality quality, BrewUse use) {
        return switch (kind) {
            case QI_GATHER -> qiGather(BrewEffectProfiles.qiGather(quality));
            case DEPLETION -> depletion(BrewEffectProfiles.depletion(quality));
            case ARMOR_ROT -> armorRot(BrewEffectProfiles.armorRot(quality));
            case INSECT_LURE -> insectLure(BrewEffectProfiles.insectLure(quality));
            case BITTER_YELLOW -> bitterYellow(BrewEffectProfiles.bitterYellow(quality, use));
            case CHARM -> charm(BrewEffectProfiles.charm(quality));
        };
    }

    private static List<MutableComponent> qiGather(QiGatherProfile profile) {
        return List.of(
                line("qi_gather", number(profile.intakeMultiplier()), number(profile.durationSeconds())),
                line("qi_gather.after", number(profile.exhaustionDurationSeconds()),
                        number(BrewModifiers.EXHAUSTED_INTAKE)));
    }

    private static List<MutableComponent> depletion(DepletionProfile profile) {
        String parts = range(profile.minimumParts(), profile.maximumParts());
        return List.of(
                line("splash_parts", number(profile.radius()), parts, number(profile.durationSeconds())),
                line("depletion", number(profile.muscleEnduranceMultiplier()),
                        number(profile.tempoMultiplier()), number(profile.partDamageHp())));
    }

    private static List<MutableComponent> armorRot(ArmorRotProfile profile) {
        String parts = range(profile.minimumParts(), profile.maximumParts());
        String durabilityPercent = number(profile.durabilityPercentPerSecond() * PERCENT);
        return List.of(
                line("splash_parts", number(profile.radius()), parts, number(profile.durationSeconds())),
                line("armor_rot", durabilityPercent, number(profile.armorMultiplier())));
    }

    private static List<MutableComponent> insectLure(InsectLureProfile profile) {
        return List.of(
                line("splash_time", number(profile.radius()), number(profile.durationSeconds())),
                line("insect_lure", number(profile.hearingDistance()), String.valueOf(profile.silverfishCount())));
    }

    private static List<MutableComponent> bitterYellow(BitterYellowProfile profile) {
        List<MutableComponent> lines = new ArrayList<>();
        lines.add(line("bitter_yellow", number(profile.durationSeconds()), number(profile.totalHealing()),
                number(profile.healingPerSecond())));
        lines.add(line("bitter_yellow.slow", number(profile.tempoMultiplier()),
                number(profile.strengthMultiplier())));
        if (profile.nauseaSeconds() > 0) {
            lines.add(line("bitter_yellow.nausea", number(profile.nauseaSeconds())));
        }
        return List.copyOf(lines);
    }

    private static List<MutableComponent> charm(CharmProfile profile) {
        return List.of(
                line("charm", number(profile.radius()), number(profile.staggerSeconds()),
                        number(profile.controlSeconds())),
                line("charm.immune", number(profile.immunitySeconds())));
    }

    private static MutableComponent line(String key, Object... args) {
        return Component.translatable(PREFIX + key, args);
    }

    private static String range(int minimum, int maximum) {
        if (minimum == maximum) {
            return String.valueOf(minimum);
        }
        return minimum + "–" + maximum;
    }

    /** 最多两位小数，去掉末尾的 0，例如 1.50 写成 1.5、90.0 写成 90。 */
    private static String number(double value) {
        return BigDecimal.valueOf(value).setScale(DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
