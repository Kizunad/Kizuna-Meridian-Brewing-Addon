package dev.kizuna.meridian.brewing.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 枯竭药水落地的半径、机会和默认方块映射。 */
public final class WitheringRules {
    public static final double MAX_TICK_RADIUS = 4.5;
    public static final int MAX_BLOCKS_PER_TICK = 8;
    public static final int MAX_SOUNDS_PER_TICK = 2;
    private static final Map<String, String> DEAD_LOGS = Map.ofEntries(
            Map.entry("minecraft:oak_log", "meridian_brewing:dead_oak_log"),
            Map.entry("minecraft:spruce_log", "meridian_brewing:dead_spruce_log"),
            Map.entry("minecraft:birch_log", "meridian_brewing:dead_birch_log"),
            Map.entry("minecraft:jungle_log", "meridian_brewing:dead_jungle_log"),
            Map.entry("minecraft:acacia_log", "meridian_brewing:dead_acacia_log"),
            Map.entry("minecraft:dark_oak_log", "meridian_brewing:dead_dark_oak_log"),
            Map.entry("minecraft:mangrove_log", "meridian_brewing:dead_mangrove_log"),
            Map.entry("minecraft:cherry_log", "meridian_brewing:dead_cherry_log"),
            Map.entry("minecraft:pale_oak_log", "meridian_brewing:dead_pale_oak_log"));

    private WitheringRules() {
        // 枯萎转换不保存世界状态。
    }

    public static double chance(double distance, double radius) {
        if (!Double.isFinite(distance) || !Double.isFinite(radius)
                || distance < 0 || radius <= 0) {
            throw new IllegalArgumentException("枯萎距离非法");
        }
        return Math.max(0.0, Math.min(1.0, 1.0 - distance / radius));
    }

    public static boolean shouldWither(WitheringCandidate candidate, double radius) {
        return candidate.randomValue() <= chance(candidate.distance(), radius);
    }

    public static List<WitheringCandidate> budget(List<WitheringCandidate> candidates) {
        if (candidates == null) {
            throw new IllegalArgumentException("枯萎候选不能为空");
        }
        List<WitheringCandidate> selected = new ArrayList<>(candidates);
        selected.sort(Comparator.comparingDouble(WitheringCandidate::distance));
        if (selected.size() > MAX_BLOCKS_PER_TICK) {
            return List.copyOf(selected.subList(0, MAX_BLOCKS_PER_TICK));
        }
        return List.copyOf(selected);
    }

    public static WitheringDecision classify(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            throw new IllegalArgumentException("方块 ID 不能为空");
        }
        String logTarget = DEAD_LOGS.get(blockId);
        if (logTarget != null) {
            return new WitheringDecision(blockId, logTarget, WitheringOutcome.DEAD_LOG);
        }
        if (blockId.endsWith("_leaves")) {
            return new WitheringDecision(blockId, "minecraft:air", WitheringOutcome.AIR);
        }
        if (blockId.equals("minecraft:grass_block") || blockId.equals("minecraft:mycelium")
                || blockId.equals("minecraft:podzol") || blockId.equals("minecraft:farmland")
                || blockId.endsWith("moss_block")) {
            return new WitheringDecision(blockId, "minecraft:dirt", WitheringOutcome.DIRT);
        }
        if (blockId.equals("minecraft:crimson_nylium") || blockId.equals("minecraft:warped_nylium")) {
            return new WitheringDecision(blockId, "minecraft:netherrack", WitheringOutcome.NETHERRACK);
        }
        if (blockId.equals("minecraft:short_grass") || blockId.equals("minecraft:fern")
                || blockId.endsWith("_sapling") || blockId.endsWith("_mushroom")
                || blockId.endsWith("_fungus")) {
            WitheringOutcome outcome = WitheringOutcome.DEAD_BUSH;
            String target = "minecraft:dead_bush";
            if (blockId.endsWith("_mushroom") || blockId.endsWith("_fungus")) {
                outcome = WitheringOutcome.SMOKE;
                target = "minecraft:air";
            }
            return new WitheringDecision(blockId, target, outcome);
        }
        if (blockId.contains("mushroom_block") || blockId.equals("minecraft:nether_wart")) {
            return new WitheringDecision(blockId, "minecraft:air", WitheringOutcome.SMOKE);
        }
        return new WitheringDecision(blockId, blockId, WitheringOutcome.UNCHANGED);
    }
}
