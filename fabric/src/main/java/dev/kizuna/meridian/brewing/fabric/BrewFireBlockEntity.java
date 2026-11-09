package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewActionResult;
import dev.kizuna.meridian.brewing.server.BrewFireSource;
import dev.kizuna.meridian.brewing.server.BrewFireState;
import dev.kizuna.meridian.brewing.server.FireKind;
import dev.kizuna.meridian.brewing.server.HeatSourceKind;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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

/** 可调火源的档位、火种和每档续炭时间。 */
public final class BrewFireBlockEntity extends BlockEntity {
    private static final double SECONDS_PER_TICK = 0.05;
    private final BrewFireState fire;

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
        // 活动熬药先走受限副手入口，普通交互只在规则接受后消费物品。
        if (level == null || level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            Optional<BrewActionResult> techniqueResult = BrewingRuntime.tryTechniqueFire(
                    serverPlayer, hand, worldPosition);
            if (techniqueResult.isPresent()) {
                BrewActionResult result = techniqueResult.orElseThrow();
                if (!result.accepted()) {
                    sendOverlay(player, result.reason());
                }
                return InteractionResult.SUCCESS;
            }
        }
        ItemStack stack = player.getItemInHand(hand);
        BrewActionResult result = useStack(stack);
        if (!result.accepted()) {
            sendOverlay(player, result.reason());
            return InteractionResult.SUCCESS;
        }
        consumeAcceptedUse(player, hand, stack);
        sendOverlay(player, result.reason());
        setChanged();
        return InteractionResult.SUCCESS;
    }

    public BrewActionResult useTechnique(ServerPlayer player, InteractionHand hand) {
        // 熬药副手操作仍需通过世界权限，成功后才消费物品或损耗工具。
        if (level == null || level.isClientSide()) {
            return BrewActionResult.returned("火源只能在服务端操作");
        }
        if (player == null || !level.mayInteract(player, worldPosition)) {
            return BrewActionResult.returned("没有权限操作火源");
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(Items.COAL) && !stack.is(Items.CHARCOAL)
                && !stack.is(ItemTags.SHOVELS) && !stack.is(Items.WATER_BUCKET)) {
            return BrewActionResult.returned("熬药中副手只能加炭、拨灰或扑灭火源");
        }
        BrewActionResult result = useStack(stack);
        if (!result.accepted()) {
            sendOverlay(player, result.reason());
            return result;
        }
        consumeAcceptedUse(player, hand, stack);
        sendOverlay(player, result.reason());
        setChanged();
        return result;
    }

    private static void sendOverlay(Player player, String message) {
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection == null) {
            return;
        }
        player.sendOverlayMessage(Component.literal(message));
    }

    private BrewActionResult useStack(ItemStack stack) {
        // 持物决定调火操作，物品消耗和容器返还由调用方在成功后统一处理。
        if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) {
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
        return BrewActionResult.returned("铁火盆和铁炉座只接受煤炭、木炭、锹或火种材料");
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

    private void tick(Level currentLevel) {
        if (!currentLevel.isClientSide()) {
            fire.tick(SECONDS_PER_TICK);
            setChanged();
        }
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
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        BrewFireState.Snapshot snapshot = fire.snapshot();
        output.putString("source", snapshot.source().name());
        output.putString("fire_kind", snapshot.fireKind().name());
        output.putInt("level", snapshot.level());
        output.putDouble("fuel_seconds", snapshot.fuelSeconds());
    }

    private static <T extends Enum<T>> T enumValue(String value, Class<T> type, T fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }
}
