package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.DaylightPhase;
import dev.kizuna.meridian.brewing.server.DryWeather;
import dev.kizuna.meridian.brewing.server.DryingConditions;
import dev.kizuna.meridian.brewing.server.HerbCrateState;
import dev.kizuna.meridian.brewing.server.PreparationRecipe;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 晒药匾的九格晒料容器：每格各自计时，晴天白天且露天时才推进，晒够了原地换成干药。
 *
 * <p>漏斗和投掷器从任意一面只送要晒的生药进来；漏斗只能从底下抽走晒好的干药，生药抽不走。</p>
 */
public final class HerbCrateBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
    private static final double SECONDS_PER_TICK = 0.05;
    private static final long DAY_TICKS = 24_000L;
    private static final long DAYLIGHT_TICKS = 12_000L;
    private static final String DRYING_TAG = "drying";
    private static final String LEGACY_SLOTS_TAG = "slots";
    private static final int[] ALL_SLOTS = IntStream.range(0, HerbCrateState.SLOT_COUNT).toArray();

    private final NonNullList<ItemStack> items = NonNullList.withSize(
            HerbCrateState.SLOT_COUNT, ItemStack.EMPTY);
    private final HerbCrateState crate = new HerbCrateState();

    public HerbCrateBlockEntity(BlockPos pos, BlockState state) {
        super(BrewingBlocks.HERB_CRATE_ENTITY, pos, state);
    }

    static <T extends BlockEntity> BlockEntityTicker<T> ticker(BlockEntityType<T> type) {
        if (type != BrewingBlocks.HERB_CRATE_ENTITY) {
            return null;
        }
        return (level, pos, state, entity) -> {
            if (entity instanceof HerbCrateBlockEntity crateEntity) {
                crateEntity.tick(level);
            }
        };
    }

    public HerbCrateState crate() {
        return crate;
    }

    InteractionResult open(Player player) {
        if (level == null || level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        player.openMenu(this);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    protected void setItems(NonNullList<ItemStack> incoming) {
        int slotCount = Math.min(items.size(), incoming.size());
        for (int slot = 0; slot < slotCount; slot++) {
            items.set(slot, incoming.get(slot));
        }
        for (int slot = slotCount; slot < items.size(); slot++) {
            items.set(slot, ItemStack.EMPTY);
        }
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.meridian_brewing.herb_crate");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return new DispenserMenu(id, inventory, this);
    }

    /** 漏斗只往里送要晒的生药；界面里玩家放别的东西不会晒。 */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot >= 0 && slot < items.size() && !isDried(stack) && HerbCrateState.acceptsRaw(itemId(stack));
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return ALL_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
        // 原版漏斗只往空格放，或者往同种、同组件的格里补；晒好的干药是另一种物品，补不进去。
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return side == Direction.DOWN && isDried(stack);
    }

    /** 晒好的格数，比较器按它出 0–9 格信号。 */
    int driedSlots() {
        int dried = 0;
        for (ItemStack stack : items) {
            if (isDried(stack)) {
                dried++;
            }
        }
        return dried;
    }

    private static boolean isDried(ItemStack stack) {
        return stack.get(BrewingComponents.PREPARED_DAY) != null;
    }

    private void tick(Level currentLevel) {
        if (currentLevel.isClientSide()) {
            return;
        }
        observeItems();
        crate.tick(SECONDS_PER_TICK, dryingConditions());
        prepareReadyItems();
        setChanged();
    }

    private DryingConditions dryingConditions() {
        long time = Math.floorMod(level.getOverworldClockTime(), DAY_TICKS);
        DaylightPhase daylight = time < DAYLIGHT_TICKS ? DaylightPhase.DAY : DaylightPhase.NIGHT;
        DryWeather weather = level.isRainingAt(worldPosition) ? DryWeather.RAIN : DryWeather.CLEAR;
        boolean exposedToSky = level.canSeeSkyFromBelowWater(worldPosition.above());
        return new DryingConditions(daylight, weather, exposedToSky);
    }

    private long currentGameDay() {
        return Math.floorDiv(level.getOverworldClockTime(), DAY_TICKS);
    }

    /** 每 tick 逐格对比物品和数量；原版界面合并堆叠时直接改原物品堆，不经过 setItem。 */
    private void observeItems() {
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stack = items.get(slot);
            crate.observe(slot, itemId(stack), stack.getCount());
        }
    }

    private void prepareReadyItems() {
        for (int slot = 0; slot < items.size(); slot++) {
            Optional<PreparationRecipe> recipe = crate.readyRecipe(slot);
            if (recipe.isEmpty() || isDried(items.get(slot))) {
                continue;
            }
            Identifier outputId = Identifier.parse(recipe.orElseThrow().outputItemId());
            ItemStack prepared = new ItemStack(BuiltInRegistries.ITEM.getValue(outputId), items.get(slot).getCount());
            prepared.set(BrewingComponents.PREPARED_DAY, currentGameDay());
            items.set(slot, prepared);
            crate.observe(slot, itemId(prepared), prepared.getCount());
        }
    }

    private static String itemId(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        for (int slot = 0; slot < items.size(); slot++) {
            items.set(slot, ItemStack.EMPTY);
        }
        ContainerHelper.loadAllItems(input, items);
        double[] seconds = new double[items.size()];
        for (ValueInput child : input.childrenListOrEmpty(DRYING_TAG)) {
            int slot = child.getIntOr("slot", -1);
            if (slot >= 0 && slot < seconds.length) {
                seconds[slot] = child.getDoubleOr("seconds", 0.0);
            }
        }
        restoreLegacyRawHerbs(input);
        List<HerbCrateState.SlotState> slots = new ArrayList<>();
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stack = items.get(slot);
            slots.add(new HerbCrateState.SlotState(itemId(stack), stack.getCount(), seconds[slot]));
        }
        crate.restore(slots);
    }

    /**
     * 旧版药匾只存了还没晒好的生药（{@code slots}），晒好的干药没有存。读到旧格式时把生药按顺序放回前几格，
     * 晒制进度从 0 重新算；之后按新格式保存，这段只会走一次。
     */
    private void restoreLegacyRawHerbs(ValueInput input) {
        int slot = 0;
        for (ValueInput child : input.childrenListOrEmpty(LEGACY_SLOTS_TAG)) {
            String legacyItemId = child.getStringOr("item_id", "");
            int count = child.getIntOr("count", 1);
            if (slot < items.size() && HerbCrateState.acceptsRaw(legacyItemId) && count > 0) {
                Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(legacyItemId));
                items.set(slot, new ItemStack(item, count));
                slot++;
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        ValueOutput.ValueOutputList list = output.childrenList(DRYING_TAG);
        for (int slot = 0; slot < items.size(); slot++) {
            double seconds = crate.dryingSeconds(slot);
            if (seconds > 0.0) {
                ValueOutput child = list.addChild();
                child.putInt("slot", slot);
                child.putDouble("seconds", seconds);
            }
        }
    }
}
