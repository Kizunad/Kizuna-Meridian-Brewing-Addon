package dev.kizuna.meridian.brewing.clienttest;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import dev.kizuna.backpack3d.server.PlayerAccount;
import dev.kizuna.bs3d.vanilla.client.StationHost;
import dev.kizuna.bs3d.vanilla.client.StationPanel;
import dev.kizuna.inventoryui.client.status.StatusIcons;
import dev.kizuna.inventoryui.client.status.StatusLayout;
import dev.kizuna.inventoryui.status.StatusArrangement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * 状态效果图标：小窗口（854×480）下，少量效果各占一格、带时间；多了才按类合并、带数字；
 * 游戏画面里按住键展开，打开背包和箱子时让开界面、鼠标悬停展开。
 * 每一步拍一张，另把排出来的格位记到运行目录的 {@code status_icons.tsv}；
 * 该合并没合并、不该合并却合并、格位压到界面让开的矩形，这一场直接报错。
 */
public final class StatusIconsClientTest implements FabricClientGameTest {
    private static final String SCENE = "status_icons";
    private static final int WINDOW_WIDTH = 854;
    private static final int WINDOW_HEIGHT = 480;
    private static final String PEEK_KEY = "key.kizuna_inventory_ui.status_peek";
    private static final String RECORD = "status_icons.tsv";
    private static final int EFFECT_SECONDS = 300;
    private static final int PANEL_READY_TICKS = 200;
    private static final Identifier TEST_BAG = Identifier.fromNamespaceAndPath("bs3d", "herb_pouch");
    /** 少量：六个，来自四个类，854×480 放得下，不该合并。 */
    private static final List<String> FEW = List.of(
            "meridian_brewing:fire_toxin", "meridian_brewing:metal_toxin", "meridian_brewing:qi_gather",
            "meridian_vanilla_damage:burn", "minecraft:speed", "minecraft:night_vision");
    /** 再加上这些就超过十二格：五种丹毒、七种药性、两种挨打附带、五种附行、四种原版。 */
    private static final List<String> MORE = List.of(
            "meridian_brewing:wood_toxin", "meridian_brewing:water_toxin", "meridian_brewing:earth_toxin",
            "meridian_brewing:depletion", "meridian_brewing:armor_rot", "meridian_brewing:insect_lure",
            "meridian_brewing:bitter_yellow", "meridian_brewing:charm_stagger", "meridian_brewing:charm_control",
            "meridian_vanilla_damage:chill",
            "meridian_evoker:metal_edge", "meridian_evoker:earth_quake", "meridian_evoker:fire_burn",
            "meridian_evoker:water_frost", "meridian_evoker:wood_thunder",
            "minecraft:strength", "minecraft:regeneration");
    private static final BlockPos CHEST = new BlockPos(0, 0, 3);
    private static final Vec3 CHEST_EYE = new Vec3(0.5, 1.62, 1.5);
    private final List<String> record = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Stage.selected(SCENE)) {
            return;
        }
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Stage stage = Stage.prepare(context, world);
            stage.clear();
            stage.play();
            stage.command("gamemode survival @a");
            few(context, stage);
            many(context, stage);
            inventory(context, stage);
            bs3dInventory(context, stage);
            chest(context, stage);
        } finally {
            save();
        }
    }

    private void few(ClientGameTestContext context, Stage stage) {
        // 放得下就一个都不合并，每个效果自己一格。
        give(stage, FEW);
        stage.shoot("status_1_few_hud");
        StatusIcons.Layout layout = layout(context, "few_hud");
        check(layout.slots().size() == FEW.size(), "少量效果应各占一格，实际 " + layout.slots().size() + " 格");
        check(merged(layout) == 0, "少量效果不该合并");
    }

    private void many(ClientGameTestContext context, Stage stage) {
        // 超过放得下的数量才合并；按住键把合并的类展开。
        give(stage, MORE);
        stage.shoot("status_2_many_hud");
        StatusIcons.Layout layout = layout(context, "many_hud");
        int capacity = context.computeOnClient(client -> StatusLayout.hudCells(
                client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()).size());
        check(layout.slots().size() <= capacity, "格子数超过了放得下的数量");
        check(merged(layout) > 0, "超过上限应该有合并的格子");
        context.getInput().holdKey(StatusIconsClientTest::peekKey);
        stage.settle();
        stage.shoot("status_3_many_peek");
        context.getInput().releaseKey(StatusIconsClientTest::peekKey);
    }

    private void inventory(ClientGameTestContext context, Stage stage) {
        // 背包打开：图标让开界面；鼠标放到第一个合并的格子上展开。
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitForScreen(InventoryScreen.class);
        context.getInput().setCursorPos(0, 0);
        stage.settle();
        stage.shoot("status_4_inventory");
        StatusIcons.Layout layout = layout(context, "inventory");
        apart(layout);
        hover(context, layout, firstMerged(layout));
        stage.settle();
        stage.shoot("status_5_inventory_hover_merged");
        context.setScreen(() -> null);
    }

    private void chest(ClientGameTestContext context, Stage stage) {
        // 箱子打开：同样让开；鼠标放到一个没合并的格子上，出名字和时间。
        stage.place(CHEST, "minecraft:chest");
        stage.look(stage.at(CHEST_EYE), stage.center(CHEST));
        stage.settle();
        stage.use();
        context.waitForScreen(ContainerScreen.class);
        context.getInput().setCursorPos(0, 0);
        stage.settle();
        stage.shoot("status_6_chest");
        StatusIcons.Layout layout = layout(context, "chest");
        apart(layout);
        hover(context, layout, firstSingle(layout));
        stage.settle();
        stage.shoot("status_7_chest_hover_single");
        context.setScreen(() -> null);
    }

    private static void give(Stage stage, List<String> effects) {
        // 命令不带隐藏粒子那一项：原版隐藏粒子时图标也不显示。
        for (String effect : effects) {
            stage.command("effect give @a " + effect + " " + EFFECT_SECONDS);
        }
        stage.settle();
    }

    private void bs3dInventory(ClientGameTestContext context, Stage stage) {
        // 同 smoke 在隔离世界穿戴真实容器，再通过页面已有入口展开内景。
        String bag = wearBag(stage);
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitForScreen(InventoryScreen.class);
        context.waitFor(client -> stationPanel(client.gui.screen()).enabled(),
                PANEL_READY_TICKS);
        context.getInput().setCursorPos(0, 0);
        stage.settle();
        StatusIcons.Layout closed = layout(context, "bs3d_info");
        StatusLayout.Area info = panelArea(context, StationPanel::infoButtonArea);
        StatusLayout.Area interior = panelArea(context, StationPanel::interiorArea);
        check(!closed.blocked().contains(interior), "未打开内景时不应占住 BS3D 右侧空场景");
        apartFrom(closed, info, "状态图标压到了 BS3D 的 i 说明按钮");
        stage.shoot("status_8_bs3d_info_button");
        context.runOnClient(client -> stationPanel(client.gui.screen()).open(bag));
        context.waitFor(client -> interiorRendered(stationPanel(client.gui.screen()), bag), PANEL_READY_TICKS);
        stage.settle();
        StatusIcons.Layout opened = layout(context, "bs3d_interior");
        apartFrom(opened, interior, "状态图标压到了 BS3D 容器内景");
        apartFrom(opened, info, "内景打开后状态图标压到了 BS3D 的 i 说明按钮");
        apart(opened);
        stage.shoot("status_9_bs3d_interior");
        context.setScreen(() -> null);
    }

    private static String wearBag(Stage stage) {
        // 穿戴位置和持久化方式与 BS3D 自己的 smoke 一致，不直接伪造客户端快照。
        return stage.computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var account = new PlayerAccount(player);
            var pouch = ContainerItems.create(TEST_BAG);
            String id = ContainerItems.identity(pouch);
            var worn = WornContainer.newBuilder()
                    .setMount(Mount.WAIST_RIGHT_FRONT)
                    .setStackNbt(StackCodec.encode(pouch, player.registryAccess()))
                    .build();
            var previous = account.data();
            var data = previous.toBuilder()
                    .setRevision(previous.getRevision() + 1)
                    .addWorn(worn)
                    .build();
            try {
                account.commit(data, account.inventory());
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            return id;
        });
    }

    private static StationPanel stationPanel(Screen screen) {
        // 只读取真实页面已有的面板，不在测试里另建一份场景。
        check(screen instanceof StationHost, "背包界面没有接入 BS3D");
        StationHost host = (StationHost) screen;
        StationPanel panel = host.stationPanel();
        check(panel != null, "背包界面没有 BS3D 面板");
        return panel;
    }

    private static StatusLayout.Area panelArea(
            ClientGameTestContext context, Function<StationPanel, StatusLayout.Area> area) {
        // 区域与排版快照一样在客户端线程读取，不复制 BS3D 的布局数值。
        return context.computeOnClient(client -> area.apply(stationPanel(client.gui.screen())));
    }

    private static boolean interiorRendered(StationPanel panel, String bag) {
        // 等到动画走完且真实内景已绘制，不能只检查打开请求是否发出。
        try {
            var field = panel.getClass().getDeclaredField("interiors");
            field.setAccessible(true /* 读取测试投影 */);
            Map<?, ?> interiors = (Map<?, ?>) field.get(panel);
            return interiors.containsKey(bag);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法读取 BS3D 内景投影", failure);
        }
    }

    private static void apartFrom(StatusIcons.Layout layout, StatusLayout.Area blocked, String message) {
        // 独立拿实际界面的区域检查，来源漏登记时不能因 blocked 也为空而漏报。
        check(!layout.slots().isEmpty(), "BS3D 验收时必须有实际画出的状态图标");
        for (StatusLayout.Area cell : layout.cells()) {
            boolean overlaps = cell.left() < blocked.right() && cell.right() > blocked.left()
                    && cell.top() < blocked.bottom() && cell.bottom() > blocked.top();
            check(!overlaps, message + "：" + cell + " / " + blocked);
        }
    }

    private StatusIcons.Layout layout(ClientGameTestContext context, String step) {
        // 排版快照在渲染线程更新，这里回到客户端线程读，并逐格记下来。
        StatusIcons.Layout layout = context.computeOnClient(client -> StatusIcons.lastLayout());
        for (int index = 0; index < layout.slots().size(); index++) {
            StatusLayout.Area cell = layout.cells().get(index);
            record.add(String.join("\t", step, Integer.toString(index), kind(layout.slots().get(index)),
                    Integer.toString(cell.left()), Integer.toString(cell.top())));
        }
        record.add(String.join("\t", step, "blocked", Integer.toString(layout.blocked().size()), "", ""));
        return layout;
    }

    private static String kind(StatusArrangement.Slot slot) {
        return switch (slot) {
            case StatusArrangement.Single single -> single.effect().id();
            case StatusArrangement.Merged merged -> merged.group().id() + " x" + merged.effects().size();
            case StatusArrangement.Overflow overflow -> "overflow x" + overflow.slots().size();
        };
    }

    private static long merged(StatusIcons.Layout layout) {
        return layout.slots().stream().filter(StatusArrangement.Merged.class::isInstance).count();
    }

    private static int firstMerged(StatusIcons.Layout layout) {
        for (int index = 0; index < layout.slots().size(); index++) {
            if (layout.slots().get(index) instanceof StatusArrangement.Merged) {
                return index;
            }
        }
        throw new AssertionError("界面里没有合并的格子可以悬停");
    }

    private static int firstSingle(StatusIcons.Layout layout) {
        for (int index = 0; index < layout.slots().size(); index++) {
            if (layout.slots().get(index) instanceof StatusArrangement.Single) {
                return index;
            }
        }
        throw new AssertionError("界面里没有单个的格子可以悬停");
    }

    private static void apart(StatusIcons.Layout layout) {
        // 用掉的每个格位都不能压到这一帧让开的任何矩形。
        check(!layout.blocked().isEmpty(), "容器界面应该报出要让开的矩形");
        for (StatusLayout.Area cell : layout.cells()) {
            for (StatusLayout.Area blocked : layout.blocked()) {
                boolean overlaps = cell.left() < blocked.right() && cell.right() > blocked.left()
                        && cell.top() < blocked.bottom() && cell.bottom() > blocked.top();
                check(!overlaps, "格位 " + cell + " 压到了界面 " + blocked);
            }
        }
    }

    private static void hover(ClientGameTestContext context, StatusIcons.Layout layout, int index) {
        // 界面坐标乘界面缩放才是窗口坐标；鼠标放在底框正中。
        StatusLayout.Area cell = layout.cells().get(index);
        int half = StatusLayout.FRAME_PIXELS / 2;
        double scale = context.computeOnClient(client -> (double) client.getWindow().getGuiScale());
        context.getInput().setCursorPos((cell.left() + half) * scale, (cell.top() + half) * scale);
    }

    private static KeyMapping peekKey(Options options) {
        return Arrays.stream(options.keyMappings)
                .filter(key -> key.getName().equals(PEEK_KEY))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有登记展开键 " + PEEK_KEY));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void save() {
        try {
            Files.write(FabricLoader.getInstance().getGameDir().resolve(RECORD), record);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
