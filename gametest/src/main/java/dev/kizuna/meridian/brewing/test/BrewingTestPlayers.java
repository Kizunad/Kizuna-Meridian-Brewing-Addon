package dev.kizuna.meridian.brewing.test;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 真实玩家登录与身体绑定，只用内存连接替代网络传输。 */
public final class BrewingTestPlayers {
    private static final int NAME_LENGTH = 8;
    private static final int HEIGHT = 3;
    private static final Map<UUID, RecordingConnection> CONNECTIONS = new HashMap<>();
    private static boolean ticking;
    private static final List<Identifier> CHANNELS = List.of(
            Identifier.parse("meridian:body_view"),
            Identifier.parse("meridian:gathering_response"),
            Identifier.parse("meridian:opening_response"),
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

    public static void close(ServerPlayer player) {
        // 真实断线同时清除施放、身体认领和服务端玩家列表。
        RecordingConnection connection = CONNECTIONS.remove(player.getUUID());
        if (connection != null) {
            connection.disconnectFromClient();
        }
    }
}
