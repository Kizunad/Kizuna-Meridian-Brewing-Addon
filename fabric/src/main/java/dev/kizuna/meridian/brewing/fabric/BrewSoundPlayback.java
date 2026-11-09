package dev.kizuna.meridian.brewing.fabric;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** 每口活动釜的声音队列，周期和音量音高集中在本类。 */
final class BrewSoundPlayback {
    private static final long CYCLE_MILLIS = 5_000;
    private static final long MILLIS_PER_TICK = 50;
    private static final float VOLUME = 0.3F;
    private static final float STIR_PITCH = 0.7F;
    private static final float FAN_PITCH = 1.6F;
    private static final float SNIFF_PITCH = 1.0F;
    private static final long FIRST_STIR_MILLIS = 500;
    private static final long SECOND_STIR_MILLIS = 1_500;
    private static final long THIRD_STIR_MILLIS = 2_500;
    private static final long FAN_MILLIS = 3_400;
    private static final long SNIFF_MILLIS = 4_200;
    private static final List<Cue> CUES = List.of(
            new Cue(FIRST_STIR_MILLIS, SoundEvents.GENERIC_SWIM, STIR_PITCH),
            new Cue(SECOND_STIR_MILLIS, SoundEvents.GENERIC_SWIM, STIR_PITCH),
            new Cue(THIRD_STIR_MILLIS, SoundEvents.GENERIC_SWIM, STIR_PITCH),
            new Cue(FAN_MILLIS, SoundEvents.ARMOR_EQUIP_LEATHER.value(), FAN_PITCH),
            new Cue(SNIFF_MILLIS, SoundEvents.FOX_SNIFF, SNIFF_PITCH));

    private final List<Pending> pending = new ArrayList<>();

    void schedule(ServerLevel level, double elapsedSeconds, long intervalMillis) {
        // 每个推进事件只预约下一秒原始剪辑中的声音；迟滞时子秒偏移随推进间隔同比拉长。
        long stepMillis = BrewingTechniqueContract.STEP_MILLIS;
        long cycleOffset = Math.round(elapsedSeconds * stepMillis) % CYCLE_MILLIS;
        for (Cue cue : CUES) {
            long offset = cue.offsetMillis() - cycleOffset;
            if (offset >= 0 && offset < stepMillis) {
                long delayTicks = (long) Math.ceil((double) offset * intervalMillis / stepMillis / MILLIS_PER_TICK);
                pending.add(new Pending(level.getGameTime() + delayTicks, cue));
            }
        }
    }

    void tick(ServerLevel level, BlockPos pos) {
        // 声音使用原版事件并归入玩家类别，出队后不会在后续 tick 重播。
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            Pending sound = iterator.next();
            if (level.getGameTime() >= sound.tick()) {
                Cue cue = sound.cue();
                level.playSound(null, pos, cue.event(), SoundSource.PLAYERS, VOLUME, cue.pitch());
                iterator.remove();
            }
        }
    }

    private record Cue(long offsetMillis, SoundEvent event, float pitch) { }
    private record Pending(long tick, Cue cue) { }
}
