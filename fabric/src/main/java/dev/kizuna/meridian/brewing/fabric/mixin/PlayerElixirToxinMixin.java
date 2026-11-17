package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.BrewElixirToxinHooks;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Player.class)
public abstract class PlayerElixirToxinMixin {
    @ModifyVariable(method = "causeFoodExhaustion", at = @At("HEAD"), argsOnly = true)
    private float meridianBrewing$toxinExhaustion(float amount) {
        // 火行丹毒在身时，每一笔饥饿消耗按丹毒的级数放大。
        return amount * BrewElixirToxinHooks.exhaustionMultiplier((Player) (Object) this);
    }
}
