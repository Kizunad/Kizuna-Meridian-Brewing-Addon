package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 阻止原版酿造台接受带巫药组件的药水。 */
@Mixin(BrewingStandBlockEntity.class)
public abstract class BrewingStandBrewMixin {
    @Inject(method = "canPlaceItem", at = @At("HEAD"), cancellable = true)
    private void meridianBrewing$rejectBrew(int slot, ItemStack stack,
                                            CallbackInfoReturnable<Boolean> callback) {
        if (BrewItemStacks.isBrew(stack)) {
            callback.setReturnValue(false /* rejectBrew */);
        }
    }
}
