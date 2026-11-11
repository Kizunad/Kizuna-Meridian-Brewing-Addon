package dev.kizuna.meridian.brewing.fabric.mixin;

import dev.kizuna.meridian.brewing.fabric.BrewWitchHut;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.ScatteredFeaturePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutPiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SwampHutPiece.class)
public abstract class WitchHutChestMixin extends ScatteredFeaturePiece {
    private WitchHutChestMixin(StructurePieceType type, CompoundTag tag) {
        // mixin 不会调用这个构造器，只为满足父类的构造约束。
        super(type, tag);
    }

    @Inject(method = "postProcess", at = @At("TAIL"))
    private void meridianBrewing$placeRecipeChest(WorldGenLevel level, StructureManager structures,
                                                  ChunkGenerator generator, RandomSource random, BoundingBox chunkBox,
                                                  ChunkPos chunk, BlockPos pivot, CallbackInfo callback) {
        // 原版搭完这块区块里的小屋以后补箱子。原版 createChest 只放在这块区块的范围里，那一格已经是箱子就不再放；
        // 找不到地面、没搭小屋的那次提前返回，不会走到这里。
        BlockPos pos = getWorldPos(BrewWitchHut.CHEST_X, BrewWitchHut.CHEST_Y, BrewWitchHut.CHEST_Z).immutable();
        createChest(level, chunkBox, random, pos, BrewWitchHut.CHEST_TABLE, BrewWitchHut.chest(getOrientation()));
    }
}
