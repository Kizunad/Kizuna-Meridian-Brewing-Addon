package dev.kizuna.meridian.brewing.fabric.mixin;

import java.util.List;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读熬药结构 NBT 的调色板；只读，不改结构。方法名带本仓前缀，和别的模组的同类 accessor 不撞名。 */
@Mixin(StructureTemplate.class)
public interface StructureTemplateAccessor {
    @Accessor("palettes")
    List<StructureTemplate.Palette> meridianBrewing$palettes();
}
