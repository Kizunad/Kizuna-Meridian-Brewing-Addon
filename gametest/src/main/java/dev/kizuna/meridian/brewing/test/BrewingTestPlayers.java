package dev.kizuna.meridian.brewing.test;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.human.fabric.HumanChannels;
import dev.kizuna.meridian.human.protocol.pb.ControlMessages.ControlIntent;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.playeranimation.network.AnimationPackets;
import dev.kizuna.playeranimation.protocol.pb.PlayerAnimationProtocol.PlayRequest;
import dev.kizuna.playeranimation.protocol.pb.PlayerAnimationProtocol.StopRequest;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import dev.kizuna.meridian.brewing.server.BrewKind;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/** 真实玩家登录与身体绑定，只用内存连接替代网络传输。 */
public final class BrewingTestPlayers {
    private static final int NAME_LENGTH = 8;
    private static final int HEIGHT = 3;
    private static final Map<UUID, RecordingConnection> CONNECTIONS = new HashMap<>();
    private static boolean ticking;
    private static final Map<UUID, List<ControlIntent>> CONTROL = new HashMap<>();
    private static final Map<UUID, List<SoundPlayed>> SOUNDS = new HashMap<>();
    private static final Map<UUID, List<AnimationSent>> ANIMATIONS = new HashMap<>();
    private static final Map<UUID, List<OverlaySent>> OVERLAYS = new HashMap<>();
    private static final List<Identifier> CHANNELS = List.of(
            Identifier.parse("meridian:body_view"),
            Identifier.parse("meridian:gathering_response"),
            Identifier.parse("meridian:opening_response"),
            Identifier.parse("meridian_human:control"),
            Identifier.parse("meridian_progression:event"),
            Identifier.parse("kizuna_players_animation:play"),
            Identifier.parse("kizuna_players_animation:stop"));

    private BrewingTestPlayers() {
        // 夹具不创建身体，所有账户都由 Human 的正式登录入口建立。
    }

    public static ServerPlayer create(GameTestHelper context) {
        // 先协商必需通道，再把玩家移入各自测试场地，避免测试间相互命中。
        initializeTicks();
        UUID id = UUID.randomUUID();
        var profile = new GameProfile(id, "brew_" + id.toString().substring(0, NAME_LENGTH));
        return logIn(context, profile);
    }

    public static ServerPlayer rejoin(GameTestHelper context, ServerPlayer previous) {
        // 同 UUID 由原版加载玩家状态，Human 同时恢复保存的唯一身体。
        return logIn(context, previous.getGameProfile());
    }

    private static ServerPlayer logIn(GameTestHelper context, GameProfile profile) {
        // 创建连接不复制状态，所有存档恢复都走正式登录方法。
        var level = context.getLevel();
        var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        var connection = new RecordingConnection(CHANNELS);
        CONNECTIONS.put(profile.id(), connection);
        CONTROL.put(profile.id(), new ArrayList<>());
        SOUNDS.put(profile.id(), new ArrayList<>());
        ANIMATIONS.put(profile.id(), new ArrayList<>());
        OVERLAYS.put(profile.id(), new ArrayList<>());
        TestPlayers.logIn(player, connection);
        BlockPos position = context.absolutePos(new BlockPos(1, HEIGHT, 1));
        player.setPos(position.getX(), position.getY(), position.getZ());
        player.setNoGravity(true /* noGravity */);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static void initializeTicks() {
        // 内存连接不在服务器的 socket 列表中，补投正式连接 tick 才会推进玩家药效与物品使用。
        if (!ticking) {
            ServerTickEvents.START_SERVER_TICK.register(server -> {
                for (RecordingConnection connection : List.copyOf(CONNECTIONS.values())) {
                    if (connection.isConnected()) {
                        connection.tick();
                        acknowledge(connection);
                    }
                }
            });
            ticking = true;
        }
    }

    private static void acknowledge(RecordingConnection connection) {
        // 长施放期间模拟客户端心跳，不能因为测试传输层缺少回包导致超时下线。
        var listener = (ServerGamePacketListenerImpl) connection.getPacketListener();
        for (var packet : List.copyOf(connection.packets())) {
            if (packet instanceof ClientboundSoundPacket sound) {
                SOUNDS.get(listener.player.getUUID()).add(new SoundPlayed(
                        listener.player.level().getGameTime(), sound));
            }
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof ProtobufChannel.Payload payload
                    && payload.type().equals(HumanChannels.CONTROL.type())) {
                recordControl(listener.player, payload.bytes());
            }
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof AnimationPackets.Payload payload) {
                recordAnimation(listener.player, payload);
            }
            if (packet instanceof ClientboundSystemChatPacket chat && chat.overlay()) {
                OVERLAYS.get(listener.player.getUUID()).add(new OverlaySent(
                        listener.player.level().getGameTime(), chat.content()));
            }
            if (packet instanceof ClientboundKeepAlivePacket keepAlive) {
                listener.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
            }
        }
        connection.packets().clear();
    }

    public static BodySnapshot body(ServerPlayer player) {
        // 公开 inspect 返回 Human 唯一权威身体，不能用测试宿主替换它。
        return VanillaDamageFabric.inspectBody(player).orElseThrow();
    }

    public static void readRecipes(ServerPlayer player, BrewKind... kinds) {
        // 照玩家的做法读真实的药方羊皮纸：主手拿着右键，读完把原来的主手物品放回去。
        Item scroll = BuiltInRegistries.ITEM.getValue(Identifier.parse("meridian_brewing:brew_recipe"));
        ItemStack held = player.getMainHandItem();
        for (BrewKind kind : kinds) {
            player.setItemInHand(InteractionHand.MAIN_HAND, recipeScroll(kind));
            scroll.use(player.level(), player, InteractionHand.MAIN_HAND);
            player.closeContainer();
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
    }

    public static void readAllRecipes(ServerPlayer player) {
        // 熬药测试的前提：六种药方都读过。
        readRecipes(player, BrewKind.values());
    }

    public static void stockQi(ServerPlayer player) {
        // 熬药耗元：开全部经脉再灌满，同一区块里几锅并行抢环境速率时由自身补足，不随排位抖动。
        command(player, "meridian opening complete " + player.getScoreboardName() + " all");
        command(player, "meridian qi fill");
    }

    public static void setQi(ServerPlayer player, double amount) {
        // 开全部经脉后把自身真元设成指定总量（自由相，按各脉内腔容量分摊）。
        command(player, "meridian opening complete " + player.getScoreboardName() + " all");
        command(player, String.format(java.util.Locale.ROOT, "meridian qi set %s %.4f",
                player.getScoreboardName(), amount));
    }

    private static void command(ServerPlayer player, String command) {
        // 走正式指令入口，按服务端控制台权限、以玩家本人为执行者；目标写玩家名（玩家参数不收 UUID），
        // 省略目标的指令就作用于执行者。反馈留在日志里，结果由调用方读身体确认。
        var server = player.level().getServer();
        var source = server.createCommandSourceStack().withEntity(player);
        server.getCommands().performPrefixedCommand(source, command);
    }

    @SuppressWarnings("unchecked")
    public static ItemStack recipeScroll(BrewKind kind) {
        // 和女巫掉落一样，药方种类写在羊皮纸的 recipe_kind 组件里。
        Item scroll = BuiltInRegistries.ITEM.getValue(Identifier.parse("meridian_brewing:brew_recipe"));
        var kindType = (DataComponentType<String>) BuiltInRegistries.DATA_COMPONENT_TYPE
                .getValue(Identifier.parse("meridian_brewing:recipe_kind"));
        ItemStack stack = new ItemStack(scroll);
        stack.set(kindType, kind.path());
        return stack;
    }

    public static List<ControlIntent> controlIntents(ServerPlayer player) {
        // 测试只读取内存网络捕获的下行意图，不直接调用决策函数代替发包。
        return List.copyOf(CONTROL.getOrDefault(player.getUUID(), List.of()));
    }

    public record SoundPlayed(long tick, ClientboundSoundPacket packet) { }

    public static List<SoundPlayed> sounds(ServerPlayer player) {
        // 捕获真正发给客户端的声音包，以服务器 tick 核对到达时刻和播声位置。
        return List.copyOf(SOUNDS.getOrDefault(player.getUUID(), List.of()));
    }

    /** 发到动作栏的一行字，{@code tick} 是服务器发包时的游戏时间。 */
    public record OverlaySent(long tick, Component text) { }

    public static List<OverlaySent> overlays(ServerPlayer player) {
        // 捕获真正发给客户端的动作栏包，核对发给谁、多久一次、用的哪些语言键。
        return List.copyOf(OVERLAYS.getOrDefault(player.getUUID(), List.of()));
    }

    /** 一行字里用到的全部语言键，连同参数和后接的片段。 */
    public static Set<String> translationKeys(Component text) {
        Set<String> keys = new HashSet<>();
        collectKeys(text, keys);
        return keys;
    }

    private static void collectKeys(Component text, Set<String> keys) {
        if (text.getContents() instanceof TranslatableContents translatable) {
            keys.add(translatable.getKey());
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Component nested) {
                    collectKeys(nested, keys);
                }
            }
        }
        for (Component sibling : text.getSiblings()) {
            collectKeys(sibling, keys);
        }
    }

    /**
     * 某个玩家客户端收到的 PA 播放或停止包，{@code playerUuid} 是动作所属的玩家；{@code startTick} 只对播放有意义。
     * PA 在有人开始追踪时补发进行中的播放，沿用原请求 ID，这里只记第一次。
     */
    public record AnimationSent(boolean play, String animationId, long startTick, String playerUuid,
                                String requestId) { }

    public static List<AnimationSent> animations(ServerPlayer player) {
        // 解析真正发出的 PA 载荷，不只断言调用了播放函数。
        return animationsAbout(player, player);
    }

    public static List<AnimationSent> animationsAbout(ServerPlayer viewer, ServerPlayer target) {
        // 其他场地的假玩家也可能在追踪范围内，按动作所属玩家筛出这一位的包。
        return ANIMATIONS.getOrDefault(viewer.getUUID(), List.of()).stream()
                .filter(sent -> sent.playerUuid().equals(target.getStringUUID())).toList();
    }

    private static void recordAnimation(ServerPlayer viewer, AnimationPackets.Payload payload) {
        AnimationSent sent = decodeAnimation(payload);
        List<AnimationSent> record = ANIMATIONS.get(viewer.getUUID());
        if (sent != null && record.stream().noneMatch(old -> old.requestId().equals(sent.requestId()))) {
            record.add(sent);
        }
    }

    private static AnimationSent decodeAnimation(AnimationPackets.Payload payload) {
        try {
            if (payload.type().equals(AnimationPackets.PLAY)) {
                PlayRequest play = AnimationPackets.read(payload, PlayRequest.parser());
                return new AnimationSent(true, play.getAnimationId(), play.getStartTick(), play.getPlayerUuid(),
                        play.getRequestId());
            }
            if (payload.type().equals(AnimationPackets.STOP)) {
                StopRequest stop = AnimationPackets.read(payload, StopRequest.parser());
                return new AnimationSent(false, stop.getAnimationId(), -1, stop.getPlayerUuid(), stop.getRequestId());
            }
            return null;
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalStateException("测试收到损坏的 PA 载荷", invalid);
        }
    }

    public static String disconnectReason(ServerPlayer player) {
        // 读取服务端实际发出的断线原因，不用测试替身模拟踢人。
        return CONNECTIONS.get(player.getUUID()).packets().stream()
                .filter(ClientboundDisconnectPacket.class::isInstance)
                .map(ClientboundDisconnectPacket.class::cast)
                .map(packet -> packet.reason().getString())
                .findFirst().orElse("");
    }

    private static void recordControl(ServerPlayer player, byte[] payload) {
        // 正式通道载荷必须能被客户端使用的协议解码。
        try {
            CONTROL.get(player.getUUID()).add(ControlIntent.parseFrom(payload));
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalStateException("测试收到损坏的受控意图", invalid);
        }
    }

    public static void close(ServerPlayer player) {
        // 真实断线同时清除施放、身体认领和服务端玩家列表。
        RecordingConnection connection = CONNECTIONS.remove(player.getUUID());
        if (connection != null) {
            connection.disconnectFromClient();
        }
        CONTROL.remove(player.getUUID());
        SOUNDS.remove(player.getUUID());
        ANIMATIONS.remove(player.getUUID());
        OVERLAYS.remove(player.getUUID());
    }
}
