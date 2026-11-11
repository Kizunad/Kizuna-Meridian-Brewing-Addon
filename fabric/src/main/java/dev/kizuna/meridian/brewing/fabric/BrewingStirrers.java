package dev.kizuna.meridian.brewing.fabric;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * 主手的搅棍不和釜、火源交互。
 *
 * <p>熬药式要主手一直拿着搅棍，辅料、引药和调火都由副手对着釜或火源右键。原版右键先试主手，主手的交互算成功就不再试副手；
 * 这里让主手搅棍返回 PASS，原版才会接着用副手。</p>
 */
final class BrewingStirrers {
    private BrewingStirrers() {
    }

    static boolean yieldsToOffHand(ItemStack stack, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND && stack.is(BrewingTags.STIRRERS);
    }
}
