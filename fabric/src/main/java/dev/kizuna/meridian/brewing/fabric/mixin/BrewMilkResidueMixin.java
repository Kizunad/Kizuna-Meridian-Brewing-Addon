package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.BrewResidues;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.consume_effects.ClearAllStatusEffectsConsumeEffect;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClearAllStatusEffectsConsumeEffect.class)
public abstract class BrewMilkResidueMixin {
    @Inject(method = "apply", at = @At("TAIL"))
    private void meridianBrewing$clear(Level level, ItemStack stack, LivingEntity entity,
                                       CallbackInfoReturnable<Boolean> callback) {
        // 牛奶的清状态消费效果也清残药，即使可见药效此前已经自然结束。
        if (!level.isClientSide()) {
            BrewResidues.clear(entity.getUUID());
        }
    }
}
