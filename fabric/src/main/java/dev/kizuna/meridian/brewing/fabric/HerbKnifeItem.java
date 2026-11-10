package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.PreparationRecipe;
import dev.kizuna.meridian.brewing.server.PreparationRecipes;
import dev.kizuna.playeranimation.animation.PlayerAnimationAction;
import dev.kizuna.playeranimation.server.ServerPlayerAnimations;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * 主手药刀切副手毒马铃薯：按住使用一刀 1 秒，刀穿过药材的第 11 tick 出片，交互位置不受方块类型限制。
 *
 * <p>切药动作由 PA 播放，剪辑时长与使用时长相同；按住使用键时原版在用满后的下一 tick 再次开始使用，一刀接一刀。
 */
final class HerbKnifeItem extends Item {
    private static final PlayerAnimationAction CUT = PlayerAnimationAction.HERB_KNIFE_CUT;
    // 第 0.55 s 刃口压过药材上沿（PA herb_knife_cut 规格），开始使用后的第 11 次使用 tick。
    private static final int SETTLE_TICK = 11;
    private static final long DAY_TICKS = 24_000L;
    private static final String INPUT_ITEM_ID = "minecraft:poisonous_potato";

    HerbKnifeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // 只认主手持刀；副手拿刀右键交回原版。
        if (hand != InteractionHand.MAIN_HAND || !canCut(player)) {
            return InteractionResult.PASS;
        }
        player.startUsingItem(hand);
        if (player instanceof ServerPlayer server) {
            ServerPlayerAnimations.play(server, CUT);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return CUT.naturalDurationTicks();
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        // 手臂全由 PA 切药剪辑画，不叠原版使用姿态。
        return ItemUseAnimation.NONE;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack knife, int remainingUseDuration) {
        // 开始使用后的下一 tick 第一次调用，那时剩余为整段时长。
        int usedTicks = getUseDuration(knife, entity) - remainingUseDuration + 1;
        if (level.isClientSide() || usedTicks != SETTLE_TICK || !(entity instanceof Player player)) {
            return;
        }
        // 这一刀之间换走了副手药材就不出片，动作照常走完。
        if (canCut(player)) {
            cut(level, player);
        }
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remainingUseDuration) {
        // 中途松手收回动作；第 11 tick 前松手不出片。
        if (entity instanceof ServerPlayer server) {
            ServerPlayerAnimations.stop(server, CUT);
        }
        return false;
    }

    private boolean canCut(Player player) {
        return player.getMainHandItem().is(this) && player.getOffhandItem().is(Items.POISONOUS_POTATO);
    }

    private static void cut(Level level, Player player) {
        PreparationRecipe recipe = PreparationRecipes.require(INPUT_ITEM_ID);
        ItemStack output = preparedStack(recipe, level);
        if (!player.hasInfiniteMaterials()) {
            player.getOffhandItem().shrink(1);
            player.getMainHandItem().hurtAndBreak(1, player, InteractionHand.MAIN_HAND);
        }
        BrewSounds.knifeCut(player);
        giveOrDrop(player, output);
    }

    private static ItemStack preparedStack(PreparationRecipe recipe, Level level) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(recipe.outputItemId()));
        ItemStack output = new ItemStack(item);
        output.set(BrewingComponents.PREPARED_DAY, currentGameDay(level));
        return output;
    }

    private static long currentGameDay(Level level) {
        return Math.floorDiv(level.getOverworldClockTime(), DAY_TICKS);
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.addItem(stack)) {
            player.drop(stack, false /* throwRandomly */);
        }
    }
}
