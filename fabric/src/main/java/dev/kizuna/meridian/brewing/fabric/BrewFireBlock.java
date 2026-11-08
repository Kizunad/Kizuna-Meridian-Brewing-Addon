package dev.kizuna.meridian.brewing.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
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

/** 铁火盆和铁炉座的可调火源方块。 */
final class BrewFireBlock extends Block implements EntityBlock {
    private final dev.kizuna.meridian.brewing.server.BrewFireSource source;

    BrewFireBlock(dev.kizuna.meridian.brewing.server.BrewFireSource source,
                  BlockBehaviour.Properties properties) {
        super(properties);
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
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof BrewFireBlockEntity fire)) {
            return InteractionResult.PASS;
        }
        return fire.use(player, hand);
    }
}
