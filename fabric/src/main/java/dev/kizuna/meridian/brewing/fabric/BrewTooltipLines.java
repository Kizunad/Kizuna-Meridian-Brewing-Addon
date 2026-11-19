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
import dev.kizuna.meridian.brewing.server.IngredientFreshness;
import dev.kizuna.meridian.brewing.server.InsectLureProfile;
import dev.kizuna.meridian.brewing.server.QiGatherProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 巫药物品提示：品质一行，再写这一档的药效。数字都从 {@link BrewEffectProfiles} 取，语言文件里只有句式。
 * 药方羊皮纸写它记的是哪一味药；釜和火写它属于哪一座药炉，铁火盆写它只是铁炉座的料；
 * 铜釜另写氧化和上蜡，晒药匾写什么时候才晒。炮制药材写炮制了几天、药性还剩多少。
 *
 * <p>原版那行「(0:01)」是登记用的显示效果，巫药物品已经用 {@code tooltip_display} 藏掉。</p>
 */
public final class BrewTooltipLines {
    private static final String PREFIX = "tooltip.meridian_brewing.";
    private static final int DECIMALS = 2;
    private static final double PERCENT = 100.0;
    private static final Map<String, List<String>> APPARATUS_LINES = Map.of(
            "copper_cauldron", List.of("apparatus.copper_cauldron",
                    "apparatus.copper_cauldron.oxidation", "apparatus.copper_cauldron.wax"),
            "witch_cauldron", List.of("apparatus.witch_cauldron"),
            "iron_stove", List.of("apparatus.iron_stove"),
            "iron_brazier", List.of("apparatus.iron_brazier"),
            "herb_crate", List.of("apparatus.herb_crate"));

    private BrewTooltipLines() {
    }

    /** 不是巫药、药方，也不是釜和火时返回空表。 */
    public static List<Component> lines(ItemStack stack) {
        // 药方、药炉器具和巫药各写各的；别的物品不加行。
        if (stack.getItem() instanceof RecipeScrollItem) {
            return List.of(recipeLine(stack).withStyle(ChatFormatting.GRAY));
        }
        List<Component> apparatus = apparatusLines(stack);
        if (!apparatus.isEmpty()) {
            return apparatus;
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

    /**
     * 炮制药材写这是炮制的第几天、药性还剩多少，再写一行药性怎么退。
     * 不是炮制药材、还没记上炮制日，或者日期对不上（时钟被往回调过）时返回空表。
     */
    public static List<Component> preparedLines(ItemStack stack, long currentGameDay) {
        Long preparedDay = stack.get(BrewingComponents.PREPARED_DAY);
        if (!(stack.getItem() instanceof PreparedIngredientItem) || preparedDay == null
                || preparedDay < 0 || currentGameDay < preparedDay) {
            return List.of();
        }
        long age = IngredientFreshness.ageDays(preparedDay, currentGameDay);
        long percent = Math.round(IngredientFreshness.multiplier(preparedDay, currentGameDay) * PERCENT);
        long lowestDay = IngredientFreshness.DECAY_END_DAY - 1;
        MutableComponent day;
        if (age == 0) {
            day = Component.translatable(PREFIX + "prepared.today");
        } else if (age <= IngredientFreshness.FULL_DAYS) {
            day = Component.translatable(PREFIX + "prepared.fresh", age);
        } else if (age < lowestDay) {
            day = Component.translatable(PREFIX + "prepared.fading", age, percent);
        } else {
            day = Component.translatable(PREFIX + "prepared.spent", age, percent);
        }
        MutableComponent rule = Component.translatable(PREFIX + "prepared.rule", IngredientFreshness.FULL_DAYS,
                lowestDay, Math.round(IngredientFreshness.MINIMUM_MULTIPLIER * PERCENT));
        return List.of(day.withStyle(ChatFormatting.GRAY), rule.withStyle(ChatFormatting.DARK_GRAY));
    }

    private static MutableComponent recipeLine(ItemStack stack) {
        // 认不出的药方照读的时候那样写字迹模糊。
        return RecipeScrollItem.kind(stack)
                .map(kind -> line("recipe", BrewTexts.component(BrewCauldron.recipeName(kind))))
                .orElseGet(() -> line("recipe.illegible"));
    }

    private static List<Component> apparatusLines(ItemStack stack) {
        // 按物品 ID 查：别的模组同名的物品不算。
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!id.getNamespace().equals(BrewingFabric.MOD_ID)) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        for (String key : APPARATUS_LINES.getOrDefault(id.getPath(), List.of())) {
            lines.add(line(key).withStyle(ChatFormatting.GRAY));
        }
        return List.copyOf(lines);
    }

    private static List<MutableComponent> effectLines(BrewKind kind, BrewQuality quality, BrewUse use) {
        // 丹露统一按药种目录读取提示，巫药分别展示自己的作用。
        if (kind.elixir()) {
            return elixir(kind, quality);
        }
        return switch (kind) {
            case QI_GATHER -> qiGather(BrewEffectProfiles.qiGather(quality));
            case DEPLETION -> depletion(BrewEffectProfiles.depletion(quality));
            case ARMOR_ROT -> armorRot(BrewEffectProfiles.armorRot(quality));
            case INSECT_LURE -> insectLure(BrewEffectProfiles.insectLure(quality));
            case BITTER_YELLOW -> bitterYellow(BrewEffectProfiles.bitterYellow(quality, use));
            case CHARM -> charm(BrewEffectProfiles.charm(quality));
            default -> throw new IllegalArgumentException("没有登记药效提示：" + kind);
        };
    }

    private static List<MutableComponent> elixir(BrewKind kind, BrewQuality quality) {
        // 喝下得多少本行真元；丹毒留多久、在身时再喝打几折。
        var settings = BrewElixirs.settings();
        return List.of(
                line(kind.path(), number(settings.dose(quality, 0))),
                line("elixir.toxin", number(settings.toxinSeconds()), number(settings.repeatFactor())));
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
                line("charm.immune", number(profile.immunitySeconds())),
                line("charm.follow"),
                line("charm.wake", number(BrewCharmTargets.RANGE)));
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
