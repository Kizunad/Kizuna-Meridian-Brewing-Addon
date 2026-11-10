package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.VesselKind;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 巫釜和铜釜的方块入口，所有状态保存在对应方块实体；碰撞与轮廓贴着釜身外框。 */
class BrewCauldronBlock extends Block implements EntityBlock {
    private final VesselKind vessel;
    private final VoxelShape shape;

    BrewCauldronBlock(VesselKind vessel, VoxelShape shape, BlockBehaviour.Properties properties) {
        super(properties);
        this.vessel = vessel;
        this.shape = shape;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BrewCauldronBlockEntity(pos, state, vessel);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return BrewCauldronBlockEntity.ticker(type);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof BrewCauldronBlockEntity cauldron)) {
            return InteractionResult.PASS;
        }
        return cauldron.use(player, hand);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof BrewCauldronBlockEntity cauldron)) {
            return InteractionResult.PASS;
        }
        return cauldron.use(player, InteractionHand.MAIN_HAND);
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                              BlockEntity blockEntity, ItemStack tool) {
        if (blockEntity instanceof BrewCauldronBlockEntity cauldron) {
            cauldron.dropStoredIngredients();
        }
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
    }
}
