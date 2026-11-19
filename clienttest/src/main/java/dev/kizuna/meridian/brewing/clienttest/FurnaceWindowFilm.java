package dev.kizuna.meridian.brewing.clienttest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.meridian.brewing.client.BrewStepsPane;
import dev.kizuna.meridian.brewing.client.BrewStepsScreen;
import dev.kizuna.meridian.brewing.client.FurnaceScreen;
import dev.kizuna.meridian.brewing.client.RecipeBookScreen;
import dev.kizuna.meridian.brewing.clienttest.FurnaceWindowClientTest.Play;
import dev.kizuna.meridian.brewing.fabric.FurnaceScenes;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 给人看的一段连续帧（每个客户端刻一帧，每秒 20 帧）：飞到一阶炉的锅沿，空手点开炼药窗口，选苦黄膏；关窗到炉底添炭、
 * 用锹把火压到两档，回锅沿添两桶水、下干苔和荧石粉；再开窗看它自己开熬，点「看炼法」看一会儿再返回；
 * 到时窗投蜂蜜瓶和蛛网，开着窗等它熬成，拿玻璃瓶在锅口收六瓶药。最后翻开苦黄膏的药方，翻到末页点「看炼法」，
 * 看演示放几步再返回书页。
 *
 * <p>开窗、选方、添水、投料、加炭、压火、收药、翻书、点按钮全是真实的右键、按键和鼠标点击，玩家是生存模式、
 * 悬在锅沿旁；投辅料和引药前向服务端问一下这一锅熬到第几秒，只为踩准时窗。每一段的起止帧号写进日志。</p>
 */
final class FurnaceWindowFilm {
    private static final Logger LOGGER = LoggerFactory.getLogger(FurnaceWindowFilm.class);
    private static final String KEY = "screen.meridian_brewing.";
    private static final String MAIN_HAND = "weapon.mainhand";
    private static final String EMPTY = "minecraft:air";
    private static final String BITTER = BrewKind.BITTER_YELLOW.path();
    private static final String SCROLL = "meridian_brewing:brew_recipe[meridian_brewing:recipe_kind=\"bitter_yellow\"]";
    private static final int FLY_TICKS = 25;
    private static final int SWAP_TICKS = 8;
    private static final int USE_TICKS = 10;
    private static final int PAGE_TICKS = 5;
    private static final int LOOK_TICKS = 30;
    private static final int WATCH_TICKS = 40;
    private static final int STEPS_TICKS = 60;
    private static final int TIMEOUT = 100;
    private static final int BREW_TIMEOUT = 800;
    private static final int POLL_TICKS = 2;
    private static final int WATER_BUCKETS = 2;
    private static final int BOTTLES = 6;
    private static final double ACCESSORY_SECOND = 6.5;
    private static final double CATALYST_SECOND = 18.5;
    private static final double HEARTH_DISTANCE = 3.2;
    private static final double HEARTH_RISE = 0.9;
    private static final int BOOK_DEMO_STEPS = 3;
    /** 抬头看天：右键打在空气上，手里物品自己的用法才生效。 */
    private static final Vec3 SKY_OFFSET = new Vec3(0, 6, 0.5);
    private final Play play;
    private final FurnaceFilm film;
    private final BlockPos rim;
    private int mark;

    private FurnaceWindowFilm(Play play, FurnaceFilm film) {
        this.play = play;
        this.film = film;
        rim = play.stage().block(FurnacesClientTest.SMALL.rim());
        mark = film.frame();
    }

    static void run(Play play) {
        // 学苦黄膏、灌满真元在开拍前做完；镜头从远处那一眼开始。
        Stage stage = play.stage();
        String name = stage.computeOnServer(server -> server.getPlayerList().getPlayers().getFirst()
                .getScoreboardName());
        stage.command("meridian progression recipes learn @a " + BITTER);
        stage.command("meridian opening complete " + name + " all");
        stage.command("execute as @a run meridian qi fill");
        hoverInSurvival(stage);
        stage.hand(MAIN_HAND, EMPTY);
        stage.settle();
        try (var film = new FurnaceFilm(play.context(), "window")) {
            var take = new FurnaceWindowFilm(play, film);
            take.choose();
            take.stoke();
            take.fill();
            take.watchStart();
            take.dose(ACCESSORY_SECOND, "minecraft:honey_bottle 2", "投蜂蜜瓶");
            take.watch(CATALYST_SECOND - 1, "开窗看辅料投进去以后");
            take.dose(CATALYST_SECOND, "minecraft:cobweb 2", "投蛛网");
            take.watch(BrewCauldron.BREW_SECONDS, "开窗等熬成");
            take.collect();
            take.book();
        }
    }

    private static void hoverInSurvival(Stage stage) {
        // 锅沿离地七格，要悬在半空才够得着；物品照生存模式真实消耗：水桶倒空、蜂蜜瓶换回空瓶、玻璃瓶装成药。
        // 创造模式手里的东西不减少，水桶点完锅沿还会被原版再倒一次水，所以不用创造模式拍。
        stage.command("gamemode survival @a");
        stage.onServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        });
    }

    private void choose() {
        // 飞到锅沿，空手右键开窗，点苦黄膏，看一眼再关。
        FurnacesClientTest.approach(play.stage(), FurnacesClientTest.SMALL);
        cut("飞到锅沿");
        FurnaceWindowClientTest.open(play, FurnacesClientTest.SMALL);
        play.stage().pause(LOOK_TICKS);
        cut("空手开窗");
        play.stage().click(FurnaceWindowClientTest.recipeCenter(play, BITTER));
        play.context().getInput().setCursorPos(0, 0);
        play.stage().pause(LOOK_TICKS);
        cut("选苦黄膏");
        closeWindow();
    }

    private void stoke() {
        // 降到炉底：一块煤炭块添进去，再用锹压两下，火停在两档。
        BlockPos port = play.stage().computeOnServer(server -> FurnaceScenes.hearthPort(server.overworld(), rim));
        Vec3 rimEye = play.stage().at(FurnacesClientTest.SMALL.nearEye());
        Vec3 target = Vec3.atCenterOf(port);
        Vec3 away = target.subtract(play.stage().at(FurnacesClientTest.SMALL.center())).multiply(1, 0, 1).normalize();
        Vec3 eye = target.add(away.scale(HEARTH_DISTANCE)).add(0, HEARTH_RISE, 0);
        fly(rimEye, eye, Vec3.atCenterOf(rim), target);
        hold("minecraft:coal_block");
        useOn(port);
        hold("minecraft:iron_shovel");
        useOn(port);
        useOn(port);
        cut("炉底添炭、压到两档");
        fly(eye, rimEye, target, Vec3.atCenterOf(rim));
    }

    private void fill() {
        // 回到锅沿：两桶水，干苔两份，荧石粉两份。底料一下锅，水火料就齐了。
        for (int bucket = 0; bucket < WATER_BUCKETS; bucket++) {
            hold("minecraft:water_bucket");
            useOn(rim);
        }
        hold("meridian_brewing:dry_moss 2");
        useOn(rim);
        hold("minecraft:glowstone_dust 2");
        useOn(rim);
        cut("添水、下主料和底料");
    }

    private void watchStart() {
        // 空手再开窗：炉子自己开熬，进度在走；点看炼法看一会儿，返回，关窗。
        hold(EMPTY);
        FurnaceWindowClientTest.open(play, FurnacesClientTest.SMALL);
        play.stage().pause(WATCH_TICKS);
        cut("开窗看它开熬");
        play.stage().clickButton(KEY + "furnace.steps");
        play.context().waitFor(client -> client.gui.screen() instanceof FurnaceScreen screen
                && screen.steps().isPresent(), TIMEOUT);
        play.context().getInput().setCursorPos(0, 0);
        play.stage().pause(STEPS_TICKS);
        cut("看炼法");
        play.stage().clickButton(KEY + "steps.back");
        play.stage().pause(SWAP_TICKS);
        cut("返回炉窗");
        closeWindow();
    }

    private void dose(double second, String item, String label) {
        // 拿好这味药，等炉钟走进它的时窗再点锅沿。
        hold(item);
        awaitElapsed(second);
        useOn(rim);
        cut(label);
    }

    private void watch(double untilSecond, String label) {
        hold(EMPTY);
        FurnaceWindowClientTest.open(play, FurnacesClientTest.SMALL);
        awaitElapsed(untilSecond);
        play.stage().pause(LOOK_TICKS);
        cut(label);
        closeWindow();
    }

    private void collect() {
        // 熬成了：拿六个玻璃瓶在锅口一瓶一瓶收。
        hold("minecraft:glass_bottle " + BOTTLES);
        for (int bottle = 0; bottle < BOTTLES; bottle++) {
            useOn(rim);
        }
        int potions = play.stage().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst()
                .getInventory().countItem(Items.POTION));
        if (potions != BOTTLES) {
            throw new AssertionError("应收到 " + BOTTLES + " 瓶药，实际 " + potions);
        }
        play.stage().pause(LOOK_TICKS);
        cut("拿瓶收药");
    }

    private void book() {
        // 抬头翻开苦黄膏的药方，一页页翻到末页，点看炼法，看演示放几步，返回书页。
        Vec3 eye = play.stage().at(FurnacesClientTest.SMALL.nearEye());
        play.stage().command("clear @a");
        fly(eye, eye, Vec3.atCenterOf(rim), eye.add(SKY_OFFSET));
        hold(SCROLL);
        play.stage().use();
        play.context().waitForScreen(RecipeBookScreen.class);
        play.context().getInput().setCursorPos(0, 0);
        play.stage().pause(LOOK_TICKS);
        while (!play.context().computeOnClient(client -> ((RecipeBookScreen) client.gui.screen()).offersSteps())) {
            play.context().getInput().pressKey(InputConstants.KEY_PAGEDOWN);
            play.stage().pause(PAGE_TICKS);
        }
        play.stage().pause(LOOK_TICKS);
        cut("药方书翻到末页");
        play.stage().clickButton(KEY + "furnace.steps");
        play.context().waitForScreen(BrewStepsScreen.class);
        play.context().getInput().setCursorPos(0, 0);
        play.stage().pause(BrewStepsPane.STEP_TICKS * BOOK_DEMO_STEPS);
        cut("书里看炼法");
        play.stage().clickButton(KEY + "steps.back");
        play.context().waitForScreen(RecipeBookScreen.class);
        play.context().getInput().setCursorPos(0, 0);
        play.stage().pause(LOOK_TICKS);
        cut("返回书页");
        play.context().setScreen(() -> null);
        play.stage().pause(SWAP_TICKS);
    }

    private void closeWindow() {
        play.context().getInput().pressKey(InputConstants.KEY_ESCAPE);
        play.context().waitFor(client -> client.gui.screen() == null, TIMEOUT);
        play.stage().pause(SWAP_TICKS);
    }

    private void fly(Vec3 fromEye, Vec3 toEye, Vec3 fromTarget, Vec3 toTarget) {
        for (int tick = 0; tick <= FLY_TICKS; tick++) {
            double t = (double) tick / FLY_TICKS;
            play.stage().aim(fromEye.lerp(toEye, t), fromTarget.lerp(toTarget, t));
            play.stage().pause(1);
        }
        play.stage().pause(SWAP_TICKS);
    }

    private void hold(String item) {
        play.stage().hand(MAIN_HAND, item);
        play.stage().pause(SWAP_TICKS);
    }

    private void useOn(BlockPos target) {
        // 准星确实落在这一格上才点；点完留几刻给动作栏的提示。
        play.context().waitFor(client -> client.hitResult instanceof BlockHitResult hit
                && hit.getBlockPos().equals(target), TIMEOUT);
        play.stage().use();
        play.stage().pause(USE_TICKS);
    }

    private void awaitElapsed(double second) {
        for (int tick = 0; tick < BREW_TIMEOUT; tick += POLL_TICKS) {
            double elapsed = play.stage().computeOnServer(server -> FurnaceScenes.elapsed(server.overworld(), rim));
            if (elapsed >= second) {
                return;
            }
            play.stage().pause(POLL_TICKS);
        }
        String state = play.stage().computeOnServer(server -> FurnaceScenes.describe(server.overworld(), rim));
        throw new AssertionError("这一锅没有熬到第 " + second + " 秒：" + state);
    }

    private void cut(String label) {
        int last = film.frame();
        LOGGER.info("[炉窗录像] {} 帧 {}–{}", label, mark + 1, last);
        mark = last;
    }
}
