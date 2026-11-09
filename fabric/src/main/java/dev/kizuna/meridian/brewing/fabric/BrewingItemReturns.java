package dev.kizuna.meridian.brewing.fabric;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 方块交互和功法消费共用的容器返还规则。 */
final class BrewingItemReturns {
    private BrewingItemReturns() {
        // 工具不保存玩家物品状态。
    }

    static void consumeWaterBucket(Player player, InteractionHand hand, ItemStack stack) {
        // 先识别容器再消费；最后一桶原位换为空桶，堆叠余量仍留在原手。
        if (player.hasInfiniteMaterials()) {
            return;
        }
        stack.shrink(1);
        ItemStack bucket = new ItemStack(Items.BUCKET);
        if (stack.isEmpty()) {
            player.setItemInHand(hand, bucket);
        } else {
            giveOrDrop(player, bucket);
        }
    }

    static void giveOrDrop(Player player, ItemStack stack) {
        // 背包满时把容器或产物掉在玩家脚边，避免静默丢失。
        if (!player.addItem(stack)) {
            player.drop(stack, false /* throwRandomly */);
        }
    }
}
