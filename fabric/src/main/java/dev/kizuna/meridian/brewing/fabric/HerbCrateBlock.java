package dev.kizuna.meridian.brewing.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 九格晒药匾的右键入口。外形前后左右对称，没有朝向；碰撞与轮廓取木框外沿。打掉时原版按容器掉出里面的东西。
 * 比较器读晒好的格数。
 */
final class HerbCrateBlock extends Block implements EntityBlock {
    private final VoxelShape shape;

    HerbCrateBlock(VoxelShape shape, BlockBehaviour.Properties properties) {
        super(properties);
        this.shape = shape;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HerbCrateBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return HerbCrateBlockEntity.ticker(type);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        if (level.getBlockEntity(pos) instanceof HerbCrateBlockEntity crate) {
            return crate.driedSlots();
        }
        return 0;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof HerbCrateBlockEntity crate)) {
            return InteractionResult.PASS;
        }
        return crate.open(player);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof HerbCrateBlockEntity crate)) {
            return InteractionResult.PASS;
        }
        return crate.open(player);
    }
}
