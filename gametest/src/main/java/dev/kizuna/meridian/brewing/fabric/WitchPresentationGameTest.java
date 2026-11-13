package dev.kizuna.meridian.brewing.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers.AnimationSent;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers.PotionParticleSent;
import dev.kizuna.meridian.human.server.HumanTechniqueAnimations;
import dev.kizuna.meridian.human.server.HumanTechniqueAnimations.CastHand;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewPartGroup;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhases;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.playeranimation.mobs.MobAnimationServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Witch;

/** 行药诀与熬药的动作和药色粒子：目录表现对上 PA 剪辑，起手开播、收势结束停，药用出去那一刻冒这种药的本色。 */
public final class WitchPresentationGameTest {
    private static final int TIMEOUT = 220;
    private static final String PA_MOD_ID = "kizuna_players_animation";
    private static final String CLIPS = "assets/" + PA_MOD_ID + "/animations/";
    private static final Set<String> EFFECT_EVENTS = Set.of("cast", "release");
    private static final long MILLIS_PER_SECOND = 1_000;
    private static final long MILLIS_PER_TICK = 50;
    private static final HumanTechniqueAnimations.Direction FORWARD = new HumanTechniqueAnimations.Direction(0, 0, 1);
    private static final String WITCH = "meridian_witch:";
    private static final String ANOINT = WITCH + "anoint";
    private static final String FLING = WITCH + "fling_seed";
    private static final String IMBIBE = WITCH + "imbibe";
    private static final int WITCH_TECHNIQUES = 5;
    private static final String BREW = "meridian_brewing:brew";
    private static final String BREW_CLIP = "witch_brew";
    private static final String FEED_CLIP = "witch_brew_feed_off";
    private static final String FLING_CLIP = "witch_fling_seed";
    private static final List<Boolean> PLAYED = List.of(Boolean.TRUE);
    private static final List<Boolean> NEVER_SENT = List.of();
    private static final int START_TICKS = 2;
    private static final int STOP_MARGIN_TICKS = 5;
    private static final int EFFECT_MARGIN_TICKS = 2;
    private static final int BIRTH_TICKS = 2;
    private static final String TRUE_QI = "true_qi";
    private static final BlockPos WITCH_SPOT = new BlockPos(3, 2, 1);
    private static final double NEARBY = 6;
    private static final float OPAQUE = 1;
    private static final double WAIST_TOLERANCE = 0.01;
    private static final double WAIST_HEIGHT = 0.5;

    @GameTest
    public void everyClipMatchesItsCastTimings(GameTestHelper context) throws IOException {
        // 剪辑一次性、长度等于起手加收势，效果帧落在起手结束；敷药两段都要对上，熬药整段对上 32 秒。
        ModContainer animations = FabricLoader.getInstance().getModContainer(PA_MOD_ID).orElseThrow();
        List<TechniqueDefinition> witch = definitions();
        context.assertTrue(witch.size() == WITCH_TECHNIQUES, "行药诀式数不符：" + witch.size());
        for (TechniqueDefinition definition : witch) {
            for (String clip : clips(context, definition)) {
                JsonObject animation = clip(animations, clip);
                assertLength(context, definition, clip, animation);
                long warmup = definition.getPhases().getWarmupMillis();
                OptionalLong frame = effectFrameMillis(animation);
                context.assertTrue(frame.equals(OptionalLong.of(warmup)), "效果帧对不上起手：" + clip + " " + frame
                        + " ≠ " + warmup);
            }
        }
        TechniqueDefinition brew = TechniqueFabric.service().registry().findLatest(BREW).orElseThrow();
        List<String> brewClips = clips(context, brew);
        context.assertTrue(brewClips.equals(List.of(BREW_CLIP)), "熬药式挂的剪辑不符：" + brewClips);
        assertLength(context, brew, BREW_CLIP, clip(animations, BREW_CLIP));
        JsonObject feed = clip(animations, FEED_CLIP);
        context.assertTrue("false".equals(feed.get("loop").getAsString()), "副手下料动作不该循环");
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void flingStartsItsClipAtCastAndRecoveryEndStopsIt(GameTestHelper context) {
        // 玩家放泼引：起手当即收到从起手那一 tick 播的 PLAY；收势结束前没有 STOP，结束后停下、不再重播。
        WitchBrewGameTest.ready(context, player -> {
            WitchBrewGameTest.hold(player, BrewKind.ARMOR_ROT, BrewQuality.COMMON, BrewUse.SPLASH);
            long started = WitchBrewGameTest.cast(context, player, "fling_seed");
            int castTicks = castTicks(FLING);
            context.runAfterDelay(START_TICKS, () -> {
                assertSent(context, player, FLING_CLIP, PLAYED);
                long startTick = sent(player, FLING_CLIP).getFirst().startTick();
                context.assertTrue(startTick == started, "泼引剪辑没有从起手那一 tick 播：" + startTick);
                context.runAfterDelay(castTicks - STOP_MARGIN_TICKS - START_TICKS, () -> {
                    assertSent(context, player, FLING_CLIP, PLAYED);
                    context.runAfterDelay(STOP_MARGIN_TICKS + STOP_MARGIN_TICKS, () -> {
                        BrewingTestPlayers.assertPlayedOnceThenStopped(context, player, FLING_CLIP);
                        WitchBrewGameTest.finish(context, player);
                    });
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void anointWithWorseLegsPlaysOnlyTheLegClip(GameTestHelper context) {
        // 腿伤重：起手播敷两腿，收势结束停；敷两臂那段从头到尾不播。
        anoint(context, "right_thigh", BrewPartGroup.LEGS, BrewPartGroup.ARMS);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void anointWithWorseArmsPlaysOnlyTheArmClip(GameTestHelper context) {
        // 臂伤重：起手播敷两臂，收势结束停；敷两腿那段从头到尾不播。
        anoint(context, "right_upper_arm", BrewPartGroup.ARMS, BrewPartGroup.LEGS);
    }

    private static void anoint(GameTestHelper context, String woundedPart, BrewPartGroup worse,
                               BrewPartGroup other) {
        // 敷药的部位组在起手那一刻按伤势定，动作随组走，和后面的揉搓声、药封用同一组。
        WitchBrewGameTest.ready(context, player -> {
            WitchBrewGameTest.wound(player, woundedPart);
            WitchBrewGameTest.hold(player, BrewKind.BITTER_YELLOW, BrewQuality.COMMON, BrewUse.DRINK);
            long started = WitchBrewGameTest.cast(context, player, "anoint");
            String clip = BrewCastAnimations.anointClip(worse);
            String otherClip = BrewCastAnimations.anointClip(other);
            int castTicks = castTicks(ANOINT);
            context.runAfterDelay(START_TICKS, () -> {
                assertSent(context, player, clip, PLAYED);
                long startTick = sent(player, clip).getFirst().startTick();
                context.assertTrue(startTick == started, "敷药剪辑没有从起手那一 tick 播：" + startTick);
                context.runAfterDelay(castTicks - STOP_MARGIN_TICKS - START_TICKS, () -> {
                    assertSent(context, player, clip, PLAYED);
                    context.runAfterDelay(STOP_MARGIN_TICKS + STOP_MARGIN_TICKS, () -> {
                        BrewingTestPlayers.assertPlayedOnceThenStopped(context, player, clip);
                        assertSent(context, player, otherClip, NEVER_SENT);
                        WitchBrewGameTest.finish(context, player);
                    });
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void flingSpreadsItsOwnColourInFrontWhenTheBrewLeavesTheHand(GameTestHelper context) {
        // 起手时还没有药色粒子；泼出那一刻身前铺开一片这种药本色的不透明粒子。
        WitchBrewGameTest.ready(context, player -> {
            WitchBrewGameTest.hold(player, BrewKind.INSECT_LURE, BrewQuality.COMMON, BrewUse.SPLASH);
            WitchBrewGameTest.cast(context, player, "fling_seed");
            int warmupTicks = warmupTicks(FLING);
            context.runAfterDelay(warmupTicks - EFFECT_MARGIN_TICKS, () -> {
                context.assertTrue(near(player, player, BrewKind.INSECT_LURE).isEmpty(), "还没泼出就冒了药色粒子");
                context.runAfterDelay(EFFECT_MARGIN_TICKS + EFFECT_MARGIN_TICKS, () -> {
                    List<PotionParticleSent> fan = near(player, player, BrewKind.INSECT_LURE);
                    context.assertTrue(!fan.isEmpty(), "泼出时没有药色粒子");
                    context.assertTrue(fan.stream().allMatch(sent -> sent.alpha() == OPAQUE), "泼引的粒子不该透明");
                    context.assertTrue(fan.stream().allMatch(sent -> sent.position().z > player.getZ()),
                            "泼引的粒子不在身前：" + fan);
                    WitchBrewGameTest.finish(context, player);
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void imbibeLeavesAFaintRingAtTheWaistWhenTheBrewGoesDown(GameTestHelper context) {
        // 服药咽下那一刻腰间一圈淡粒子：这种药的本色，透明度压低，高度在腰间。
        WitchBrewGameTest.ready(context, player -> {
            WitchBrewGameTest.hold(player, BrewKind.BITTER_YELLOW, BrewQuality.COMMON, BrewUse.DRINK);
            WitchBrewGameTest.cast(context, player, "imbibe");
            int warmupTicks = warmupTicks(IMBIBE);
            context.runAfterDelay(warmupTicks - EFFECT_MARGIN_TICKS, () -> {
                context.assertTrue(near(player, player, BrewKind.BITTER_YELLOW).isEmpty(), "还没咽下就冒了药色粒子");
                context.runAfterDelay(EFFECT_MARGIN_TICKS + EFFECT_MARGIN_TICKS, () -> {
                    List<PotionParticleSent> ring = near(player, player, BrewKind.BITTER_YELLOW);
                    double waist = player.getY() + player.getBbHeight() * WAIST_HEIGHT;
                    context.assertTrue(!ring.isEmpty(), "咽下时身上没有一圈药色粒子");
                    context.assertTrue(ring.stream().allMatch(sent -> sent.alpha() > 0 && sent.alpha() < OPAQUE),
                            "服药那一圈不够淡：" + ring);
                    context.assertTrue(ring.stream().allMatch(sent ->
                            Math.abs(sent.position().y - waist) < WAIST_TOLERANCE), "那一圈不在腰间：" + ring);
                    WitchBrewGameTest.finish(context, player);
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void witchFlingPlaysOnTheWitchAndShowsItsColourToNearbyPlayers(GameTestHelper context) {
        // 女巫放泼引：生物动作同步登记这一段，收势结束撤掉；旁边的玩家收到这种药本色的粒子。
        ServerPlayer viewer = BrewingTestPlayers.create(context);
        Witch witch = BrewingPlayFixGameTest.spawnWitch(context, TRUE_QI, WITCH_SPOT);
        context.runAfterDelay(BIRTH_TICKS, () -> {
            prepare(witch, BrewKind.CHARM, BrewUse.SPLASH);
            castByWitch(context, witch, FLING);
            int castTicks = castTicks(FLING);
            int warmupTicks = warmupTicks(FLING);
            context.runAfterDelay(START_TICKS, () -> {
                List<String> playing = mobClips(witch);
                context.assertTrue(playing.contains(FLING_CLIP), "女巫起手没有播泼引：" + playing);
                context.runAfterDelay(warmupTicks + EFFECT_MARGIN_TICKS - START_TICKS, () -> {
                    List<PotionParticleSent> fan = near(viewer, witch, BrewKind.CHARM);
                    context.assertTrue(!fan.isEmpty(), "女巫泼出时旁人没看到药色粒子");
                    context.runAfterDelay(castTicks + STOP_MARGIN_TICKS - warmupTicks - EFFECT_MARGIN_TICKS, () -> {
                        context.assertTrue(!mobClips(witch).contains(FLING_CLIP), "收势结束后泼引动作还挂着");
                        finish(context, viewer, witch);
                    });
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void witchAnointPlaysTheWorseGroupOnTheWitchUntilRecoveryEnds(GameTestHelper context) {
        // 女巫腿伤重：起手时生物动作里登记敷两腿，没有敷两臂；收势结束后撤掉。
        ServerPlayer viewer = BrewingTestPlayers.create(context);
        Witch witch = BrewingPlayFixGameTest.spawnWitch(context, TRUE_QI, WITCH_SPOT);
        context.runAfterDelay(BIRTH_TICKS, () -> {
            prepare(witch, BrewKind.BITTER_YELLOW, BrewUse.DRINK);
            WitchBrewGameTest.wound(witch, "right_thigh");
            castByWitch(context, witch, ANOINT);
            String legs = BrewCastAnimations.anointClip(BrewPartGroup.LEGS);
            String arms = BrewCastAnimations.anointClip(BrewPartGroup.ARMS);
            int castTicks = castTicks(ANOINT);
            context.runAfterDelay(START_TICKS, () -> {
                List<String> playing = mobClips(witch);
                context.assertTrue(playing.contains(legs) && !playing.contains(arms), "女巫敷药播错了组：" + playing);
                context.runAfterDelay(castTicks + STOP_MARGIN_TICKS - START_TICKS, () -> {
                    context.assertTrue(!mobClips(witch).contains(legs), "收势结束后敷腿动作还挂着");
                    finish(context, viewer, witch);
                });
            });
        });
    }

    private static List<String> clips(GameTestHelper context, TechniqueDefinition definition) {
        // 敷药按伤势挑两臂或两腿，两段都要对上；其余每式只挂一段，剪辑名由目录的表现 ID 解析。
        List<PresentationEffect> presentations = definition.getEffectsList().stream()
                .filter(TechniqueEffect::hasPresentation)
                .map(TechniqueEffect::getPresentation)
                .toList();
        if (definition.getId().equals(ANOINT)) {
            context.assertTrue(presentations.isEmpty(), "敷药的剪辑由炼药按伤势选组后播，目录不该再挂");
            return List.of(BrewCastAnimations.anointClip(BrewPartGroup.ARMS),
                    BrewCastAnimations.anointClip(BrewPartGroup.LEGS));
        }
        context.assertTrue(presentations.size() == 1, "这一式没有挂上剪辑：" + definition.getId());
        String clip = HumanTechniqueAnimations.resolve(presentations.getFirst().getId(), FORWARD, CastHand.MAIN);
        return List.of(clip);
    }

    private static void assertLength(GameTestHelper context, TechniqueDefinition definition, String clip,
                                     JsonObject animation) {
        // 一次性剪辑的长度等于起手、持续、收势之和。
        CastPhases phases = definition.getPhases();
        long castMillis = phases.getWarmupMillis() + phases.getActiveMillis() + phases.getRecoveryMillis();
        long clipMillis = Math.round(animation.get("animation_length").getAsDouble() * MILLIS_PER_SECOND);
        context.assertTrue("false".equals(animation.get("loop").getAsString()), "剪辑不该循环：" + clip);
        context.assertTrue(clipMillis == castMillis, "剪辑长度对不上施放：" + clip + " " + clipMillis
                + " ≠ " + castMillis);
    }

    private static OptionalLong effectFrameMillis(JsonObject animation) {
        // 时间线的键是秒数字符串、值是事件名数组；药用出去的那一帧叫 cast，掷引出手那一帧叫 release。
        JsonObject timeline = animation.getAsJsonObject("timeline");
        for (var entry : timeline.entrySet()) {
            for (var event : entry.getValue().getAsJsonArray()) {
                if (EFFECT_EVENTS.contains(event.getAsString())) {
                    return OptionalLong.of(Math.round(Double.parseDouble(entry.getKey()) * MILLIS_PER_SECOND));
                }
            }
        }
        return OptionalLong.empty();
    }

    private static JsonObject clip(ModContainer animations, String clip) throws IOException {
        // 读 PA 模组容器里打包后的剪辑；一个文件只有一段动画。
        var path = animations.findPath(CLIPS + clip + ".animation.json").orElseThrow();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject clips = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations");
            return clips.entrySet().iterator().next().getValue().getAsJsonObject();
        }
    }

    private static List<TechniqueDefinition> definitions() {
        // 生产服务里行药诀的全部功法。
        return TechniqueFabric.service().registry().definitions().stream()
                .filter(definition -> definition.getId().startsWith(WITCH))
                .toList();
    }

    private static int castTicks(String technique) {
        // 施放全长取生产注册表里的起手、持续与收势。
        CastPhases phases = TechniqueFabric.service().registry().findLatest(technique).orElseThrow().getPhases();
        long millis = phases.getWarmupMillis() + phases.getActiveMillis() + phases.getRecoveryMillis();
        return Math.toIntExact(millis / MILLIS_PER_TICK);
    }

    private static int warmupTicks(String technique) {
        // 行药诀的药都在起手结束那一刻用出去。
        CastPhases phases = TechniqueFabric.service().registry().findLatest(technique).orElseThrow().getPhases();
        return Math.toIntExact(phases.getWarmupMillis() / MILLIS_PER_TICK);
    }

    private static List<AnimationSent> sent(ServerPlayer player, String clip) {
        // 这个玩家自己收到的、关于自己的这一段的 PA 包，按发包先后排。
        return BrewingTestPlayers.animations(player).stream()
                .filter(sent -> sent.animationId().equals(clip))
                .toList();
    }

    private static void assertSent(GameTestHelper context, ServerPlayer player, String clip, List<Boolean> expected) {
        // 播放记 true、停止记 false，序列要和期望一模一样。
        List<Boolean> actions = BrewingTestPlayers.playsAndStops(player, clip);
        context.assertTrue(actions.equals(expected), clip + " 的播停序列不符：期望 " + expected + "，收到 " + actions);
    }

    private static List<PotionParticleSent> near(ServerPlayer viewer, LivingEntity caster, BrewKind kind) {
        // 别的场地也可能有人在泼药，只算施放者身边这种药颜色的粒子。
        return BrewingTestPlayers.potionParticles(viewer).stream()
                .filter(sent -> sent.rgb() == kind.colorRgb())
                .filter(sent -> sent.position().distanceTo(caster.position()) < NEARBY)
                .toList();
    }

    private static void prepare(Witch witch, BrewKind kind, BrewUse use) {
        // 真炁女巫补满真元，主手换成这一瓶；副手保持空着，敷药也放得出。
        var source = witch.level().getServer().createCommandSourceStack()
                .withLevel((ServerLevel) witch.level())
                .withPosition(witch.position())
                .withSuppressedOutput();
        witch.level().getServer().getCommands().performPrefixedCommand(source,
                "meridian qi fill @e[type=minecraft:witch,limit=1,sort=nearest]");
        witch.setItemSlot(EquipmentSlot.MAINHAND, BrewItemStacks.create(kind, BrewQuality.COMMON, use));
    }

    private static void castByWitch(GameTestHelper context, Witch witch, String technique) {
        // 女巫走同一个 TechniqueService 起手，持物、费用、效果都和玩家一样经正式入口核对。
        var definition = TechniqueFabric.service().registry().findLatest(technique).orElseThrow();
        var reference = TechniqueReference.newBuilder()
                .setId(definition.getId())
                .setVersion(definition.getVersion())
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(BrewBodyHost.inspect(witch).orElseThrow().body().getId())
                .setCastId(UUID.randomUUID().toString())
                .setTechnique(reference)
                .build();
        var outcome = TechniqueFabric.service().cast(witch.getUUID(), request);
        context.assertTrue(outcome.hasBinding(), "女巫起手失败：" + outcome);
    }

    private static List<String> mobClips(Witch witch) {
        // 生物动作同步里还登记着的播放，按剪辑名列出。
        return MobAnimationServer.snapshot(witch).stream()
                .filter(request -> request.hasPlay())
                .map(request -> request.getPlay().getAnimationId())
                .toList();
    }

    private static void finish(GameTestHelper context, ServerPlayer viewer, Witch witch) {
        // 卸掉女巫、退出旁观玩家，再完成本用例。
        witch.discard();
        BrewingTestPlayers.close(viewer);
        context.succeed();
    }
}
