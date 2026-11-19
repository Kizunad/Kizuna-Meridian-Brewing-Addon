package dev.kizuna.meridian.brewing.client;

import dev.kizuna.meridian.brewing.protocol.pb.BrewTimeline;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceRequest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 药方卷轴翻开的书：原版书本界面，只在最后一页多一颗「看炼法」。
 *
 * <p>按钮摆在翻页箭头那一排、正文最后一行的下方：末页没有「下一页」箭头，那块地方空着，不压正文；
 * 只有一页的药方翻开就在末页，直接看得到。原版不公开当前页码，这里跟着翻页、跳页各记一份，只用来决定按钮显不显示。</p>
 *
 * <p>点了以后向服务端要这张方的一套手熬步骤（服务端核对手里确实拿着这张卷轴），收到再打开演示；
 * 从演示返回时还是这一个画面，页码不变。读没读过由服务端在右键卷轴时记，这里不发任何「看完了」。</p>
 */
public final class RecipeBookScreen extends BookViewScreen {
    private static final Component LABEL = Component.translatable(BrewNames.SCREEN + "furnace.steps");
    /** 和原版书本界面一致：书页贴图宽 192，贴图顶边在屏幕上方 2 像素。 */
    private static final int BOOK_TOP = 2;
    /** 「上一页」箭头右边到正文右边线之间的那一段，高度和箭头一样。 */
    private static final int BUTTON_X = 70;
    private static final int BUTTON_Y = 157;
    private static final int BUTTON_WIDTH = 78;
    private static final int BUTTON_HEIGHT = 13;
    private final String document;
    private final int lastPage;
    private int page;
    private Button stepsButton;

    RecipeBookScreen(BookAccess pages, String document) {
        super(pages);
        this.document = document;
        lastPage = pages.getPageCount() - 1;
    }

    static void register() {
        // 药方书要来的步骤菜单号是 0；只在书还开着、要的又正是这一篇时打开演示。
        FurnaceClientData.STEPS.register(steps -> {
            if (steps.getMenu() == 0 && Minecraft.getInstance().gui.screen() instanceof RecipeBookScreen book) {
                book.show(steps);
            }
        });
    }

    @Override
    protected void init() {
        // 从演示返回、窗口尺寸变化都会重进这里：按钮重摆，显不显示照记着的页码。
        super.init();
        int left = (width - IMAGE_WIDTH) / 2;
        stepsButton = addRenderableWidget(Button.builder(LABEL, pressed -> requestSteps())
                .bounds(left + BUTTON_X, BOOK_TOP + BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        stepsButton.visible = page == lastPage;
    }

    private void requestSteps() {
        // 篇名就是服务端让翻开的那一篇，和卷轴上的药方路径相同。
        FurnaceRequest request = FurnaceRequest.newBuilder()
                .setBook(document)
                .build();
        FurnaceClientData.request(request);
    }

    private void show(BrewTimeline steps) {
        if (steps.getChoice().getRecipe().equals(document)) {
            minecraft.gui.setScreen(new BrewStepsScreen(steps, this));
        }
    }

    @Override
    protected void pageBack() {
        super.pageBack();
        turned(page - 1);
    }

    @Override
    protected void pageForward() {
        super.pageForward();
        turned(page + 1);
    }

    @Override
    public boolean setPage(int wanted) {
        boolean changed = super.setPage(wanted);
        turned(wanted);
        return changed;
    }

    private void turned(int wanted) {
        // 原版把页码夹在首页和末页之间，这里照样夹；界面还没建好时只记页码。
        page = Mth.clamp(wanted, 0, lastPage);
        if (stepsButton != null) {
            stepsButton.visible = page == lastPage;
        }
    }

    /** 现在翻到第几页，从 0 数。 */
    public int page() {
        return page;
    }

    /** 这一页有没有「看炼法」。 */
    public boolean offersSteps() {
        return stepsButton.visible;
    }
}
