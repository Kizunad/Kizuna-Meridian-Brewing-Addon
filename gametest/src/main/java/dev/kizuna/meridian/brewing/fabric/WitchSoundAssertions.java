package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

/** 直接核对正式网络下发的动作声；表中的 tick 来自设计时刻。 */
final class WitchSoundAssertions {
    private static final double POSITION_TOLERANCE = 0.13;
    private static final double FLOAT_TOLERANCE = 0.00001;
    private static final List<Cue> IMBIBE = List.of(new Cue("cork", 6, 0.5F, 1.8F),
            new Cue("imbibe.sip", 18, 0.5F, 1.2F), new Cue("imbibe.sip", 30, 0.5F, 1.2F),
            new Cue("imbibe.sip", 42, 0.5F, 1.2F), new Cue("imbibe.stroke", 48, 0.3F, 1.4F),
            new Cue("imbibe.stroke", 57, 0.3F, 1.4F));
    private static final List<Cue> ANOINT_LEGS = List.of(new Cue("cork", 5, 0.5F, 1.8F),
            new Cue("anoint.pour", 14, 0.5F, 1.3F), new Cue("anoint.rub", 25, 0.5F, 1.1F),
            new Cue("anoint.rub", 31, 0.5F, 1.1F), new Cue("anoint.rub", 38, 0.5F, 1.1F),
            new Cue("anoint.rub", 44, 0.5F, 1.1F), new Cue("anoint.pat", 51, 0.4F, 1.4F));
    private static final List<Cue> ANOINT_ARMS = List.of(new Cue("cork", 5, 0.5F, 1.8F),
            new Cue("anoint.pour", 14, 0.5F, 1.3F), new Cue("anoint.rub", 24, 0.5F, 0.9F),
            new Cue("anoint.rub", 42, 0.5F, 0.9F));
    private static final List<Cue> LOB = List.of(new Cue("lob_seed.shake", 3, 0.4F, 1.5F),
            new Cue("lob_seed.throw", 20, 0.5F, 0.8F));
    private static final List<Cue> FAN = List.of(new Cue("cork", 3, 0.5F, 1.8F),
            new Cue("fling_seed.splash", 16, 0.5F, 1.4F));
    private static final List<Cue> MIST = List.of(new Cue("cork", 3, 0.5F, 1.8F),
            new Cue("spray_trigger.sip", 12, 0.4F, 1.4F), new Cue("spray_trigger.spit", 32, 0.6F, 1.1F));

    private WitchSoundAssertions() {
        // 期望表独立于生产目录，避免目录时刻写错后测试跟着变绿。
    }

    static void timeline(GameTestHelper context, ServerPlayer player, String technique, long started) {
        // 连接在下一 tick 捕获声音包，允许一个 tick 的传输观察差。
        List<Cue> expected = switch (technique) {
            case "imbibe" -> IMBIBE;
            case "anoint_legs" -> ANOINT_LEGS;
            case "anoint_arms" -> ANOINT_ARMS;
            case "lob_seed" -> LOB;
            case "fling_seed" -> FAN;
            case "spray_trigger" -> MIST;
            default -> throw new IllegalArgumentException("未知测试动作：" + technique);
        };
        var played = BrewingTestPlayers.sounds(player).stream()
                .filter(sound -> sound.tick() > started)
                .filter(sound -> sound.packet().getSound().value().location().getNamespace().equals("meridian_witch"))
                .filter(sound -> atPlayer(player, sound)).toList();
        context.assertTrue(played.size() == expected.size(), "动作声数量不符：" + technique + " " + played);
        for (int index = 0; index < expected.size(); index++) {
            Cue cue = expected.get(index);
            var sound = played.get(index);
            var packet = sound.packet();
            long offset = sound.tick() - started;
            context.assertTrue(packet.getSound().value().location().getPath().equals(cue.path())
                    && offset >= cue.tick() && offset <= cue.tick() + 1
                    && packet.getSource() == SoundSource.PLAYERS
                    && Math.abs(packet.getVolume() - cue.volume()) < FLOAT_TOLERANCE
                    && Math.abs(packet.getPitch() - cue.pitch()) < FLOAT_TOLERANCE,
                    "动作声时刻或参数不符：" + cue + " 实际 tick=" + offset + " " + packet.getSound());
        }
    }

    private static boolean atPlayer(ServerPlayer player, BrewingTestPlayers.SoundPlayed sound) {
        // 邻近并发用例的声音也会传给玩家，只核对本人的位置。
        var packet = sound.packet();
        return Math.abs(packet.getX() - player.getX()) < POSITION_TOLERANCE
                && Math.abs(packet.getY() - player.getY()) < POSITION_TOLERANCE
                && Math.abs(packet.getZ() - player.getZ()) < POSITION_TOLERANCE;
    }

    static void effectAt(GameTestHelper context, ServerPlayer listener, LivingEntity target, String path) {
        // 药效声的锚点必须是中药者，而非施放者或固定世界原点。
        boolean found = BrewingTestPlayers.sounds(listener).stream().map(sound -> sound.packet())
                .anyMatch(packet -> packet.getSound().value().location().toString().equals("meridian_brewing:" + path)
                        && Math.abs(packet.getX() - target.getX()) < POSITION_TOLERANCE
                        && Math.abs(packet.getY() - target.getY()) < POSITION_TOLERANCE
                        && Math.abs(packet.getZ() - target.getZ()) < POSITION_TOLERANCE);
        context.assertTrue(found, "中药者位置没有收到药效声：" + path);
    }

    private record Cue(String path, long tick, float volume, float pitch) { }
}
