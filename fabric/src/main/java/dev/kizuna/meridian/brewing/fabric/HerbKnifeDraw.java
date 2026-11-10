package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.playeranimation.animation.PlayerAnimationAction;
import dev.kizuna.playeranimation.server.ServerPlayerAnimations;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 主手从别的物品换成药刀时，服务端让 PA 播一次甩腕开刃，刀刃开合跟着这段剪辑的 open 轨道。
 *
 * <p>上线、换维度和重生时只记下当前主手，不算「换成药刀」；副手拿药刀不播。
 */
final class HerbKnifeDraw {
    private static final Map<UUID, Boolean> HOLDING = new HashMap<>();

    private HerbKnifeDraw() {
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(HerbKnifeDraw::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> remember(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> HOLDING.remove(handler.player.getUUID()));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(
                (player, origin, destination) -> remember(player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> remember(newPlayer));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> HOLDING.clear());
    }

    private static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean holding = holdsKnife(player);
            Boolean previous = HOLDING.put(player.getUUID(), holding);
            // 第一次见到的玩家没有「之前」，只记下不播。
            if (holding && Boolean.FALSE.equals(previous)) {
                ServerPlayerAnimations.play(player, PlayerAnimationAction.HERB_KNIFE_DRAW);
            }
        }
    }

    private static void remember(ServerPlayer player) {
        HOLDING.put(player.getUUID(), holdsKnife(player));
    }

    private static boolean holdsKnife(ServerPlayer player) {
        return player.getMainHandItem().is(BrewingItems.item("herb_knife"));
    }
}
