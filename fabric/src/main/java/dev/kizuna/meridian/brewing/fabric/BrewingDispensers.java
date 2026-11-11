package dev.kizuna.meridian.brewing.fabric;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.core.dispenser.OptionalDispenseItemBehavior;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DispenserBlock;

/**
 * 发射器对着釜：水桶、水瓶注水，玻璃瓶舀药。换回的空桶、玻璃瓶、成药或药渣照原版发射器对蜂巢的做法放回发射器，
 * 放不下才吐出来。釜不收时发射器咔哒一声，东西不动。前面不是釜时照旧走原版的行为。
 */
final class BrewingDispensers {
    private static final List<Item> ITEMS = List.of(Items.WATER_BUCKET, Items.POTION, Items.GLASS_BOTTLE);

    private BrewingDispensers() {
    }

    /** 原版发射器行为登记完以后调用。 */
    static void register() {
        IntoCauldron intoCauldron = new IntoCauldron();
        for (Item item : ITEMS) {
            DispenseItemBehavior vanilla = DispenserBlock.DISPENSER_REGISTRY.getOrDefault(
                    item, new DefaultDispenseItemBehavior());
            DispenserBlock.registerBehavior(item, (source, stack) -> {
                if (forCauldron(stack) && cauldronInFront(source).isPresent()) {
                    return intoCauldron.dispense(source, stack);
                }
                return vanilla.dispense(source, stack);
            });
        }
    }

    /** 药水里只有水瓶归釜管，别的药水照旧被发射器吐出去。 */
    private static boolean forCauldron(ItemStack stack) {
        return !stack.is(Items.POTION) || BrewCauldronBlockEntity.isWaterBottle(stack);
    }

    private static Optional<BrewCauldronBlockEntity> cauldronInFront(BlockSource source) {
        BlockPos front = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
        if (source.level().getBlockEntity(front) instanceof BrewCauldronBlockEntity cauldron) {
            return Optional.of(cauldron);
        }
        return Optional.empty();
    }

    private static final class IntoCauldron extends OptionalDispenseItemBehavior {
        @Override
        protected ItemStack execute(BlockSource source, ItemStack stack) {
            Optional<ItemStack> remainder = cauldronInFront(source).orElseThrow().dispense(stack);
            setSuccess(remainder.isPresent());
            if (remainder.isEmpty()) {
                return stack;
            }
            return consumeWithRemainder(source, stack, remainder.orElseThrow());
        }
    }
}
