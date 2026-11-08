package dev.kizuna.meridian.brewing.fabric;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 炮制成品物品；首次进入实体或物品栏时记录制作游戏日。 */
final class PreparedIngredientItem extends Item {
    private static final long DAY_TICKS = 24_000L;

    PreparedIngredientItem(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
        if (stack.get(BrewingComponents.PREPARED_DAY) == null) {
            long gameDay = Math.floorDiv(level.getOverworldClockTime(), DAY_TICKS);
            stack.set(BrewingComponents.PREPARED_DAY, gameDay);
        }
    }
}
