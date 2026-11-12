package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.BrewTowers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 药塔火室里点着的熔岩已经在烧：桶舀（玩家、发射器都走这里）只把它弄灭，桶还是空的。 */
@Mixin(LiquidBlock.class)
public abstract class TowerLavaPickupMixin {
    @Inject(method = "pickupBlock", at = @At("HEAD"), cancellable = true)
    private void meridianBrewing$quenchBurningTowerLava(LivingEntity user, LevelAccessor level, BlockPos pos,
                                                         BlockState state, CallbackInfoReturnable<ItemStack> callback) {
        // 舀起来再倒回去会被当成新放的熔岩重新点满（复审第 1 条），所以点着的这格舀不出东西。
        if (level instanceof ServerLevel serverLevel && BrewTowers.litFuelAt(serverLevel, pos)) {
            serverLevel.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            callback.setReturnValue(ItemStack.EMPTY);
        }
    }
}
