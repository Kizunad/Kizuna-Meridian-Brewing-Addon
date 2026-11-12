package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewActionResult;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQiTally;
import dev.kizuna.meridian.brewing.server.BrewSelfQi;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.test.BrewSites;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionView;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * 熬药耗真元：每推进一秒付一锅的 1/30，先取釜所在区域的环境真元，不够的从施法者已开经脉里扣；
 * 起熬前估不够就不起熬；中途付不满记欠付，下一秒连欠的一起要，欠付超过 3 秒的份才中断。
 *
 * <p>环境速率是同一区块里所有吐纳身体和装置共用的，所以分两批跑：「环境够」单独一批，
 * 其余放进「占满」批，每个测试每 tick 先用一个大额取用把本区块这一秒的速率占满，再推进熬药。
 * 同一测试里先占满再推进，顺序固定。</p>
 */
public final class BrewingQiGameTest {
    private static final String AMBIENT_BATCH = "meridian_brewing_test:qi_ambient";
    private static final String SATURATED_BATCH = "meridian_brewing_test:qi_saturated";
    /** {@link BrewingRecipeGameTest#preparedQiGather} 摆的二阶结构里釜在场地哪一格；占速率要占釜所在的区块。 */
    private static final BlockPos CAULDRON = BrewSites.anchorInSite(BrewSites.Shipped.LV2);
    private static final int TIMEOUT = 200;
    private static final int TICKS_PER_SECOND = 20;
    private static final int AMBIENT_STEPS = 6;
    private static final int CLEAN_AMBIENT_STEPS = 4;
    private static final int SELF_STEPS = 5;
    private static final int EXHAUST_BEFORE = 2;
    /** 占位宽限 3 秒：身上剩 0.1 元时欠 0.16、0.42、0.68 都不超过 0.78，第四秒欠到 0.94 才断锅。 */
    private static final int OWING_STEPS = 3;
    private static final int OWED_SECONDS = 2;
    private static final String OWED_KEY = BrewText.MESSAGE_PREFIX + "progress.qi_owed";
    /** 清空以后吐纳每秒往经脉里回的一点（实测 0.0135）；欠付只断言到这个余量以内。 */
    private static final double BREATH_SLACK = 0.05;
    private static final double HOG_AMOUNT = 1.0e6;
    private static final double QI_DELTA = 1e-6;
    private static final double LOW_SELF = 1.0;
    private static final double DRAINED_SELF = 0.1;
    private static final double WITCH_BREW = 7.8;
    private static final String SHORT_AT_START = "真元不够起熬：这锅要 7.8 元，环境约能给 0.0，自身有 1.0，还缺 6.8";

    @GameTest(environment = AMBIENT_BATCH, maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void ambientPaysWhileRegionHasRate(GameTestHelper context) {
        // 区域还有速率：每秒那一份从区域里取，区域库存正好少这么多，施法者身上一点不动。
        // 窗尾不满一秒的那一次 Human 只按剩下的时长给，差额由自身补，所以只要求大多数秒是纯环境付。
        Brew brew = start(context, BrewingTestPlayers::stockQi);
        RegionView first = region(brew);
        double share = brew.cauldron().qiTally().orElseThrow().share();
        context.assertTrue(first.getSpareAbsorptionPerSecond() >= share,
                "测试区域的剩余速率不够一秒的份额，场地前提不成立：" + first.getSpareAbsorptionPerSecond());
        var samples = new ArrayList<Sample>();
        for (int step = 1; step <= AMBIENT_STEPS; step++) {
            context.runAfterDelay((long) step * TICKS_PER_SECOND, () -> samples.add(advance(context, brew)));
        }
        context.runAfterDelay((long) (AMBIENT_STEPS * TICKS_PER_SECOND + 1), () -> {
            long pureAmbient = samples.stream()
                    .filter(sample -> sample.self() == 0 && Math.abs(sample.ambient() - share) < QI_DELTA)
                    .count();
            context.assertTrue(pureAmbient >= CLEAN_AMBIENT_STEPS, "纯环境付的秒数太少：" + samples);
            long checked = samples.stream().filter(Sample::regionCheckable).count();
            context.assertTrue(checked >= CLEAN_AMBIENT_STEPS, "能核对区域库存的秒数太少：" + samples);
            for (Sample sample : samples) {
                context.assertTrue(Math.abs(sample.ambient() + sample.self() - share) < QI_DELTA,
                        "这一秒付的不是 1/30：" + sample);
                if (sample.regionCheckable()) {
                    context.assertTrue(Math.abs(sample.regionDrop() - sample.ambient()) < QI_DELTA,
                            "区域库存变少的量和环境付的不一样：" + sample);
                }
                context.assertTrue(Math.abs(sample.selfDrop() - sample.self()) < QI_DELTA,
                        "施法者身上少的和自身付的不一样：" + sample);
            }
            System.out.println("[熬药耗元] 环境够：" + samples);
            finish(context, brew);
        });
    }

    @GameTest(environment = SATURATED_BATCH, maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void selfPaysWhenRegionRateIsTaken(GameTestHelper context) {
        // 本区块这一秒的速率被占满：环境取到零，整份从施法者已开经脉里扣，身上正好少这么多。
        Hog hog = new Hog(context);
        var samples = new ArrayList<Sample>();
        var brew = new Brew[1];
        int[] step = {0};
        context.onEachTick(() -> {
            hog.take();
            if (step[0] < 0) {
                return;
            }
            // 断言失败时停在 -1，后面的步骤不再跑：同一 tick 里到期的事件 GameTest 会全跑完，最后一个错误才被报出来。
            int tick = step[0];
            step[0] = -1;
            if (tick == 0) {
                brew[0] = start(context, BrewingTestPlayers::stockQi);
            } else if (tick <= SELF_STEPS) {
                samples.add(advance(context, brew[0]));
            } else if (tick == 1 + SELF_STEPS) {
                double share = WITCH_BREW / BrewCauldron.BREW_SECONDS;
                for (Sample sample : samples) {
                    context.assertTrue(sample.ambient() == 0, "速率占满了环境还给了真元：" + sample);
                    context.assertTrue(Math.abs(sample.self() - share) < QI_DELTA, "自身没有付满这一秒：" + sample);
                    context.assertTrue(Math.abs(sample.selfDrop() - sample.self()) < QI_DELTA,
                            "施法者身上少的和自身付的不一样：" + sample);
                }
                BrewQiTally tally = brew[0].cauldron().qiTally().orElseThrow();
                context.assertTrue(Math.abs(tally.self() - SELF_STEPS * share) < QI_DELTA,
                        "账上自身付的总数不对：" + tally);
                System.out.println("[熬药耗元] 速率占满：" + samples);
                finish(context, brew[0]);
            }
            step[0] = tick + 1;
        });
    }

    @GameTest(environment = SATURATED_BATCH, maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void startIsRefusedWhenAmbientAndSelfAreBothShort(GameTestHelper context) {
        // 环境这三十秒给不了（速率占满），身上只有 1 元：一锅巫釜要 7.8 元，起不了熬，釜保持生药。
        Hog hog = new Hog(context);
        boolean[] done = {false};
        context.onEachTick(() -> {
            hog.take();
            if (done[0]) {
                return;
            }
            done[0] = true;
            ServerPlayer player = brewer(context);
            BrewCauldronBlockEntity cauldron = BrewingRecipeGameTest.preparedQiGather(context, player);
            BrewingTestPlayers.setQi(player, LOW_SELF);
            context.assertTrue(Math.abs(self(player) - LOW_SELF) < QI_DELTA, "自身真元没设成 1 元：" + self(player));
            BrewActionResult refused = cauldron.startBrew(player);
            context.assertFalse(refused.accepted(), "环境和自身都不够也起熬了");
            context.assertTrue(BrewTexts.chinese(refused.message()).equals(SHORT_AT_START),
                    "起熬被拒的提示不对：" + BrewTexts.chinese(refused.message()));
            context.assertTrue(cauldron.cauldron().state() == BrewCauldronState.RAW, "被拒以后釜不该进熬制");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(environment = SATURATED_BATCH, maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void owingWithinGraceKeepsBrewingButBeyondItInterrupts(GameTestHelper context) {
        // 起熬时身上灌满，熬了两秒后身上只剩 0.1 元、环境速率占满：下一秒扣光 0.1，欠 0.16，
        // 之后每秒再欠约一份（吐纳会往经脉里回一点，也是有多少扣多少）。欠付不超过 3 秒的份（0.78）时照熬，
        // 动作栏红字写真元不济；第四次付不上欠到 0.9 以上，熬制中断，生药留在釜里，本次累计清空。
        Hog hog = new Hog(context);
        var brew = new Brew[1];
        int[] step = {0};
        context.onEachTick(() -> {
            hog.take();
            if (step[0] < 0) {
                return;
            }
            // 断言失败时停在 -1，后面的步骤不再跑：同一 tick 里到期的事件 GameTest 会全跑完，最后一个错误才被报出来。
            int tick = step[0];
            step[0] = -1;
            int owing = tick - EXHAUST_BEFORE;
            if (tick == 0) {
                brew[0] = start(context, BrewingTestPlayers::stockQi);
            } else if (tick <= EXHAUST_BEFORE) {
                advance(context, brew[0]);
                context.assertFalse(brew[0].cauldron().qiTally().orElseThrow().owing(), "身上够时也记了欠付");
            } else if (owing <= OWING_STEPS) {
                if (owing == 1) {
                    context.assertFalse(overlayKeys(brew[0]).contains(OWED_KEY), "还没欠付动作栏就写了真元不济");
                    BrewingTestPlayers.setQi(brew[0].player(), DRAINED_SELF);
                }
                Sample sample = advance(context, brew[0]);
                context.assertTrue(self(brew[0].player()) < QI_DELTA, "身上有多少就该扣多少，扣完还剩：" + sample);
                context.assertTrue(Math.abs(sample.selfDrop() - sample.self()) < QI_DELTA,
                        "施法者身上少的和自身付的不一样：" + sample);
                if (owing == 1) {
                    context.assertTrue(Math.abs(sample.self() - DRAINED_SELF) < QI_DELTA, "没把剩下的 0.1 扣走：" + sample);
                }
                BrewQiTally tally = brew[0].cauldron().qiTally().orElseThrow();
                double owedAtMost = owing * tally.share() - DRAINED_SELF;
                context.assertTrue(tally.arrears() <= owedAtMost + QI_DELTA
                                && tally.arrears() > owedAtMost - BREATH_SLACK,
                        "欠付不对：" + tally.arrears() + "，应略少于 " + owedAtMost);
                context.assertFalse(tally.beyondGrace(), "欠付没超过 3 秒的份就判超了：" + tally.arrears());
            } else if (owing == OWING_STEPS + 1) {
                context.assertTrue(overlayKeys(brew[0]).contains(OWED_KEY), "欠付时动作栏没写真元不济");
                BrewActionResult interrupted = brew[0].cauldron().advanceTechniqueBrew(brew[0].player(), drawId());
                context.assertFalse(interrupted.accepted(), "欠付超过宽限还在熬");
                context.assertTrue(interrupted.message().equals(BrewText.message("technique.qi_short")),
                        "中断原因不对：" + interrupted.message());
                BrewCauldron state = brew[0].cauldron().cauldron();
                context.assertTrue(state.state() == BrewCauldronState.RAW, "中断后釜没回到生药：" + state.state());
                context.assertTrue(state.brewElapsedSeconds() == 0, "中断后熬制累计没清空");
                context.assertTrue(brew[0].cauldron().qiTally().isEmpty(), "中断后耗元账还在");
                context.assertTrue(self(brew[0].player()) < QI_DELTA, "身上的零头应在欠付那一秒扣光：" + self(brew[0].player()));
            } else {
                context.assertTrue(overlayKeys(brew[0]).contains(BrewText.MESSAGE_PREFIX + "technique.qi_short"),
                        "动作栏没有提示真元不济、熬药中断");
                BrewingTestPlayers.close(brew[0].player());
                context.succeed();
            }
            step[0] = tick + 1;
        });
    }

    @GameTest(environment = SATURATED_BATCH, maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void arrearsArePaidBackOnceQiReturns(GameTestHelper context) {
        // 环境速率占满、身上清零：两秒各欠约一份。第三秒身上灌满，这一秒连本带欠一起付，欠付清零，
        // 动作栏不再写真元不济，账上已付等于推进三秒应付的。
        Hog hog = new Hog(context);
        var brew = new Brew[1];
        int[] step = {0};
        context.onEachTick(() -> {
            hog.take();
            if (step[0] < 0) {
                return;
            }
            // 断言失败时停在 -1，后面的步骤不再跑。
            int tick = step[0];
            step[0] = -1;
            if (tick == 0) {
                brew[0] = start(context, BrewingTestPlayers::stockQi);
                BrewingTestPlayers.setQi(brew[0].player(), 0);
            } else if (tick <= OWED_SECONDS) {
                advance(context, brew[0]);
                BrewQiTally tally = brew[0].cauldron().qiTally().orElseThrow();
                context.assertTrue(tally.arrears() > tick * tally.share() - BREATH_SLACK,
                        "身上清零、环境占满时每秒该欠约一份：" + tally.arrears());
            } else if (tick == OWED_SECONDS + 1) {
                context.assertTrue(lastOverlayKeys(brew[0]).contains(OWED_KEY), "欠付时动作栏没写真元不济");
                BrewingTestPlayers.stockQi(brew[0].player());
                double owed = brew[0].cauldron().qiTally().orElseThrow().nextPayment();
                Sample sample = advance(context, brew[0]);
                BrewQiTally tally = brew[0].cauldron().qiTally().orElseThrow();
                context.assertTrue(owed > tick * tally.share() - BREATH_SLACK, "这一秒要的没有带上前面欠的：" + owed);
                context.assertTrue(Math.abs(sample.self() - owed) < QI_DELTA,
                        "真元回来的这一秒没有连欠的一起付：" + sample);
                context.assertFalse(tally.owing(), "补付以后还欠着：" + tally.arrears());
                context.assertTrue(Math.abs(tally.paid() - tally.due()) < QI_DELTA, "已付和应付对不上：" + tally);
            } else {
                context.assertFalse(lastOverlayKeys(brew[0]).contains(OWED_KEY), "付清以后动作栏还写真元不济");
                System.out.println("[熬药耗元] 欠两秒后补付：" + brew[0].cauldron().qiTally().orElseThrow());
                finish(context, brew[0]);
            }
            step[0] = tick + 1;
        });
    }

    private static List<String> overlayKeys(Brew brew) {
        // 到目前为止发给施法者的全部动作栏里用到的语言键。
        List<String> keys = new ArrayList<>();
        BrewingTestPlayers.overlays(brew.player())
                .forEach(line -> keys.addAll(BrewingTestPlayers.translationKeys(line.text())));
        return keys;
    }

    private static Set<String> lastOverlayKeys(Brew brew) {
        // 最近一行动作栏用到的语言键；还没发过就是空的。
        List<BrewingTestPlayers.OverlaySent> sent = BrewingTestPlayers.overlays(brew.player());
        if (sent.isEmpty()) {
            return Set.of();
        }
        return BrewingTestPlayers.translationKeys(sent.get(sent.size() - 1).text());
    }

    /** 每 tick 用一个大额取用把本测试所在区块这一秒的速率占满；测试结束随 onEachTick 一起停。 */
    private static final class Hog {
        private final GameTestHelper context;
        private final BlockPos pos;

        private Hog(GameTestHelper context) {
            this.context = context;
            this.pos = context.absolutePos(CAULDRON);
        }

        private void take() {
            BrewEnvironment.draw(drawId(), context.getLevel(), pos, HOG_AMOUNT);
        }
    }

    private record Brew(ServerPlayer player, BrewCauldronBlockEntity cauldron) { }

    /** 一次推进前后同一 tick 里读到的账、区域和身上的真元。 */
    private record Sample(boolean accepted, double ambient, double self, double regionDrop, long revisionStep,
                          double selfDrop) {
        boolean regionCheckable() {
            // 同一 tick 里修订号只涨 1 才是只有这次取用改过区域；续窗会多涨。
            return revisionStep == 1 || (ambient == 0 && revisionStep == 0);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "{环境 %.4f 自身 %.4f 区域少 %.4f 修订+%d 身上少 %.4f%s}",
                    ambient, self, regionDrop, revisionStep, selfDrop, accepted ? "" : " 中断");
        }
    }

    private static ServerPlayer brewer(GameTestHelper context) {
        // 生存模式、六种药方都读过的施法者；身上的真元由各测试自己备。
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.setGameMode(GameType.SURVIVAL);
        BrewingTestPlayers.readAllRecipes(player);
        return player;
    }

    private static Brew start(GameTestHelper context, Consumer<ServerPlayer> qi) {
        // 和熬药式起手同一条：先过起熬检查（含耗元预估），再由技法接管、开一本耗元账。
        ServerPlayer player = brewer(context);
        BrewCauldronBlockEntity cauldron = BrewingRecipeGameTest.preparedQiGather(context, player);
        qi.accept(player);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        BrewActionResult started = cauldron.startBrew(player);
        context.assertTrue(started.accepted(), "起熬被拒：" + BrewTexts.chinese(started.message()));
        cauldron.beginTechniqueControl(player.getUUID());
        BrewQiTally tally = cauldron.qiTally().orElseThrow();
        context.assertTrue(Math.abs(tally.total() - WITCH_BREW) < QI_DELTA, "巫釜一锅的耗元总数不对：" + tally);
        context.assertTrue(BrewKind.QI_GATHER == cauldron.cauldron().recipe().orElseThrow().kind(), "药方不对");
        return new Brew(player, cauldron);
    }

    private static Sample advance(GameTestHelper context, Brew brew) {
        // 同一 tick 里先读区域和身上，再推进一秒，再读一次，差额只来自这一次推进。
        RegionView before = region(brew);
        double selfBefore = self(brew.player());
        BrewQiTally paidBefore = brew.cauldron().qiTally().orElseThrow();
        BrewActionResult result = brew.cauldron().advanceTechniqueBrew(brew.player(), drawId());
        context.assertTrue(result.accepted(), "推进被拒：" + BrewTexts.chinese(result.message()));
        BrewQiTally paidAfter = brew.cauldron().qiTally().orElseThrow();
        RegionView after = region(brew);
        return new Sample(result.accepted(), paidAfter.ambient() - paidBefore.ambient(),
                paidAfter.self() - paidBefore.self(), total(before) - total(after),
                after.getSourceRevision() - before.getSourceRevision(), selfBefore - self(brew.player()));
    }

    private static RegionView region(Brew brew) {
        return BrewEnvironment.region(brew.cauldron().getLevel(), brew.cauldron().getBlockPos()).orElseThrow();
    }

    private static double total(RegionView view) {
        return BodyProtobuf.fromInventory(view.getInventory()).totalQi();
    }

    private static double self(ServerPlayer player) {
        return BrewSelfQi.balance(BrewSelfQi.openedLumens(BrewingTestPlayers.body(player)));
    }

    private static String drawId() {
        return "meridian_brewing_test:qi/" + UUID.randomUUID();
    }

    private static void finish(GameTestHelper context, Brew brew) {
        // 放开接管，下线，结束。
        brew.cauldron().interruptTechniqueBrew();
        BrewingTestPlayers.close(brew.player());
        context.succeed();
    }
}
