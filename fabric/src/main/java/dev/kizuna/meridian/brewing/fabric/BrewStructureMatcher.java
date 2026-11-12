package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewStructureDefinitions.Cell;
import dev.kizuna.meridian.brewing.fabric.BrewStructureDefinitions.Definition;
import dev.kizuna.meridian.brewing.server.BrewStructureSettings;
import dev.kizuna.meridian.brewing.server.StructureMatch;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * 拿世界里的方块和熬药结构定义比。认 4 个水平朝向、不认镜像；坐标换算和原版摆结构一样：
 * 世界位置 = 原点 + {@code StructureTemplate.transform(相对位置, NONE, 朝向, (sx/2, 0, sz/2))}。
 *
 * <p>比对顺序：核心 → 必须空 → 燃料位 → 外框，同一类里从下往上。几个候选都不对时，挑核心对得最多、
 * 再比总共对得最多的那个，报它的第一处不符。</p>
 */
final class BrewStructureMatcher {
    private static final Rotation[] ROTATIONS = {
        Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90
    };
    private static final Result UNCHECKED = new Result(StructureMatch.unloaded(), Optional.empty(), Optional.empty());
    private static BrewStructureSettings settings;

    private BrewStructureMatcher() {
    }

    static void initialize(Path configPath) {
        // 外框容差读配置；旧导出的文件缺键时用默认值并提示。
        settings = BrewStructureSettings.load(configPath);
    }

    /** 还没查过时的结果：当作暂未加载，起不了锅。 */
    static Result unchecked() {
        return UNCHECKED;
    }

    /** 以釜为锚：这只釜配的每份定义、每个朝向各反推一个原点来比。 */
    static Result matchCauldron(ServerLevel level, BlockPos cauldronPos, Block cauldron) {
        List<Placement> candidates = new ArrayList<>();
        for (Definition definition : BrewStructureDefinitions.anchoredOn(level.getServer(), cauldron)) {
            for (Rotation rotation : ROTATIONS) {
                candidates.add(Placement.anchoredAt(definition, cauldronPos, rotation));
            }
        }
        if (candidates.isEmpty()) {
            return new Result(StructureMatch.undefined(), Optional.empty(), Optional.empty());
        }
        return best(level, candidates, Scan.FULL);
    }

    /**
     * 以玩家点的那一格为准：{@code definitions} 里每个 {@code entry} 认的格子，只要世界里这格的方块合它的规则，
     * 就让它落在 {@code clicked}，每个朝向各比一次。药塔从玻璃、铜灯认就走这里。
     */
    static Result matchAround(ServerLevel level, BlockPos clicked, List<Definition> definitions,
                              Predicate<Cell> entry) {
        // 候选不多：点玻璃是 8 格 × 4 向，点铜灯是 4 格 × 4 向；先比核心，碰到第一处不符就换下一个候选。
        // 这里只问认不认得出，认不出就交给原版，用不着挑最接近的那个。
        BlockState actual = level.getBlockState(clicked);
        List<Placement> candidates = new ArrayList<>();
        for (Definition definition : definitions) {
            for (Cell cell : entryCells(definition, entry, actual)) {
                for (Rotation rotation : ROTATIONS) {
                    candidates.add(Placement.placedAt(definition, cell.local(), clicked, rotation));
                }
            }
        }
        if (candidates.isEmpty()) {
            return new Result(StructureMatch.undefined(), Optional.empty(), Optional.empty());
        }
        return best(level, candidates, Scan.UNTIL_STRICT_MISMATCH);
    }

    private static List<Cell> entryCells(Definition definition, Predicate<Cell> entry, BlockState actual) {
        // 只取入口格里规则认得世界这格方块的，点的是铜灯就不拿玻璃格去比。
        List<Cell> cells = new ArrayList<>();
        for (Cell cell : definition.cores()) {
            if (entry.test(cell) && cell.rule().accepts().test(actual)) {
                cells.add(cell);
            }
        }
        for (Cell cell : definition.frames()) {
            if (entry.test(cell) && cell.rule().accepts().test(actual)) {
                cells.add(cell);
            }
        }
        return cells;
    }

    /** 只比一个已知的摆法，例如塔记下的原点和朝向。 */
    static Result matchPlacement(ServerLevel level, Placement placement) {
        return best(level, List.of(placement), Scan.FULL);
    }

    private static Result best(ServerLevel level, List<Placement> candidates, Scan scan) {
        // 有效的候选直接用；区块没加载的候选不比，要是加载了的都不对，就当暂未加载，不判失效。
        Evaluation closest = null;
        boolean anyUnloaded = false;
        for (Placement placement : candidates) {
            if (!placement.loaded(level)) {
                anyUnloaded = true;
                continue;
            }
            Evaluation evaluation = evaluate(level, placement, scan);
            if (evaluation.failure().isEmpty()) {
                return new Result(StructureMatch.valid(), Optional.of(placement), Optional.empty());
            }
            if (closest == null || evaluation.closerThan(closest)) {
                closest = evaluation;
            }
        }
        if (anyUnloaded || closest == null) {
            return new Result(StructureMatch.unloaded(), Optional.empty(), Optional.empty());
        }
        Failure failure = closest.failure().orElseThrow();
        return new Result(StructureMatch.mismatch(failure.mismatch(closest.placement())),
                Optional.of(closest.placement()), Optional.of(failure.marker()));
    }

    private static Evaluation evaluate(ServerLevel level, Placement placement, Scan scan) {
        // 核心、必须空、燃料位一格不对就不认；外框缺格不超过容差仍算有效。
        // 要挑最接近的候选时每格都查一遍；只问认不认得出时，第一处严格不符就停。
        Definition definition = placement.definition();
        Tally tally = new Tally(scan);
        int coresMatched = checkStrict(level, placement, definition.cores(), tally);
        checkStrict(level, placement, definition.requiredAir(), tally);
        checkStrict(level, placement, definition.fuel(), tally);
        Optional<Failure> strictFailure = tally.first;
        if (tally.stopped()) {
            return new Evaluation(placement, coresMatched, tally.matched, strictFailure);
        }
        int frameFailures = 0;
        Failure firstFrame = null;
        for (Cell cell : definition.frames()) {
            BlockPos world = placement.world(cell.local());
            BlockState actual = level.getBlockState(world);
            if (cell.rule().accepts().test(actual)) {
                tally.matched++;
            } else {
                frameFailures++;
                if (firstFrame == null) {
                    firstFrame = new Failure(cell, world, actual);
                }
            }
        }
        Optional<Failure> failure = strictFailure;
        if (failure.isEmpty() && frameFailures > settings.frameTolerance()) {
            failure = Optional.of(firstFrame);
        }
        return new Evaluation(placement, coresMatched, tally.matched, failure);
    }

    private static int checkStrict(ServerLevel level, Placement placement, List<Cell> cells, Tally tally) {
        // 一格不对就不认：记下第一处；要挑最接近的就接着数，不然到这里就停。返回这一组对了几格。
        int matchedHere = 0;
        for (Cell cell : cells) {
            if (tally.stopped()) {
                return matchedHere;
            }
            BlockPos world = placement.world(cell.local());
            BlockState actual = level.getBlockState(world);
            if (cell.rule().accepts().test(actual)) {
                matchedHere++;
                tally.matched++;
            } else if (tally.first.isEmpty()) {
                tally.first = Optional.of(new Failure(cell, world, actual));
            }
        }
        return matchedHere;
    }

    /** 一个候选要比到哪：起锅、复查要比完好挑最接近的、说清第一处不符；认塔只问认不认得出。 */
    private enum Scan {
        FULL,
        UNTIL_STRICT_MISMATCH
    }

    /** 一个候选数到哪了：总共对了几格、第一处严格不符。 */
    private static final class Tally {
        private final Scan scan;
        private int matched;
        private Optional<Failure> first = Optional.empty();

        private Tally(Scan scan) {
            this.scan = scan;
        }

        private boolean stopped() {
            // 只问认不认得出时，有了第一处严格不符就不用再读。
            return scan == Scan.UNTIL_STRICT_MISMATCH && first.isPresent();
        }
    }

    /**
     * 一种摆法：哪份定义、NBT 原点落在世界哪一格、转了多少。
     */
    record Placement(Definition definition, BlockPos origin, Rotation rotation) {
        /** 让定义的锚点格正好落在 {@code anchorWorld}。 */
        static Placement anchoredAt(Definition definition, BlockPos anchorWorld, Rotation rotation) {
            return placedAt(definition, definition.anchor(), anchorWorld, rotation);
        }

        /** 让定义里相对位置 {@code local} 的那格正好落在 {@code world}。 */
        static Placement placedAt(Definition definition, BlockPos local, BlockPos world, Rotation rotation) {
            // 先把这格按朝向转过去，再从世界位置倒推原点。
            BlockPos turned = StructureTemplate.transform(local, Mirror.NONE, rotation, pivot(definition));
            return new Placement(definition, world.subtract(turned), rotation);
        }

        /** NBT 里的相对位置在世界里是哪一格。 */
        BlockPos world(BlockPos local) {
            return origin.offset(StructureTemplate.transform(local, Mirror.NONE, rotation, pivot(definition)));
        }

        /** 锚点格在世界里的位置；不符时的方位从这里量。 */
        BlockPos anchorWorld() {
            return world(definition.anchor());
        }

        private boolean loaded(ServerLevel level) {
            // 结构跨的区块都加载了才比，两个对角转过去取最小、最大。
            BlockPos first = world(BlockPos.ZERO);
            BlockPos last = world(new BlockPos(definition.size().getX() - 1, definition.size().getY() - 1,
                    definition.size().getZ() - 1));
            BlockPos min = BlockPos.min(first, last);
            BlockPos max = BlockPos.max(first, last);
            return level.hasChunksAt(min, max);
        }

        private static BlockPos pivot(Definition definition) {
            // 和结构放置器、原版 /place template 摆出来的旋转中心一致。
            return new BlockPos(definition.size().getX() / 2, 0, definition.size().getZ() / 2);
        }
    }

    /** 不符那一格在世界里的位置和标记粒子要显示的方块。 */
    record Marker(BlockPos pos, BlockState shown) {
    }

    /** 比对结果：给规则看的 {@link StructureMatch}、认出来或最接近的摆法、不符时的标记。 */
    record Result(StructureMatch match, Optional<Placement> placement, Optional<Marker> marker) {
    }

    private record Failure(Cell cell, BlockPos world, BlockState actual) {
        StructureMatch.Mismatch mismatch(Placement placement) {
            // 方位按世界东、上、南量，相对锚点。
            BlockPos offset = world.subtract(placement.anchorWorld());
            String actualId = BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString();
            return new StructureMatch.Mismatch(placement.definition().anchorName(), offset.getX(), offset.getY(),
                    offset.getZ(), cell.rule().expected(), actualId);
        }

        Marker marker() {
            return new Marker(world, cell.rule().shown());
        }
    }

    private record Evaluation(Placement placement, int coresMatched, int matched, Optional<Failure> failure) {
        boolean closerThan(Evaluation other) {
            // 先比核心对了几格，再比总共对了几格；一样时先比的那个留下。
            if (coresMatched != other.coresMatched) {
                return coresMatched > other.coresMatched;
            }
            return matched > other.matched;
        }
    }
}
