package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.WitheringCandidate;
import dev.kizuna.meridian.brewing.server.WitheringDecision;
import dev.kizuna.meridian.brewing.server.WitheringOutcome;
import dev.kizuna.meridian.brewing.server.WitheringPermissions;
import dev.kizuna.meridian.brewing.server.WitheringRules;
import dev.kizuna.meridian.brewing.server.WitheringSource;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/** 枯竭药水落点的延迟枯萎队列，负责预算和世界权限检查。 */
final class WitheringService {
    private static final double DELAY_TICKS_PER_BLOCK = 6.0;
    private static final int SEARCH_MARGIN = 1;
    private static final int PARTICLE_COUNT = 4;
    private static final Map<ServerLevel, List<PendingWither>> QUEUES = new IdentityHashMap<>();

    private WitheringService() {
        // 枯萎任务只在服务端内存中排队，世界保存由方块本身负责。
    }

    static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> QUEUES.clear());
    }

    static void enqueue(ServerLevel level, Vec3 center, double radius, Entity source) {
        int searchRadius = (int) Math.ceil(radius) + SEARCH_MARGIN;
        List<PendingWither> pending = new ArrayList<>();
        BlockPos origin = BlockPos.containing(center);
        WitheringSource sourceKind = source instanceof Player
                ? WitheringSource.PLAYER : WitheringSource.MOB;
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -searchRadius; y <= searchRadius; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    addCandidate(level, center, origin.offset(x, y, z), radius,
                            source, sourceKind, pending);
                }
            }
        }
        QUEUES.computeIfAbsent(level, ignored -> new ArrayList<>()).addAll(pending);
    }

    static void tick(MinecraftServer server) {
        Iterator<Map.Entry<ServerLevel, List<PendingWither>>> iterator = QUEUES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ServerLevel, List<PendingWither>> entry = iterator.next();
            List<PendingWither> ready = advance(entry.getValue());
            applyReady(server, entry.getKey(), ready);
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    private static void addCandidate(ServerLevel level, Vec3 center, BlockPos pos,
                                     double radius, Entity source, WitheringSource sourceKind,
                                     List<PendingWither> pending) {
        if (!level.hasChunkAt(pos)) {
            return;
        }
        double distance = Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(center));
        if (distance > radius || !isExposed(level, pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        WitheringDecision decision = classify(state);
        if (decision.outcome() == WitheringOutcome.UNCHANGED) {
            return;
        }
        double randomValue = level.getRandom().nextDouble();
        WitheringCandidate candidate = new WitheringCandidate(
                state.getBlock().builtInRegistryHolder().key().identifier().toString(),
                distance, randomValue);
        if (!WitheringRules.shouldWither(candidate, radius)) {
            return;
        }
        long delayTicks = Math.max(0L, Math.round(distance * DELAY_TICKS_PER_BLOCK));
        pending.add(new PendingWither(pos.immutable(), candidate, source, sourceKind, delayTicks));
    }

    private static List<PendingWither> advance(List<PendingWither> queue) {
        List<PendingWither> ready = new ArrayList<>();
        for (PendingWither task : queue) {
            task.tick();
            if (task.ready()) {
                ready.add(task);
            }
        }
        queue.removeAll(ready);
        ready.sort(Comparator.comparingDouble(task -> task.candidate().distance()));
        if (ready.size() > WitheringRules.MAX_BLOCKS_PER_TICK) {
            List<PendingWither> deferred = new ArrayList<>(ready.subList(
                    WitheringRules.MAX_BLOCKS_PER_TICK, ready.size()));
            for (PendingWither task : deferred) {
                task.delayOneTick();
            }
            queue.addAll(deferred);
            return List.copyOf(ready.subList(0, WitheringRules.MAX_BLOCKS_PER_TICK));
        }
        return List.copyOf(ready);
    }

    private static void applyReady(MinecraftServer server, ServerLevel level,
                                   List<PendingWither> ready) {
        for (PendingWither task : ready) {
            applyOne(server, level, task);
        }
    }

    private static void applyOne(MinecraftServer server, ServerLevel level, PendingWither task) {
        BlockPos pos = task.pos();
        BlockState sourceState = level.getBlockState(pos);
        WitheringDecision decision = classify(sourceState);
        if (decision.outcome() == WitheringOutcome.UNCHANGED
                || !isExposed(level, pos)) {
            return;
        }
        boolean protectedSpawn = isProtectedSpawn(server, level, pos, task.source());
        boolean mobGriefing = level.getGameRules().get(GameRules.MOB_GRIEFING);
        WitheringPermissions permissions = new WitheringPermissions(
                task.sourceKind(), true, mobGriefing, protectedSpawn);
        if (!permissions.allowsBlockChange()) {
            return;
        }
        if (task.source() instanceof Player player && !level.mayInteract(player, pos)) {
            return;
        }
        BlockState targetState = targetState(level, pos, sourceState, decision);
        if (targetState == null) {
            return;
        }
        level.setBlock(pos, targetState, Block.UPDATE_ALL);
        level.sendParticles(decision.outcome() == WitheringOutcome.SMOKE
                        ? ParticleTypes.CAMPFIRE_COSY_SMOKE : ParticleTypes.WHITE_ASH,
                pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5,
                PARTICLE_COUNT, 0.2, 0.2, 0.2, 0.01);
        level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH,
                SoundSource.BLOCKS, 0.35F, 1.0F);
    }

    private static WitheringDecision classify(BlockState state) {
        String blockId = state.getBlock().builtInRegistryHolder().key().identifier().toString();
        if (state.is(BrewingTags.WITHERS_TO_DIRT)) {
            return new WitheringDecision(blockId, "minecraft:dirt", WitheringOutcome.DIRT);
        }
        if (state.is(BrewingTags.WITHERS_TO_NETHERRACK)) {
            return new WitheringDecision(blockId, "minecraft:netherrack", WitheringOutcome.NETHERRACK);
        }
        if (state.is(BrewingTags.WITHERS_TO_DEAD_BUSH)) {
            return new WitheringDecision(blockId, "minecraft:dead_bush", WitheringOutcome.DEAD_BUSH);
        }
        if (state.is(BrewingTags.WITHERS_TO_SMOKE)) {
            return new WitheringDecision(blockId, "minecraft:air", WitheringOutcome.SMOKE);
        }
        return WitheringRules.classify(blockId);
    }

    private static BlockState targetState(ServerLevel level, BlockPos pos, BlockState sourceState,
                                          WitheringDecision decision) {
        if (decision.outcome() == WitheringOutcome.DEAD_BUSH) {
            BlockState deadBush = Blocks.DEAD_BUSH.defaultBlockState();
            if (deadBush.canSurvive(level, pos)) {
                return deadBush;
            }
            return Blocks.AIR.defaultBlockState();
        }
        Block target = BuiltInRegistries.BLOCK.getValue(
                Identifier.parse(decision.targetBlockId()));
        BlockState state = target.defaultBlockState();
        if (sourceState.hasProperty(RotatedPillarBlock.AXIS)
                && state.hasProperty(RotatedPillarBlock.AXIS)) {
            state = state.setValue(RotatedPillarBlock.AXIS,
                    sourceState.getValue(RotatedPillarBlock.AXIS));
        }
        return state;
    }

    private static boolean isExposed(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProtectedSpawn(MinecraftServer server, ServerLevel level,
                                            BlockPos pos, Entity source) {
        if (source instanceof Player player) {
            return server.isUnderSpawnProtection(level, pos, player);
        }
        LevelData.RespawnData respawn = level.getRespawnData();
        if (!level.dimension().equals(respawn.dimension())) {
            return false;
        }
        int spawnRadius = level.getGameRules().get(GameRules.RESPAWN_RADIUS);
        return pos.distSqr(respawn.pos()) <= (double) spawnRadius * spawnRadius;
    }

    private static final class PendingWither {
        private final BlockPos pos;
        private final WitheringCandidate candidate;
        private final Entity source;
        private final WitheringSource sourceKind;
        private long delayTicks;

        private PendingWither(BlockPos pos, WitheringCandidate candidate, Entity source,
                              WitheringSource sourceKind, long delayTicks) {
            this.pos = pos;
            this.candidate = candidate;
            this.source = source;
            this.sourceKind = sourceKind;
            this.delayTicks = delayTicks;
        }

        private void tick() {
            delayTicks = Math.max(0L, delayTicks - 1L);
        }

        private void delayOneTick() {
            delayTicks = 1L;
        }

        private boolean ready() {
            return delayTicks == 0L;
        }

        private BlockPos pos() {
            return pos;
        }

        private WitheringCandidate candidate() {
            return candidate;
        }

        private Entity source() {
            return source;
        }

        private WitheringSource sourceKind() {
            return sourceKind;
        }
    }
}
