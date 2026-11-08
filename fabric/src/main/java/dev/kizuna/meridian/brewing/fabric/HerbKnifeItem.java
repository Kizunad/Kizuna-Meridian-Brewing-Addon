package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.PreparationRecipe;
import dev.kizuna.meridian.brewing.server.PreparationRecipes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** 主手药刀切副手毒马铃薯，交互位置不受方块类型限制。 */
final class HerbKnifeItem extends Item {
    private static final long DAY_TICKS = 24_000L;
    private static final String INPUT_ITEM_ID = "minecraft:poisonous_potato";

    HerbKnifeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack knife = player.getItemInHand(hand);
        ItemStack ingredient = player.getItemInHand(otherHand(hand));
        if (!knife.is(this) || !ingredient.is(Items.POISONOUS_POTATO)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        PreparationRecipe recipe = PreparationRecipes.require(INPUT_ITEM_ID);
        ItemStack output = preparedStack(recipe, level);
        if (!player.hasInfiniteMaterials()) {
            ingredient.shrink(1);
            knife.hurtAndBreak(1, player, hand);
        }
        giveOrDrop(player, output);
        return InteractionResult.SUCCESS;
    }

    private static ItemStack preparedStack(PreparationRecipe recipe, Level level) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(recipe.outputItemId()));
        ItemStack output = new ItemStack(item);
        output.set(BrewingComponents.PREPARED_DAY, currentGameDay(level));
        return output;
    }

    private static InteractionHand otherHand(InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
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
