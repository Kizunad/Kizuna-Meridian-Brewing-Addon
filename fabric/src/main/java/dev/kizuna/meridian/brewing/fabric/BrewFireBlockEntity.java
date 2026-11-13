package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewActionResult;
import dev.kizuna.meridian.brewing.server.BrewFireSource;
import dev.kizuna.meridian.brewing.server.BrewFireState;
import dev.kizuna.meridian.brewing.server.BrewSaveMarks;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.FireKind;
import dev.kizuna.meridian.brewing.server.HeatSourceKind;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 可调火源的档位、火种和每档续炭时间。
 *
 * <p>漏斗、投掷器从任意一面往里送煤炭或木炭：先放进一格待烧，下一 tick 照手动加炭的规则升一档、加 60 秒。
 * 已经四档或者这一格还没烧掉时不收，炭留在漏斗里；这一格不往外出东西。</p>
 *
 * <p>存档：火源、火种、档位、待烧那格一变当 tick 标脏（比较器读档位）；燃着时剩余秒数每秒最多标一次，
 * 熄着、营火不标（{@link BrewSaveMarks}）。</p>
 */
public final class BrewFireBlockEntity extends BlockEntity implements WorldlyContainer {
    private static final double SECONDS_PER_TICK = 0.05;
    private static final int[] FUEL_SLOT = {0};
    private final BrewFireState fire;
    private final NonNullList<ItemStack> pendingFuel = NonNullList.withSize(1, ItemStack.EMPTY);
    private final BrewSaveMarks<SaveContent, Double> saveMarks = new BrewSaveMarks<>();
    private long ticks;

    public BrewFireBlockEntity(BlockPos pos, BlockState state, BrewFireSource source) {
        super(BrewingBlocks.BREW_FIRE_ENTITY, pos, state);
        this.fire = source == BrewFireSource.BRAZIER
                ? BrewFireState.brazier(FireKind.SUN_FIRE)
                : BrewFireState.stove(FireKind.SUN_FIRE);
    }

    static <T extends BlockEntity> BlockEntityTicker<T> ticker(BlockEntityType<T> type) {
        if (type != BrewingBlocks.BREW_FIRE_ENTITY) {
            return null;
        }
        return (level, pos, state, entity) -> {
            if (entity instanceof BrewFireBlockEntity fireEntity) {
                fireEntity.tick(level);
            }
        };
    }

    HeatSourceKind heatSource() {
        return fire.heatSource();
    }

    public BrewFireState fire() {
        return fire;
    }

    public InteractionResult use(Player player, InteractionHand hand) {
        // 活动熬药先走受限副手入口，成功一次叠一段副手下料动作；普通交互只在规则接受后消费物品。
        if (level == null || level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            Optional<BrewActionResult> techniqueResult = BrewingRuntime.tryTechniqueFire(
                    serverPlayer, hand, worldPosition);
            if (techniqueResult.isPresent()) {
                BrewActionResult result = techniqueResult.orElseThrow();
                if (result.accepted()) {
                    BrewCastAnimations.feedOffHand(serverPlayer);
                } else {
                    BrewTexts.sendOverlay(player, result.message());
                }
                return InteractionResult.SUCCESS;
            }
        }
        ItemStack stack = player.getItemInHand(hand);
        BrewActionResult result = useStack(stack);
        if (!result.accepted()) {
            BrewTexts.sendOverlay(player, result.message());
            return InteractionResult.SUCCESS;
        }
        playAcceptedSound(stack);
        consumeAcceptedUse(player, hand, stack);
        BrewTexts.sendOverlay(player, result.message());
        setChanged();
        return InteractionResult.SUCCESS;
    }

    public BrewActionResult useTechnique(ServerPlayer player, InteractionHand hand) {
        // 熬药副手操作仍需通过世界权限，成功后才消费物品或损耗工具。
        if (level == null || level.isClientSide()) {
            return BrewActionResult.returned(BrewText.message("fire.server_only"));
        }
        if (player == null || !level.mayInteract(player, worldPosition)) {
            return BrewActionResult.returned(BrewText.message("no_permission"));
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!isFuel(stack) && !stack.is(ItemTags.SHOVELS) && !stack.is(Items.WATER_BUCKET)) {
            return BrewActionResult.returned(BrewText.message("fire.technique_offhand"));
        }
        BrewActionResult result = useStack(stack);
        if (!result.accepted()) {
            BrewTexts.sendOverlay(player, result.message());
            return result;
        }
        playAcceptedSound(stack);
        consumeAcceptedUse(player, hand, stack);
        BrewTexts.sendOverlay(player, result.message());
        setChanged();
        return result;
    }

    private BrewActionResult useStack(ItemStack stack) {
        // 持物决定调火操作，物品消耗和容器返还由调用方在成功后统一处理。
        if (isFuel(stack)) {
            return fire.addFuel("minecraft:coal");
        }
        if (stack.is(ItemTags.SHOVELS)) {
            return fire.lowerLevel();
        }
        if (stack.is(Items.WATER_BUCKET)) {
            return fire.extinguish();
        }
        if (stack.is(Items.SOUL_SAND) || stack.is(Items.SOUL_SOIL)) {
            return fire.changeFireKind(FireKind.SOUL_FIRE);
        }
        if (stack.is(Items.SAND)) {
            return fire.changeFireKind(FireKind.SUN_FIRE);
        }
        return BrewActionResult.returned(BrewText.message("fire.wrong_item"));
    }

    private void playAcceptedSound(ItemStack stack) {
        if (isFuel(stack)) {
            BrewSounds.atBlock(level, worldPosition, BrewSounds.BRAZIER_STOKE);
        } else if (stack.is(ItemTags.SHOVELS)) {
            BrewSounds.atBlock(level, worldPosition, BrewSounds.BRAZIER_RAKE);
        }
    }

    private void consumeAcceptedUse(Player player, InteractionHand hand, ItemStack stack) {
        // 工具损耗、容器置换与耗材扣除分别处理，空堆叠不再用于识别原物品。
        if (player.hasInfiniteMaterials() || stack.isEmpty()) {
            return;
        }
        if (stack.is(ItemTags.SHOVELS)) {
            stack.hurtAndBreak(1, player, hand);
            return;
        }
        if (stack.is(Items.WATER_BUCKET)) {
            BrewingItemReturns.consumeWaterBucket(player, hand, stack);
            return;
        }
        stack.shrink(1);
    }

    /** 对着釜用这件东西时该不该转给釜下的火：煤、木炭、铲子、沙子、灵魂沙、灵魂土；水桶留给釜注水。 */
    static boolean tendsFire(ItemStack stack) {
        return isFuel(stack) || stack.is(ItemTags.SHOVELS) || stack.is(Items.SAND)
                || stack.is(Items.SOUL_SAND) || stack.is(Items.SOUL_SOIL);
    }

    private static boolean isFuel(ItemStack stack) {
        return stack.is(Items.COAL) || stack.is(Items.CHARCOAL);
    }

    private void tick(Level currentLevel) {
        if (!currentLevel.isClientSide()) {
            ticks++;
            burnPendingFuel();
            fire.tick(SECONDS_PER_TICK);
            markIfChanged();
        }
    }

    @Override
    public void setChanged() {
        // 右键、漏斗哪个入口标的脏都记下这一刻的存档内容，tick 末只和它比。
        super.setChanged();
        rememberSaved();
    }

    private void rememberSaved() {
        // 记下此刻会写进存档的内容；读档后也记一次，刚读进来、什么都没变就不用再存。
        BrewFireState.Snapshot snapshot = fire.snapshot();
        saveMarks.marked(saveContent(snapshot), snapshot.fuelSeconds(), ticks);
    }

    private void markIfChanged() {
        // 档位、火种、待烧那格一变当 tick 标；只是燃料秒数在走，最多每秒标一次。
        BrewFireState.Snapshot snapshot = fire.snapshot();
        if (saveMarks.due(saveContent(snapshot), snapshot.fuelSeconds(), ticks)) {
            setChanged();
        }
    }

    private SaveContent saveContent(BrewFireState.Snapshot snapshot) {
        // 燃料秒数以外的存档内容，加上待烧那格的物品和数量。
        ItemStack fuel = pendingFuel.getFirst();
        String fuelItem = "";
        if (!fuel.isEmpty()) {
            fuelItem = BuiltInRegistries.ITEM.getKey(fuel.getItem()).toString();
        }
        return new SaveContent(snapshot.source(), snapshot.fireKind(), snapshot.level(), fuelItem, fuel.getCount());
    }

    /** 存档里一变就要存的那部分。 */
    private record SaveContent(BrewFireSource source, FireKind fireKind, int level, String pendingItem,
                               int pendingCount) {
    }

    private void burnPendingFuel() {
        ItemStack fuel = pendingFuel.getFirst();
        if (fuel.isEmpty()) {
            return;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(fuel.getItem()).toString();
        if (fire.addFuel(itemId).accepted()) {
            fuel.shrink(1);
            BrewSounds.atBlock(level, worldPosition, BrewSounds.BRAZIER_STOKE);
        }
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return FUEL_SLOT;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return pendingFuel.getFirst().isEmpty() && isFuel(stack) && fire.level() < BrewFireState.MAX_LEVEL;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public int getContainerSize() {
        return pendingFuel.size();
    }

    @Override
    public boolean isEmpty() {
        return pendingFuel.getFirst().isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return pendingFuel.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack taken = ContainerHelper.removeItem(pendingFuel, slot, count);
        setChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(pendingFuel, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        pendingFuel.set(slot, stack);
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        pendingFuel.clear();
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        fire.restore(new BrewFireState.Snapshot(
                enumValue(input.getStringOr("source", fire.source().name()), BrewFireSource.class,
                        fire.source()),
                enumValue(input.getStringOr("fire_kind", fire.fireKind().name()), FireKind.class,
                        fire.fireKind()),
                input.getIntOr("level", fire.level()),
                input.getDoubleOr("fuel_seconds", fire.fuelSeconds())));
        pendingFuel.clear();
        ContainerHelper.loadAllItems(input, pendingFuel);
        rememberSaved();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        BrewFireState.Snapshot snapshot = fire.snapshot();
        output.putString("source", snapshot.source().name());
        output.putString("fire_kind", snapshot.fireKind().name());
        output.putInt("level", snapshot.level());
        output.putDouble("fuel_seconds", snapshot.fuelSeconds());
        ContainerHelper.saveAllItems(output, pendingFuel);
    }

    private static <T extends Enum<T>> T enumValue(String value, Class<T> type, T fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }
}
