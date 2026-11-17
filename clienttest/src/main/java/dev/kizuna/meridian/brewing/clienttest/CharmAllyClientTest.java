package dev.kizuna.meridian.brewing.clienttest;

import dev.kizuna.meridian.brewing.fabric.BrewCharmSessions;
import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewingRuntime;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 用户报的那一局，改后的样子：真炁女巫和拿弩的掠夺者用调试命令互相仇恨，打上三秒；女巫的魅惑落在掠夺者身上，
 * 再过两秒清掉调试仇恨；一直拍到魅惑到期后三秒。游戏冻结后一 tick 放行一次、拍一张，旁观者在侧面看。
 * 每张另记一行：两边的目标、掠夺者是不是受控、两边的血，写到运行目录的 {@code charm_ally.tsv}。
 */
public final class CharmAllyClientTest implements FabricClientGameTest {
    private static final String SCENE = "charm_ally";
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final Vec3 WITCH_FEET = new Vec3(0.5, 0, 2.5);
    private static final Vec3 PILLAGER_FEET = new Vec3(0.5, 0, 9.5);
    private static final Vec3 CAMERA = new Vec3(13.5, 5, 6);
    private static final Vec3 CAMERA_TARGET = new Vec3(0.5, 1, 6);
    private static final AABB NEAR = new AABB(-2, -2, -2, 2, 3, 2);
    private static final int STEP_SETTLE_TICKS = 2;
    /** 先打三秒，中魅惑后两秒清调试仇恨，魅惑（优质 120 tick）到期后再拍三秒。 */
    private static final int FIGHT_TICKS = 60;
    private static final int CLEAR_AFTER_CHARM_TICKS = 40;
    private static final int CHARM_TICKS = 120;
    private static final int AFTER_TICKS = 60;
    private static final String RECORD = "charm_ally.tsv";

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Stage.selected(SCENE)) {
            return;
        }
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Stage stage = Stage.prepare(context, world);
            stage.clear();
            stage.hideHud();
            Vec3 witchFeet = stage.at(WITCH_FEET);
            Vec3 pillagerFeet = stage.at(PILLAGER_FEET);
            spawn(stage, "minecraft:witch", "true_qi", witchFeet);
            spawn(stage, "minecraft:pillager", "mortal", pillagerFeet);
            stage.settle();
            stage.command("meridian qi fill @e[type=minecraft:witch,limit=1]");
            Witch witch = stage.computeOnServer(server -> only(server, Witch.class, witchFeet));
            Pillager pillager = stage.computeOnServer(server -> only(server, Pillager.class, pillagerFeet));
            stage.onServer(server -> {
                pillager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
                witch.setPersistenceRequired();
                pillager.setPersistenceRequired();
            });
            stage.look(stage.at(CAMERA), stage.at(CAMERA_TARGET));
            stage.closeScreen();
            stage.command("tick freeze");
            stage.command("meridian humanoid aggro " + pillager.getStringUUID() + " " + witch.getStringUUID());
            film(stage, witch, pillager);
            stage.command("tick unfreeze");
        }
    }

    private static void film(Stage stage, Witch witch, Pillager pillager) {
        // 一张一个游戏 tick。到点丢魅惑（施药者是女巫）、清调试仇恨；每张记一行事实。
        List<String> rows = new ArrayList<>();
        int total = FIGHT_TICKS + CHARM_TICKS + AFTER_TICKS;
        for (int frame = 1; frame <= total; frame++) {
            if (frame == FIGHT_TICKS) {
                stage.onServer(server -> BrewingRuntime.splash(server.overworld(),
                        BrewItemStacks.create(BrewKind.CHARM, BrewQuality.SUPERIOR, BrewUse.SPLASH),
                        pillager.position(), witch));
            }
            if (frame == FIGHT_TICKS + CLEAR_AFTER_CHARM_TICKS) {
                stage.command("meridian humanoid aggro clear");
            }
            stage.command("tick step 1");
            stage.pause(STEP_SETTLE_TICKS);
            stage.shoot(String.format(Locale.ROOT, "charm_ally_%04d", frame));
            rows.add(frame + "\t" + stage.computeOnServer(server -> facts(witch, pillager)));
        }
        write("张\t女巫的目标\t掠夺者的目标\t掠夺者\t女巫血\t掠夺者血\t相距\n" + String.join("\n", rows) + "\n");
    }

    private static String facts(Witch witch, Pillager pillager) {
        // 这一 tick 的事实：两边的目标、掠夺者受没受控、两边的血、相距。
        return String.format(Locale.ROOT, "%s\t%s\t%s\t%.1f\t%.1f\t%.1f", name(witch.getTarget()),
                name(pillager.getTarget()), BrewCharmSessions.controlled(pillager.getUUID()) ? "受控" : "自由",
                witch.getHealth(), pillager.getHealth(), witch.distanceTo(pillager));
    }

    private static void spawn(Stage stage, String type, String tier, Vec3 feet) {
        // 正式的生成命令，带人体。
        stage.command(String.format(Locale.ROOT, "meridian humanoid spawn %s %s %.2f %.2f %.2f", type, tier, feet.x,
                feet.y, feet.z));
    }

    private static <T extends Mob> T only(MinecraftServer server, Class<T> type, Vec3 feet) {
        // 生成命令在原地生出的那一只；没有或多出来都说明场地不对。
        List<T> found = server.overworld().getEntitiesOfClass(type, NEAR.move(feet));
        if (found.size() != 1) {
            throw new AssertionError(type.getSimpleName() + " 生成位置附近应只有一只：" + found);
        }
        return found.getFirst();
    }

    private static String name(LivingEntity entity) {
        if (entity == null) {
            return "无";
        }
        return entity instanceof Witch ? "女巫" : entity instanceof Pillager ? "掠夺者" : entity.getName().getString();
    }

    private static void write(String content) {
        try {
            Files.writeString(FabricLoader.getInstance().getGameDir().resolve(RECORD), content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
