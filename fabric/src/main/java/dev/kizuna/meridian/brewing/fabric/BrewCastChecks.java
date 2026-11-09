package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewUseMethod;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import java.util.Optional;
import java.util.UUID;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** 噀催的瞬时世界条件在起手检查，已付款动作的效果边界允许时机损失。 */
final class BrewCastChecks {
    private BrewCastChecks() {
        // 无独立状态，始终查询 Brewing 的残药存储。
    }

    static Optional<String> rejection(MinecraftServer server, UUID entity, CastRequest request) {
        // 其他功法和非法持物交给正式目录校验，避免掩盖原有拒绝原因。
        var definition = TechniqueFabric.service().registry().find(request.getTechnique());
        if (server == null || definition.isEmpty()) {
            return Optional.empty();
        }
        for (var effect : definition.orElseThrow().getEffectsList()) {
            if (!effect.hasUseBrew() || !effect.getUseBrew().hasTechnique()) {
                continue;
            }
            var parameters = effect.getUseBrew().getTechnique();
            if (parameters.getMethod() != BrewUseMethod.MIST) {
                continue;
            }
            for (ServerLevel level : server.getAllLevels()) {
                if (!(level.getEntity(entity) instanceof LivingEntity actor)) {
                    continue;
                }
                var stack = actor.getMainHandItem();
                if (BrewItemStacks.isBrew(stack)
                        && !BrewResidues.canTrigger(actor, BrewItemStacks.kind(stack),
                        parameters.getRange().getValue())) {
                    return Optional.of(String.format(Locale.ROOT, "%s m 内没有你下的这种药",
                            rangeText(parameters.getRange().getValue())));
                }
            }
        }
        return Optional.empty();
    }

    private static String rangeText(double range) {
        // 提示使用目录中的实际射程，整数射程不显示无意义的小数位。
        if (range == Math.rint(range)) {
            return Long.toString((long) range);
        }
        return Double.toString(range);
    }
}
