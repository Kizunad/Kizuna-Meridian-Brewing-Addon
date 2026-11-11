package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewText;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 药方羊皮纸的最小原版书本使用路径。 */
final class RecipeScrollItem extends Item {
    RecipeScrollItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide()) {
            BrewKind kind = kind(stack);
            RecipeKnowledgeStore.learn(player, kind);
            BrewTexts.sendOverlay(player, BrewText.message("recipe.learned", BrewCauldron.recipeName(kind)));
        }
        player.openItemGui(stack, hand);
        return InteractionResult.SUCCESS;
    }

    private static BrewKind kind(ItemStack stack) {
        String path = stack.get(BrewingComponents.RECIPE_KIND);
        if (path == null) {
            return BrewKind.DEPLETION;
        }
        for (BrewKind kind : BrewKind.values()) {
            if (kind.path().equals(path)) {
                return kind;
            }
        }
        return BrewKind.DEPLETION;
    }
}
