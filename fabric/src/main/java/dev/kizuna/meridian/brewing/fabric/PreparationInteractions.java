package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.PreparationRecipe;
import dev.kizuna.meridian.brewing.server.PreparationMethod;
import dev.kizuna.meridian.brewing.server.PreparationRecipes;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

/** 砂轮研料的服务端方块交互。 */
final class PreparationInteractions {
    private static final long DAY_TICKS = 24_000L;

    private PreparationInteractions() {
        // 交互只通过 Fabric 事件注册，不保存方块状态。
    }

    static void register() {
        UseBlockCallback.EVENT.register(PreparationInteractions::useBlock);
    }

    private static InteractionResult useBlock(Player player, Level level, InteractionHand hand,
                                              BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.PASS;
        }
        if (!level.getBlockState(hit.getBlockPos()).is(Blocks.GRINDSTONE)) {
            return InteractionResult.PASS;
        }
        return grindIngredient(player, hand);
    }

    private static InteractionResult grindIngredient(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        PreparationRecipe recipe = recipeFor(stack);
        if (recipe == null || recipe.method() != PreparationMethod.GRINDSTONE) {
            return InteractionResult.PASS;
        }
        ItemStack output = new ItemStack(item(recipe.outputItemId()));
        output.set(BrewingComponents.PREPARED_DAY, currentGameDay(player.level()));
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        giveOrDrop(player, output);
        return InteractionResult.SUCCESS;
    }

    private static PreparationRecipe recipeFor(ItemStack stack) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return PreparationRecipes.all().get(itemId);
    }

    private static Item item(String itemId) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
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
