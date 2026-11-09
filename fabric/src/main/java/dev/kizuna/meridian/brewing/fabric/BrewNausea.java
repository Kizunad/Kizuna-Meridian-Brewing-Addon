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
            if (blocks(player, player.getItemInHand(hand))) {
                player.sendOverlayMessage(Component.translatable("message.meridian_brewing.nausea"));
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }

    public static boolean blocks(LivingEntity entity, ItemStack stack) {
        // 本入口只处理玩家；生物的自主喝药由其身体宿主执行。
        if (!(entity instanceof Player) || !(entity.level() instanceof ServerLevel)
                || !entity.hasEffect(BrewingEffects.BITTER_YELLOW)) {
            return false;
        }
        var consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable == null || consumable.animation() != ItemUseAnimation.EAT
                && consumable.animation() != ItemUseAnimation.DRINK) {
            return false;
        }
        var binding = BrewBodyHost.inspect(entity).orElseThrow();
        var body = binding.body();
        return BrewBodyProgress.read(body).getNauseaUntilMillis() > body.getSettledAt().getElapsedMillis();
    }
}
