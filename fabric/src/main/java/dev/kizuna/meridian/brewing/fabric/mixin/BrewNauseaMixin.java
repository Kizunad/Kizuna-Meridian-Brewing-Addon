package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.BrewNausea;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class BrewNauseaMixin {
    @Inject(method = "startUsingItem", at = @At("HEAD"), cancellable = true)
    private void meridianBrewing$startUse(InteractionHand hand, CallbackInfo callback) {
        // 可信代码直接启动使用物品时也遵守反胃，不依赖是否经过右键回调。
        LivingEntity entity = (LivingEntity) (Object) this;
        if (BrewNausea.blocks(entity, entity.getItemInHand(hand))) {
            callback.cancel();
        }
    }

    @Inject(method = "completeUsingItem", at = @At("HEAD"), cancellable = true)
    private void meridianBrewing$finishUse(CallbackInfo callback) {
        // 开始进食后才获得反胃时，在消费前再检查，避免跨 tick 绕过。
        LivingEntity entity = (LivingEntity) (Object) this;
        if (BrewNausea.blocks(entity, entity.getUseItem())) {
            entity.stopUsingItem();
            callback.cancel();
        }
    }
}
