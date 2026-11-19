package dev.kizuna.meridian.brewing.clienttest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.meridian.brewing.client.BrewStepsPane;
import dev.kizuna.meridian.brewing.client.BrewStepsScreen;
import dev.kizuna.meridian.brewing.client.RecipeBookScreen;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 药方书末页的「看炼法」和它打开的独立演示，窗口 1280×720、界面比例 2：聚气汤药方的首页没有按钮、末页有；
 * 往回翻一页按钮收起、再翻回来又出现；点按钮打开演示（没有炉子，只有演示游标），返回还是同一本书的同一页；
 * 《熬药总纲》用原版界面、翻到末页也没有这颗按钮；换一种只排得出一页的语言，翻开就看得到按钮。
 *
 * <p>右键卷轴、翻页键、点按钮都是真实输入。页码除了画面自己记的那一份，还读原版书本界面的当前页核对两边一致。</p>
 */
public final class RecipeBookClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecipeBookClientTest.class);
    private static final String SCENE = "recipe_book";
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int GUI_SCALE = 2;
    private static final String MAIN_HAND = "weapon.mainhand";
    private static final String RECIPE = "meridian_brewing:brew_recipe[meridian_brewing:recipe_kind=\"qi_gather\"]";
    private static final String MANUAL = "meridian_brewing:brew_manual";
    private static final String STEPS = "screen.meridian_brewing.furnace.steps";
    private static final String BACK = "screen.meridian_brewing.steps.back";
    private static final String SINGLE_PAGE = "en_gb";
    private static final String CHINESE = "zh_cn";
    /** 原版翻页会把页码夹在末页，给最大值就翻到最后一页。 */
    private static final int LAST_PAGE = Integer.MAX_VALUE;
    private static final int HAND_STEPS = 10;
    private static final int START_STEP = 4;
    private static final int NO_REAL_STEP = -1;
    private static final int TIMEOUT = 60;
    /** 抬头看天：右键打在空气上，手里物品自己的用法才生效。 */
    private static final Vec3 SKY = new Vec3(0.5, 12, 6.5);
    private static final Field VANILLA_PAGE = vanillaPage();

    private record Play(ClientGameTestContext context, Stage stage) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Stage.selected(SCENE)) {
            return;
        }
        context.getInput().resizeWindow(WIDTH, HEIGHT);
        try (var world = context.worldBuilder().create()) {
            Stage stage = Stage.prepare(context, world);
            stage.guiScale(GUI_SCALE);
            stage.play();
            stage.look(stage.eye(BlockPos.ZERO), stage.at(SKY));
            Play play = new Play(context, stage);
            int last = lastPage(play);
            demo(play, last);
            manual(play);
            singlePage(play);
        }
    }

    private static int lastPage(Play play) {
        // 多页的药方：首页没有按钮；翻到末页出现；翻回上一页收起，再翻过去又出现。返回末页的页码。
        read(play, RECIPE, RecipeBookScreen.class);
        expect(page(play) == 0 && !offered(play), "多页药方的首页不该有看炼法");
        play.stage().shoot("book_01_first_page");
        play.context().runOnClient(client -> book(client).setPage(LAST_PAGE));
        play.stage().settle();
        int last = page(play);
        expect(last > 0 && offered(play), "末页没有看炼法，页码 " + last);
        expect(vanillaPage(play) == last, "记的页码和原版不一致");
        play.stage().shoot("book_02_last_page");
        play.context().getInput().pressKey(InputConstants.KEY_PAGEUP);
        play.stage().settle();
        expect(page(play) == last - 1 && vanillaPage(play) == last - 1 && !offered(play), "翻回上一页按钮还在");
        play.stage().shoot("book_02b_before_last");
        play.context().getInput().pressKey(InputConstants.KEY_PAGEDOWN);
        play.stage().settle();
        expect(page(play) == last && vanillaPage(play) == last && offered(play), "再翻到末页按钮没回来");
        return last;
    }

    private static void demo(Play play, int last) {
        // 点看炼法：服务端回一套手熬步骤，打开独立演示，没有炉里的进度；演示放到起熬那一步时拍一张。返回还是原来那本书、那一页。
        Screen book = play.context().computeOnClient(client -> client.gui.screen());
        play.stage().clickButton(STEPS);
        play.context().waitForScreen(BrewStepsScreen.class);
        List<String> sentences = pane(play, pane -> pane.sentences().stream().map(Component::getString).toList());
        sentences.forEach(sentence -> LOGGER.info("[药方书] 步骤：{}", sentence));
        expect(sentences.size() == HAND_STEPS, "聚气汤手熬应有十步，实际 " + sentences.size());
        String start = sentences.get(START_STEP);
        expect(start.equals("水、火、料齐备：施放熬药式开始熬制，阳火调至 4 档"), "药方书的起熬步骤不符：" + start);
        expect(pane(play, BrewStepsPane::realStep) == NO_REAL_STEP, "独立演示不该有炉里的进度");
        expect(pane(play, pane -> pane.timeline().getMenu()) == 0, "药方书的步骤不该带菜单号");
        play.context().waitFor(client -> steps(client).pane().demoStep() == START_STEP,
                BrewStepsPane.STEP_TICKS * HAND_STEPS);
        play.context().getInput().setCursorPos(0, 0);
        play.stage().pause(2);
        play.stage().shoot("book_03_demo");
        play.stage().clickButton(BACK);
        play.context().waitForScreen(RecipeBookScreen.class);
        boolean same = play.context().computeOnClient(client -> client.gui.screen() == book);
        expect(same && page(play) == last && vanillaPage(play) == last && offered(play), "返回后不是原来那一页");
        play.context().getInput().setCursorPos(0, 0);
        play.stage().settle();
        play.stage().shoot("book_04_back");
        close(play);
    }

    private static void manual(Play play) {
        // 总纲不是哪一张方：用原版书本界面，翻到末页也没有看炼法。
        read(play, MANUAL, BookViewScreen.class);
        play.context().runOnClient(client -> ((BookViewScreen) client.gui.screen()).setPage(LAST_PAGE));
        play.stage().settle();
        String label = Component.translatable(STEPS).getString();
        boolean plain = play.context().computeOnClient(client -> client.gui.screen().getClass() == BookViewScreen.class
                && client.gui.screen().children().stream().noneMatch(child -> child instanceof AbstractWidget widget
                && widget.getMessage().getString().equals(label)));
        expect(plain, "总纲不该出现看炼法");
        play.stage().shoot("book_05_manual_last");
        close(play);
    }

    private static void singlePage(Play play) {
        // 换成正文只有两三行的测试语言：这张方只排得出一页，翻开就在末页，按钮直接看得到，也点得开。
        play.stage().language(SINGLE_PAGE);
        read(play, RECIPE, RecipeBookScreen.class);
        expect(page(play) == 0 && offered(play), "只有一页的药方翻开就该有看炼法");
        play.stage().shoot("book_06_single_page");
        play.stage().clickButton(STEPS);
        play.context().waitForScreen(BrewStepsScreen.class);
        play.stage().clickButton(BACK);
        play.context().waitForScreen(RecipeBookScreen.class);
        expect(page(play) == 0 && offered(play), "单页药方从演示返回后按钮没了");
        close(play);
        play.stage().language(CHINESE);
    }

    private static void read(Play play, String item, Class<? extends Screen> screen) {
        // 手持这本右键，等书本界面打开；光标挪到角上免得压着按钮。
        play.stage().hand(MAIN_HAND, item);
        play.stage().settle();
        play.stage().use();
        play.context().waitFor(client -> client.gui.screen() != null && client.gui.screen().getClass() == screen,
                TIMEOUT);
        play.context().getInput().setCursorPos(0, 0);
        play.stage().settle();
    }

    private static void close(Play play) {
        play.context().setScreen(() -> null);
        play.stage().settle();
    }

    private static int page(Play play) {
        return play.context().computeOnClient(client -> book(client).page());
    }

    private static boolean offered(Play play) {
        return play.context().computeOnClient(client -> book(client).offersSteps());
    }

    private static int vanillaPage(Play play) {
        return play.context().computeOnClient(client -> {
            try {
                return VANILLA_PAGE.getInt(client.gui.screen());
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("读取原版页码失败", failure);
            }
        });
    }

    private static <T> T pane(Play play, Function<BrewStepsPane, T> read) {
        return play.context().computeOnClient(client -> read.apply(steps(client).pane()));
    }

    private static RecipeBookScreen book(Minecraft client) {
        if (client.gui.screen() instanceof RecipeBookScreen book) {
            return book;
        }
        throw new AssertionError("药方书没有开着，现在是 " + client.gui.screen());
    }

    private static BrewStepsScreen steps(Minecraft client) {
        if (client.gui.screen() instanceof BrewStepsScreen steps) {
            return steps;
        }
        throw new AssertionError("步骤演示没有开着，现在是 " + client.gui.screen());
    }

    private static Field vanillaPage() {
        // 原版书本界面不公开当前页码，只在客户端场景里读它核对。
        try {
            Field field = BookViewScreen.class.getDeclaredField("currentPage");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("原版 BookViewScreen 没有 currentPage 字段", failure);
        }
    }

    private static void expect(boolean condition, String failure) {
        if (!condition) {
            throw new AssertionError(failure);
        }
    }
}
