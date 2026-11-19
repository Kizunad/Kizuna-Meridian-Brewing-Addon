package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.fabric.FurnacePatterns.Cell;
import dev.kizuna.meridian.brewing.fabric.FurnacePatterns.Pattern;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** 所有坐标相对底板中心。模板放置和检查使用原版相同的镜像、旋转顺序。 */
record FurnaceLayout(Pattern pattern, BlockPos anchor, Mirror mirror, Rotation rotation) {
    private static final List<Property<?>> GEOMETRY = List.of(BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.AXIS, BlockStateProperties.SLAB_TYPE, BlockStateProperties.HALF,
            BlockStateProperties.STAIRS_SHAPE, BlockStateProperties.CHEST_TYPE);

    record Fault(BlockPos pos, BlockState shown) {}
    enum Status { FORMED, BROKEN, UNLOADED }
    record Check(Status status, int faults, Fault first) {}

    BlockPos world(Cell cell) {
        return world(cell.offset());
    }

    BlockPos world(BlockPos local) {
        return anchor.offset(StructureTemplate.transform(local, mirror, rotation, BlockPos.ZERO));
    }

    static BlockPos offset(Cell cell, Mirror mirror, Rotation rotation) {
        return StructureTemplate.transform(cell.offset(), mirror, rotation, BlockPos.ZERO);
    }

    Check check(ServerLevel level) {
        // 两个对角覆盖全部实体格和要求留空的格。缺区块时不读世界方块，也不宣布失效。
        if (!loaded(level)) {
            return new Check(Status.UNLOADED, 0, null);
        }
        int faults = 0;
        Fault first = null;
        for (Cell cell : pattern.cells()) {
            BlockPos pos = world(cell);
            BlockState actual = level.getBlockState(pos);
            BlockState expected = cell.shown().mirror(mirror).rotate(rotation);
            if (!matches(level, pos, actual, expected, cell)) {
                faults++;
                if (first == null) {
                    BlockState shown = expected;
                    if (expected.isAir()) {
                        shown = Blocks.BARRIER.defaultBlockState();
                    }
                    first = new Fault(pos, shown);
                }
            }
        }
        if (first != null) {
            return new Check(Status.BROKEN, faults, first);
        }
        return new Check(Status.FORMED, 0, null);
    }

    boolean loaded(ServerLevel level) {
        var bounds = pattern.bounds();
        BlockPos corner = anchor.offset(StructureTemplate.transform(bounds.min(), mirror, rotation, BlockPos.ZERO));
        BlockPos opposite = anchor.offset(StructureTemplate.transform(bounds.max(), mirror, rotation, BlockPos.ZERO));
        return level.hasChunksAt(BlockPos.min(corner, opposite), BlockPos.max(corner, opposite));
    }

    boolean nearFires(ServerLevel level) {
        // 一处缺损仍要能定位。先用少数火位筛掉附近同材质的普通建筑。
        int missing = 0;
        for (Cell fire : pattern.fires()) {
            BlockPos pos = world(fire);
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(fire.shown().getBlock())) {
                missing++;
            }
        }
        return missing <= 1;
    }

    static boolean sameMaterial(BlockState expected, BlockState actual) {
        if (expected.is(actual.getBlock())) {
            return true;
        }
        return base(expected.getBlock()) == base(actual.getBlock());
    }

    static Block base(Block material) {
        Block unwaxed = HoneycombItem.WAX_OFF_BY_BLOCK.get().getOrDefault(material, material);
        return WeatheringCopper.getFirst(unwaxed);
    }

    private static boolean matches(ServerLevel level, BlockPos pos, BlockState actual, BlockState expected, Cell cell) {
        return switch (cell.role()) {
            case LIQUID, FEED, CLEAR -> actual.isAir();
            case HEARTH -> !actual.is(BlockTags.CAMPFIRES);
            case INPUTS -> actual.is(BrewingTags.WOODEN_INPUT_CHESTS) && sameGeometry(actual, expected)
                    && level.getBlockEntity(pos) instanceof Container;
            case OUTPUTS -> actual.is(BlockTags.COPPER_CHESTS) && sameGeometry(actual, expected)
                    && level.getBlockEntity(pos) instanceof Container;
            default -> sameMaterial(expected, actual) && sameGeometry(actual, expected);
        };
    }

    private static boolean sameGeometry(BlockState actual, BlockState expected) {
        for (var property : GEOMETRY) {
            if (expected.hasProperty(property) && (!actual.hasProperty(property)
                    || !actual.getValue(property).equals(expected.getValue(property)))) {
                return false;
            }
        }
        return true;
    }
}
