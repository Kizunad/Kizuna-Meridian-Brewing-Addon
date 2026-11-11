package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/** 药效和残药声音在实际中药者处发出，釜和火源的声音在方块处发出；功法动作声仍由目录控制。 */
final class BrewSounds {
    private static final Cue DEPLETION = new Cue("potion.depletion", 0.3F, 1.8F);
    private static final Cue ARMOR_ROT = new Cue("potion.armor_rot", 0.4F, 1.6F);
    private static final Cue INSECT_LURE = new Cue("potion.insect_lure", 0.4F, 1.2F);
    private static final Cue BITTER_YELLOW = new Cue("potion.bitter_yellow", 0.5F, 1);
    private static final Cue QI_GATHER = new Cue("potion.qi_gather", 0.3F, 1.4F);
    private static final Cue CHARM = new Cue("potion.charm", 0.5F, 1.3F);
    private static final Cue RESIDUE = new Cue("residue.plant", 0.25F, 1.6F);
    private static final Cue KNIFE_CUT = new Cue("knife.cut", 0.5F, 1.2F);
    static final Cue CAULDRON_ADD = new Cue("cauldron.add", 0.3F, 1.4F);
    static final Cue CAULDRON_DONE = new Cue("cauldron.done", 0.8F, 1);
    static final Cue CAULDRON_SCORCH = new Cue("cauldron.scorch", 0.5F, 0.8F);
    static final Cue CAULDRON_SCORCH_FIRE = new Cue("cauldron.scorch_fire", 0.4F, 0.6F);
    static final Cue CAULDRON_SCRUB = new Cue("cauldron.scrub", 0.5F, 1.2F);
    static final Cue CAULDRON_DUMP = new Cue("cauldron.dump", 0.6F, 0.8F);
    static final Cue BRAZIER_STOKE = new Cue("brazier.stoke", 0.6F, 1);
    static final Cue BRAZIER_RAKE = new Cue("brazier.rake", 0.4F, 0.7F);
    private static final String CAULDRON_QUALITY = "cauldron.quality";
    private static final float CAULDRON_QUALITY_VOLUME = 0.4F;
    private static final float POOR_PITCH = 0.8F;
    private static final float FINE_PITCH = 1.2F;
    private static final float SUPERIOR_PITCH = 1.5F;

    private BrewSounds() {
        // 事件资源只引用原版声音，不持有客户端声音对象。
    }

    static void effect(LivingEntity target, BrewKind kind) {
        // 一次用药对每个目标只播一次，逐部位催发由调用方合并。
        Cue cue = switch (kind) {
            case DEPLETION -> DEPLETION;
            case ARMOR_ROT -> ARMOR_ROT;
            case INSECT_LURE -> INSECT_LURE;
            case BITTER_YELLOW -> BITTER_YELLOW;
            case QI_GATHER -> QI_GATHER;
            case CHARM -> CHARM;
        };
        play(target, cue);
    }

    static void residue(LivingEntity target) {
        // 同一瓶沾多个部位仍只播一声下引。
        play(target, RESIDUE);
    }

    static void knifeCut(LivingEntity cutter) {
        // 一刀出片时在持刀者处响一声。
        play(cutter, KNIFE_CUT);
    }

    /** 釜和火源上的声音在方块中心发出。 */
    static void atBlock(Level level, BlockPos pos, Cue cue) {
        level.playSound(null, pos, event(cue), SoundSource.BLOCKS, cue.volume(), cue.pitch());
    }

    /** 舀出成药时按品质升调。 */
    static void quality(Level level, BlockPos pos, BrewQuality quality) {
        float pitch = switch (quality) {
            case POOR -> POOR_PITCH;
            case COMMON -> 1;
            case FINE -> FINE_PITCH;
            case SUPERIOR -> SUPERIOR_PITCH;
        };
        atBlock(level, pos, new Cue(CAULDRON_QUALITY, CAULDRON_QUALITY_VOLUME, pitch));
    }

    private static SoundEvent event(Cue cue) {
        return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, cue.path()));
    }

    private static void play(LivingEntity target, Cue cue) {
        // 内联事件让纯资源包提供声音映射，服务端固定位置、类别、音量和音高。
        target.level().playSound(null, target.getX(), target.getY(), target.getZ(), event(cue),
                SoundSource.PLAYERS, cue.volume(), cue.pitch());
    }

    record Cue(String path, float volume, float pitch) { }
}
