package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;

/** 苦黄膏反胃期间拦截吃喝，读身体进度而不是把所有原版反胃都当作药封。 */
public final class BrewNausea {
    private BrewNausea() {
        // 不记录额外倒计时，使用身体里保存的药效期限。
    }

    static void initialize() {
        // 在物品开始使用前拒绝，食物、药水和牛奶都不消耗。
        UseItemCallback.EVENT.register((player, level, hand) -> {
            Refusal refusal = refusal(player, player.getItemInHand(hand));
            if (refusal == Refusal.NONE) {
                return InteractionResult.PASS;
            }
            player.sendOverlayMessage(Component.translatable(refusal.message()));
            return InteractionResult.FAIL;
        });
    }

    public static boolean blocks(LivingEntity entity, ItemStack stack) {
        // 玩家使用回调与生物自主喝药共享同一身体期限。
        return refusal(entity, stack) != Refusal.NONE;
    }

    private static Refusal refusal(LivingEntity entity, ItemStack stack) {
        // 只有吃喝才查身体。身体被宿主暂停时，自己喝巫药和带着苦黄膏吃喝都按失败拒绝：
        // 巫药生效不了，反胃期限也读不到，不能放行。
        if (!(entity.level() instanceof ServerLevel)) {
            return Refusal.NONE;
        }
        var consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable == null || consumable.animation() != ItemUseAnimation.EAT
                && consumable.animation() != ItemUseAnimation.DRINK) {
            return Refusal.NONE;
        }
        boolean bitter = entity.hasEffect(BrewingEffects.BITTER_YELLOW);
        if (!bitter && !BrewItemStacks.isBrew(stack)) {
            return Refusal.NONE;
        }
        var observed = BrewBodyHost.observe(entity);
        if (observed.presence() == BrewBodyHost.Presence.PAUSED) {
            return Refusal.BODY_PAUSED;
        }
        if (!bitter) {
            return Refusal.NONE;
        }
        var body = observed.found().orElseThrow().body();
        if (BrewBodyProgress.read(body).getNauseaUntilMillis() > body.getSettledAt().getElapsedMillis()) {
            return Refusal.NAUSEA;
        }
        return Refusal.NONE;
    }

    /** 拒绝吃喝的原因，玩家开始使用时提示对应文案。 */
    private enum Refusal {
        NONE(""),
        NAUSEA("message.meridian_brewing.nausea"),
        BODY_PAUSED("message.meridian_brewing.body_paused");

        private final String message;

        Refusal(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }
}
