package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.FireKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** 炉窗与自动炉钟向所在方块或多方块结构查询的世界状态。 */
interface BrewStation {
    ServerLevel level();
    BlockPos anchor();
    AABB bounds();
    FurnaceBatch batch();
    boolean available();
    void changed();
    int grade();
    FireKind fireKind();
    long fuelSeconds();

    default boolean continuousFire() {
        return false;
    }
}
