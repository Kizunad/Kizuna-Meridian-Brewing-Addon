package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.RecipeKnowledgeStore;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 将已读药方写入玩家 addon_progress 载荷并在读档时恢复。 */
@Mixin(Player.class)
public abstract class RecipeKnowledgePlayerMixin {
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void meridianBrewing$read(ValueInput input, CallbackInfo callback) {
        RecipeKnowledgeStore.load((Player) (Object) this, input);
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void meridianBrewing$write(ValueOutput output, CallbackInfo callback) {
        RecipeKnowledgeStore.save((Player) (Object) this, output);
    }
}
