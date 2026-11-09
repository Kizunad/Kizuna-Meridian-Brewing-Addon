package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewBodyFacts;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewThresholds;
import dev.kizuna.meridian.brewing.server.RecoveryState;
import dev.kizuna.meridian.brewing.server.ResidueKey;
import dev.kizuna.meridian.brewing.server.ResidueSeed;
import dev.kizuna.meridian.brewing.server.ResidueSnapshot;
import dev.kizuna.meridian.brewing.server.ResidueStore;
import dev.kizuna.meridian.brewing.server.ResidueTrigger;
import dev.kizuna.meridian.brewing.server.BrewPotency;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewTechniqueParameters;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewPartGroup;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Category;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** 世界只提供当前身体事实；剂量、覆盖与指数衰减复用纯规则存储。 */
public final class BrewResidues {
    public interface Triggered {
        void onTriggered(LivingEntity source, BrewKind kind, int targets);
    }

    public static final Event<Triggered> TRIGGERED = EventFactory.createArrayBacked(Triggered.class,
            callbacks -> (source, kind, count) -> {
                for (Triggered callback : callbacks) {
                    callback.onTriggered(source, kind, count);
                }
            });
    private static final double SECONDS_PER_TICK = 0.05;
    private static ResidueStore store = new ResidueStore();
    private static final Map<UUID, LivingEntity> targets = new HashMap<>();
    private static BrewThresholds thresholds;

    private BrewResidues() {
        // 残药只活在当前服务端进程中。
    }

    static void initialize() {
        // 与身体死亡、实体卸载和停服共同清理，不能把旧世界对象带到新会话。
        thresholds = BrewThresholds.load(FabricLoader.getInstance().getConfigDir()
                .resolve("meridian-brewing.properties"));
        ServerTickEvents.END_SERVER_TICK.register(BrewResidues::tick);
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> clear(entity.getUUID()));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> clear(entity.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            targets.clear();
            store = new ResidueStore();
        });
    }

    static void seed(LivingEntity target, BrewBodyEffects.Application application, List<String> parts,
                     double dose) {
        // 下引只接受活着的施药者与实际命中的部位，无害肾脏不保留枯竭残药。
        if (dose <= 0 || !(application.source() instanceof LivingEntity source) || !target.isAlive()) {
            return;
        }
        var key = new ResidueKey(source.getUUID(), target.getUUID(), application.kind(), "");
        targets.put(target.getUUID(), target);
        if (conditions(key).kidneyMultiplier() == 0) {
            return;
        }
        for (String part : parts) {
            store.put(new ResidueSeed(source.getUUID(), target.getUUID(), application.kind(),
                    application.quality(), part, dose));
        }
        if (!parts.isEmpty()) {
            BrewSounds.residue(target);
        }
    }

    static int trigger(LivingEntity source, BrewKind kind, BrewTechniqueParameters parameters) {
        // 距离过滤没有视线检查；同种同源残药取出后按各部位原品质重新生效。
        int count = 0;
        var scale = new ResidueStore.ActivationScale(parameters.getStrengthMultiplier(),
                parameters.getDurationMultiplier());
        for (LivingEntity target : List.copyOf(targets.values())) {
            double distance = source.distanceTo(target);
            if (target.level() != source.level() || distance > parameters.getRange().getValue()) {
                continue;
            }
            var trigger = new ResidueTrigger(source.getUUID(), target.getUUID(), kind, distance);
            var activations = store.trigger(trigger, scale);
            for (var activation : activations) {
                var potency = new BrewPotency(activation.quality(), activation.doseMultiplier(),
                        activation.durationMultiplier(), 0);
                var delivery = new BrewDelivery(potency, BrewPartGroup.BREW_PART_GROUP_UNSPECIFIED,
                        List.of(activation.key().partId()));
                var application = new BrewBodyEffects.Application(kind, activation.quality(), BrewUse.MIST,
                        target.position(), source);
                BrewBodyEffects.apply(target, application, delivery);
            }
            if (!activations.isEmpty()) {
                BrewSounds.effect(target, kind);
                if (kind == BrewKind.INSECT_LURE) {
                    var strongest = activations.stream().max(Comparator.comparingDouble(
                            activation -> activation.doseMultiplier())).orElseThrow();
                    BrewingRuntime.spawnSilverfish((ServerLevel) target.level(),
                            target.position(), strongest.quality(), source, scale.strength());
                }
                count++;
            }
        }
        if (count > 0) {
            TRIGGERED.invoker().onTriggered(source, kind, count);
        }
        return count;
    }

    static List<ResidueSnapshot> snapshots() {
        // 只读结果也用于核对跨仓用药后实际留下的来源与部位。
        return store.snapshots();
    }

    public static List<ResidueSnapshot> onTarget(UUID source, UUID target) {
        // 返回该来源在目标上的各药种、部位和当前剂量，不向调用方暴露可变存储。
        return store.snapshots().stream()
                .filter(snapshot -> snapshot.key().source().equals(source)
                        && snapshot.key().target().equals(target)).toList();
    }

    public static boolean canTrigger(LivingEntity source, BrewKind kind, double range) {
        // 起手查询与催发共用同一存储；只检查来源、药种、维度和实际距离。
        return store.snapshots().stream().anyMatch(snapshot -> {
            var key = snapshot.key();
            LivingEntity target = targets.get(key.target());
            return key.source().equals(source.getUUID()) && key.kind() == kind
                    && target != null && target.isAlive() && target.level() == source.level()
                    && source.distanceToSqr(target) <= range * range;
        });
    }

    public static void clear(UUID target) {
        // 牛奶即使没有可见状态，也清除尚未消散的残药。
        store.clearTarget(target);
        targets.remove(target);
    }

    private static void tick(MinecraftServer server) {
        // 身体恢复与肾脏每 tick 重读，不能把下引时的状态永久缓存。
        store.tick(SECONDS_PER_TICK, BrewResidues::conditions);
        var retained = store.snapshots().stream().map(snapshot -> snapshot.key().target()).collect(Collectors.toSet());
        targets.keySet().removeIf(id -> !retained.contains(id));
    }

    private static ResidueStore.Decay conditions(ResidueKey key) {
        // 只有枯竭看肾脏；任意恢复类施放均加快消散，不依赖具体基础功法 ID。
        LivingEntity target = targets.get(key.target());
        var found = BrewBodyHost.inspect(target);
        if (found.isEmpty()) {
            return new ResidueStore.Decay(1, RecoveryState.INACTIVE);
        }
        var body = found.get().body();
        double kidney = 1;
        if (key.kind() == BrewKind.DEPLETION) {
            kidney = BrewBodyFacts.detox(body, thresholds).tier().durationMultiplier();
        }
        var progress = TechniqueState.read(BodyProtobuf.fromMessage(body));
        RecoveryState recovery = RecoveryState.INACTIVE;
        if (progress.hasActiveCast()) {
            var definition = TechniqueFabric.service().registry()
                    .find(progress.getActiveCast().getRequest().getTechnique()).orElseThrow();
            if (definition.getCategory() == Category.RECOVERY) {
                recovery = RecoveryState.ACTIVE;
            }
        }
        return new ResidueStore.Decay(kidney, recovery);
    }
}
