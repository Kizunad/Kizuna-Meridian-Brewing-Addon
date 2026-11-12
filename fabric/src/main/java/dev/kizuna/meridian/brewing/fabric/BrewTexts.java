package dev.kizuna.meridian.brewing.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewProgress;
import dev.kizuna.meridian.brewing.server.BrewQiTally;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewStructure;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.HeatBand;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 把炼药的 {@link BrewText} 拼成文字。
 *
 * <p>发给玩家的提示一律是语言键，由客户端按玩家的语言显示。熬药式被拒绝时功法框架只收一句现成的话，
 * 这时按本仓自己的中文语言文件拼出来，和客户端看到的中文是同一份文字。</p>
 */
final class BrewTexts {
    private static final String ZH_CN = "/assets/" + BrewingFabric.MOD_ID + "/lang/zh_cn.json";
    private static final String QUALITY_PREFIX = BrewText.MESSAGE_PREFIX + "quality.";
    private static final String PROGRESS_PREFIX = BrewText.MESSAGE_PREFIX + "progress.";
    private static final String SEPARATOR = " · ";
    private static Map<String, String> chinese;

    private BrewTexts() {
    }

    static void sendOverlay(Player player, BrewText text) {
        // GameTest 的假玩家没有网络连接，动作栏无处可发。
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection == null) {
            return;
        }
        player.sendOverlayMessage(component(text));
    }

    /** 熬药式每推进一秒发给施放者的一行进度，只发给本人。 */
    static void sendProgress(ServerPlayer caster, BrewProgress progress, BrewQiTally qi) {
        if (caster.connection == null) {
            return;
        }
        caster.sendOverlayMessage(progress(progress, qi));
    }

    /** 熬成时的一句话；这锅耗了元就带上总数和来源。 */
    static BrewText brewed(BrewQiTally qi) {
        if (qi.total() <= 0) {
            return BrewText.message("cauldron.brewed");
        }
        return BrewText.message("cauldron.brewed_qi", BrewQiPayment.amount(qi.paid()),
                BrewQiPayment.amount(qi.ambient()), BrewQiPayment.amount(qi.self()));
    }

    /**
     * 进度行加上耗元，例如「… · 耗元 2.4/6.0（环境 1.8 · 自身 0.6）」；欠着真元时再红字写「真元不济，欠 0.3/0.8」。
     * 关掉耗元时不写这一段。
     */
    static MutableComponent progress(BrewProgress progress, BrewQiTally qi) {
        MutableComponent line = progress(progress);
        if (qi.total() > 0) {
            appendPart(line, component(BrewText.message("progress.qi", BrewQiPayment.amount(qi.paid()),
                    BrewQiPayment.amount(qi.total()), BrewQiPayment.amount(qi.ambient()),
                    BrewQiPayment.amount(qi.self()))));
        }
        if (qi.owing()) {
            appendPart(line, component(BrewText.message("progress.qi_owed", BrewQiPayment.amount(qi.arrears()),
                    BrewQiPayment.amount(qi.graceQi()))).withStyle(ChatFormatting.RED));
        }
        return line;
    }

    /** 例如「阳火 · 87° / 86–94° · 还剩 18 s · 辅料可下（还剩 3 s）」；温度按火候带上色。 */
    static MutableComponent progress(BrewProgress progress) {
        MutableComponent line = component(BrewStructure.fireName(progress.fireKind()));
        appendPart(line, temperature(progress));
        appendPart(line, component(BrewText.message("progress.remaining", progress.remainingSeconds())));
        for (BrewProgress.Window window : progress.windows()) {
            appendPart(line, component(windowText(window)));
        }
        if (progress.scorchCount() > 0) {
            appendPart(line, component(BrewText.message("progress.scorched", progress.scorchCount())));
        }
        return line;
    }

    static MutableComponent component(BrewText text) {
        Object[] args = text.args().stream().map(BrewTexts::componentArg).toArray();
        return Component.translatable(text.key(), args);
    }

    static MutableComponent quality(BrewQuality quality) {
        return Component.translatable(qualityKey(quality)).withStyle(qualityColor(quality));
    }

    static String qualityKey(BrewQuality quality) {
        return QUALITY_PREFIX + quality.name().toLowerCase(Locale.ROOT);
    }

    static ChatFormatting qualityColor(BrewQuality quality) {
        return switch (quality) {
            case POOR -> ChatFormatting.GRAY;
            case COMMON -> ChatFormatting.WHITE;
            case FINE -> ChatFormatting.GREEN;
            case SUPERIOR -> ChatFormatting.GOLD;
        };
    }

    /** 按本仓中文语言文件拼出的一句话；只给功法框架的拒绝理由用。 */
    static String chinese(BrewText text) {
        String pattern = chineseTable().get(text.key());
        if (pattern == null) {
            throw new IllegalStateException("中文语言文件缺少键：" + text.key());
        }
        Object[] args = text.args().stream().map(BrewTexts::chineseArg).toArray();
        return String.format(Locale.ROOT, pattern, args);
    }

    private static void appendPart(MutableComponent line, Component part) {
        line.append(SEPARATOR);
        line.append(part);
    }

    private static MutableComponent temperature(BrewProgress progress) {
        long degrees = Math.round(progress.temperature());
        MutableComponent current = Component.literal(degrees + "°").withStyle(heatColor(progress.heat()));
        HeatBand band = progress.band();
        String minimum = String.valueOf(Math.round(band.minimumDegrees()));
        String maximum = String.valueOf(Math.round(band.maximumDegrees()));
        return Component.translatable(PROGRESS_PREFIX + "temperature", current, minimum, maximum);
    }

    private static ChatFormatting heatColor(BrewProgress.Heat heat) {
        return switch (heat) {
            case LOW -> ChatFormatting.YELLOW;
            case IN_BAND -> ChatFormatting.GREEN;
            case HIGH -> ChatFormatting.RED;
        };
    }

    private static BrewText windowText(BrewProgress.Window window) {
        BrewText slot = BrewCauldron.slotName(window.slot());
        return switch (window.phase()) {
            case UPCOMING -> BrewText.message("progress.window.upcoming", slot, window.seconds());
            case OPEN -> BrewText.message("progress.window.open", slot, window.seconds());
        };
    }

    private static Object componentArg(Object arg) {
        return switch (arg) {
            case BrewText nested -> component(nested);
            case BrewText.ItemName item -> Component.translatable(descriptionId(item));
            case BrewText.BlockName block -> Component.translatable(descriptionId(block));
            case BrewQuality quality -> quality(quality);
            case Number number -> number.toString();
            default -> throw new IllegalArgumentException("未知提示参数：" + arg);
        };
    }

    private static Object chineseArg(Object arg) {
        return switch (arg) {
            case BrewText nested -> chinese(nested);
            // 服务端没有原版物品的中文名，本仓以外的物品照旧显示物品 ID。
            case BrewText.ItemName item -> chineseTable().getOrDefault(descriptionId(item), item.itemId());
            case BrewText.BlockName block -> chineseTable().getOrDefault(descriptionId(block), block.blockId());
            case BrewQuality quality -> chineseTable().get(qualityKey(quality));
            case Number number -> number.toString();
            default -> throw new IllegalArgumentException("未知提示参数：" + arg);
        };
    }

    private static String descriptionId(BrewText.ItemName item) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(item.itemId())).getDescriptionId();
    }

    private static String descriptionId(BrewText.BlockName block) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(block.blockId())).getDescriptionId();
    }

    private static synchronized Map<String, String> chineseTable() {
        if (chinese == null) {
            chinese = readLanguage(ZH_CN);
        }
        return chinese;
    }

    private static Map<String, String> readLanguage(String resource) {
        try (InputStream stream = BrewTexts.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("找不到语言文件：" + resource);
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                Map<String, String> table = new HashMap<>();
                json.entrySet().forEach(entry -> table.put(entry.getKey(), entry.getValue().getAsString()));
                return Map.copyOf(table);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("读不了语言文件：" + resource, exception);
        }
    }
}
