package dev.kizuna.meridian.brewing.fabric;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 炮制成品物品；首次进入实体或物品栏时记录制作游戏日。 */
public final class PreparedIngredientItem extends Item {
    private static final long DAY_TICKS = 24_000L;

    PreparedIngredientItem(Properties properties) {
        super(properties);
    }

    /** 今天是第几个游戏日；两端都按主世界的时钟算，物品提示和熬药用同一个数。 */
    public static long gameDay(Level level) {
        return Math.floorDiv(level.getOverworldClockTime(), DAY_TICKS);
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
        if (stack.get(BrewingComponents.PREPARED_DAY) == null) {
            stack.set(BrewingComponents.PREPARED_DAY, gameDay(level));
        }
    }
}
