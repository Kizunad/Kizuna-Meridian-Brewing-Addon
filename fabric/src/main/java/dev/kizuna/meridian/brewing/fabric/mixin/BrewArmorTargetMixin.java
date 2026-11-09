package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.BrewArmorTarget;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class BrewArmorTargetMixin implements BrewArmorTarget {
    @Unique private String meridianBrewing$armorSlot = "";

    @Override
    public String meridianBrewing$armorSlot() {
        // 无身体物种的命中槽不依赖后来换装或实体朝向。
        return meridianBrewing$armorSlot;
    }

    @Override
    public void meridianBrewing$armorSlot(String slot) {
        // 新瓶刷新命中槽，身体实体使用自身进度而不写此字段。
        meridianBrewing$armorSlot = slot;
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void meridianBrewing$saveArmor(ValueOutput output, CallbackInfo callback) {
        // 和原版状态效果同次保存，防止卸载后蚀到未命中的装备。
        output.putString("meridian_brewing_armor_slot", meridianBrewing$armorSlot);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void meridianBrewing$loadArmor(ValueInput input, CallbackInfo callback) {
        // 没有药效历史的实体保持空槽，不推测四件盔甲都被命中。
        meridianBrewing$armorSlot = input.getStringOr("meridian_brewing_armor_slot", "");
    }

}
