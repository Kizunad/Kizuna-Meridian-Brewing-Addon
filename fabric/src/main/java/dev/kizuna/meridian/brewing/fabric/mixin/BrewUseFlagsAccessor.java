package dev.kizuna.meridian.brewing.fabric.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 只给点击者重发原版用物状态，保留同一字节里的其他标记。 */
@Mixin(LivingEntity.class)
public interface BrewUseFlagsAccessor {
    @Accessor("DATA_LIVING_ENTITY_FLAGS")
    static EntityDataAccessor<Byte> meridianBrewing$useFlags() {
        throw new AssertionError();
    }
}
