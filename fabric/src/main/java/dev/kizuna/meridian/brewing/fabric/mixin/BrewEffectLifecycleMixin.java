package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.BrewEffectLifecycle;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

@Mixin(LivingEntity.class)
public abstract class BrewEffectLifecycleMixin {
    @Inject(method = "onEffectsRemoved", at = @At("TAIL"))
    private void meridianBrewing$removed(Collection<MobEffectInstance> removed, CallbackInfo callback) {
        // 原版已移除显示状态，随后撤销权威身体中属于这些药的持续修正。
        BrewEffectLifecycle.removed((LivingEntity) (Object) this, removed);
    }

}
