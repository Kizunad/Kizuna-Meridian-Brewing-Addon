package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.fabric.FurnacePatterns.Role;
import dev.kizuna.meridian.brewing.server.BrewFireState;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewSteps;
import dev.kizuna.meridian.brewing.server.BrewStructure;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 炉窗客户端场景在服务端摆状态用的入口。炉的批次、炉口都是 fabric 模块包内可见的，所以这个类放在同名包里。
 *
 * <p>自己这一锅的添水、投料、加燃料走和右键相同的 {@link FurnacePorts#use}；「别人在炼」那一锅没有第二个真玩家，
 * 直接把批次摆成别人选方、已经开熬的样子。</p>
 */
public final class FurnaceScenes {
    private static final long TEST_DAY = 0L;
    private static final List<IngredientSlot> STARTING = List.of(IngredientSlot.PRIMARY, IngredientSlot.BASE,
            IngredientSlot.PROPELLANT);

    private FurnaceScenes() {
        // 只有静态入口。
    }

    /** 拿着这件东西点锅沿：添水、投料、拿瓶收药。点完手里清空，后面还能空手开窗。 */
    public static void offerRim(ServerPlayer player, BlockPos rim, ItemStack stack) {
        FurnacePorts ports = ports(player.level(), rim);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        ports.use(player, InteractionHand.MAIN_HAND, rim);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    /** 拿着这件东西点炉底：加燃料、换火种、用锹压火。 */
    public static void offerHearth(ServerPlayer player, BlockPos rim, ItemStack stack) {
        FurnacePorts ports = ports(player.level(), rim);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        ports.use(player, InteractionHand.MAIN_HAND, ports.layout().anchor());
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    /** 炉底朝外、离这格锅沿最近的一块炉体，拿燃料点它就是往炉底添。 */
    public static BlockPos hearthPort(ServerLevel level, BlockPos rim) {
        FurnaceLayout layout = ports(level, rim).layout();
        int fireY = layout.pattern().fires().getFirst().offset().getY();
        return layout.pattern().cells().stream()
                .filter(cell -> cell.role() == Role.BODY && cell.offset().getY() == fireY)
                .map(layout::world)
                .min(Comparator.comparingDouble(pos -> horizontalDistance(pos, rim)))
                .orElseThrow();
    }

    /**
     * 把这座炉摆成「别人选了这张方、已经开熬」：水、起始料、燃料备齐后直接起熬，选方人是一个不在线的身份。
     * 选方人不在，炉钟不会往前走，阶段一直停在熬制中。
     */
    public static void foreignBrew(ServerLevel level, BlockPos rim, BrewKind kind, BrewForm form) {
        FurnacePorts ports = ports(level, rim);
        FurnaceBatch batch = ports.batch();
        var recipe = BrewRecipes.require(kind);
        batch.next = new FurnaceOrder(kind, form, UUID.randomUUID());
        batch.fire = kind.fireKind();
        batch.addFuel(new ItemStack(Items.COAL_BLOCK));
        batch.cauldron.addWater(batch.vessel.waterCapacity());
        int sets = batch.vessel.batch().sets();
        for (IngredientSlot slot : STARTING) {
            if (slot == IngredientSlot.PROPELLANT && form == BrewForm.DRINK) {
                continue;
            }
            var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(BrewSteps.itemId(recipe, slot)));
            batch.addIngredient(new ItemStack(item, sets), TEST_DAY);
        }
        var heat = BrewStructure.built(BrewFireState.stoveHeatSource(batch.fire, ports.grade()));
        var started = batch.cauldron.startBrew(heat);
        if (!started.accepted()) {
            throw new AssertionError("别人的这一锅没能开熬：" + describe(level, rim));
        }
        batch.active = batch.next;
        batch.refundable.clear();
        batch.batchNumber++;
        batch.version++;
        batch.tally = BrewQiPayment.tally(batch.vessel);
        FurnaceRegister.of(level).setDirty();
    }

    /** 这一锅开熬后过了多少秒；录像踩投料时窗用。 */
    public static double elapsed(ServerLevel level, BlockPos rim) {
        return ports(level, rim).batch().cauldron.brewElapsedSeconds();
    }

    /** 这座炉此刻的批次，写进日志对照截图。 */
    public static String describe(ServerLevel level, BlockPos rim) {
        FurnaceBatch batch = ports(level, rim).batch();
        var pot = batch.cauldron;
        return String.format(Locale.ROOT, "阶段 %s 水 %d 火 %s 余燃 %d 版本 %d 批号 %d 已熬 %.0f 秒 %.0f 度 等 %s",
                pot.state(), pot.waterUnits(), batch.fire, batch.fuelSeconds(), batch.version, batch.batchNumber,
                pot.brewElapsedSeconds(), pot.temperature(), batch.waiting);
    }

    private static FurnacePorts ports(ServerLevel level, BlockPos rim) {
        var found = BrewingFurnaces.inspect(level, rim);
        if (found == null || found.check().status() != FurnaceLayout.Status.FORMED) {
            throw new AssertionError("这一格不是成形的炉 " + rim);
        }
        return FurnacePorts.of(level, found.layout());
    }

    private static double horizontalDistance(BlockPos pos, BlockPos other) {
        return Math.hypot(pos.getX() - other.getX(), pos.getZ() - other.getZ());
    }
}
