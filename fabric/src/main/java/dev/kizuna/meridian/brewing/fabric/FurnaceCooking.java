package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewStructure;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import net.minecraft.server.level.ServerPlayer;

/** 一秒只结算当前批次；开窗的人和下一锅的选方人都不能代付。 */
final class FurnaceCooking {
    private static final int TENDING_DISTANCE = 6;

    private FurnaceCooking() {}

    static void advance(BrewStation ports, BrewStructure heat) {
        var batch = ports.batch();
        var pot = batch.cauldron;
        batch.waiting = "";
        if (batch.active == null && batch.next == null) {
            return;
        }
        if (pot.state() == BrewCauldronState.EMPTY || pot.state() == BrewCauldronState.SCORCHED) {
            return;
        }
        var order = batch.active == null ? batch.next : batch.active;
        if (pot.state() != BrewCauldronState.BREWING) {
            if (pot.state() != BrewCauldronState.RAW) {
                return;
            }
            if (!batch.ingredientsMatch(order)) {
                batch.waiting = "message.meridian_brewing.furnace.reclaim_first";
                return;
            }
            if (order.form() == BrewForm.SPLASH && pot.needed(IngredientSlot.PROPELLANT) > 0) {
                return;
            }
            var problem = pot.startProblem(heat);
            if (problem.isPresent()) {
                batch.waiting = problem.orElseThrow().key();
                return;
            }
        }
        var player = ports.level().getServer().getPlayerList().getPlayer(order.owner());
        if (!present(ports, player) || !BrewRecipeBook.lookup(player).knows(order.kind())) {
            batch.waiting = "payer";
            return;
        }
        if (pot.state() == BrewCauldronState.RAW) {
            start(ports, heat, order, player);
        } else {
            payAndAdvance(ports, heat, order, player);
        }
    }

    private static boolean present(BrewStation ports, ServerPlayer player) {
        return player != null && player.isAlive() && !player.isSpectator() && player.level() == ports.level()
                && ports.bounds().inflate(TENDING_DISTANCE).contains(player.position())
                && ports.level().mayInteract(player, ports.anchor());
    }

    private static void start(BrewStation ports, BrewStructure heat, FurnaceOrder order, ServerPlayer player) {
        var batch = ports.batch();
        var shortfall = BrewQiPayment.startProblem(player, ports.level(), ports.anchor(), batch.vessel);
        if (shortfall.isPresent()) {
            batch.waiting = "qi";
            return;
        }
        if (batch.cauldron.startBrew(heat).accepted()) {
            batch.active = order;
            batch.refundable.clear();
            batch.batchNumber++;
            batch.version++;
            batch.tally = BrewQiPayment.tally(batch.vessel);
        }
    }

    private static void payAndAdvance(BrewStation ports, BrewStructure heat, FurnaceOrder order, ServerPlayer player) {
        var batch = ports.batch();
        var kind = order.kind();
        if (heat.problemFor(kind, kind.fireKind()).isPresent()) {
            batch.waiting = "fire";
            return;
        }
        var anchor = ports.anchor();
        var level = ports.level();
        String draw = "furnace/" + level.dimension().identifier() + "/" + anchor.asLong() + "/"
                + batch.batchNumber + "/" + batch.tally.seconds();
        var paid = BrewQiPayment.paySecond(player, level, anchor, draw, batch.tally);
        batch.tally = paid;
        if (paid.beyondGrace()) {
            batch.cauldron.interruptBrew();
            batch.waiting = "qi";
            return;
        }
        batch.cauldron.tick(1, heat);
    }
}
