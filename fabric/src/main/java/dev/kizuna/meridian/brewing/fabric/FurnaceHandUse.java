package dev.kizuna.meridian.brewing.fabric;

import net.minecraft.world.InteractionHand;

/** 炉口处理了方块点击，通知这名玩家的连接跳过同次点击的手持物后备动作。 */
public interface FurnaceHandUse {
    void meridianBrewing$claimHand(InteractionHand hand);
}
