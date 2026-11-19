package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewActionResult;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCollectionKind;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 所有装瓶入口在服务端逐瓶结算，最后一瓶清锅前先记下药种。 */
record BrewBottle(BrewActionResult result, ItemStack output) {
    private static final long DAY_TICKS = 24_000L;

    static BrewBottle collect(BrewCauldron cauldron, Level level, BlockPos pos) {
        return collect(cauldron, level, pos, BrewQuality.SUPERIOR);
    }

    static BrewBottle collect(BrewCauldron cauldron, Level level, BlockPos pos, BrewQuality ceiling) {
        var recipe = cauldron.recipe();
        BrewForm form = cauldron.form();
        long day = Math.floorDiv(level.getOverworldClockTime(), DAY_TICKS);
        var collected = cauldron.collectBottle(day, ceiling);
        if (collected.kind() == BrewCollectionKind.NOTHING) {
            return new BrewBottle(BrewActionResult.returned(collected.message()), ItemStack.EMPTY);
        }
        ItemStack output;
        if (collected.kind() == BrewCollectionKind.POTION) {
            var quality = collected.score().orElseThrow().quality().orElseThrow();
            var use = form == BrewForm.SPLASH ? BrewUse.SPLASH : BrewUse.DRINK;
            output = BrewItemStacks.create(recipe.orElseThrow().kind(), quality, use);
            BrewSounds.quality(level, pos, quality);
        } else {
            output = new ItemStack(BrewingItems.item("brew_residue"));
            BrewSounds.atBlock(level, pos, BrewSounds.CAULDRON_DUMP);
        }
        return new BrewBottle(BrewActionResult.accepted(collected.message()), output);
    }
}
