package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.BrewingRuntime;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在原版药水消费入口接入巫药效果和生命周期。 */
@Mixin(PotionContents.class)
public abstract class PotionContentsBrewMixin {
    @Inject(method = "onConsume", at = @At("HEAD"), cancellable = true)
    private void meridianBrewing$consume(Level level, LivingEntity entity, ItemStack stack,
                                         Consumable consumable, CallbackInfo callback) {
        if (BrewingRuntime.consume(level, entity, stack)) {
            callback.cancel();
        }
    }
}
