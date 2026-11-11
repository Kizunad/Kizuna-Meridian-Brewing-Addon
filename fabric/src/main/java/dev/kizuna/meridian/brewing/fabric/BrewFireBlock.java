package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewFireSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 铁火盆和铁炉座的可调火源方块。比较器读火档，0–4 格信号。 */
final class BrewFireBlock extends FacingApparatusBlock implements EntityBlock {
    private final BrewFireSource source;

    BrewFireBlock(BrewFireSource source, VoxelShape shape, BlockBehaviour.Properties properties) {
        super(shape, properties);
        this.source = source;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BrewFireBlockEntity(pos, state, source);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return BrewFireBlockEntity.ticker(type);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        if (level.getBlockEntity(pos) instanceof BrewFireBlockEntity fire) {
            return fire.fire().level();
        }
        return 0;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (BrewingStirrers.yieldsToOffHand(stack, hand)) {
            return InteractionResult.PASS;
        }
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof BrewFireBlockEntity fire)) {
            return InteractionResult.PASS;
        }
        return fire.use(player, hand);
    }
}
