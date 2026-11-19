package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewFireState;
import dev.kizuna.meridian.brewing.server.FireKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.phys.AABB;

/** 单口釜从正下方读取实际火档和余燃；原版篝火没有燃料倒计时。 */
record PortableStation(ServerLevel level, BrewCauldronBlockEntity pot) implements BrewStation {
    public BlockPos anchor() {
        return pot.getBlockPos();
    }

    public AABB bounds() {
        return new AABB(anchor());
    }

    public FurnaceBatch batch() {
        return pot.batch();
    }

    public boolean available() {
        var pos = anchor();
        if (!level.hasChunkAt(pos) || pot.isRemoved() || level.getBlockEntity(pos) != pot) {
            return false;
        }
        var below = pos.below();
        return level.getBlockState(below).is(BlockTags.CAMPFIRES)
                || level.getBlockEntity(below) instanceof BrewFireBlockEntity;
    }

    public void changed() {
        pot.setChanged();
    }

    private BrewFireState fire() {
        if (level.getBlockEntity(anchor().below()) instanceof BrewFireBlockEntity source) {
            return source.fire();
        }
        return null;
    }

    public int grade() {
        var fire = fire();
        if (fire != null) {
            return fire.level();
        }
        return CampfireBlock.isLitCampfire(level.getBlockState(anchor().below())) ? BrewFireState.MAX_LEVEL : 0;
    }

    public FireKind fireKind() {
        var fire = fire();
        if (fire != null) {
            return fire.fireKind();
        }
        return level.getBlockState(anchor().below()).is(Blocks.SOUL_CAMPFIRE) ? FireKind.SOUL_FIRE : FireKind.SUN_FIRE;
    }

    public long fuelSeconds() {
        var fire = fire();
        if (fire != null) {
            return (long) Math.ceil(fire.fuelSeconds());
        }
        return 0;
    }

    public boolean continuousFire() {
        return CampfireBlock.isLitCampfire(level.getBlockState(anchor().below()));
    }
}
