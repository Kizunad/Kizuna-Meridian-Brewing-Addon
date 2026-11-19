package dev.kizuna.meridian.brewing.fabric;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 结构不符时，在第一处不符的那一格给这名玩家发一个方块标记粒子（原版 block_marker，显示 4 秒），
 * 显示那格应有的方块；必须空的格显示屏障。只发给这名玩家，别人看不见。
 *
 * <p>空手检查锅体时发给点击者，同一格 2 秒内不重复发。</p>
 */
final class BrewStructureMarkers {
    private static final long REPEAT_TICKS = 40;
    private static final Map<UUID, Shown> LAST_SHOWN = new HashMap<>();

    private BrewStructureMarkers() {
    }

    static void register() {
        // 每名玩家只记最近一次发在哪、什么时候，停服时清掉。
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_SHOWN.clear());
    }

    /** 有不符就在那一格发标记；有效或暂未加载时什么也不发。 */
    static void show(ServerPlayer player, FurnaceLayout.Fault marker) {
        // 粒子强制远距离显示，站在结构外圈也看得到。
        if (marker == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        Shown last = LAST_SHOWN.get(player.getUUID());
        if (last != null && last.pos().equals(marker.pos()) && now - last.gameTime() < REPEAT_TICKS) {
            return;
        }
        LAST_SHOWN.put(player.getUUID(), new Shown(marker.pos(), now));
        BlockPos pos = marker.pos();
        level.sendParticles(player, new BlockParticleOption(ParticleTypes.BLOCK_MARKER, marker.shown()),
                true /* overrideLimiter */, false /* alwaysShow */,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, 0, 0, 0, 0);
    }

    private record Shown(BlockPos pos, long gameTime) {
    }
}
