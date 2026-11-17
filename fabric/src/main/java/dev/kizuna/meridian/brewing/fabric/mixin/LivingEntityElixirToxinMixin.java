package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.BrewElixirToxinHooks;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityElixirToxinMixin {
    @Shadow
    protected abstract double getEffectiveGravity();

    @ModifyVariable(method = "setSprinting", at = @At("HEAD"), argsOnly = true)
    private boolean meridianBrewing$toxinSprint(boolean sprinting) {
        // 土毒到指定等级时禁止开始疾跑。
        return sprinting && !BrewElixirToxinHooks.blocksSprint((LivingEntity) (Object) this);
    }

    @Inject(method = "getJumpPower(F)F", at = @At("RETURN"), cancellable = true)
    private void meridianBrewing$toxinJump(float scale, CallbackInfoReturnable<Float> callback) {
        // 在原版和已有能力算完起跳速度后，仅缩短土毒要求的跳跃高度。
        float original = callback.getReturnValue();
        callback.setReturnValue(BrewElixirToxinHooks.jumpPower((LivingEntity) (Object) this,
                original, getEffectiveGravity()));
    }
}
