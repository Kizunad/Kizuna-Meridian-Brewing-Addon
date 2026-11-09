package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BrewDoseResume;
import dev.kizuna.meridian.brewing.server.BrewKind;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Human 恢复唯一身体后，依据原版玩家存档中的状态倒计时恢复药效。 */
final class BrewEffectResume {
    private static final long MILLIS_PER_TICK = 50;
    private static final Identifier RESUME_PHASE = Identifier.parse("meridian_brewing:resume_effects");
    private static final Map<UUID, ServerPlayer> pending = new HashMap<>();

    private BrewEffectResume() {
        // 待登录完成队列仅保存一次性事件，不保存身体或离线时钟。
    }

    static void initialize() {
        // 26.2 的 JOIN 早于玩家加入在线列表，下一 tick 输入处理前再调用在线身体宿主。
        ServerPlayConnectionEvents.JOIN.addPhaseOrdering(Event.DEFAULT_PHASE, RESUME_PHASE);
        ServerPlayConnectionEvents.JOIN.register(RESUME_PHASE,
                (handler, sender, server) -> pending.put(handler.player.getUUID(), handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> pending.remove(handler.player.getUUID()));
        ServerPlayerEvents.AFTER_RESPAWN.register((previous, player, alive) -> pending.put(player.getUUID(), player));
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            List<ServerPlayer> joined = List.copyOf(pending.values());
            pending.clear();
            joined.forEach(BrewEffectResume::resume);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> pending.clear());
    }

    private static void resume(ServerPlayer player) {
        // 死亡由身体核心清账；无药效历史的登录无需生成空活动。
        var binding = BrewBodyHost.inspect(player).orElseThrow();
        if (binding.body().getVitality().getDead()
                || !binding.body().containsAddonProgress(BrewBodyProgress.KEY)) {
            return;
        }
        var remaining = new EnumMap<BrewKind, Long>(BrewKind.class);
        for (BrewKind kind : BrewKind.values()) {
            var effect = player.getEffect(BrewingEffects.status(kind));
            if (effect != null) {
                remaining.put(kind, effect.getDuration() * MILLIS_PER_TICK);
            }
        }
        BrewBodyHost.activity(binding, BrewDoseResume.plan(binding.body(), remaining));
    }
}
