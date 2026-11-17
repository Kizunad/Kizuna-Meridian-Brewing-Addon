package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 药方讲解和《熬药总纲》：服务端只发「打开哪一篇」，正文在语言文件里，客户端按玩家的语言取出来排成原版书页。
 *
 * <p>六张药方各一篇，篇名就是药方 ID；总纲一篇叫 {@link #OVERVIEW}。羊皮纸只要认得出药方就能读，
 * 旧存档里的羊皮纸也照样能看，不靠物品上另存的正文。</p>
 */
public final class BrewLore {
    public static final String OVERVIEW = "overview";
    private static final String TEXT_PREFIX = "lore." + BrewingFabric.MOD_ID + ".";
    private static final int MAX_DOCUMENT_LENGTH = 32;

    private BrewLore() {
        // 只有静态入口。
    }

    /** 各篇的篇名：药方按 {@link BrewKind} 的顺序（六张巫药、然后是丹露），最后是总纲。 */
    public static List<String> documents() {
        // 篇名就是药方路径名，和羊皮纸上的药方组件同一套。
        List<String> documents = new ArrayList<>();
        for (BrewKind kind : BrewKind.values()) {
            documents.add(kind.path());
        }
        documents.add(OVERVIEW);
        return List.copyOf(documents);
    }

    /** 一篇正文的语言键，例如 {@code lore.meridian_brewing.qi_gather}。 */
    public static String textKey(String document) {
        // 全文放在一个键里，段落之间空一行，排页交给客户端。
        return TEXT_PREFIX + document;
    }

    /** 书页第一行的篇名：药方用药名，总纲用书名；认不出的篇名为空。 */
    public static Optional<Component> title(String document) {
        // 篇名和物品名、药名共用语言键，不另起一份。
        if (document.equals(OVERVIEW)) {
            return Optional.of(BrewingItems.item("brew_manual").getDefaultInstance().getHoverName());
        }
        return RecipeKnowledge.kind(document)
                .map(kind -> BrewTexts.component(BrewCauldron.recipeName(kind)));
    }

    static void register() {
        // 两端都要登记载荷类型：服务端编码，客户端解码后才能交给收包回调。
        PayloadTypeRegistry.clientboundPlay().register(OpenPayload.TYPE, OpenPayload.CODEC);
    }

    /** 让这位玩家的客户端翻开一篇讲解；GameTest 里没有连接的假玩家不发。 */
    static void open(ServerPlayer reader, String document) {
        // 客户端没登记这条通道时不发，免得原版客户端收到认不出的包。
        if (reader.connection == null || !ServerPlayNetworking.canSend(reader, OpenPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(reader, new OpenPayload(document));
    }

    /** 打开哪一篇；篇名限长，客户端认不出的篇名不打开。 */
    public record OpenPayload(String document) implements CustomPacketPayload {
        public static final Type<OpenPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "lore"));
        static final StreamCodec<RegistryFriendlyByteBuf, OpenPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MAX_DOCUMENT_LENGTH), OpenPayload::document, OpenPayload::new);

        @Override
        public Type<OpenPayload> type() {
            // 收包回调按类型分派。
            return TYPE;
        }
    }
}
