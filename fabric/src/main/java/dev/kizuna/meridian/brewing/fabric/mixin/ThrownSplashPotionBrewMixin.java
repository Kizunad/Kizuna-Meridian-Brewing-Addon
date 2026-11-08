package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.BrewingRuntime;
import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在原版喷溅命中入口接入巫药范围效果和落地枯萎。 */
@Mixin(ThrownSplashPotion.class)
public abstract class ThrownSplashPotionBrewMixin {
    @Inject(method = "onHitAsPotion", at = @At("HEAD"), cancellable = true)
    private void meridianBrewing$splash(ServerLevel level, ItemStack stack, HitResult hit,
                                        CallbackInfo callback) {
        if (!BrewItemStacks.isBrew(stack)) {
            return;
        }
        ThrownSplashPotion potion = (ThrownSplashPotion) (Object) this;
        BrewingRuntime.splash(level, stack, potion.position(), potion.getOwner());
        callback.cancel();
    }
}
