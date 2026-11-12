package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewActionResult;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewStructure;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.BrewTowerController;
import dev.kizuna.meridian.brewing.server.FireKind;
import dev.kizuna.meridian.brewing.server.FrameClass;
import dev.kizuna.meridian.brewing.server.HeatSourceKind;
import dev.kizuna.meridian.brewing.server.StructureMatch;
import dev.kizuna.meridian.brewing.test.BrewSites;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 随包熬药结构的识别：SPEC E1 第 1–9 条（按 PLAN 定案改），外加 P1（煤对着釜用，转给釜下的火）
 * 和 P2（定义里点名的外框格可以换成漏斗、投掷器）。三阶药池的几条在塔那一段补。
 *
 * <p>结构都用 JAR 里的 NBT 摆，摆法和玩家用 {@code /place template} 一样；比对走方块实体同一个入口。</p>
 */
public final class BrewingStructureGameTest {
    private static final int TIMEOUT = 100;
    private static final int OBSERVE_TICKS = 2;
    private static final int COAL = 3;
    private static final int FILLING_BUCKETS = 2;
    // 铜釜（未氧化）每秒收敛 12%，40 秒从 0 升到 98° 上下。
    private static final int HEAT_TICKS = 800;
    // 旧灶缺灶石封顶 70°，再加 3° 火候波动也到不了这里。
    private static final double PAST_OLD_CAP = 95.0;
    private static final double STAND_CENTER = 0.5;
    private static final String FRAME_TOLERANCE_BATCH = "meridian_brewing_test:frame_tolerance";
    private static final String TOLERANT_CONFIG = "meridian-brewing-frame-tolerance-test.properties";
    private static final String BREWING_CONFIG = "meridian-brewing.properties";
    private static final List<String> SHIPPED_DEFINITIONS = List.of("meridian_brewing:potion-lv1@1",
            "meridian_brewing:potion-lv2@2", "meridian_brewing:potion-lv3-auto@3");
    private static final Rotation[] ROTATIONS = {
        Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90
    };

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV1_SITE)
    public void copperCauldronStartsOnShippedLv1(GameTestHelper context) {
        // E1 #1、#9：一阶结构照 NBT 摆好，营火本来点着，下水、下料、读过药方就能起锅；不用盆栽、灶石。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV1);
        ServerPlayer player = standingPlayer(context, site);
        BrewCauldronBlockEntity cauldron = cauldron(context, site);
        use(cauldron, player, new ItemStack(Items.WATER_BUCKET));
        use(cauldron, player, item("meridian_brewing:dried_pufferfish"));
        use(cauldron, player, new ItemStack(Items.GLOWSTONE_DUST));
        BrewingTestPlayers.readRecipes(player, BrewKind.QI_GATHER);
        BrewingTestPlayers.stockQi(player);
        BrewActionResult started = cauldron.startBrew(player);
        context.assertTrue(started.accepted(), "一阶结构起不了锅：" + BrewTexts.chinese(started.message()));
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void coalUsedOnWitchCauldronFeedsBuriedStoveAndLv2Starts(GameTestHelper context) {
        // E1 #1 和 P1：铁炉座四面埋在砖里够不着，煤对着巫釜用就转给釜下的炉座，加了炭就能起锅。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV2);
        ServerPlayer player = standingPlayer(context, site);
        BrewCauldronBlockEntity cauldron = cauldron(context, site);
        BrewFireBlockEntity stove = (BrewFireBlockEntity) context.getLevel().getBlockEntity(site.fire());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, COAL));
        for (int lump = 0; lump < COAL; lump++) {
            cauldron.use(player, InteractionHand.MAIN_HAND);
        }
        context.assertTrue(player.getMainHandItem().isEmpty(),
                "煤没有全转给炉座，手里还剩 " + player.getMainHandItem().getCount());
        context.assertTrue(cauldron.cauldron().ingredients().isEmpty(), "煤不该当成药料下进釜里");
        context.runAfterDelay(OBSERVE_TICKS, () -> {
            context.assertTrue(stove.heatSource() != HeatSourceKind.NONE, "炉座加了煤还没起火：" + stove.fire());
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET, FILLING_BUCKETS));
            for (int bucket = 0; bucket < FILLING_BUCKETS; bucket++) {
                cauldron.use(player, InteractionHand.MAIN_HAND);
            }
            use(cauldron, player, item("meridian_brewing:dried_pufferfish"));
            use(cauldron, player, new ItemStack(Items.GLOWSTONE_DUST));
            BrewingTestPlayers.readRecipes(player, BrewKind.QI_GATHER);
            BrewingTestPlayers.stockQi(player);
            BrewActionResult started = cauldron.startBrew(player);
            context.assertTrue(started.accepted(), "二阶结构起不了锅：" + BrewTexts.chinese(started.message()));
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV1_SITE)
    public void lv1MatchesInAllFourRotations(GameTestHelper context) {
        // E1 #2：一阶四个朝向。
        allRotationsMatch(context, BrewSites.Shipped.LV1);
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void lv2MatchesInAllFourRotations(GameTestHelper context) {
        // E1 #2：二阶四个朝向。
        allRotationsMatch(context, BrewSites.Shipped.LV2);
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV1_SITE)
    public void lv1FrameSwappedWithinClassStillMatches(GameTestHelper context) {
        // E1 #3：一阶的石头格（台阶、墙、半砖都算）全换成整块圆石，铁链换铜链，照样认。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV1);
        Swapped swapped = swapFrames(context, site);
        context.assertTrue(swapped.stone() > 0 && swapped.chains() == 2, "一阶外框没换到预期的格子：" + swapped);
        StructureMatch match = result(context, site).match();
        context.assertTrue(match.isValid(), "一阶换了同类外框材料就不认了：" + describe(match));
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void lv2FrameSwappedWithinClassStillMatches(GameTestHelper context) {
        // E1 #3：二阶的磨制黑石砖、楼梯、台阶、墙全换成整块圆石，照样认。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV2);
        Swapped swapped = swapFrames(context, site);
        context.assertTrue(swapped.stone() > 0, "二阶外框没换到石头格：" + swapped);
        StructureMatch match = result(context, site).match();
        context.assertTrue(match.isValid(), "二阶换了同类外框材料就不认了：" + describe(match));
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void lv2WithoutStoveReportsIronStoveBelowCauldron(GameTestHelper context) {
        // E1 #4：拿掉铁炉座，第一处不符就是釜正下方那格。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV2);
        context.getLevel().setBlock(site.fire(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        assertMismatch(context, result(context, site), "巫釜下 1：应为铁炉座", site.fire());
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV1_SITE)
    public void lv1BlockedAboveCauldronReportsMustBeEmpty(GameTestHelper context) {
        // E1 #5：铜釜正上方 (2,3,2) 必须空，放块石头就不认。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV1);
        BlockPos above = site.cauldron().above();
        context.getLevel().setBlock(above, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        assertMismatch(context, result(context, site), "铜釜上 1：应为空着", above);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV1_SITE)
    public void lv1StoneFrameCellAsPlanksReportsAnyStone(GameTestHelper context) {
        // E1 #6：一格石墙换成木板，木头不是石头类，不认。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV1);
        BlockPos corner = site.world(1, 1, 1);
        context.getLevel().setBlock(corner, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        assertMismatch(context, result(context, site), "铜釜北 1、西 1、下 1：应为任一石头方块", corner);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void copperCauldronInLv2IsNotRecognized(GameTestHelper context) {
        // E1 #7：铜釜只配一阶，放进二阶的位置不认。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV2);
        context.getLevel().setBlock(site.cauldron(), BrewingBlocks.COPPER_CAULDRON.defaultBlockState(),
                Block.UPDATE_ALL);
        StructureMatch match = result(context, site).match();
        context.assertTrue(match.status() == StructureMatch.Status.MISMATCH, "铜釜放进二阶被认了：" + describe(match));
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV1_SITE)
    public void witchCauldronInLv1IsNotRecognized(GameTestHelper context) {
        // E1 #7：巫釜只配二阶，放进一阶的位置不认。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV1);
        context.getLevel().setBlock(site.cauldron(), BrewingBlocks.WITCH_CAULDRON.defaultBlockState(),
                Block.UPDATE_ALL);
        StructureMatch match = result(context, site).match();
        context.assertTrue(match.status() == StructureMatch.Status.MISMATCH, "巫釜放进一阶被认了：" + describe(match));
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV1_SITE)
    public void lv1SoulCampfireGivesSoulFireButIronBrazierReportsCampfire(GameTestHelper context) {
        // E1 #8（D1）：一阶只认营火，灵魂营火也是营火，得阴火；换成铁火盆就报应为营火。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV1);
        ServerLevel level = context.getLevel();
        level.setBlock(site.fire(), Blocks.SOUL_CAMPFIRE.defaultBlockState(), Block.UPDATE_ALL);
        BrewCauldronBlockEntity cauldron = cauldron(context, site);
        context.runAfterDelay(OBSERVE_TICKS, () -> {
            BrewStructure structure = cauldron.structure();
            context.assertTrue(structure.match().isValid(), "灵魂营火的一阶没认出来：" + describe(structure.match()));
            context.assertTrue(structure.heatSource() == HeatSourceKind.SOUL_CAMPFIRE
                            && structure.heatSource().fireKind() == FireKind.SOUL_FIRE,
                    "灵魂营火没给阴火：" + structure.heatSource());
            level.setBlock(site.fire(), BrewingBlocks.IRON_BRAZIER.defaultBlockState(), Block.UPDATE_ALL);
            BrewStructureMatcher.Result brazier = result(context, site);
            StructureMatch.Mismatch mismatch = brazier.match().mismatch().orElseThrow(
                    () -> new IllegalStateException("铁火盆的一阶被认了：" + describe(brazier.match())));
            // 服务端没有原版方块的中文名，所以比提示的结构：应为“营火”这个方块、在釜正下方一格。
            BrewText.BlockName campfireBlock = new BrewText.BlockName("minecraft:campfire");
            BrewText campfire = BrewText.message("structure.expect.block", campfireBlock);
            context.assertTrue(mismatch.expected().equals(campfire), "铁火盆的一阶没报应为营火：" + mismatch);
            context.assertTrue(mismatch.east() == 0 && mismatch.up() == -1 && mismatch.south() == 0,
                    "应为营火报在了别处：" + mismatch);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void lv2CampfireReportsIronStove(GameTestHelper context) {
        // E1 #8：二阶只认铁炉座，放营火报应为铁炉座。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV2);
        context.getLevel().setBlock(site.fire(), Blocks.CAMPFIRE.defaultBlockState(), Block.UPDATE_ALL);
        assertMismatch(context, result(context, site), "巫釜下 1：应为铁炉座", site.fire());
        context.succeed();
    }

    @GameTest(maxTicks = HEAT_TICKS + TIMEOUT, structure = BrewSites.LV1_SITE)
    public void lv1HeatsPastOldHearthCapWithoutPotsOrHearthStones(GameTestHelper context) {
        // E1 #9：旧灶缺灶石封顶 70°；现在结构对、营火点着，铜釜一路升到 100° 附近。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV1);
        BrewCauldronBlockEntity cauldron = cauldron(context, site);
        context.runAfterDelay(HEAT_TICKS, () -> {
            BrewStructure structure = cauldron.structure();
            double temperature = cauldron.cauldron().temperature();
            context.assertTrue(structure.match().isValid() && structure.heatSource() == HeatSourceKind.SUN_CAMPFIRE,
                    "一阶结构或营火没认出来：" + structure);
            context.assertTrue(structure.maximumTemperature() == BrewStructure.LIT_MAXIMUM,
                    "结构有效时温度上限不是 100°：" + structure.maximumTemperature());
            context.assertTrue(temperature >= PAST_OLD_CAP, "铜釜 40 秒还没升过旧灶的封顶：" + temperature);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void automationBlocksFitListedFrameCellsOnly(GameTestHelper context) {
        // P2：定义里点名的格（釜四周的台阶、炉座四周的砖）可换成漏斗、投掷器；没点名的外框格不行。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV2);
        ServerLevel level = context.getLevel();
        level.setBlock(site.world(5, 1, 4), Blocks.HOPPER.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(site.world(6, 1, 5), Blocks.DROPPER.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(site.world(5, 0, 6), Blocks.HOPPER.defaultBlockState(), Block.UPDATE_ALL);
        StructureMatch listed = result(context, site).match();
        context.assertTrue(listed.isValid(), "点名的格换成漏斗、投掷器就不认了：" + describe(listed));
        BlockPos pillar = site.world(3, 1, 3);
        level.setBlock(pillar, Blocks.HOPPER.defaultBlockState(), Block.UPDATE_ALL);
        assertMismatch(context, result(context, site), "巫釜北 2、西 2：应为任一石头方块", pillar);
        context.succeed();
    }

    @GameTest(environment = FRAME_TOLERANCE_BATCH, maxTicks = TIMEOUT, structure = BrewSites.LV1_SITE)
    public void frameToleranceOneForgivesAFrameCellButNotACore(GameTestHelper context) {
        // 复审第 2 条：外框容差设成 1 时，一阶少一格石墙照样认；少了营火（核心格）还是不认。
        // 容差是全局配置，所以这条单独一批跑，同一 tick 里改完就换回正式配置。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV1);
        ServerLevel level = context.getLevel();
        Path tolerant = FabricLoader.getInstance().getGameDir().resolve(TOLERANT_CONFIG);
        try {
            Files.writeString(tolerant, "structure.frame_tolerance=1\n");
            BrewStructureMatcher.initialize(tolerant);
            level.setBlock(site.world(1, 1, 1), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            StructureMatch forgiven = result(context, site).match();
            context.assertTrue(forgiven.isValid(), "容差 1 时少一格外框就不认了：" + describe(forgiven));
            level.setBlock(site.fire(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            // 服务端没有方块的中文名，具体方块写成 ID。
            assertMismatch(context, result(context, site), "铜釜下 1：应为minecraft:campfire", site.fire());
        } catch (IOException unwritable) {
            throw new IllegalStateException("测试配置写不了：" + tolerant, unwritable);
        } finally {
            BrewStructureMatcher.initialize(FabricLoader.getInstance().getConfigDir().resolve(BREWING_CONFIG));
        }
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV1_SITE)
    public void brokenDefinitionsAreRejectedAtLoad(GameTestHelper context) {
        // 复审第 6 条：测试数据包里放了三份坏定义——没有铜箱子、木桶的三阶，只有 3 格燃料的三阶，
        // 燃料格落在 NBT 外的一阶。读的时候各自打错误日志跳过，剩下的只有随包的三份。
        List<BrewStructureDefinitions.Definition> loaded = BrewStructureDefinitions.all(context.getLevel().getServer());
        List<String> ids = loaded.stream().map(definition -> definition.id() + "@" + definition.tier()).toList();
        context.assertTrue(ids.equals(SHIPPED_DEFINITIONS), "读到的定义不对：" + ids);
        BrewStructureDefinitions.Definition tower = loaded.getLast();
        context.assertTrue(tower.fuel().size() == BrewTowerController.FUEL_CELLS, "三阶的燃料格不是 4 格：" + tower.fuel());
        context.succeed();
    }

    private static void allRotationsMatch(GameTestHelper context, BrewSites.Shipped shipped) {
        // 同一块场地依次摆四个朝向：底面是方的，转过去占的还是这片格子，后摆的整块盖掉先摆的。
        for (Rotation rotation : ROTATIONS) {
            BrewSites.Site site = BrewSites.place(context, shipped, rotation);
            StructureMatch match = result(context, site).match();
            context.assertTrue(match.isValid(), shipped + " 转 " + rotation + " 没认出来：" + describe(match));
        }
        context.succeed();
    }

    private static Swapped swapFrames(GameTestHelper context, BrewSites.Site site) {
        // 只换结构范围内属于石头类、铁链的格；核心（釜、火）和空气不动。
        ServerLevel level = context.getLevel();
        BlockState copperChain = block("minecraft:copper_chain").defaultBlockState();
        int stone = 0;
        int chains = 0;
        for (BlockPos pos : site.cells()) {
            BlockState state = level.getBlockState(pos);
            if (state.is(BrewingTags.FRAMES.get(FrameClass.STONE))) {
                level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
                stone++;
            } else if (state.is(BlockTags.CHAINS)) {
                level.setBlock(pos, copperChain, Block.UPDATE_ALL);
                chains++;
            }
        }
        return new Swapped(stone, chains);
    }

    private static BrewStructureMatcher.Result result(GameTestHelper context, BrewSites.Site site) {
        // 和方块实体刷新结构用的是同一个入口：按这只釜的种类找定义，四个朝向都试。
        ServerLevel level = context.getLevel();
        Block cauldron = level.getBlockState(site.cauldron()).getBlock();
        return BrewStructureMatcher.matchCauldron(level, site.cauldron(), cauldron);
    }

    private static void assertMismatch(GameTestHelper context, BrewStructureMatcher.Result result, String expected,
                                       BlockPos marker) {
        // 提示文字按中文比；标记粒子要落在不符的那一格。
        StructureMatch match = result.match();
        context.assertTrue(match.status() == StructureMatch.Status.MISMATCH, "结构该不符却：" + describe(match));
        String text = describe(match);
        context.assertTrue(text.contains(expected), "不符提示不对，应含「" + expected + "」：" + text);
        BlockPos shown = result.marker().orElseThrow().pos();
        context.assertTrue(shown.equals(marker), "标记没落在不符那格：" + shown + "，应为 " + marker);
    }

    private static String describe(StructureMatch match) {
        // 有不符就给中文提示，没有就给状态名。
        return match.problem().map(BrewTexts::chinese).orElse(match.status().name());
    }

    private static ServerPlayer standingPlayer(GameTestHelper context, BrewSites.Site site) {
        // 站在结构里那格台子上，离釜不到 3 格，起锅的距离检查能过。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BlockPos stand = site.stand();
        player.setPos(stand.getX() + STAND_CENTER, stand.getY(), stand.getZ() + STAND_CENTER);
        return player;
    }

    private static BrewCauldronBlockEntity cauldron(GameTestHelper context, BrewSites.Site site) {
        return (BrewCauldronBlockEntity) context.getLevel().getBlockEntity(site.cauldron());
    }

    private static void use(BrewCauldronBlockEntity cauldron, ServerPlayer player, ItemStack stack) {
        // 主手拿着对釜右键，走方块实体的投料入口。
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        cauldron.use(player, InteractionHand.MAIN_HAND);
    }

    private static ItemStack item(String id) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
    }

    private static Block block(String id) {
        // 注册表查不到时返回空气，这里直接报错，免得把格子换成空气还以为换成了铜链。
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
        if (block == Blocks.AIR) {
            throw new IllegalStateException("没有这种方块：" + id);
        }
        return block;
    }

    private record Swapped(int stone, int chains) {
    }
}
