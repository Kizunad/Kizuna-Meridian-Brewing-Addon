package dev.kizuna.meridian.brewing.clienttest;

import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 药方讲解和《熬药总纲》：创造模式里手持聚气汤药方右键，翻开原版书本界面，拍首页、第二页和末页；
 * 再手持《熬药总纲》右键拍同样三页；最后把语言换成英文再翻一次总纲，看英文排页，拍完换回中文。
 * 截图写到运行目录 {@code screenshots/}。
 */
public final class BrewLoreClientTest implements FabricClientGameTest {
    private static final String SCENE = "lore";
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 960;
    private static final String MAIN_HAND = "weapon.mainhand";
    private static final String RECIPE = "meridian_brewing:brew_recipe[meridian_brewing:recipe_kind=\"qi_gather\"]";
    private static final String MANUAL = "meridian_brewing:brew_manual";
    private static final int SECOND_PAGE = 1;
    /** 原版翻页会把页码夹在末页，给最大值就翻到最后一页。 */
    private static final int LAST_PAGE = Integer.MAX_VALUE;
    private static final String ENGLISH = "en_us";
    private static final String CHINESE = "zh_cn";
    /** 重载资源包最多等这么多 tick。 */
    private static final int RELOAD_TICKS = 1200;
    /** 抬头看天：右键打在空气上，手里物品自己的用法才生效。 */
    private static final Vec3 SKY = new Vec3(0.5, 12, 6.5);

    @Override
    public void runTest(ClientGameTestContext context) {
        // 默认世界、创造模式；每本书拍几页，拍完关掉再换下一本。
        if (!Stage.selected(SCENE)) {
            return;
        }
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Stage stage = Stage.prepare(context, world);
            stage.play();
            stage.look(stage.eye(BlockPos.ZERO), stage.at(SKY));
            read(context, stage, RECIPE, "lore_qi_gather");
            read(context, stage, MANUAL, "lore_manual");
            switchLanguage(context, ENGLISH);
            read(context, stage, MANUAL, "lore_manual_en");
            switchLanguage(context, CHINESE);
        }
    }

    private static void read(ClientGameTestContext context, Stage stage, String item, String name) {
        // 手持这本右键，等原版书本界面打开；光标挪到角上免得压着翻页按钮，拍首页、第二页、末页后关掉。
        stage.hand(MAIN_HAND, item);
        stage.settle();
        stage.use();
        context.waitForScreen(BookViewScreen.class);
        context.getInput().setCursorPos(0, 0);
        stage.settle();
        stage.shoot(name + "_1_first");
        page(context, stage, SECOND_PAGE, name + "_2_second");
        page(context, stage, LAST_PAGE, name + "_3_last");
        context.setScreen(() -> null);
        stage.settle();
    }

    private static void page(ClientGameTestContext context, Stage stage, int page, String name) {
        // 走原版的翻页入口，页码越界时原版夹到末页。
        context.runOnClient(client -> ((BookViewScreen) client.gui.screen()).setPage(page));
        stage.settle();
        stage.shoot(name);
    }

    private static void switchLanguage(ClientGameTestContext context, String language) {
        // 和在语言设置里换语言一样：记下选择、重载资源，等重载和加载遮罩都结束再往下。
        CompletableFuture<Void> reload = context.computeOnClient(client -> {
            client.options.languageCode = language;
            client.getLanguageManager().setSelected(language);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, RELOAD_TICKS);
    }
}
