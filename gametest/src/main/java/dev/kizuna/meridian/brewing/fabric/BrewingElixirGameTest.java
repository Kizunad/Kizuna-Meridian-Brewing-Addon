package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewElixirIntake;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewMetalToxinPlan;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import dev.kizuna.meridian.core.CapabilityModifier;
import dev.kizuna.meridian.core.OrganCapabilities;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.server.BodyActivityExecution;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** 五行丹露的正式摄入、丹毒隔离、逐级副作用与生命周期回归。 */
public final class BrewingElixirGameTest {
    private static final int TIMEOUT = 600;
    private static final double TOLERANCE = 1e-6;
    private static final double FINE_QI = 1.5;
    private static final double HALF_QI = 0.75;
    private static final double LITTLE_QI = 0.5;
    private static final double SECOND_ROOM_QI = 0.25;
    private static final int TOXIN_TICKS = 6_000;
    private static final int MAX_LEVEL = 3;
    private static final int SECOND_LEVEL = 2;
    private static final int BURN_TICKS = 405;
    private static final int EFFECT_TICKS = 2;
    private static final int EXPIRING_TICKS = 3;
    private static final int EXPIRY_CHECK_TICKS = 5;
    private static final int SAVED_TOXIN_TICKS = 40;
    private static final int SAVED_EXPIRY_CHECK_TICKS = SAVED_TOXIN_TICKS + EXPIRY_CHECK_TICKS;
    private static final int MODIFIER_RETRY_TICKS = 25;
    private static final long FILLER_DURATION_MILLIS = 60_000;
    private static final int FULL_FOOD = 20;
    private static final float EXHAUSTION = 6;
    private static final int FOOD_SETTLEMENT_TICKS = 4;
    private static final int JUMP_TICKS = 20;
    private static final double HEIGHT_TOLERANCE = 0.01;
    private static final double[] INTAKE = {0.8, 0.6, 0.4};
    private static final double[] SPEED = {0.9, 0.8, 0.7};
    private static final double[] HEIGHT = {0.8, 0.6, 0.6};
    private static final int[] FOOD_LOSS = {2, 3, 3};
    private static final double[] EXHAUSTION_MULTIPLIERS = {1.5, 2.5, 2.5};
    private static final double[] GAINS = {FINE_QI, HALF_QI, HALF_QI, 0};
    private static final int[] LEVELS = {1, 2, 3, 3};
    private static final BlockPos CAULDRON = new BlockPos(1, 2, 1);
    private static final Item[] PRIMARY_ITEMS = {
        Items.BLAZE_POWDER, Items.IRON_NUGGET, Items.MOSS_BLOCK, Items.PACKED_ICE, Items.CLAY_BALL
    };

    @GameTest(maxTicks = TIMEOUT)
    public void vanillaPrimaryIngredientsChooseAllFiveElixirsWithoutPreparation(GameTestHelper context) {
        // 直接手持原版物品投真实方块，不预先补炮制组件；每行仍拒绝把丹露改成喷溅形态。
        BrewKind[] kinds = BrewKind.elixirKinds();
        for (int index = 0; index < kinds.length; index++) {
            ServerPlayer player = BrewingTestPlayers.create(context);
            context.setBlock(CAULDRON, BrewingBlocks.WITCH_CAULDRON);
            var entity = context.getBlockEntity(CAULDRON, BrewCauldronBlockEntity.class);
            useIngredient(player, entity, Items.WATER_BUCKET);
            useIngredient(player, entity, PRIMARY_ITEMS[index]);
            context.assertTrue(entity.cauldron().recipe().orElseThrow().kind() == kinds[index],
                    kinds[index] + " 没有识别未炮制的原版主料");
            useIngredient(player, entity, Items.GUNPOWDER);
            context.assertTrue(!entity.cauldron().ingredients().containsKey(IngredientSlot.PROPELLANT),
                    kinds[index] + " 收了火药");
            BrewingTestPlayers.close(player);
            context.setBlock(CAULDRON, net.minecraft.world.level.block.Blocks.AIR);
        }
        context.succeed();
    }

    private static void useIngredient(ServerPlayer player, BrewCauldronBlockEntity cauldron, Item item) {
        // 使用完整方块交互入口，保留原版物品扣除与主料识别过程。
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        cauldron.use(player, InteractionHand.MAIN_HAND);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void drinkingPutsElementQiIntoOpenMatchingMeridiansAndLeavesToxin(GameTestHelper context) {
        // 每行都从真实喝药入口进入对应标签的经，别的经不收这份真元。
        for (BrewKind kind : BrewKind.elixirKinds()) {
            ServerPlayer player = opened(context);
            double before = matchingQi(player, kind);
            drink(context, player, kind);
            closeTo(context, matchingQi(player, kind) - before, FINE_QI, kind + " 本行真元入账");
            closeTo(context, qi(player, kind, ""), matchingQi(player, kind), kind + " 真元串经");
            toxin(context, player, kind, 1);
            context.assertTrue(dev.kizuna.meridian.brewing.server.BrewBodyProgress.read(
                    BrewingTestPlayers.body(player)).getDosesCount() == 0, kind + " 留下了残药");
            BrewingTestPlayers.close(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void partialIntakeContinuesToTheNextMeridianAndWastesOverflow(GameTestHelper context) {
        // 首经只留半元、次经只留四分之一元，其余本行经填满；半瓶余量应浪费。
        for (BrewKind kind : BrewKind.elixirKinds()) {
            ServerPlayer player = opened(context);
            BodySnapshot before = leavePartialRoom(player, kind);
            List<MeridianDefinition> meridians = matchingMeridians(before, kind);
            context.assertTrue(meridians.size() >= SECOND_LEVEL, kind + " 没有两条本行经可验证顺延");
            String attribute = kind.elixirElement().orElseThrow();
            var intake = BrewElixirIntake.plan(before, attribute, FINE_QI);
            var applied = BodyActivityExecution.apply(BodyProtobuf.fromMessage(before), intake.activity(),
                    BrewBodyHost.SOURCE);
            BodySnapshot body = BodyProtobuf.toMessage(applied);
            for (int index = 0; index < meridians.size(); index++) {
                String id = meridians.get(index).getId();
                double credited = body.getMeridianStatesList().stream().filter(state -> state.getId().equals(id))
                        .flatMap(state -> state.getLumenQi().getEntriesList().stream())
                        .filter(entry -> entry.getAttribute().equals(attribute)).mapToDouble(Qi::getAmount).sum();
                closeTo(context, credited, partialRoom(index), kind + " 经 " + id + " 装填量");
            }
            closeTo(context, intake.credited(), HALF_QI, kind + " 溢出没有浪费");
            BrewingTestPlayers.close(player);
        }
        context.succeed();
    }

    private static BodySnapshot leavePartialRoom(ServerPlayer player, BrewKind kind) {
        // 从真实人体快照准备容量边界，清除在途预约并直接验证摄入活动，隔离被动扩散。
        BodySnapshot body = BrewingTestPlayers.body(player);
        List<MeridianDefinition> meridians = matchingMeridians(body, kind);
        List<MeridianState> states = body.getMeridianStatesList();
        var prepared = body.toBuilder()
                .clearTransits();
        for (int index = 0; index < meridians.size(); index++) {
            MeridianDefinition meridian = meridians.get(index);
            var state = states.stream()
                    .filter(item -> item.getId().equals(meridian.getId())).findFirst().orElseThrow();
            double amount = meridian.getLumenVolume() * QiDensity.FREE - partialRoom(index);
            var filled = state.toBuilder()
                    .setLumenQi(neutralQi(amount))
                    .build();
            prepared.setMeridianStates(states.indexOf(state), filled);
        }
        return prepared.build();
    }

    private static List<MeridianDefinition> matchingMeridians(BodySnapshot body, BrewKind kind) {
        // 与身体列表原有顺序无关，按稳定身份安排容量边界。
        String tag = "human:meridian_" + kind.elementPath();
        return body.getStructure().getMeridiansList().stream()
                .filter(meridian -> meridian.getTagsList().contains(tag))
                .sorted(Comparator.comparing(MeridianDefinition::getId)).toList();
    }

    private static double partialRoom(int index) {
        // 前两条经容量各不相同，余下经全满，可同时区分顺延与溢出。
        if (index == 0) {
            return LITTLE_QI;
        }
        if (index == 1) {
            return SECOND_ROOM_QI;
        }
        return 0;
    }

    private static QiInventory neutralQi(double amount) {
        // 填充只用游离态无属性真元，和喝下新增的本行属性分开核对。
        Qi entry = Qi.newBuilder()
                .setAttribute("meridian:none")
                .setAmount(amount)
                .setDensity(QiDensity.FREE)
                .build();
        return QiInventory.newBuilder()
                .addEntries(entry)
                .build();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void trueQiDrinkCreditsGasPhaseForEveryElement(GameTestHelper context) {
        // 用人体正式境界指令进入真炁，再从真实喝药入口逐行核对气态入账。
        ServerPlayer player = opened(context);
        var server = context.getLevel().getServer();
        var source = server.createCommandSourceStack().withEntity(player);
        server.getCommands().performPrefixedCommand(source, "meridian body realm true_qi");
        context.assertTrue(BrewingTestPlayers.body(player).getRealm() == Realm.TRUE_QI, "未进入真炁境界");
        for (BrewKind kind : BrewKind.elixirKinds()) {
            drink(context, player, kind);
            String attribute = kind.elixirElement().orElseThrow();
            List<Qi> entries = BrewingTestPlayers.body(player).getMeridianStatesList().stream()
                    .flatMap(state -> state.getLumenQi().getEntriesList().stream())
                    .filter(entry -> entry.getAttribute().equals(attribute)).toList();
            closeTo(context, entries.stream().mapToDouble(Qi::getAmount).sum(), FINE_QI, kind + " 真炁入账量");
            context.assertTrue(entries.stream().allMatch(entry -> QiDensity.phase(entry.getDensity()) == QiPhase.GAS),
                    kind + " 真炁丹露未记为气态");
        }
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void splashingElixirsDoesNotCreditQiOrApplyToxin(GameTestHelper context) {
        // 强制把丹露装进喷溅瓶再走正式落地入口，附近身体、丹毒和残药都应不变。
        ServerPlayer player = opened(context);
        BodySnapshot before = BrewingTestPlayers.body(player);
        for (BrewKind kind : BrewKind.elixirKinds()) {
            ItemStack bottle = BrewItemStacks.create(kind, BrewQuality.FINE, BrewUse.DRINK);
            ItemStack splash = bottle.transmuteCopy(Items.SPLASH_POTION);
            BrewingRuntime.splash(context.getLevel(), splash, player.position(), player);
            context.assertTrue(!player.hasEffect(BrewingEffects.status(kind)), kind + " 泼出去留下丹毒");
        }
        context.assertTrue(before.equals(BrewingTestPlayers.body(player)), "丹露喷溅改变了身体或残药");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void drinkingAgainUnderToxinHalvesThenGivesNothing(GameTestHelper context) {
        // 连喝四瓶逐次核对药效、等级及刷新时间，不用配置里的折扣来生成期望值。
        for (BrewKind kind : BrewKind.elixirKinds()) {
            ServerPlayer player = opened(context);
            for (int bottle = 0; bottle < GAINS.length; bottle++) {
                double before = matchingQi(player, kind);
                drink(context, player, kind);
                closeTo(context, matchingQi(player, kind) - before, GAINS[bottle],
                        kind + " 第 " + (bottle + 1) + " 瓶药效");
                toxin(context, player, kind, LEVELS[bottle]);
            }
            BrewingTestPlayers.close(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void drinkingAgainRefreshesOnlyItsOwnToxinClock(GameTestHelper context) {
        // 等效果实际走过时间再喝，避免同一 tick 连喝掩盖没有刷新倒计时的问题。
        ServerPlayer player = opened(context);
        for (BrewKind kind : BrewKind.elixirKinds()) {
            drink(context, player, kind);
        }
        context.runAfterDelay(EFFECT_TICKS, () -> {
            for (BrewKind kind : BrewKind.elixirKinds()) {
                var before = new EnumMap<BrewKind, Integer>(BrewKind.class);
                for (BrewKind other : BrewKind.elixirKinds()) {
                    before.put(other, player.getEffect(BrewingEffects.status(other)).getDuration());
                }
                context.assertTrue(before.get(kind) < TOXIN_TICKS, "丹毒时间没有推进：" + kind);
                drink(context, player, kind);
                toxin(context, player, kind, SECOND_LEVEL);
                for (BrewKind other : BrewKind.elixirKinds()) {
                    if (other != kind) {
                        int duration = player.getEffect(BrewingEffects.status(other)).getDuration();
                        context.assertTrue(duration == before.get(other), kind + " 刷新了 " + other + " 的计时");
                    }
                }
            }
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void drinkingReportsIntakeDiscountAndWaste(GameTestHelper context) {
        // 由正式发送的动作栏包验证入账、打折、满毒和未开经四种结果。
        ServerPlayer opened = opened(context);
        ServerPlayer closed = BrewingTestPlayers.create(context);
        for (int bottle = 0; bottle < GAINS.length; bottle++) {
            drink(context, opened, BrewKind.FIRE_ELIXIR);
        }
        drink(context, closed, BrewKind.FIRE_ELIXIR);
        context.runAfterDelay(EFFECT_TICKS, () -> {
            for (String outcome : List.of("taken", "dulled", "spent")) {
                assertMessage(context, opened, outcome);
            }
            assertMessage(context, closed, "no_room");
            BrewingTestPlayers.close(opened);
            BrewingTestPlayers.close(closed);
            context.succeed();
        });
    }

    private static void assertMessage(GameTestHelper context, ServerPlayer player, String outcome) {
        // 只检查语言键，正文由语言资源提供。
        String key = "message.meridian_brewing.elixir." + outcome;
        context.assertTrue(BrewingTestPlayers.overlays(player).stream()
                .anyMatch(sent -> BrewingTestPlayers.translationKeys(sent.text()).contains(key)), "动作栏缺少 " + key);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void withoutAnOpenMatchingMeridianTheElixirIsWastedButToxinStays(GameTestHelper context) {
        // 未开经的玩家逐行喝下，真元全浪费但各行丹毒照常保留。
        for (BrewKind kind : BrewKind.elixirKinds()) {
            ServerPlayer player = BrewingTestPlayers.create(context);
            drink(context, player, kind);
            closeTo(context, qi(player, kind, ""), 0, kind + " 未开经却得了真元");
            toxin(context, player, kind, 1);
            BrewingTestPlayers.close(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void milkDoesNotCureAnyToxinButStillCuresBrews(GameTestHelper context) {
        // 牛奶只解原有聚气汤，五行丹毒的等级、时间都保持原样。
        for (BrewKind kind : BrewKind.elixirKinds()) {
            ServerPlayer player = opened(context);
            drink(context, player, kind);
            BrewingRuntime.consume(context.getLevel(), player,
                    BrewItemStacks.create(BrewKind.QI_GATHER, BrewQuality.COMMON, BrewUse.DRINK));
            context.assertTrue(player.hasEffect(BrewingEffects.QI_GATHER), "聚气汤没有生效");
            new ItemStack(Items.MILK_BUCKET).finishUsingItem(context.getLevel(), player);
            context.assertTrue(!player.hasEffect(BrewingEffects.QI_GATHER), "牛奶没有解除聚气汤");
            toxin(context, player, kind, 1);
            BrewingTestPlayers.close(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void toxinsOfDifferentElementsDoNotReduceEachOther(GameTestHelper context) {
        // 所有有序行对都核对；包含火毒在身喝水丹露的具体场景。
        for (BrewKind first : BrewKind.elixirKinds()) {
            for (BrewKind second : BrewKind.elixirKinds()) {
                if (first != second) {
                    independent(context, first, second);
                }
            }
        }
        context.succeed();
    }

    private static void independent(GameTestHelper context, BrewKind first, BrewKind second) {
        // 第一行先喝到三级，第二行仍给足量，且不改第一行等级。
        ServerPlayer player = opened(context);
        doseToLevel(context, player, first, MAX_LEVEL);
        double before = matchingQi(player, second);
        drink(context, player, second);
        closeTo(context, matchingQi(player, second) - before, FINE_QI, first + " 影响了 " + second);
        toxin(context, player, first, MAX_LEVEL);
        toxin(context, player, second, 1);
        BrewingTestPlayers.close(player);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void fireToxinMultipliesHungerAtEveryLevel(GameTestHelper context) {
        // 同一笔六点消耗：正常扣一格；各级火毒分别扣两格、三格、三格。
        ServerPlayer plain = BrewingTestPlayers.create(context);
        Hunger baseline = hunger(plain);
        closeTo(context, baseline.multiplier(), 1, "无丹毒的消耗基线");
        closeTo(context, baseline.foodLoss(), 1, "无丹毒的饥饿基线");
        BrewingTestPlayers.close(plain);
        for (int level = 1; level <= MAX_LEVEL; level++) {
            ServerPlayer player = opened(context);
            doseToLevel(context, player, BrewKind.FIRE_ELIXIR, level);
            Hunger measured = hunger(player);
            closeTo(context, measured.multiplier(), EXHAUSTION_MULTIPLIERS[level - 1], "火毒 " + level + " 级消耗");
            closeTo(context, measured.foodLoss(), FOOD_LOSS[level - 1], "火毒 " + level + " 级饥饿");
            BrewingTestPlayers.close(player);
        }
        context.succeed();
    }

    private static Hunger hunger(ServerPlayer player) {
        // 直接推进正式食物结算，避免等待期间的走动给饥饿量混入额外来源。
        player.getFoodData().setFoodLevel(FULL_FOOD);
        player.getFoodData().setSaturation(0);
        double before = exhaustion(player);
        player.causeFoodExhaustion(EXHAUSTION);
        double multiplier = (exhaustion(player) - before) / EXHAUSTION;
        for (int tick = 0; tick < FOOD_SETTLEMENT_TICKS; tick++) {
            player.getFoodData().tick(player);
        }
        return new Hunger(multiplier, FULL_FOOD - player.getFoodData().getFoodLevel());
    }

    private static double exhaustion(ServerPlayer player) {
        // 从原版存档接口读取实际累计消耗，不以效果配置反推。
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        player.getFoodData().addAdditionalSaveData(output);
        return output.buildResult().getFloatOr("foodExhaustionLevel", 0);
    }

    private record Hunger(double multiplier, int foodLoss) { }

    @GameTest(maxTicks = TIMEOUT)
    public void onlyThirdLevelFireToxinBurnsTheChestEveryTwentySeconds(GameTestHelper context) {
        // 一、二级都不烫，三级过满二十秒才受热伤。
        List<ServerPlayer> players = new ArrayList<>();
        double[] before = new double[MAX_LEVEL];
        for (int level = 1; level <= MAX_LEVEL; level++) {
            ServerPlayer player = opened(context);
            doseToLevel(context, player, BrewKind.FIRE_ELIXIR, level);
            players.add(player);
            before[level - 1] = chest(player);
        }
        context.runAfterDelay(BURN_TICKS, () -> {
            for (int index = 0; index < players.size(); index++) {
                double lost = before[index] - chest(players.get(index));
                if (index + 1 < MAX_LEVEL) {
                    closeTo(context, lost, 0, "低级火毒造成胸口热伤");
                } else {
                    context.assertTrue(lost > 0 && lost <= 1 + TOLERANCE, "三级火毒的胸口热伤不对：" + lost);
                }
                BrewingTestPlayers.close(players.get(index));
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void metalToxinReducesLungIntakeAtEveryLevel(GameTestHelper context) {
        // 读取 Framework 真正使用且向吐纳窗口展示的器官摄入能力。
        ServerPlayer player = opened(context);
        for (int level = 1; level <= MAX_LEVEL; level++) {
            drink(context, player, BrewKind.METAL_ELIXIR);
            assertIntake(context, player, INTAKE[level - 1]);
        }
        player.removeEffect(BrewingEffects.status(BrewKind.METAL_ELIXIR));
        assertIntake(context, player, 1);
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void metalToxinUpgradeReplacesTheExistingIntakeModifier(GameTestHelper context) {
        // 原版直接升级身上的效果时没有先移除，身体修正仍应立即替换且不能叠乘。
        ServerPlayer player = opened(context);
        var toxin = BrewingEffects.status(BrewKind.METAL_ELIXIR);
        drink(context, player, BrewKind.METAL_ELIXIR);
        for (int level = SECOND_LEVEL; level <= MAX_LEVEL; level++) {
            context.assertTrue(player.addEffect(new MobEffectInstance(toxin, TOXIN_TICKS, level - 1)),
                    "金毒未原地升级到 " + level + " 级");
            assertIntake(context, player, INTAKE[level - 1]);
        }
        player.removeEffect(toxin);
        assertIntake(context, player, 1);
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void fullModifierBudgetKeepsMetalToxinWithoutInstallingItsPenalty(GameTestHelper context) {
        // 身体修正满额时仍有丹毒，立即安装和周期重试都应提示并保持已有修正。
        ServerPlayer player = opened(context);
        fillModifierBudget(player);
        var before = BrewingTestPlayers.body(player).getModifiersList();
        try {
            drink(context, player, BrewKind.METAL_ELIXIR);
        } catch (RuntimeException failure) {
            // 安装失败也断开夹具，避免留下的效果继续 tick 干扰测试服务器。
            BrewingTestPlayers.close(player);
            throw failure;
        }
        toxin(context, player, BrewKind.METAL_ELIXIR, 1);
        assertIntake(context, player, 1);
        context.runAfterDelay(MODIFIER_RETRY_TICKS, () -> {
            context.assertTrue(before.equals(BrewingTestPlayers.body(player).getModifiersList()),
                    "满额金毒改变了已有修正");
            context.assertTrue(player.hasEffect(BrewingEffects.status(BrewKind.METAL_ELIXIR)),
                    "满额拒绝身体修正时移除了金毒");
            boolean reported = BrewingTestPlayers.overlays(player).stream()
                    .anyMatch(sent -> BrewingTestPlayers.translationKeys(sent.text())
                            .contains("message.meridian_brewing.modifier_limit"));
            context.assertTrue(reported, "满额金毒没有发送修正上限提示");
            assertIntake(context, player, 1);
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    private static void fillModifierBudget(ServerPlayer player) {
        // 用合法的中性曲线占满额度，不让填充修正改变肺摄入。
        var binding = BrewBodyHost.inspect(player).orElseThrow();
        BodySnapshot body = binding.body();
        var template = BrewMetalToxinPlan.synchronize(body, FILLER_DURATION_MILLIS, 1)
                .getAddModifiers(0);
        var activity = BodyActivity.newBuilder();
        int remaining = CapabilityModifier.MAX_PER_BODY - body.getModifiersCount();
        for (int index = 0; index < remaining; index++) {
            var modifier = template.toBuilder()
                    .setId("meridian_brewing:elixir_limit_" + index)
                    .build();
            activity.addAddModifiers(modifier);
        }
        BrewBodyHost.activity(binding, activity.build());
    }

    @GameTest(maxTicks = TIMEOUT)
    public void woodToxinReducesAttackSpeedAtEveryLevel(GameTestHelper context) {
        // 原版实际攻击速度逐级降低，移除后恢复原有值。
        attributePenalty(context, BrewKind.WOOD_ELIXIR, Attributes.ATTACK_SPEED);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void waterToxinReducesMovementSpeedAtEveryLevel(GameTestHelper context) {
        // 原版实际移动速度逐级降低，移除后恢复原有值。
        attributePenalty(context, BrewKind.WATER_ELIXIR, Attributes.MOVEMENT_SPEED);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void woodToxinSurvivesReloginAndExpiresWithItsAttribute(GameTestHelper context) {
        // 攻击速度修正必须从玩家存档恢复，并随原有倒计时到期。
        savedAttributePenalty(context, BrewKind.WOOD_ELIXIR, Attributes.ATTACK_SPEED);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void waterToxinSurvivesReloginAndExpiresWithItsAttribute(GameTestHelper context) {
        // 移动速度修正必须从玩家存档恢复，并随原有倒计时到期。
        savedAttributePenalty(context, BrewKind.WATER_ELIXIR, Attributes.MOVEMENT_SPEED);
    }

    private static void savedAttributePenalty(GameTestHelper context, BrewKind kind, Holder<Attribute> attribute) {
        // 重登以后不重新挂效果，直接量存档恢复的属性与自然到期后的基线。
        ServerPlayer previous = opened(context);
        double baseline = previous.getAttributeValue(attribute);
        ServerPlayer restored = rejoinWithToxin(context, previous, kind);
        closeTo(context, restored.getAttributeValue(attribute) / baseline, SPEED[MAX_LEVEL - 1],
                kind + " 重登后属性修正丢失");
        context.runAfterDelay(SAVED_EXPIRY_CHECK_TICKS, () -> {
            context.assertTrue(!restored.hasEffect(BrewingEffects.status(kind)), kind + " 重登后没有自然到期");
            closeTo(context, restored.getAttributeValue(attribute), baseline, kind + " 到期后属性未恢复");
            BrewingTestPlayers.close(restored);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void fireToxinSurvivesReloginWithHungerAndChestBurn(GameTestHelper context) {
        // 保存的三级火毒继续放大饥饿、按剩余时间灼伤胸口，到期后消耗恢复。
        ServerPlayer previous = opened(context);
        double before = chest(previous);
        ServerPlayer restored = rejoinWithToxin(context, previous, BrewKind.FIRE_ELIXIR);
        closeTo(context, hunger(restored).multiplier(), EXHAUSTION_MULTIPLIERS[MAX_LEVEL - 1], "重登火毒消耗");
        context.runAfterDelay(SAVED_EXPIRY_CHECK_TICKS, () -> {
            double lost = before - chest(restored);
            context.assertTrue(lost > 0 && lost <= 1 + TOLERANCE, "重登火毒未按剩余时间灼伤胸口：" + lost);
            context.assertTrue(!restored.hasEffect(BrewingEffects.status(BrewKind.FIRE_ELIXIR)), "重登火毒未到期");
            closeTo(context, hunger(restored).multiplier(), 1, "火毒到期后消耗未恢复");
            BrewingTestPlayers.close(restored);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void earthToxinSurvivesReloginWithJumpAndSprintPenalty(GameTestHelper context) {
        // 重登后仍按三级土毒限制实际跳高与疾跑，到期后两项都恢复。
        ServerPlayer previous = opened(context);
        double baseline = jumpHeight(previous);
        ServerPlayer restored = rejoinWithToxin(context, previous, BrewKind.EARTH_ELIXIR);
        double ratio = jumpHeight(restored) / baseline;
        context.assertTrue(Math.abs(ratio - HEIGHT[MAX_LEVEL - 1]) < HEIGHT_TOLERANCE, "重登土毒跳高：" + ratio);
        restored.setSprinting(true /* sprinting */);
        context.assertTrue(!restored.isSprinting(), "重登后三级土毒仍能疾跑");
        context.runAfterDelay(SAVED_EXPIRY_CHECK_TICKS, () -> {
            context.assertTrue(!restored.hasEffect(BrewingEffects.status(BrewKind.EARTH_ELIXIR)), "重登土毒未到期");
            closeTo(context, jumpHeight(restored), baseline, "土毒到期后跳高未恢复");
            restored.setSprinting(true /* sprinting */);
            context.assertTrue(restored.isSprinting(), "土毒到期后仍不能疾跑");
            BrewingTestPlayers.close(restored);
            context.succeed();
        });
    }

    private static ServerPlayer rejoinWithToxin(GameTestHelper context, ServerPlayer previous, BrewKind kind) {
        // 短倒计时在断线前写入，读档后保持原效果，不能靠重新添加掩盖丢失的属性。
        var toxin = BrewingEffects.status(kind);
        previous.addEffect(new MobEffectInstance(toxin, SAVED_TOXIN_TICKS, MAX_LEVEL - 1));
        BrewingTestPlayers.close(previous);
        ServerPlayer restored = BrewingTestPlayers.rejoin(context, previous);
        var saved = restored.getEffect(toxin);
        context.assertTrue(saved != null, kind + " 重登没有恢复丹毒");
        int duration = saved.getDuration();
        context.assertTrue(saved.getAmplifier() + 1 == MAX_LEVEL && duration > 0 && duration <= SAVED_TOXIN_TICKS,
                kind + " 重登没有恢复丹毒等级与剩余时间");
        return restored;
    }

    private static void attributePenalty(GameTestHelper context, BrewKind kind, Holder<Attribute> attribute) {
        // 共用属性断言，期望值独立于丹毒配置读取。
        ServerPlayer player = opened(context);
        double before = player.getAttributeValue(attribute);
        for (int level = 1; level <= MAX_LEVEL; level++) {
            drink(context, player, kind);
            closeTo(context, player.getAttributeValue(attribute) / before, SPEED[level - 1],
                    kind + " " + level + " 级属性");
        }
        player.removeEffect(BrewingEffects.status(kind));
        closeTo(context, player.getAttributeValue(attribute), before, kind + " 移除后未恢复");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void earthToxinReducesJumpHeightAndBlocksSprintAtThirdLevel(GameTestHelper context) {
        // 实际推进原版跳跃运动测顶点，一级八成、二三级六成；仅三级禁止疾跑。
        ServerPlayer player = opened(context);
        double baseline = jumpHeight(player);
        for (int level = 1; level <= MAX_LEVEL; level++) {
            drink(context, player, BrewKind.EARTH_ELIXIR);
            double ratio = jumpHeight(player) / baseline;
            context.assertTrue(Math.abs(ratio - HEIGHT[level - 1]) < HEIGHT_TOLERANCE,
                    "土毒 " + level + " 级跳高比例：" + ratio);
            player.setSprinting(true /* sprinting */);
            context.assertTrue(player.isSprinting() == (level < MAX_LEVEL), "土毒疾跑门禁等级不对：" + level);
            player.setSprinting(false /* sprinting */);
        }
        player.removeEffect(BrewingEffects.status(BrewKind.EARTH_ELIXIR));
        closeTo(context, jumpHeight(player), baseline, "土毒移除后跳高未恢复");
        player.setSprinting(true /* sprinting */);
        context.assertTrue(player.isSprinting(), "土毒移除后仍不能疾跑");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    private static double jumpHeight(ServerPlayer player) {
        // 假连接没有客户端移动包，主动推进相同的原版 travel 路径测量实际位移。
        Vec3 origin = player.position();
        player.setNoGravity(false /* noGravity */);
        player.setDeltaMovement(Vec3.ZERO);
        player.setOnGround(true /* onGround */);
        player.jumpFromGround();
        double peak = origin.y;
        for (int tick = 0; tick < JUMP_TICKS; tick++) {
            player.travel(Vec3.ZERO);
            peak = Math.max(peak, player.getY());
        }
        player.setPos(origin);
        player.setDeltaMovement(Vec3.ZERO);
        player.setNoGravity(true /* noGravity */);
        return peak - origin.y;
    }

    @GameTest(maxTicks = TIMEOUT)
    public void metalToxinExpiresAndResumesFromSavedEffect(GameTestHelper context) {
        // 重登从保存的效果恢复身体修正，短效到期后能力恢复。
        ServerPlayer previous = opened(context);
        drink(context, previous, BrewKind.METAL_ELIXIR);
        BrewingTestPlayers.close(previous);
        ServerPlayer player = BrewingTestPlayers.rejoin(context, previous);
        context.runAfterDelay(EFFECT_TICKS, () -> {
            assertIntake(context, player, INTAKE[0]);
            var toxin = BrewingEffects.status(BrewKind.METAL_ELIXIR);
            player.removeEffect(toxin);
            player.addEffect(new MobEffectInstance(toxin, EXPIRING_TICKS));
            assertIntake(context, player, INTAKE[0]);
            context.runAfterDelay(EXPIRY_CHECK_TICKS, () -> {
                context.assertTrue(!player.hasEffect(toxin), "短效丹毒没有自然到期");
                assertIntake(context, player, 1);
                BrewingTestPlayers.close(player);
                context.succeed();
            });
        });
    }

    private static void assertIntake(GameTestHelper context, ServerPlayer player, double expected) {
        // 由器官能力计算入口核对每一个肺，不能仅检查修正对象是否存在。
        var body = BodyProtobuf.fromMessage(BrewingTestPlayers.body(player));
        for (var organ : body.structure().organs()) {
            if (organ.kind().value().equals("human:lung")) {
                closeTo(context, OrganCapabilities.intakeMultiplier(body, organ.id()), expected, "金毒摄入能力");
            }
        }
    }

    private static ServerPlayer opened(GameTestHelper context) {
        // 开全脉并留下少量真元，为五行都留出容量。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewingTestPlayers.setQi(player, LITTLE_QI);
        return player;
    }

    private static void doseToLevel(GameTestHelper context, ServerPlayer player, BrewKind kind, int level) {
        // 等级只由真实喝下的瓶数获得。
        for (int bottle = 0; bottle < level; bottle++) {
            drink(context, player, kind);
        }
    }

    private static void drink(GameTestHelper context, ServerPlayer player, BrewKind kind) {
        // 走正式喝药入口，同时覆盖药水注册及身体宿主调用。
        BrewingRuntime.consume(context.getLevel(), player,
                BrewItemStacks.create(kind, BrewQuality.FINE, BrewUse.DRINK));
    }

    private static double matchingQi(ServerPlayer player, BrewKind kind) {
        // 只统计对应五行标签的经。
        return qi(player, kind, "human:meridian_" + kind.elementPath());
    }

    private static double qi(ServerPlayer player, BrewKind kind, String tag) {
        // 空标签统计全身，以检查本行真元有没有落进别行经脉。
        BodySnapshot body = BrewingTestPlayers.body(player);
        String attribute = kind.elixirElement().orElseThrow();
        double total = 0;
        for (int index = 0; index < body.getMeridianStatesCount(); index++) {
            if (tag.isEmpty() || body.getStructure().getMeridians(index).getTagsList().contains(tag)) {
                total += body.getMeridianStates(index).getLumenQi().getEntriesList().stream()
                        .filter(entry -> entry.getAttribute().equals(attribute))
                        .mapToDouble(entry -> entry.getAmount()).sum();
            }
        }
        return total;
    }

    private static void toxin(GameTestHelper context, ServerPlayer player, BrewKind kind, int level) {
        // 每次喝后都必须重置为完整五分钟。
        MobEffectInstance effect = player.getEffect(BrewingEffects.status(kind));
        context.assertTrue(effect != null && effect.getAmplifier() + 1 == level
                && effect.getDuration() == TOXIN_TICKS, kind + " 丹毒等级或时间不对：" + effect);
        context.assertTrue(!effect.isVisible() && effect.showIcon(), kind + " 丹毒应保留图标且不出粒子");
    }

    private static double chest(ServerPlayer player) {
        // 胸口血量来自唯一身体账本。
        return BrewingTestPlayers.body(player).getPartStatesList().stream()
                .filter(part -> part.getId().equals("chest")).findFirst().orElseThrow().getHp();
    }

    private static void closeTo(GameTestHelper context, double actual, double expected, String message) {
        // 统一浮点容差，断言失败时同时显示期望值与实际值。
        context.assertTrue(Math.abs(actual - expected) < TOLERANCE,
                message + "：期望 " + expected + "，实际 " + actual);
    }
}
