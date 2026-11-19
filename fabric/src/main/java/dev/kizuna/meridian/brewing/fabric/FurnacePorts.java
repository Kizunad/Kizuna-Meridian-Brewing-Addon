package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.fabric.FurnacePatterns.Cell;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewFireState;
import dev.kizuna.meridian.brewing.server.BrewStructure;
import dev.kizuna.meridian.brewing.server.FireKind;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.phys.AABB;

/** 锅沿和炉底只在完整、已加载的炉上交接物品。手持和落地堆叠走同一笔交易。 */
record FurnacePorts(ServerLevel level, FurnaceLayout layout, FurnaceBatch batch) implements BrewStation {
    private static final int BUCKET_WATER = 3;
    private static final long DAY_TICKS = 24_000L;
    private static final int HIGH_FIRE = 4;
    private enum Port { KETTLE, FUEL, NONE }

    private record Exchange(int count, ItemStack output) {
        static final Exchange REFUSED = new Exchange(0, ItemStack.EMPTY);
    }

    static FurnacePorts of(ServerLevel level, FurnaceLayout layout) {
        return new FurnacePorts(level, layout, FurnaceRegister.of(level).batch(layout));
    }

    InteractionResult use(ServerPlayer player, InteractionHand hand, BlockPos clicked) {
        var stack = player.getItemInHand(hand);
        Port port = handPort(clicked);
        // 直接点营火时锹仍走原版灭火；点外围底座则拨低整个炉的火档。
        if (port == Port.FUEL && stack.is(ItemTags.SHOVELS) && !level.getBlockState(clicked).is(
                BlockTags.CAMPFIRES)) {
            if (batch.fireLimit > 0) {
                batch.fireLimit--;
                if (!player.hasInfiniteMaterials()) {
                    stack.hurtAndBreak(1, player, hand);
                }
                FurnaceRegister.of(level).setDirty();
            }
            return InteractionResult.SUCCESS;
        }
        if (port == Port.FUEL && (stack.is(Items.SAND) || stack.is(Items.SOUL_SAND) || stack.is(Items.SOUL_SOIL))) {
            FireKind fire = stack.is(Items.SAND) ? FireKind.SUN_FIRE : FireKind.SOUL_FIRE;
            if (batch.fire != fire) {
                batch.fire = fire;
                if (!player.hasInfiniteMaterials()) {
                    stack.shrink(1);
                }
                FurnaceRegister.of(level).setDirty();
            }
            return InteractionResult.SUCCESS;
        }
        if (port == Port.NONE || !brewingItem(stack)) {
            return InteractionResult.PASS;
        }
        ItemStack offered = stack;
        if (port == Port.FUEL) {
            offered = stack.copyWithCount(1);
        }
        Exchange trade = exchange(port, offered, clicked);
        if (trade.count() == 0) {
            player.sendOverlayMessage(Component.translatable("message.meridian_brewing.furnace.refused"));
            return InteractionResult.FAIL;
        }
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(trade.count());
        }
        if (!trade.output().isEmpty() && (!player.hasInfiniteMaterials() || offered.is(Items.GLASS_BOTTLE))) {
            BrewingItemReturns.giveOrDrop(player, trade.output());
        }
        player.sendOverlayMessage(describe(player));
        return InteractionResult.SUCCESS;
    }

    Component describe(ServerPlayer player) {
        var pot = batch.cauldron;
        String stage = pot.state().name().toLowerCase(Locale.ROOT);
        var known = BrewRecipeBook.lookup(player);
        if (pot.recipe().isPresent() && !known.knows(pot.recipe().orElseThrow().kind())) {
            return BrewTexts.component(BrewText.message("state." + stage));
        }
        BrewText recipe = pot.recipe().map(value -> BrewCauldron.recipeName(value.kind()))
                .orElseGet(() -> BrewText.message("cauldron.no_recipe"));
        return Component.translatable("message.meridian_brewing.furnace.stock",
                BrewTexts.component(recipe), BrewTexts.component(BrewText.message("state." + stage)),
                pot.waterUnits(), pot.remainingBottles(), batch.fuelSeconds());
    }

    private static boolean brewingItem(ItemStack stack) {
        // 认得的药材即使投早、投错口也应告知玩家；其余物品交回原版交互。
        if (stack.is(Items.WATER_BUCKET) || stack.is(Items.GLASS_BOTTLE)
                || BrewCauldronBlockEntity.isWaterBottle(stack) || BrewFireBlockEntity.fuelUnits(stack) > 0
                || stack.is(Items.GLOWSTONE_DUST) || stack.is(Items.GUNPOWDER)) {
            return true;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        for (var recipe : BrewRecipes.all().values()) {
            var ingredients = recipe.ingredients();
            if (BrewRecipes.sameItemId(ingredients.preparedPrimary(), id)
                    || BrewRecipes.sameItemId(ingredients.accessory(), id)
                    || BrewRecipes.sameItemId(ingredients.catalyst(), id)) {
                return true;
            }
        }
        return false;
    }

    private Port handPort(BlockPos pos) {
        int fireY = layout.pattern().fires().getFirst().offset().getY();
        for (Cell cell : layout.pattern().cells()) {
            if (!layout.world(cell).equals(pos)) {
                continue;
            }
            return switch (cell.role()) {
                case RIM -> Port.KETTLE;
                case FIRES -> Port.FUEL;
                case BODY -> cell.offset().getY() <= fireY ? Port.FUEL : Port.NONE;
                default -> Port.NONE;
            };
        }
        return Port.NONE;
    }

    private Exchange exchange(Port port, ItemStack offered, BlockPos pos) {
        Exchange result;
        if (port == Port.FUEL) {
            result = new Exchange(batch.addFuel(offered), ItemStack.EMPTY);
        } else {
            result = atKettle(offered, pos);
        }
        if (result.count() > 0) {
            FurnaceRegister.of(level).setDirty();
        }
        return result;
    }

    private Exchange atKettle(ItemStack stack, BlockPos pos) {
        var pot = batch.cauldron;
        if (stack.is(Items.GLASS_BOTTLE)) {
            BrewBottle bottle = BrewBottle.collect(pot, level, pos);
            if (bottle.result().accepted()) {
                if (pot.state() == BrewCauldronState.EMPTY) {
                    batch.active = null;
                    batch.tally = null;
                    batch.refundable.clear();
                    batch.version++;
                }
                return new Exchange(1, bottle.output());
            }
            return Exchange.REFUSED;
        }
        if (stack.is(Items.WATER_BUCKET) || BrewCauldronBlockEntity.isWaterBottle(stack)) {
            boolean bucket = stack.is(Items.WATER_BUCKET);
            if (bucket && pot.state() == BrewCauldronState.SCORCHED) {
                pot.scrubWithWater();
                batch.active = null;
                batch.tally = null;
                batch.version++;
                return new Exchange(1, new ItemStack(Items.BUCKET));
            }
            int units = bucket ? BUCKET_WATER : 1;
            if (pot.addWater(units).accepted()) {
                return new Exchange(1, new ItemStack(bucket ? Items.BUCKET : Items.GLASS_BOTTLE));
            }
            return Exchange.REFUSED;
        }
        long day = Math.floorDiv(level.getOverworldClockTime(), DAY_TICKS);
        int accepted = batch.addIngredient(stack, day);
        var container = stack.getItem().getCraftingRemainder();
        if (accepted > 0 && container != null) {
            return new Exchange(accepted, container.create().copyWithCount(accepted));
        }
        return new Exchange(accepted, ItemStack.EMPTY);
    }

    public BlockPos anchor() {
        return layout.anchor();
    }

    public boolean available() {
        return layout.check(level).status() == FurnaceLayout.Status.FORMED;
    }

    public void changed() {
        FurnaceRegister.of(level).setDirty();
    }

    public FireKind fireKind() {
        return batch.fire;
    }

    public long fuelSeconds() {
        return batch.fuelSeconds();
    }

    public int grade() {
        if (batch.fuelSeconds() == 0) {
            return 0;
        }
        int lit = 0;
        for (Cell fire : layout.pattern().fires()) {
            if (CampfireBlock.isLitCampfire(level.getBlockState(layout.world(fire)))) {
                lit++;
            }
        }
        int available = lit <= 1 ? lit : BrewFireState.MAX_LEVEL;
        return Math.min(available, batch.fireLimit);
    }

    void tickSecond() {
        int grade = grade();
        var heat = BrewStructure.built(BrewFireState.stoveHeatSource(batch.fire, grade));
        int rate = grade <= 1 ? grade : HIGH_FIRE;
        boolean burnt = batch.burn(rate);
        var state = batch.cauldron.state();
        if ((batch.active != null || batch.next != null) && state != BrewCauldronState.BREWING
                && state != BrewCauldronState.EMPTY && state != BrewCauldronState.SCORCHED) {
            batch.cauldron.tick(1, heat);
        }
        FurnaceCooking.advance(this, heat);
        if (burnt || batch.next != null && batch.cauldron.state() != BrewCauldronState.EMPTY) {
            FurnaceRegister.of(level).setDirty();
        }
        receiveDrops();
    }

    public AABB bounds() {
        var bounds = layout.pattern().bounds();
        BlockPos low = layout.world(bounds.min());
        BlockPos high = layout.world(bounds.max());
        return AABB.encapsulatingFullBlocks(BlockPos.min(low, high), BlockPos.max(low, high));
    }

    private void receiveDrops() {
        var box = bounds();
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, box)) {
            BlockPos pos = drop.blockPosition();
            if (!(drop.getOwner() instanceof ServerPlayer owner) || owner.isSpectator()
                    || !level.mayInteract(owner, pos)) {
                continue;
            }
            Port port = dropPort(pos);
            if (port == Port.NONE) {
                continue;
            }
            ItemStack stack = drop.getItem();
            Exchange trade = exchange(port, stack, pos);
            if (trade.count() > 0) {
                finishDrop(drop, trade);
            }
        }
    }

    private Port dropPort(BlockPos pos) {
        for (Cell cell : layout.pattern().cells()) {
            if (layout.world(cell).equals(pos)) {
                return switch (cell.role()) {
                    case LIQUID, FEED -> Port.KETTLE;
                    case FIRES, HEARTH -> Port.FUEL;
                    default -> Port.NONE;
                };
            }
        }
        return Port.NONE;
    }

    private void finishDrop(ItemEntity drop, Exchange trade) {
        ItemStack left = drop.getItem().copy();
        left.shrink(trade.count());
        if (left.isEmpty()) {
            drop.discard();
        } else {
            drop.setItem(left);
        }
        if (!trade.output().isEmpty()) {
            var returned = new ItemEntity(level, drop.getX(), drop.getY(), drop.getZ(), trade.output());
            returned.setDefaultPickUpDelay();
            level.addFreshEntity(returned);
        }
    }
}
