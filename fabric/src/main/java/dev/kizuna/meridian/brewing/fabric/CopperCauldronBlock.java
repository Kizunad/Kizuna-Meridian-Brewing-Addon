package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.CopperOxidation;
import dev.kizuna.meridian.brewing.server.VesselKind;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 铜釜方块。氧化级以方块实体里的规则状态为准，方块状态 {@code oxidation} 只是它的外观镜像：
 * 四级贴图走区块网格，不需要客户端同步包，导出结构时也随方块带出；上蜡没有外观差别，不进方块状态。
 */
final class CopperCauldronBlock extends BrewCauldronBlock {
    static final EnumProperty<WeatheringCopper.WeatherState> OXIDATION =
            EnumProperty.create("oxidation", WeatheringCopper.WeatherState.class);

    CopperCauldronBlock(VoxelShape shape, BlockBehaviour.Properties properties) {
        super(VesselKind.COPPER_CAULDRON, shape, properties);
        registerDefaultState(stateDefinition.any().setValue(OXIDATION, WeatheringCopper.WeatherState.UNAFFECTED));
    }

    static CopperOxidation oxidation(BlockState state) {
        // 原版铜的四级与规则层同名同序，按名字互换。
        return CopperOxidation.valueOf(state.getValue(OXIDATION).name());
    }

    static BlockState showOxidation(BlockState state, CopperOxidation oxidation) {
        return state.setValue(OXIDATION, WeatheringCopper.WeatherState.valueOf(oxidation.name()));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OXIDATION);
    }
}
