package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.DaylightPhase;
import dev.kizuna.meridian.brewing.server.DryWeather;
import dev.kizuna.meridian.brewing.server.DryingConditions;
import dev.kizuna.meridian.brewing.server.HerbCrateState;
import dev.kizuna.meridian.brewing.server.IngredientBatch;
import dev.kizuna.meridian.brewing.server.PreparationKind;
import dev.kizuna.meridian.brewing.server.PreparationMethod;
import dev.kizuna.meridian.brewing.server.PreparationRecipe;
import dev.kizuna.meridian.brewing.server.PreparationRecipes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
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

/** 晒药匾的九格晒料容器，晴天白天且露天时才推进。 */
public final class HerbCrateBlockEntity extends BaseContainerBlockEntity {
    private static final double SECONDS_PER_TICK = 0.05;
    private static final long DAY_TICKS = 24_000L;
    private static final long DAYLIGHT_TICKS = 12_000L;

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
        return ChestMenu.threeRows(id, inventory, this);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot >= 0 && slot < items.size() && rawSunDryBatch(stack) != null;
    }

    private void tick(Level currentLevel) {
        if (currentLevel.isClientSide()) {
            return;
        }
        syncStateFromItems();
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

    private void syncStateFromItems() {
        List<IngredientBatch> batches = new ArrayList<>();
        for (ItemStack stack : items) {
            IngredientBatch batch = rawSunDryBatch(stack);
            if (batch != null) {
                batches.add(batch);
            }
        }
        double dryingSeconds = crate.dryingSeconds();
        crate.restore(new HerbCrateState.Snapshot(batches, dryingSeconds));
    }

    private void prepareReadyItems() {
        long preparedGameDay = currentGameDay();
        for (int slot = 0; slot < items.size(); slot++) {
            IngredientBatch rawBatch = rawSunDryBatch(items.get(slot));
            if (rawBatch == null) {
                continue;
            }
            PreparationRecipe recipe = PreparationRecipes.all().get(rawBatch.itemId());
            if (recipe == null || crate.dryingSeconds() < recipe.durationSeconds()) {
                continue;
            }
            IngredientBatch preparedBatch = IngredientBatch.prepared(
                    recipe.outputItemId(), preparedGameDay, rawBatch.count());
            items.set(slot, itemStack(preparedBatch));
        }
        syncStateFromItems();
    }

    private IngredientBatch rawSunDryBatch(ItemStack stack) {
        if (stack.isEmpty() || stack.get(BrewingComponents.PREPARED_DAY) != null) {
            return null;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        PreparationRecipe recipe = PreparationRecipes.all().get(itemId);
        if (recipe == null || recipe.method() != PreparationMethod.SUN_DRY) {
            return null;
        }
        return IngredientBatch.raw(itemId, stack.getCount());
    }

    private ItemStack itemStack(IngredientBatch batch) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(batch.itemId()));
        ItemStack stack = new ItemStack(item, batch.count());
        if (batch.preparation() == PreparationKind.PREPARED) {
            stack.set(BrewingComponents.PREPARED_DAY, batch.preparedGameDay());
        }
        return stack;
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        HerbCrateState.Snapshot snapshot = readSnapshot(input);
        crate.restore(snapshot);
        if (snapshot.slots().isEmpty()) {
            syncStateFromItems();
            return;
        }
        restoreItems(snapshot.slots());
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        syncStateFromItems();
        super.saveAdditional(output);
        HerbCrateState.Snapshot snapshot = crate.snapshot();
        output.putDouble("drying_seconds", snapshot.dryingSeconds());
        ValueOutput.ValueOutputList list = output.childrenList("slots");
        for (IngredientBatch batch : snapshot.slots()) {
            ValueOutput child = list.addChild();
            child.putString("item_id", batch.itemId());
            child.putString("preparation", batch.preparation().name());
            child.putLong("prepared_game_day", batch.preparedGameDay());
            child.putInt("count", batch.count());
        }
    }

    private static HerbCrateState.Snapshot readSnapshot(ValueInput input) {
        List<IngredientBatch> slots = new ArrayList<>();
        for (ValueInput child : input.childrenListOrEmpty("slots")) {
            String itemId = child.getStringOr("item_id", "");
            PreparationKind preparation = enumValue(child.getStringOr("preparation", "RAW"),
                    PreparationKind.RAW);
            long preparedGameDay = child.getLongOr("prepared_game_day", -1L);
            int count = child.getIntOr("count", 1);
            slots.add(new IngredientBatch(itemId, preparation, preparedGameDay, count));
        }
        double dryingSeconds = input.getDoubleOr("drying_seconds", 0.0);
        return new HerbCrateState.Snapshot(slots, dryingSeconds);
    }

    private void restoreItems(List<IngredientBatch> slots) {
        for (int slot = 0; slot < items.size(); slot++) {
            items.set(slot, ItemStack.EMPTY);
        }
        for (int slot = 0; slot < slots.size(); slot++) {
            items.set(slot, itemStack(slots.get(slot)));
        }
    }

    private static <T extends Enum<T>> T enumValue(String value, T fallback) {
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }
}
