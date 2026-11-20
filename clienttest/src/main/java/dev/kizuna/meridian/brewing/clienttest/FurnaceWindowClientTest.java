package dev.kizuna.meridian.brewing.clienttest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.meridian.brewing.client.BrewStepsPane;
import dev.kizuna.meridian.brewing.client.FurnaceClientData;
import dev.kizuna.meridian.brewing.client.FurnaceScreen;
import dev.kizuna.meridian.brewing.clienttest.FurnacesClientTest.Shot;
import dev.kizuna.meridian.brewing.fabric.FurnaceScenes;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceView;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 炼药窗口的真实客户端场景，窗口 1280×720、界面比例 2：没读过药方的空列表；读过两张方的空窗口；连选两张方、备料到一半
 * （有缺项、能取回）；半锅换剂型被拒；取回原料；开熬后走到辅料时窗；窗口里的步骤视图（炉里的进度和演示游标分开）；
 * 别人在炼而自己没读过（只见「炉子在忙」）；三阶炉等铜傀儡；长名字和英文句子；十一张方全读过时列表滚动。另有一段连续帧，见 {@link FurnaceWindowFilm}。
 *
 * <p>开窗、点药方、点按钮都是真实的右键和鼠标点击；自己这一锅的添水、投料、加燃料经 {@link FurnaceScenes}
 * 走和右键相同的服务端入口。每一场先核对画面自己报出来的状态（列了哪些方、哪张高亮、有没有材料表、按钮在不在），再截图。</p>
 */
public final class FurnaceWindowClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(FurnaceWindowClientTest.class);
    private static final String SCENE = "furnace_window";
    private static final String FILM_SCENE = "furnace_window_film";
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int ACTION_TIMEOUT = 60;
    private static final int BREW_TIMEOUT = 400;
    private static final String KEY = "screen.meridian_brewing.";
    private static final String BITTER = BrewKind.BITTER_YELLOW.path();
    private static final String QI_GATHER = BrewKind.QI_GATHER.path();
    private static final String DRY_MOSS = "meridian_brewing:dry_moss";
    private static final String ACCESSORY = "ACCESSORY";
    private static final int ACCESSORY_STEP = 6;
    private static final int DRINK_STEPS = 10;
    private static final int JUMP_STEP = 5;
    private static final int PAUSE_CHECK_TICKS = BrewStepsPane.STEP_TICKS + 10;
    private static final int DEMO_SHOT_TICKS = BrewStepsPane.STEP_TICKS * 2 + 5;
    private static final String LONG_NAMES = "en_gb";
    private static final String CHINESE = "zh_cn";
    private static final int LIST_WIDTH = 128;
    private static final double SCROLL_DOWN = -1;
    private static final List<String> RAW_MARKS = List.of("screen.", "message.", "minecraft:", "meridian_brewing");

    record Play(ClientGameTestContext context, Stage stage) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        // 截图场景和连续帧各用各的世界，互不留料。
        context.getInput().resizeWindow(WIDTH, HEIGHT);
        if (Stage.selected(SCENE)) {
            shots(context);
        }
        if (Stage.selected(FILM_SCENE)) {
            try (var world = context.worldBuilder().create()) {
                Stage stage = FurnacesClientTest.prepare(context, world);
                FurnacesClientTest.enterCreativeFlight(stage);
                FurnaceWindowFilm.run(new Play(context, stage));
            }
        }
    }

    private static void shots(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            Stage stage = FurnacesClientTest.prepare(context, world);
            FurnacesClientTest.enterCreativeFlight(stage);
            Play play = new Play(context, stage);
            unread(play);
            empty(play);
            preparing(play);
            reclaim(play);
            brewing(play);
            steps(play);
            longText(play);
            concealed(play);
            automatic(play);
            scrolling(play);
        }
    }

    private static void unread(Play play) {
        // 一张方都没读过：列表空着，写一句提示；没有可看的步骤。
        FurnacesClientTest.approach(play.stage(), FurnacesClientTest.SMALL);
        open(play, FurnacesClientTest.SMALL);
        FurnaceScreen.Shown shown = shown(play);
        expect(shown.recipes().isEmpty() && !shown.steps() && !shown.reclaim(), "没读过药方却列出了方 " + shown);
        shoot(play, "window_00_unread");
        close(play);
    }

    private static void empty(Play play) {
        // 读过两张方时按目录列两行；空炉报水、火、药方三个缺项。
        play.stage().command("meridian progression recipes learn @a " + BITTER);
        play.stage().command("meridian progression recipes learn @a " + QI_GATHER);
        open(play, FurnacesClientTest.SMALL);
        FurnaceScreen.Shown shown = shown(play);
        expect(shown.recipes().equals(List.of(BITTER, QI_GATHER)), "列表不是读过的两张方 " + shown);
        expect(shown.highlighted().isEmpty() && shown.doses() == 0 && !shown.concealed(), "空炉不该有高亮和材料 "
                + shown);
        var missing = List.of(text(KEY + "furnace.missing.water"), text(KEY + "furnace.missing.fire"),
                text(KEY + "furnace.missing.recipe"));
        expect(shown.missing().equals(missing), "空炉应缺水、火和药方 " + shown);
        expect(!shown.reclaim() && !shown.steps(), "空炉不该有取回原料和炼法 " + shown);
        shoot(play, "window_01_empty");
    }

    private static void preparing(Play play) {
        // 连点两张方：第二次点的时候选方版本已经变了，带着新版本才换得过去。再添一桶水、下主料，停在缺火缺底料。
        clickRecipe(play, QI_GATHER);
        await(play, "点聚气汤后没有高亮", shown -> shown.highlighted().equals(Optional.of(QI_GATHER)));
        clickRecipe(play, BITTER);
        await(play, "带新版本号改选苦黄膏没有生效", shown -> shown.highlighted().equals(Optional.of(BITTER)));
        BlockPos rim = play.stage().block(FurnacesClientTest.SMALL.rim());
        onPlayer(play, player -> {
            FurnaceScenes.offerRim(player, rim, new ItemStack(Items.WATER_BUCKET));
            FurnaceScenes.offerRim(player, rim, stack(DRY_MOSS, 2));
        });
        await(play, "下了主料没有出现取回原料", FurnaceScreen.Shown::reclaim);
        FurnaceScreen.Shown shown = shown(play);
        String fire = text(KEY + "furnace.missing.fire");
        String glowstone = Items.GLOWSTONE_DUST.getDefaultInstance().getHoverName().getString();
        String honey = Items.HONEY_BOTTLE.getDefaultInstance().getHoverName().getString();
        expect(shown.doses() == 4 && shown.steps(), "备料中应有四味药和炼法 " + shown);
        expect(shown.missing().contains(fire) && shown.missing().contains(glowstone), "应缺火和底料 " + shown);
        expect(!shown.missing().contains(honey), "还没开熬不该催辅料 " + shown);
        play.stage().hover(recipeCenter(play, QI_GATHER));
        shoot(play, "window_02_preparing");
    }

    private static void reclaim(Play play) {
        // 锅里有料时换剂型被服务端拦下：动作栏提示先取回，剂型钮不动。点取回原料后回到只有水，按钮收起。
        play.stage().clickButton(KEY + "furnace.form.splash");
        play.stage().pause(ACTION_TIMEOUT / 2);
        expect(form(play) == BrewForm.DRINK, "半锅换剂型不该生效");
        play.context().getInput().setCursorPos(0, 0);
        shoot(play, "window_03_reclaim_first");
        play.stage().clickButton(KEY + "furnace.reclaim");
        await(play, "取回原料后按钮还在", shown -> !shown.reclaim());
        boolean returned = play.stage().computeOnServer(server -> player(server).getInventory()
                .countItem(item(DRY_MOSS)) == 2);
        expect(returned, "取回的主料没有回到背包");
        shoot(play, "window_03b_reclaimed");
        play.stage().command("clear @a");
    }

    private static void brewing(Play play) {
        // 把水、料、火备齐，真元灌满：下一秒自己开熬。等炉钟走到辅料时窗，这时「缺」里才出现蜂蜜瓶。
        BlockPos rim = play.stage().block(FurnacesClientTest.SMALL.rim());
        String name = play.stage().computeOnServer(server -> player(server).getScoreboardName());
        play.stage().command("meridian opening complete " + name + " all");
        play.stage().command("execute as @a run meridian qi fill");
        onPlayer(play, player -> {
            FurnaceScenes.offerRim(player, rim, new ItemStack(Items.WATER_BUCKET));
            FurnaceScenes.offerRim(player, rim, stack(DRY_MOSS, 2));
            FurnaceScenes.offerRim(player, rim, new ItemStack(Items.GLOWSTONE_DUST, 2));
            FurnaceScenes.offerHearth(player, rim, new ItemStack(Items.COAL_BLOCK));
            FurnaceScenes.offerHearth(player, rim, new ItemStack(Items.IRON_SHOVEL));
            FurnaceScenes.offerHearth(player, rim, new ItemStack(Items.IRON_SHOVEL));
        });
        play.context().waitFor(client -> view(client).getClock().getCurrentStep().equals(ACCESSORY), BREW_TIMEOUT);
        FurnaceScreen.Shown shown = shown(play);
        FurnaceView view = play.context().computeOnClient(FurnaceWindowClientTest::view);
        String honey = Items.HONEY_BOTTLE.getDefaultInstance().getHoverName().getString();
        expect(view.getOrders().getActual().getRecipe().equals(BITTER) && view.getOrders().getBatch() == 1,
                "开熬后锅里应是第一批苦黄膏 " + view.getOrders());
        expect(view.getClock().getElapsed() >= view.getClock().getWindowStart(), "还没进辅料时窗 " + view.getClock());
        expect(shown.missing().contains(honey) && !shown.reclaim() && shown.steps(), "时窗内应催辅料、不能取回 "
                + shown);
        log(play, "开熬");
        shoot(play, "window_04_brewing");
    }

    private static void steps(Play play) {
        // 炼法：窗口换成步骤，炉里真正走到辅料那一步，演示游标从头自己放；暂停、重播、点行跳步、返回和 Esc 都试一遍。
        play.stage().clickButton(KEY + "furnace.steps");
        play.context().waitFor(client -> screen(client).steps().isPresent(), ACTION_TIMEOUT);
        expect(pane(play, pane -> pane.sentences().size()) == DRINK_STEPS, "饮用苦黄膏应有十步");
        expect(pane(play, BrewStepsPane::realStep) == ACCESSORY_STEP, "炉里的进度没有落在辅料那一步");
        for (String sentence : pane(play, pane -> pane.sentences().stream().map(Component::getString).toList())) {
            expect(RAW_MARKS.stream().noneMatch(sentence::contains) && !sentence.isBlank(), "步骤露出了标识 "
                    + sentence);
            LOGGER.info("[炉窗] 步骤：{}", sentence);
        }
        play.stage().pause(DEMO_SHOT_TICKS);
        int demo = pane(play, BrewStepsPane::demoStep);
        expect(demo > 0 && demo < ACCESSORY_STEP && pane(play, BrewStepsPane::playing),
                "演示游标应自己往下放、还没追上炉里的进度，现在停在 " + demo);
        play.context().getInput().setCursorPos(0, 0);
        shoot(play, "window_05_steps");
        play.stage().clickButton(KEY + "steps.pause");
        play.stage().pause(PAUSE_CHECK_TICKS);
        expect(pane(play, BrewStepsPane::demoStep) == demo && !pane(play, BrewStepsPane::playing), "暂停后游标还在走");
        play.stage().click(pane(play, pane -> pane.rowCenter(JUMP_STEP).orElseThrow()));
        expect(pane(play, BrewStepsPane::demoStep) == JUMP_STEP, "点第六行没有跳过去");
        shoot(play, "window_05b_steps_paused");
        play.stage().clickButton(KEY + "steps.replay");
        expect(pane(play, BrewStepsPane::demoStep) == 0 && pane(play, BrewStepsPane::playing), "重播没有从头放");
        FurnaceView live = play.context().computeOnClient(FurnaceWindowClientTest::view);
        expect(pane(play, pane -> pane.timeline().getSteps(pane.realStep()).getAction())
                .equals(live.getClock().getCurrentStep()), "重播后炉里的标记与炉钟不符");
        play.stage().clickButton(KEY + "steps.back");
        expect(stepsClosed(play), "返回后还在步骤视图");
        play.stage().clickButton(KEY + "furnace.steps");
        play.context().waitFor(client -> screen(client).steps().isPresent(), ACTION_TIMEOUT);
        play.context().getInput().pressKey(InputConstants.KEY_ESCAPE);
        play.stage().pause(2);
        expect(stepsClosed(play), "Esc 应先回到炉窗");
        expect(shown(play).highlighted().equals(Optional.of(BITTER)), "看完步骤炉窗状态变了");
    }

    private static void longText(Play play) {
        // 换成带超长药名的测试语言（其余文字落回英文）：列表、状态行、步骤句子都要截断或折行，不能溢出底板。
        close(play);
        play.stage().language(LONG_NAMES);
        int widest = play.context().computeOnClient(client -> Math.min(
                client.font.width(Component.translatable("potion.meridian_brewing_" + BITTER)),
                client.font.width(Component.translatable("potion.meridian_brewing_" + QI_GATHER))));
        expect(widest > LIST_WIDTH, "测试语言里的药名不够长，考不到截断");
        open(play, FurnacesClientTest.SMALL);
        expect(shown(play).recipes().size() == 2, "换语言后列表行数变了");
        shoot(play, "window_08_long_status");
        play.stage().clickButton(KEY + "furnace.steps");
        play.context().waitFor(client -> screen(client).steps().isPresent(), ACTION_TIMEOUT);
        play.stage().pause(BrewStepsPane.STEP_TICKS * 2 + 5);
        play.context().getInput().setCursorPos(0, 0);
        shoot(play, "window_09_long_steps");
        close(play);
        play.stage().language(CHINESE);
    }

    private static void concealed(Play play) {
        // 二阶炉里别人在熬一锅自己没读过的枯竭药水：只见「炉子在忙」和阶段，没有材料表、缺项、步骤和取回原料。
        BlockPos rim = play.stage().block(FurnacesClientTest.MEDIUM.rim());
        play.stage().onServer(server -> FurnaceScenes.foreignBrew(server.overworld(), rim, BrewKind.DEPLETION,
                BrewForm.SPLASH));
        FurnacesClientTest.approach(play.stage(), FurnacesClientTest.MEDIUM);
        open(play, FurnacesClientTest.MEDIUM);
        FurnaceScreen.Shown shown = shown(play);
        expect(shown.concealed(), "没读过别人那张方，应只见炉子在忙 " + shown);
        expect(shown.doses() == 0 && shown.missing().isEmpty(), "没读过的人不该看到材料和缺项 " + shown);
        expect(!shown.steps() && !shown.reclaim() && shown.highlighted().isEmpty(), "没读过的人不该有步骤和取回 "
                + shown);
        expect(shown.recipes().equals(List.of(BITTER, QI_GATHER)), "自己读过的方仍应列出 " + shown);
        log(play, "别人在炼");
        shoot(play, "window_06_concealed");
        close(play);
    }

    private static void automatic(Play play) {
        // 三阶自动锅炉：能开窗选方，状态里写着在等铜傀儡。
        FurnacesClientTest.approach(play.stage(), FurnacesClientTest.LARGE);
        open(play, FurnacesClientTest.LARGE);
        clickRecipe(play, BITTER);
        await(play, "三阶炉选方没有生效", shown -> shown.highlighted().equals(Optional.of(BITTER)));
        expect(shown(play).awaitingWorker(), "三阶炉应显示在等铜傀儡 " + shown(play));
        play.context().getInput().setCursorPos(0, 0);
        shoot(play, "window_07_automatic");
        close(play);
    }

    private static void scrolling(Play play) {
        // 十一张方全读过：列表一屏放不下，最后一张要用滚轮卷下去才看得到、点得到。
        play.stage().command("meridian progression recipes learn @a all");
        FurnacesClientTest.approach(play.stage(), FurnacesClientTest.SMALL);
        open(play, FurnacesClientTest.SMALL);
        String last = BrewKind.EARTH_ELIXIR.path();
        expect(shown(play).recipes().size() == BrewKind.values().length, "全读过应列出全部药方 " + shown(play));
        expect(!listed(play, last), "最后一张方不该在第一屏");
        play.stage().hover(recipeCenter(play, BITTER));
        play.context().getInput().scroll(SCROLL_DOWN);
        play.stage().pause(2);
        expect(listed(play, last), "滚轮卷下去以后还是看不到最后一张方");
        clickRecipe(play, last);
        await(play, "卷下去以后点最后一张方没有生效", shown -> shown.highlighted().equals(Optional.of(last)));
        play.context().getInput().setCursorPos(0, 0);
        shoot(play, "window_10_scrolled");
        close(play);
    }

    private static boolean listed(Play play, String recipe) {
        return play.context().computeOnClient(client -> screen(client).recipeCenter(recipe).isPresent());
    }

    static void open(Play play, Shot shot) {
        // 准星对着锅沿空手右键，等炉窗打开、第一份状态到齐；光标先挪到角上，免得压出悬停。
        open(play, play.stage().block(shot.rim()));
    }

    static void open(Play play, BlockPos rim) {
        // 多方块点锅沿，便携釜直接点釜身。
        play.context().waitFor(client -> client.hitResult instanceof BlockHitResult hit
                && hit.getBlockPos().equals(rim), ACTION_TIMEOUT);
        play.stage().use();
        play.context().waitForScreen(FurnaceScreen.class);
        play.context().waitFor(client -> FurnaceClientData.view(client.player.containerMenu.containerId).isPresent(),
                ACTION_TIMEOUT);
        play.context().getInput().setCursorPos(0, 0);
        play.stage().settle();
    }

    static void close(Play play) {
        play.context().runOnClient(client -> client.gui.screen().onClose());
        play.stage().settle();
    }

    private static void clickRecipe(Play play, String recipe) {
        play.stage().click(recipeCenter(play, recipe));
    }

    static ScreenPosition recipeCenter(Play play, String recipe) {
        return play.context().computeOnClient(client -> screen(client).recipeCenter(recipe).orElseThrow());
    }

    private static void await(Play play, String failure, Predicate<FurnaceScreen.Shown> wanted) {
        // 服务端每 10 刻才发一次变化，等到画面报出想要的状态；等不到就把此刻的状态写进失败信息。
        for (int tick = 0; tick < ACTION_TIMEOUT; tick++) {
            if (wanted.test(shown(play))) {
                return;
            }
            play.context().waitTick();
        }
        throw new AssertionError(failure + "：" + shown(play));
    }

    private static FurnaceScreen.Shown shown(Play play) {
        return play.context().computeOnClient(client -> screen(client).shown());
    }

    private static BrewForm form(Play play) {
        return play.context().computeOnClient(client -> screen(client).form());
    }

    private static boolean stepsClosed(Play play) {
        return play.context().computeOnClient(client -> screen(client).steps().isEmpty());
    }

    private static <T> T pane(Play play, Function<BrewStepsPane, T> read) {
        return play.context().computeOnClient(client -> read.apply(screen(client).steps().orElseThrow()));
    }

    private static FurnaceScreen screen(Minecraft client) {
        if (client.gui.screen() instanceof FurnaceScreen screen) {
            return screen;
        }
        throw new AssertionError("炉窗没有开着，现在是 " + client.gui.screen());
    }

    private static FurnaceView view(Minecraft client) {
        return FurnaceClientData.view(client.player.containerMenu.containerId).orElseThrow();
    }

    private static void onPlayer(Play play, Consumer<ServerPlayer> action) {
        play.stage().onServer(server -> action.accept(player(server)));
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static ItemStack stack(String id, int count) {
        return new ItemStack(item(id), count);
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static String text(String key) {
        return Component.translatable(key).getString();
    }

    private static void log(Play play, String label) {
        BlockPos small = play.stage().block(FurnacesClientTest.SMALL.rim());
        BlockPos medium = play.stage().block(FurnacesClientTest.MEDIUM.rim());
        String state = play.stage().computeOnServer(server -> FurnaceScenes.describe(server.overworld(), small)
                + " ｜ 二阶 " + FurnaceScenes.describe(server.overworld(), medium));
        LOGGER.info("[炉窗] {}：一阶 {}", label, state);
    }

    private static void shoot(Play play, String name) {
        play.stage().pause(2);
        play.stage().shoot(name);
    }

    private static void expect(boolean condition, String failure) {
        if (!condition) {
            throw new AssertionError(failure);
        }
    }
}
