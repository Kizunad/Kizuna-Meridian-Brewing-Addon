package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import java.util.EnumSet;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 药方羊皮纸：服务端把药方记进读者的身体，再让读者的客户端翻开这张药方的讲解。 */
final class RecipeScrollItem extends Item {
    RecipeScrollItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // 羊皮纸不消耗；读不出药方、身体暂停都只提示，不学。认得出药方就翻开讲解，学没学会都能读。
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer reader) {
            BrewTexts.sendOverlay(player, read(reader, stack));
            kind(stack).ifPresent(kind -> BrewLore.open(reader, kind.path()));
        }
        return InteractionResult.SUCCESS;
    }

    static BrewText read(ServerPlayer reader, ItemStack stack) {
        // 先认药方，再读身体；已经学过的不再写身体。
        Optional<BrewKind> kind = kind(stack);
        if (kind.isEmpty()) {
            return BrewText.message("recipe.illegible");
        }
        BrewText name = BrewCauldron.recipeName(kind.orElseThrow());
        BrewRecipeBook.Lookup current = BrewRecipeBook.lookup(reader);
        if (current.presence() == BrewBodyHost.Presence.PAUSED) {
            return BrewText.message("recipe.body_paused");
        }
        if (current.presence() == BrewBodyHost.Presence.UNBOUND) {
            return BrewText.message("recipe.no_body");
        }
        if (current.knows(kind.orElseThrow())) {
            return BrewText.message("recipe.already_known", name);
        }
        EnumSet<BrewKind> known = current.known().isEmpty()
                ? EnumSet.noneOf(BrewKind.class) : EnumSet.copyOf(current.known());
        known.add(kind.orElseThrow());
        BrewRecipeBook.replace(reader, known);
        return BrewText.message("recipe.learned", name);
    }

    /** 记着某个药方的一张羊皮纸，和女巫掉落、小屋箱子里的是同一份物品数据。 */
    static ItemStack stack(BrewKind kind) {
        // 物品默认组件是枯竭药方，这里按药方覆盖。
        ItemStack stack = new ItemStack(BrewingItems.item("brew_recipe"));
        stack.set(BrewingComponents.RECIPE_KIND, kind.path());
        return stack;
    }

    static Optional<BrewKind> kind(ItemStack stack) {
        // 没有药方组件或组件认不出的羊皮纸算字迹模糊，不再默认当成枯竭药方。
        String path = stack.get(BrewingComponents.RECIPE_KIND);
        if (path == null) {
            return Optional.empty();
        }
        return RecipeKnowledge.kind(path);
    }
}
