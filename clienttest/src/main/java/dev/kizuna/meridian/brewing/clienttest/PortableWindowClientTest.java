package dev.kizuna.meridian.brewing.clienttest;

import dev.kizuna.meridian.brewing.client.FurnaceClientData;
import dev.kizuna.meridian.brewing.client.FurnaceScreen;
import dev.kizuna.meridian.brewing.clienttest.FurnaceWindowClientTest.Play;
import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceClock;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.VesselKind;
import java.util.Locale;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 单口釜从生存模式放置、空手开窗到实际装瓶；手熬另起一锅，让先打开的窗口观察木棍接管。 */
public final class PortableWindowClientTest implements FabricClientGameTest {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    static final int SCALE = 3;

    @Override
    public void runTest(ClientGameTestContext context) {
        for (var kind : new VesselKind[]{VesselKind.COPPER_CAULDRON, VesselKind.WITCH_CAULDRON}) {
            if (!Stage.selected("portable_window") && !Stage.selected(kind.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            context.getInput().resizeWindow(WIDTH, HEIGHT);
            try (var world = context.worldBuilder().create()) {
                Stage stage = Stage.prepare(context, world);
                int before = stage.guiScale(SCALE);
                new PortableWindowScene(new Play(context, stage), kind).run();
                stage.guiScale(before);
            }
        }
    }
}

final class PortableWindowScene {
    private static final Logger LOGGER = LoggerFactory.getLogger(PortableWindowClientTest.class);
    private static final BlockPos FIRE = BlockPos.ZERO;
    private static final BlockPos POT = FIRE.above();
    private static final BlockPos STAND = new BlockPos(1, 0, 3);
    private static final String HAND = "weapon.mainhand";
    private static final String PREFIX = "meridian_brewing:";
    private static final String KEY = "screen.meridian_brewing.";
    private static final String EMPTY = "minecraft:air";
    private static final String RECIPE = BrewKind.METAL_ELIXIR.path();
    private static final int TIMEOUT = 800;
    private static final int SYNC = 4;
    private static final int READ_TICKS = 18;
    private static final int BRAZIER_GRADE = 3;
    private static final int CAMPFIRE_GRADE = 4;
    private final Play play;
    private final Stage stage;
    private final VesselKind vessel;
    private final String block;

    PortableWindowScene(Play play, VesselKind vessel) {
        this.play = play;
        this.vessel = vessel;
        stage = play.stage();
        block = vessel.name().toLowerCase(Locale.ROOT);
    }

    void run() {
        setup();
        cold();
        if (vessel == VesselKind.COPPER_CAULDRON) {
            try (var film = new FurnaceFilm(play.context(), "portable")) {
                brew();
                LOGGER.info("[便携锅录像] 自动收药结束于第 {} 帧", film.frame());
                manual();
                LOGGER.info("[便携锅录像] 手熬提示结束于第 {} 帧", film.frame());
            }
        } else {
            brew();
            manual();
        }
    }

    private void setup() {
        // 单人测试世界内只授予这张方、熬药式和可开熬的身体。
        stage.clear();
        stage.play();
        stage.command("gamemode survival @a");
        stage.command("meridian opening complete @a all");
        stage.command("execute as @a run meridian qi fill");
        stage.command("meridian progression recipes learn @a " + RECIPE);
        stage.command("meridian technique learn @a meridian_brewing:brewing_basics");
        stage.look(stage.eye(STAND), stage.center(POT));
        int scale = play.context().computeOnClient(client -> client.getWindow().getGuiScale());
        if (scale != PortableWindowClientTest.SCALE) {
            throw new AssertionError("实际界面比例为 " + scale);
        }
    }

    private void cold() {
        // 同一口釜先放在石头上，再换熄灭的营火或冷炉座，验证两种不同的火源提示。
        stage.place(FIRE, "minecraft:stone");
        stage.place(POT, PREFIX + block);
        hold(EMPTY);
        stage.use();
        var message = Component.translatable("message.meridian_brewing.structure.no_fire");
        play.context().waitFor(client -> client.gui.screen() == null
                && FurnacesClientTest.visible(client.gui.hud, message), TIMEOUT);
        shot("00_no_source");
        String cold = "minecraft:campfire[lit=false]";
        if (vessel == VesselKind.WITCH_CAULDRON) {
            cold = PREFIX + "iron_stove";
        }
        stage.place(FIRE, cold);
        open();
        await(clock -> clock.getGrade() == 0 && clock.getMissingList().contains("fire"));
        shot("01_cold");
        close();
        stage.place(POT, EMPTY);
    }

    private void brew() {
        // 摄影从放锅开始；选方、注水、下料和装瓶均由真实的右键或菜单点击完成。
        place();
        open();
        if (vessel == VesselKind.WITCH_CAULDRON) {
            await(clock -> !clock.getContinuousFire() && clock.getGrade() == BRAZIER_GRADE && clock.getFuel() > 0);
        } else {
            await(clock -> clock.getContinuousFire() && clock.getGrade() == CAMPFIRE_GRADE);
        }
        shot("02_open");
        stage.click(FurnaceWindowClientTest.recipeCenter(play, RECIPE));
        await(clock -> clock.getDosesCount() > 0);
        shot("03_selected");
        demo("自动", "水、火、料齐备：自动开始熬制");
        close();
        use("minecraft:water_bucket");
        use("minecraft:iron_nugget");
        open();
        await(clock -> clock.getMissingList().contains("BASE"));
        shot("04_missing_base");
        close();
        use("minecraft:glowstone_dust");
        open();
        await(clock -> clock.getPhase().equals("BREWING") && !clock.getManual());
        shot("05_auto");
        await(clock -> clock.getCurrentStep().equals("ACCESSORY"));
        close();
        use("minecraft:quartz");
        open();
        await(clock -> clock.getDosesList().stream().anyMatch(dose -> dose.getMissed() && !dose.getLateAllowed()));
        warning("：已错过，无法加入", "06_closed");
        await(clock -> clock.getBottles() == vessel.batch().bottles());
        shot("07_brewed");
        close();
        collect();
    }

    private void place() {
        if (vessel == VesselKind.WITCH_CAULDRON) {
            stage.place(FIRE, PREFIX + "iron_brazier");
            stage.place(POT, PREFIX + block);
            stage.look(stage.eye(STAND), stage.center(POT));
            use("minecraft:coal_block");
            use("minecraft:coal");
            use("minecraft:coal");
            return;
        }
        stage.place(FIRE, "minecraft:campfire");
        var pos = stage.block(FIRE);
        var top = stage.computeOnServer(server -> {
            var level = server.overworld();
            var bounds = level.getBlockState(pos).getShape(level, pos).bounds().move(pos);
            var center = bounds.getCenter();
            return new Vec3(center.x, bounds.maxY, center.z);
        });
        stage.look(stage.eye(STAND), top);
        use(PREFIX + block);
        stage.look(stage.eye(STAND), stage.center(POT));
    }

    private void collect() {
        int bottles = vessel.batch().bottles();
        hold("minecraft:glass_bottle " + bottles);
        for (int i = 0; i < bottles; i++) {
            stage.use();
            stage.settle();
        }
        int obtained = stage.computeOnServer(server -> server.getPlayerList().getPlayers().getFirst()
                .getInventory().getNonEquipmentItems().stream().filter(BrewItemStacks::isBrew)
                .filter(stack -> BrewItemStacks.kind(stack) == BrewKind.METAL_ELIXIR)
                .mapToInt(stack -> stack.getCount()).sum());
        if (obtained != bottles) {
            throw new AssertionError(block + " 实收白矾丹露 " + obtained + "，期望 " + bottles);
        }
        shot("08_collected");
    }

    private void manual() {
        // 用一口还没选方的新釜备好料，观察窗口打开后手持木棍起式，等真实的技法周期推进到过窗。
        stage.look(stage.eye(new BlockPos(0, 0, 2)), stage.center(POT));
        stage.place(POT, EMPTY);
        stage.place(POT, PREFIX + block);
        use("minecraft:water_bucket");
        use("minecraft:iron_nugget");
        use("minecraft:glowstone_dust");
        open();
        BrewPlayScenes.cast(stage);
        await(clock -> clock.getManual() && clock.getPhase().equals("BREWING"));
        demo("手熬", "水、火、料齐备：施放熬药式开始熬制");
        await(clock -> clock.getDosesList().stream().anyMatch(dose -> dose.getLateAllowed()));
        warning("：已过最佳时机，仍可加入（品质降低）", "09_late_allowed");
        close();
    }

    private void demo(String mode, String expected) {
        stage.clickButton(KEY + "furnace.steps");
        play.context().waitFor(client -> screen(client).steps().isPresent(), TIMEOUT);
        boolean matches = play.context().computeOnClient(client -> screen(client).steps().orElseThrow()
                .sentences().stream().anyMatch(sentence -> sentence.getString().contains(expected)));
        if (!matches) {
            throw new AssertionError(mode + "演示的起熬步骤没有「" + expected + "」");
        }
        shot("steps_" + mode);
        stage.clickButton(KEY + "steps.back");
    }

    private void warning(String expected, String name) {
        String visible = play.context().computeOnClient(client -> screen(client).missedDose()
                .map(Component::getString).orElse(""));
        if (!visible.contains(expected)) {
            throw new AssertionError("炉窗应显示「" + expected + "」，实际「" + visible + "」");
        }
        shot(name);
    }

    private static FurnaceScreen screen(Minecraft client) {
        return (FurnaceScreen) client.gui.screen();
    }

    private void await(Predicate<FurnaceClock> wanted) {
        play.context().waitFor(client -> FurnaceClientData.view(client.player.containerMenu.containerId)
                .map(view -> wanted.test(view.getClock())).orElse(false), TIMEOUT);
    }

    private void hold(String item) {
        stage.hand(HAND, item);
        stage.pause(SYNC);
    }

    private void use(String item) {
        hold(item);
        stage.use();
        stage.settle();
    }

    private void open() {
        hold(EMPTY);
        FurnaceWindowClientTest.open(play, stage.block(POT));
    }

    private void close() {
        FurnaceWindowClientTest.close(play);
    }

    private void shot(String name) {
        play.context().getInput().setCursorPos(0, 0);
        stage.pause(READ_TICKS);
        stage.shoot(block + "_" + name);
    }
}
