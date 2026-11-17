package dev.kizuna.meridian.brewing.clienttest;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * 五行丹露：开全脉的生存模式玩家按住右键喝一瓶精良的白矾丹露，拍喝下的过程、动作栏的话和右上角的丹毒图标；
 * 丹毒还在时再喝一瓶（只化开一半、丹毒加重）；再把另外四味各喝一瓶，打开背包看五种丹毒排在一起。
 * 每瓶喝下前后对应经脉里那一行真元的数写进运行目录的 {@code elixir-facts.tsv}，不对就报错。
 */
public final class ElixirClientTest implements FabricClientGameTest {
    private static final String SCENE = "elixir";
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 960;
    private static final Path FACTS = Path.of("elixir-facts.tsv");
    private static final BrewQuality QUALITY = BrewQuality.FINE;
    /** 精良一瓶给 1.5 元；丹毒在身再喝同一行只得一半。 */
    private static final double FULL_DOSE = 1.5;
    private static final double REPEAT_DOSE = 0.75;
    private static final double DOSE_TOLERANCE = 0.01;
    /** 原版喝一瓶要按住 32 tick；多按几 tick，分 5 tick 一张拍下来。 */
    private static final int DRINK_TICKS = 40;
    private static final int FRAME_TICKS = 5;
    /** 喝完以后动作栏的话还亮着的时候再拍几张。 */
    private static final int AFTER_FRAMES = 4;
    private static final List<BrewKind> OTHERS = List.of(BrewKind.WOOD_ELIXIR, BrewKind.WATER_ELIXIR,
            BrewKind.FIRE_ELIXIR, BrewKind.EARTH_ELIXIR);
    /** 平视前方的空地：右键落在空气上，手里的药才喝得下去。 */
    private static final Vec3 AHEAD = new Vec3(0.5, 1.62, 8.5);
    private final StringBuilder facts = new StringBuilder("瓶\t药\t喝前这一行真元\t喝后\t得\n");
    private int bottle;

    @Override
    public void runTest(ClientGameTestContext context) {
        // 默认世界、生存模式、显示界面；先连喝两瓶金行，再把另外四行各喝一瓶。
        if (!Stage.selected(SCENE)) {
            return;
        }
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Stage stage = Stage.prepare(context, world);
            stage.command("gamemode survival @a");
            stage.showHud();
            stage.command("meridian opening complete @a all");
            stage.look(stage.eye(BlockPos.ZERO), stage.at(AHEAD));
            drink(stage, BrewKind.METAL_ELIXIR, FULL_DOSE, "elixir_1_first");
            drink(stage, BrewKind.METAL_ELIXIR, REPEAT_DOSE, "elixir_2_again");
            for (BrewKind kind : OTHERS) {
                drink(stage, kind, FULL_DOSE, "elixir_3_" + kind.elementPath());
            }
            context.getInput().pressKey(options -> options.keyInventory);
            context.waitForScreen(InventoryScreen.class);
            stage.settle();
            stage.shoot("elixir_4_inventory");
            context.setScreen(() -> null);
            write();
        }
    }

    private void drink(Stage stage, BrewKind kind, double expected, String name) {
        // 换上一瓶新的，按住右键喝完，一路拍；喝前喝后各读一次这一行的真元，差多少记下来并核对。
        stage.onServer(server -> player(server).setItemInHand(InteractionHand.MAIN_HAND,
                BrewItemStacks.create(kind, QUALITY, BrewUse.DRINK)));
        stage.settle();
        double before = stage.computeOnServer(server -> elementQi(player(server), kind));
        stage.shoot(name + "_00_holding");
        stage.holdUse();
        int frame = 1;
        for (int tick = 0; tick < DRINK_TICKS; tick += FRAME_TICKS) {
            stage.pause(FRAME_TICKS);
            stage.shoot(String.format(Locale.ROOT, "%s_%02d", name, frame++));
        }
        stage.releaseUse();
        for (int after = 0; after < AFTER_FRAMES; after++) {
            stage.pause(FRAME_TICKS);
            stage.shoot(String.format(Locale.ROOT, "%s_%02d", name, frame++));
        }
        double after = stage.computeOnServer(server -> elementQi(player(server), kind));
        facts.append(String.format(Locale.ROOT, "%d\t%s\t%.3f\t%.3f\t%.3f%n", ++bottle, kind.path(), before, after,
                after - before));
        if (Math.abs(after - before - expected) > DOSE_TOLERANCE) {
            write();
            throw new AssertionError(kind.path() + " 这一瓶应得 " + expected + " 元，实际 " + (after - before));
        }
    }

    private static double elementQi(ServerPlayer player, BrewKind kind) {
        // 全身经脉内腔里这一行的真元合计。
        String attribute = kind.elixirElement().orElseThrow();
        return VanillaDamageFabric.inspectBody(player).orElseThrow().getMeridianStatesList().stream()
                .flatMap(state -> state.getLumenQi().getEntriesList().stream())
                .filter(entry -> entry.getAttribute().equals(attribute))
                .mapToDouble(entry -> entry.getAmount())
                .sum();
    }

    private static ServerPlayer player(MinecraftServer server) {
        // 单人世界里只有测试客户端这一个玩家。
        return server.getPlayerList().getPlayers().getFirst();
    }

    private void write() {
        // 事实表和截图放在同一个运行目录里。
        try {
            Files.writeString(FACTS, facts);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
