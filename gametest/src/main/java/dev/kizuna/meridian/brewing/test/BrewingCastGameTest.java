package dev.kizuna.meridian.brewing.test;

import dev.kizuna.meridian.brewing.fabric.BrewCauldronBlockEntity;
import dev.kizuna.meridian.brewing.fabric.BrewFireBlockEntity;
import dev.kizuna.meridian.brewing.fabric.BrewingTechniqueContract;
import dev.kizuna.meridian.brewing.fabric.BrewingBodyGameTest;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQiTally;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueCastEnded;
import dev.kizuna.meridian.technique.server.TechniqueCastEndReason;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 学卷、正式施放、周期调度与结束通知共同驱动真实巫釜的跨仓回归。 */
public final class BrewingCastGameTest {
    private static final int TIMEOUT = 700;
    private static final int SHORT_TIMEOUT = 140;
    private static final long STARTED_TICK = 25;
    private static final long PROGRESSED_TICK = 65;
    private static final long INTERRUPTION_WAIT = 25;
    private static final long TICKER_WAIT = 5;
    private static final long BREWED_TICK = 625;
    private static final long FINISHED_TICK = 645;
    private static final double FAR_DISTANCE = 10;
    private static final long GAZE_WAIT = 70;
    private static final double GAZE_STEPS = 3;
    private static final double FIRE_FACE_HEIGHT = 0.25;
    private static final double CENTER = 0.5;
    private static final long STAGGER_MILLIS = 500;
    private static final double STAGGER_SPEED = 0.5;
    private static final String VOLUME = "meridian_brewing:brewing_basics";
    private static final int SLOWED_TIMEOUT = 1_000;
    private static final long SLOWED_FINISHED_TICK = 900;
    // 起手约一秒后开始推进，到这一刻熬了约 11 秒：引药时窗还没开，辅料时窗（8–12 秒）正开着。
    private static final long PROGRESS_OBSERVED_TICK = 245;
    private static final int MINIMUM_PROGRESS_LINES = 10;
    private static final long TICKS_PER_SECOND = 20;
    private static final String MESSAGE = "message.meridian_brewing.";
    private static final String PROGRESS_LINE_KEY = MESSAGE + "progress.remaining";
    private static final double QI_DELTA = 1e-6;
    private static final String BREW_CLIP = "witch_brew";
    private static final String FEED_CLIP = "witch_brew_feed_off";
    // 副手往釜里投一次料、给火加一次炭、拨一次灰，三下都成功。
    private static final long OFF_HAND_FEEDS = 3;

    @GameTest(maxTicks = SHORT_TIMEOUT, structure = BrewSites.LV2_SITE)
    public void realCastRejectsIncompleteStructureWithReason(GameTestHelper context) {
        // 药材齐备但釜南面那格外框换成了泥土，拒绝必须来自真实目录事件并回到施放结果。
        // 玩家站在北边看釜，换北面那格会先挡住视线，所以换背面那格。
        Fixture fixture = fixture(context);
        context.getLevel().setBlockAndUpdate(fixture.pos().south(), Blocks.DIRT.defaultBlockState());
        cast(context, fixture);
        context.runAfterDelay(STARTED_TICK, () -> {
            TechniqueCastEnded ended = ended(context, fixture, TechniqueCastEndReason.FAILED);
            context.assertTrue(ended.outcome().getFailure() == TechniqueFailure.EFFECT_REJECTED,
                    "结构不全没有返回 EFFECT_REJECTED");
            context.assertTrue(ended.outcome().getReason().contains("南 1：应为任一石头方块"),
                    "结构拒绝没有说哪一格不对：" + ended.outcome().getReason());
            context.assertTrue(fixture.cauldron().cauldron().state() == BrewCauldronState.RAW,
                    "结构拒绝仍启动了巫釜");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void learnedVolumeBrewsForThirtyOneSecondsAndCompletes(GameTestHelper context) {
        // 使用生产目录的 31 个周期事件，途中验证副手投料和调火、主手拦截。
        Fixture fixture = fixture(context);
        cast(context, fixture);
        context.runAfterDelay(STARTED_TICK, () -> {
            assertBrewing(context, fixture);
            assertClip(context, fixture.player(), BREW_CLIP, List.of(Boolean.TRUE));
            BrewingC4cGameTest.assertOffHandOperations(context, fixture.player(), fixture.cauldron(),
                    context.getLevel(), fixture.pos());
            fixture.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        });
        context.runAfterDelay(BREWED_TICK, () -> {
            // 假玩家下一 tick 开头才把收到的包记下来，副手三下叠的下料动作到这时一定都进了记录。
            long feeds = BrewingTestPlayers.playsAndStops(fixture.player(), FEED_CLIP).stream()
                    .filter(Boolean.TRUE::equals)
                    .count();
            context.assertTrue(feeds == OFF_HAND_FEEDS, "副手每成功一下该叠一段下料动作：" + feeds);
            context.assertTrue(fixture.cauldron().cauldron().state() == BrewCauldronState.BREWED,
                    "31 秒时巫釜没有熬成");
            context.assertTrue(fixture.cauldron().cauldron().brewElapsedSeconds() == BrewCauldron.BREW_SECONDS,
                    "巫釜推进次数与 30 秒规则不一致");
            context.assertTrue(fixture.ended().isEmpty(), "收势前提前结束施放");
            BrewQiTally qi = fixture.cauldron().qiTally().orElseThrow();
            context.assertTrue(Math.abs(qi.paid() - qi.total()) < QI_DELTA && qi.total() > 0,
                    "熬成时这锅的真元没有付满：" + qi);
        });
        context.runAfterDelay(FINISHED_TICK, () -> {
            ended(context, fixture, TechniqueCastEndReason.COMPLETED);
            BrewingTestPlayers.assertPlayedOnceThenStopped(context, fixture.player(), BREW_CLIP);
            Set<String> brewedKeys = new HashSet<>();
            BrewingTestPlayers.overlays(fixture.player())
                    .forEach(line -> brewedKeys.addAll(BrewingTestPlayers.translationKeys(line.text())));
            context.assertTrue(brewedKeys.contains(MESSAGE + "cauldron.brewed_qi"),
                    "熬成那一句没有带耗元总数：" + brewedKeys);
            var body = BodyProtobuf.fromMessage(BrewingTestPlayers.body(fixture.player()));
            context.assertTrue(!TechniqueState.read(body).hasActiveCast(),
                    "正常收势后仍保留活动施放");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void progressLineGoesOnlyToCasterOncePerSecond(GameTestHelper context) {
        // 动作栏进度只发给施放者本人，每推进一秒一行；整行由语言键拼成，客户端按自己的语言显示。
        Fixture fixture = fixture(context);
        ServerPlayer watcher = BrewingTestPlayers.create(context);
        cast(context, fixture);
        context.runAfterDelay(PROGRESS_OBSERVED_TICK, () -> {
            List<BrewingTestPlayers.OverlaySent> lines = progressLines(fixture.player());
            context.assertTrue(lines.size() >= MINIMUM_PROGRESS_LINES, "动作栏进度行太少：" + lines.size());
            for (int index = 1; index < lines.size(); index++) {
                long gap = lines.get(index).tick() - lines.get(index - 1).tick();
                context.assertTrue(gap == TICKS_PER_SECOND, "动作栏进度不是一秒一行，间隔 " + gap + " tick");
            }
            Set<String> keys = new HashSet<>();
            lines.forEach(line -> keys.addAll(BrewingTestPlayers.translationKeys(line.text())));
            String fireKey = MESSAGE + "fire." + BrewKind.QI_GATHER.fireKind().name().toLowerCase(Locale.ROOT);
            List<String> expectedKeys = List.of(fireKey, MESSAGE + "progress.temperature", PROGRESS_LINE_KEY,
                    MESSAGE + "progress.window.upcoming", MESSAGE + "progress.window.open", MESSAGE + "progress.qi");
            context.assertTrue(keys.containsAll(expectedKeys), "动作栏进度缺语言键：" + keys);
            context.assertTrue(progressLines(watcher).isEmpty(), "旁边的玩家也收到了熬药进度");
            BrewingTestPlayers.close(watcher);
            finish(context, fixture);
        });
    }

    private static List<BrewingTestPlayers.OverlaySent> progressLines(ServerPlayer player) {
        return BrewingTestPlayers.overlays(player).stream()
                .filter(line -> BrewingTestPlayers.translationKeys(line.text()).contains(PROGRESS_LINE_KEY))
                .toList();
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, structure = BrewSites.LV2_SITE)
    public void walkingAwayPreservesIngredientsAndSoakingButClearsFireAndTiming(GameTestHelper context) {
        // 距离变化通过后续正式周期事件拒绝，不直接调用附属处理器。
        Fixture fixture = fixture(context);
        cast(context, fixture);
        context.runAfterDelay(PROGRESSED_TICK, () -> {
            assertBrewing(context, fixture);
            BrewCauldron.Snapshot before = fixture.cauldron().cauldron().snapshot();
            fixture.player().setPos(fixture.pos().getX() + FAR_DISTANCE,
                    fixture.pos().getY() + 1, fixture.pos().getZ() + CENTER);
            context.runAfterDelay(INTERRUPTION_WAIT, () -> {
                ended(context, fixture, TechniqueCastEndReason.FAILED);
                assertInterrupted(context, fixture, before);
                finish(context, fixture);
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV1_SITE)
    public void lookingAtFireUnderCauldronKeepsBrewingButFrameInterrupts(GameTestHelper context) {
        // 一阶结构里营火东面露着一格空：低头从那格看营火仍算看着这只釜，周期事件照常推进；改看旁边的外框照旧打断。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV1);
        Fixture fixture = campfireFixture(context, site);
        cast(context, fixture);
        context.runAfterDelay(PROGRESSED_TICK, () -> {
            assertBrewing(context, fixture);
            double elapsed = fixture.cauldron().cauldron().brewElapsedSeconds();
            // 人悬在结构东面那格（NBT (4,0,2)）上方半格，离釜心约 2.8 格；眼睛低于营火上面那层台阶，视线从空格穿进去。
            BlockPos outside = site.world(4, 0, 2);
            BlockPos gap = site.world(3, 1, 2);
            ServerPlayer player = fixture.player();
            player.setPos(outside.getX() + CENTER, outside.getY() + CENTER, outside.getZ() + CENTER);
            player.lookAt(EntityAnchorArgument.Anchor.EYES,
                    new Vec3(gap.getX(), gap.getY() + FIRE_FACE_HEIGHT, gap.getZ() + CENTER));
            context.runAfterDelay(GAZE_WAIT, () -> {
                assertBrewing(context, fixture);
                context.assertTrue(fixture.ended().isEmpty(), "看釜下的火被当成视线离开：" + fixture.ended());
                double gained = fixture.cauldron().cauldron().brewElapsedSeconds() - elapsed;
                context.assertTrue(gained >= GAZE_STEPS, "看火期间熬制没有推进：" + gained);
                BrewCauldron.Snapshot before = fixture.cauldron().cauldron().snapshot();
                BlockPos frame = site.world(3, 0, 2);
                player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.upFromBottomCenterOf(frame, 1.0));
                context.runAfterDelay(INTERRUPTION_WAIT, () -> {
                    ended(context, fixture, TechniqueCastEndReason.FAILED);
                    assertInterrupted(context, fixture, before);
                    finish(context, fixture);
                });
            });
        });
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, structure = BrewSites.LV2_SITE)
    public void disconnectReleasesCauldronRestoresTickerAndUnblocksMainHand(GameTestHelper context) {
        // Fabric 下线事件调用正式生命周期入口，即使没有下一次周期事件也必须放手。
        Fixture fixture = fixture(context);
        cast(context, fixture);
        context.runAfterDelay(PROGRESSED_TICK, () -> {
            assertBrewing(context, fixture);
            BrewCauldron.Snapshot before = fixture.cauldron().cauldron().snapshot();
            ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(
                    fixture.player().connection, context.getLevel().getServer());
            ended(context, fixture, TechniqueCastEndReason.DISCONNECTED);
            assertInterrupted(context, fixture, before);
            assertMainHandReleased(context, fixture);
            double temperature = fixture.cauldron().cauldron().temperature();
            context.runAfterDelay(TICKER_WAIT, () -> {
                context.assertTrue(fixture.cauldron().cauldron().temperature() != temperature,
                        "下线后普通巫釜 ticker 没有恢复");
                finish(context, fixture);
            });
        });
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, structure = BrewSites.LV2_SITE)
    public void offHandWaterExtinguishesFireAndReturnsBucketInHand(GameTestHelper context) {
        // 生存玩家在真实熬制中使用最后一桶水，原手位应直接变为空桶。
        Fixture fixture = fixture(context);
        cast(context, fixture);
        context.runAfterDelay(STARTED_TICK, () -> {
            assertBrewing(context, fixture);
            fixture.player().setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.WATER_BUCKET));
            BrewFireBlockEntity fire = fire(fixture);
            fire.use(fixture.player(), InteractionHand.OFF_HAND);
            context.assertTrue(fire.fire().level() == 0, "水桶没有扑灭火源");
            context.assertTrue(fixture.player().getOffhandItem().is(Items.BUCKET), "副手没有返还空桶");
            context.runAfterDelay(INTERRUPTION_WAIT, () -> {
                ended(context, fixture, TechniqueCastEndReason.FAILED);
                finish(context, fixture);
            });
        });
    }

    @GameTest(maxTicks = SHORT_TIMEOUT, structure = BrewSites.LV2_SITE)
    public void staggerDuringStirringReleasesCauldron(GameTestHelper context) {
        // 搅药属于可打断起手窗口，不能错误地进入免打断的生效段。
        Fixture fixture = fixture(context);
        cast(context, fixture);
        context.runAfterDelay(PROGRESSED_TICK, () -> {
            assertBrewing(context, fixture);
            var stagger = new TechniqueService.Stagger(STAGGER_MILLIS, STAGGER_SPEED);
            TechniqueFabric.service().stagger(fixture.player().getUUID(), stagger);
            ended(context, fixture, TechniqueCastEndReason.STAGGERED);
            context.assertTrue(fixture.cauldron().cauldron().state() == BrewCauldronState.RAW,
                    "僵直后巫釜没有回到生药");
            finish(context, fixture);
        });
    }

    private static Fixture fixture(GameTestHelper context) {
        // 摆随包二阶结构、通过方块交互备料，然后学会生产目录里的熬药卷，不手工构造效果事件。
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.setGameMode(GameType.SURVIVAL);
        BrewCauldronBlockEntity cauldron = BrewingC4cGameTest.placeCauldron(context, player);
        BrewingC4cGameTest.prepareInputs(cauldron, player, context.getLevel(), cauldron.getBlockPos());
        return learnedFixture(player, cauldron);
    }

    private static Fixture campfireFixture(GameTestHelper context, BrewSites.Site site) {
        // 一阶结构：铜釜坐在 NBT 里本来点着的营火上；一桶水、主料、底料经方块交互下齐，人站在南面台阶上。
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.setGameMode(GameType.SURVIVAL);
        BlockPos stand = site.stand();
        player.setPos(stand.getX() + CENTER, stand.getY(), stand.getZ() + CENTER);
        BrewCauldronBlockEntity cauldron = (BrewCauldronBlockEntity) context.getLevel().getBlockEntity(site.cauldron());
        ItemStack primary = new ItemStack(BuiltInRegistries.ITEM.getValue(
                Identifier.parse("meridian_brewing:dried_pufferfish")));
        for (ItemStack stack : List.of(new ItemStack(Items.WATER_BUCKET), primary,
                new ItemStack(Items.GLOWSTONE_DUST))) {
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            cauldron.use(player, InteractionHand.MAIN_HAND);
        }
        return learnedFixture(player, cauldron);
    }

    private static Fixture learnedFixture(ServerPlayer player, BrewCauldronBlockEntity cauldron) {
        // 学卷、读方、备真元，再挂上本人的施放结束监听。
        TechniqueFabric.service().learn(player.getUUID(), VOLUME);
        BrewingTestPlayers.readAllRecipes(player);
        BrewingTestPlayers.stockQi(player);
        var ended = new ArrayList<TechniqueCastEnded>();
        Consumer<TechniqueCastEnded> listener = event -> {
            if (event.entityUuid().equals(player.getUUID())) {
                ended.add(event);
            }
        };
        TechniqueFabric.service().addCastEndListener(listener);
        return new Fixture(player, cauldron.getBlockPos(), cauldron, ended, listener);
    }

    @GameTest(maxTicks = SLOWED_TIMEOUT, structure = BrewSites.LV2_SITE)
    public void depletionActuallyDelaysBrewingAndCompletion(GameTestHelper context) {
        // 起手读取绑定手臂的迟滞；药劲中途消退也不改写已经冻结的施放时序。
        Fixture fixture = fixture(context);
        BrewingBodyGameTest.install(fixture.player(), BrewKind.DEPLETION,
                List.of("right_upper_arm"), BrewUse.SPLASH);
        context.runAfterDelay(STARTED_TICK, () -> cast(context, fixture));
        context.runAfterDelay(FINISHED_TICK + STARTED_TICK, () -> {
            assertBrewing(context, fixture);
            context.assertTrue(fixture.ended().isEmpty(), "迟滞熬药仍按普通时长结束");
        });
        context.runAfterDelay(SLOWED_FINISHED_TICK, () -> {
            ended(context, fixture, TechniqueCastEndReason.COMPLETED);
            context.assertTrue(fixture.cauldron().cauldron().state() == BrewCauldronState.BREWED,
                    "迟滞施放结束后巫釜没有完成");
            finish(context, fixture);
        });
    }

    private static void cast(GameTestHelper context, Fixture fixture) {
        // 绑定身体版本与正式式引用，经 TechniqueService 的公开入口起手。
        ServerPlayer player = fixture.player();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        BrewingC4cGameTest.setLookingAt(player, fixture.pos());
        var definition = TechniqueFabric.service().registry().findLatest(BrewingTechniqueContract.TECHNIQUE_ID)
                .orElseThrow();
        var body = BrewingTestPlayers.body(player);
        TechniqueReference reference = TechniqueReference.newBuilder()
                .setId(definition.getId())
                .setVersion(definition.getVersion())
                .build();
        CastRequest request = CastRequest.newBuilder()
                .setCastId("brew_" + player.getUUID())
                .setBodyId(body.getId())
                .setTechnique(reference)
                .build();
        CastOutcome outcome = TechniqueFabric.service().cast(player.getUUID(), request);
        context.assertTrue(outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                "正式起手失败：" + outcome.getReason());
    }

    private static void assertClip(GameTestHelper context, ServerPlayer player, String clip, List<Boolean> expected) {
        // 播放记 true、停止记 false：熬药那段起手就播，熬的途中不停也不重播。
        List<Boolean> actions = BrewingTestPlayers.playsAndStops(player, clip);
        context.assertTrue(actions.equals(expected), clip + " 的播停序列不符：期望 " + expected + "，收到 " + actions);
    }

    private static void assertBrewing(GameTestHelper context, Fixture fixture) {
        // 开始状态必须由调度器驱动产生。
        context.assertTrue(fixture.cauldron().cauldron().state() == BrewCauldronState.BREWING,
                "正式熬药式没有接管巫釜，结束回执：" + fixture.ended());
    }

    private static TechniqueCastEnded ended(GameTestHelper context, Fixture fixture, TechniqueCastEndReason reason) {
        // 结束身份与原因来自服务通知，不根据巫釜状态反推施放结果。
        context.assertTrue(fixture.ended().size() == 1, "施放结束通知数量错误：" + fixture.ended());
        TechniqueCastEnded event = fixture.ended().getFirst();
        context.assertTrue(event.reason() == reason, "施放结束原因不符：" + event);
        return event;
    }

    private static void assertInterrupted(GameTestHelper context, Fixture fixture, BrewCauldron.Snapshot before) {
        // 料、浸保留；火候累计和投料时间被重置，允许重新熬制。
        BrewCauldron.Snapshot after = fixture.cauldron().cauldron().snapshot();
        context.assertTrue(after.state() == BrewCauldronState.RAW, "打断没有恢复生药");
        context.assertTrue(after.ingredients().equals(before.ingredients()), "打断丢失药材");
        context.assertTrue(after.soakSeconds() >= before.soakSeconds(), "打断丢失浸泡累计");
        context.assertTrue(after.brewElapsedSeconds() == 0 && after.inBandSeconds() == 0,
                "打断没有清零熬制和火候累计");
        context.assertTrue(after.ingredientTimes().isEmpty(), "打断没有清零投料时窗记录");
    }

    private static void assertMainHandReleased(GameTestHelper context, Fixture fixture) {
        // 同一玩家身份结束后主手能够再次调火，验证 activeBrews 已移除。
        BrewFireBlockEntity fire = fire(fixture);
        int before = fire.fire().level();
        fixture.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SHOVEL));
        fire.use(fixture.player(), InteractionHand.MAIN_HAND);
        context.assertTrue(fire.fire().level() == before - 1, "结束后主手仍被熬药记录拦截");
    }

    private static BrewFireBlockEntity fire(Fixture fixture) {
        // 只查找本次目标釜下的实际火源。
        return (BrewFireBlockEntity) fixture.player().level().getBlockEntity(fixture.pos().below());
    }

    private static void finish(GameTestHelper context, Fixture fixture) {
        // 先结束服务会话再撤销宿主，生命周期回调不保留跨用例引用。
        TechniqueFabric.service().cancelOnUnload(fixture.player().getUUID());
        TechniqueFabric.service().removeCastEndListener(fixture.listener());
        BrewingTestPlayers.close(fixture.player());
        context.succeed();
    }

    private record Fixture(ServerPlayer player, BlockPos pos, BrewCauldronBlockEntity cauldron,
                           List<TechniqueCastEnded> ended, Consumer<TechniqueCastEnded> listener) { }
}
