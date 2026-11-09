package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.WitheringCandidate;
import dev.kizuna.meridian.brewing.server.WitheringDecision;
import dev.kizuna.meridian.brewing.server.WitheringOutcome;
import dev.kizuna.meridian.brewing.server.WitheringRules;
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
        // 只在喷溅时扫描有限范围，逐 tick 执行时再检查权限和方块现状。
        int searchRadius = (int) Math.ceil(radius) + SEARCH_MARGIN;
        List<PendingWither> pending = new ArrayList<>();
        BlockPos origin = BlockPos.containing(center);
        WitheringArea area = new WitheringArea(level, center, radius, source);
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -searchRadius; y <= searchRadius; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    addCandidate(area, origin.offset(x, y, z), pending);
                }
            }
        }
        QUEUES.computeIfAbsent(level, ignored -> new ArrayList<>()).addAll(pending);
    }

    static void tick(MinecraftServer server) {
        // 每个世界独立消化预算，完成的队列立即释放。
        Iterator<Map.Entry<ServerLevel, List<PendingWither>>> iterator = QUEUES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ServerLevel, List<PendingWither>> entry = iterator.next();
            List<PendingWither> ready = advance(entry.getValue());
            applyReady(entry.getKey(), ready);
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    private static void addCandidate(WitheringArea area, BlockPos pos, List<PendingWither> pending) {
        // 候选只包含已加载、暴露且可枯萎的方块，不因喷溅加载新区块。
        ServerLevel level = area.level();
        if (!level.hasChunkAt(pos)) {
            return;
        }
        double distance = Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(area.center()));
        if (distance > area.radius() || !isExposed(level, pos)) {
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
        if (!WitheringRules.shouldWither(candidate, area.radius())) {
            return;
        }
        long delayTicks = Math.max(0L, Math.round(distance * DELAY_TICKS_PER_BLOCK));
        pending.add(new PendingWither(pos.immutable(), candidate, area.source(), delayTicks));
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

    private static void applyReady(ServerLevel level, List<PendingWither> ready) {
        // 延迟期间方块或权限可能变化，每格在写入前重新确认。
        for (PendingWither task : ready) {
            applyOne(level, task);
        }
    }

    private static void applyOne(ServerLevel level, PendingWither task) {
        // 玩家使用原版交互与建造权限，生物使用游戏规则；只有允许后才生成世界变化。
        BlockPos pos = task.pos();
        BlockState sourceState = level.getBlockState(pos);
        WitheringDecision decision = classify(sourceState);
        if (decision.outcome() == WitheringOutcome.UNCHANGED
                || !isExposed(level, pos)) {
            return;
        }
        if (!mayWither(level, pos, task.source())) {
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

    private static boolean mayWither(ServerLevel level, BlockPos pos, Entity source) {
        // 玩家遵守原版逐格交互和建造权限；生物来源只服从 mobGriefing。
        if (source instanceof Player player) {
            return player.mayBuild() && level.mayInteract(player, pos);
        }
        return level.getGameRules().get(GameRules.MOB_GRIEFING);
    }

    private record WitheringArea(ServerLevel level, Vec3 center, double radius, Entity source) { }

    private static final class PendingWither {
        private final BlockPos pos;
        private final WitheringCandidate candidate;
        private final Entity source;
        private long delayTicks;

        private PendingWither(BlockPos pos, WitheringCandidate candidate, Entity source,
                              long delayTicks) {
            // 保存来源实体以便执行时重新判断权限，不缓存起手时的授权结论。
            this.pos = pos;
            this.candidate = candidate;
            this.source = source;
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

    }
}
