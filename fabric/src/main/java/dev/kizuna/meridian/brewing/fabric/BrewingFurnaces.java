package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.fabric.FurnaceLayout.Check;
import dev.kizuna.meridian.brewing.fabric.FurnaceLayout.Status;
import dev.kizuna.meridian.brewing.fabric.FurnacePatterns.Pattern;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;

/** 空手点锅体或锅沿认炉；箱子继续用原版交互。 */
final class BrewingFurnaces {
    private static final List<Mirror> MIRRORS = List.of(Mirror.NONE, Mirror.FRONT_BACK);
    private static final int RECHECK_TICKS = 20;
    private static final int FAULT_DIVISOR = 4;

    record Inspection(FurnaceLayout layout, Check check) {}

    private BrewingFurnaces() {}

    static void register() {
        UseBlockCallback.EVENT.register(BrewingFurnaces::use);
        ServerTickEvents.END_LEVEL_TICK.register(level -> {
            if (level.getGameTime() % RECHECK_TICKS == 0) {
                recheck(level);
            }
        });
    }

    private static InteractionResult use(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer viewer) || !(world instanceof ServerLevel level)
                || viewer.isSpectator()) {
            return InteractionResult.PASS;
        }
        BlockPos clicked = hit.getBlockPos();
        if (!level.mayInteract(viewer, clicked)) {
            return InteractionResult.PASS;
        }
        Inspection found = inspect(level, clicked);
        if (found == null) {
            return InteractionResult.PASS;
        }
        if (!player.getItemInHand(hand).isEmpty()) {
            if (found.check().status() != Status.FORMED) {
                return InteractionResult.PASS;
            }
            var result = FurnacePorts.of(level, found.layout()).use(viewer, hand, clicked);
            if (result != InteractionResult.PASS) {
                ((FurnaceHandUse) viewer.connection).meridianBrewing$claimHand(hand);
            }
            return result;
        }
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        Pattern pattern = found.layout().pattern();
        if (found.check().status() == Status.FORMED) {
            FurnaceBuiltTrigger.TRIGGER.trigger(viewer, pattern.tier());
            FurnaceMenu.open(viewer, found.layout(), clicked);
        } else {
            BrewStructureMarkers.show(viewer, found.check().first());
            var name = Component.translatable(pattern.id().toLanguageKey("structure"));
            viewer.sendOverlayMessage(Component.translatable("message.meridian_brewing.furnace.broken", name));
        }
        return InteractionResult.SUCCESS;
    }

    static Inspection inspect(ServerLevel level, BlockPos clicked) {
        Block base = FurnaceLayout.base(level.getBlockState(clicked).getBlock());
        Inspection best = null;
        for (Pattern pattern : FurnacePatterns.all(level.getServer())) {
            var entries = pattern.entries().get(base);
            if (entries == null) {
                continue;
            }
            for (Mirror mirror : MIRRORS) {
                for (Rotation rotation : Rotation.values()) {
                    var orientation = new FurnaceLayout(pattern, clicked, mirror, rotation);
                    best = find(level, orientation, entries, best);
                    if (best != null && best.check().status() == Status.FORMED) {
                        FurnaceRegister.of(level).remember(best.layout());
                        return best;
                    }
                }
            }
        }
        if (best != null) {
            FurnaceRegister.of(level).forget(best.layout().anchor());
        }
        return best;
    }

    private static Inspection find(ServerLevel level, FurnaceLayout orientation,
                                   List<FurnacePatterns.Cell> entries, Inspection best) {
        // 从被点击的部件反推底板中心；火位筛选通过后才作完整比对。
        Pattern pattern = orientation.pattern();
        Inspection closest = best;
        for (var cell : entries) {
            var offset = FurnaceLayout.offset(cell, orientation.mirror(), orientation.rotation());
            BlockPos center = orientation.anchor().subtract(offset);
            var layout = new FurnaceLayout(pattern, center, orientation.mirror(), orientation.rotation());
            if (!layout.nearFires(level)) {
                continue;
            }
            Check check = layout.check(level);
            if (check.status() == Status.FORMED) {
                return new Inspection(layout, check);
            }
            if (check.status() == Status.BROKEN && check.faults() <= pattern.cells().size() / FAULT_DIVISOR
                    && (closest == null || check.faults() < closest.check().faults())) {
                closest = new Inspection(layout, check);
            }
        }
        return closest;
    }

    static void recheck(ServerLevel level) {
        FurnaceRegister register = FurnaceRegister.of(level);
        var patterns = FurnacePatterns.all(level.getServer());
        for (var site : register.sites()) {
            var current = patterns.stream().filter(pattern -> pattern.id().equals(site.definition())
                    && pattern.version() == site.version() && pattern.tier() == site.tier()).findFirst();
            if (current.isEmpty()) {
                register.forget(site.anchor());
                continue;
            }
            var layout = new FurnaceLayout(current.orElseThrow(), site.anchor(), site.mirror(), site.rotation());
            switch (layout.check(level).status()) {
                case BROKEN -> register.forget(site.anchor());
                case FORMED -> FurnacePorts.of(level, layout).tickSecond();
                case UNLOADED -> { }
            }
        }
    }
}
