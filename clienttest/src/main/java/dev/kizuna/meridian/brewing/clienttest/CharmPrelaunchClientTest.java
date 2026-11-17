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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 「上一瓶还在天上，魅惑先到」：女巫朝掠夺者扔出一瓶伤害药，药还在飞的时候魅惑把掠夺者控住；
 * 先前那瓶随后落地、伤到掠夺者，掠夺者不醒，一直控到魅惑到期。
 * 游戏冻结后一 tick 放行一次、拍一张，旁观者在侧面看。每张另记一行事实，写到运行目录的 {@code charm_prelaunch.tsv}；
 * 药没伤到掠夺者、伤到时还没控住、或者伤到以后醒了，这一场直接报错。
 */
public final class CharmPrelaunchClientTest implements FabricClientGameTest {
    private static final String SCENE = "charm_prelaunch";
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final Vec3 WITCH_FEET = new Vec3(0.5, 0, 2.5);
    private static final Vec3 PILLAGER_FEET = new Vec3(0.5, 0, 9.5);
    private static final Vec3 CAMERA = new Vec3(11.5, 4, 6);
    private static final Vec3 CAMERA_TARGET = new Vec3(0.5, 1.5, 6);
    private static final AABB NEAR = new AABB(-2, -2, -2, 2, 3, 2);
    private static final int STEP_SETTLE_TICKS = 2;
    /** 先站一秒；药出手后 6 tick 魅惑到；魅惑（优质 120 tick）到期后再拍一秒。 */
    private static final int IDLE_TICKS = 20;
    private static final int CHARM_AFTER_LAUNCH_TICKS = 6;
    private static final int CHARM_TICKS = 120;
    private static final int AFTER_TICKS = 20;
    /** 药瓶大约飞这么多 tick 落到掠夺者脚边：魅惑到的时候它还在半空。 */
    private static final int FLIGHT_TICKS = 24;
    /** 原版投掷物每 tick 往下加的速度（格/tick）。 */
    private static final double THROWN_GRAVITY = 0.05;
    /** 药落地后至少还要控着这么多张，才算「没被打醒」。 */
    private static final int HELD_AFTER_HIT_TICKS = 40;
    private static final int NOT_SEEN = -1;
    private static final String RECORD = "charm_prelaunch.tsv";

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
            Witch witch = stage.computeOnServer(server -> only(server, Witch.class, witchFeet));
            Pillager pillager = stage.computeOnServer(server -> only(server, Pillager.class, pillagerFeet));
            stage.onServer(server -> {
                pillager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
                // 女巫只当施药者：药由场景替她扔，她自己不走动、不另外出手，免得后来的药把这一场搅乱。
                witch.setNoAi(true /* aiDisabled */);
                witch.setPersistenceRequired();
                pillager.setPersistenceRequired();
            });
            stage.look(stage.at(CAMERA), stage.at(CAMERA_TARGET));
            stage.closeScreen();
            stage.command("tick freeze");
            film(stage, witch, pillager);
            stage.command("tick unfreeze");
        }
    }

    private static void film(Stage stage, Witch witch, Pillager pillager) {
        // 一张一个游戏 tick。到点扔伤害药、到点落魅惑；每张记一行事实，拍完核对先后。
        List<String> rows = new ArrayList<>();
        int charmFrame = IDLE_TICKS + CHARM_AFTER_LAUNCH_TICKS;
        int total = charmFrame + CHARM_TICKS + AFTER_TICKS;
        float healthBefore = stage.computeOnServer(server -> pillager.getHealth());
        int hitFrame = NOT_SEEN;
        int wokeFrame = NOT_SEEN;
        for (int frame = 1; frame <= total; frame++) {
            if (frame == IDLE_TICKS) {
                stage.onServer(server -> throwHarm(server, witch, pillager));
            }
            if (frame == charmFrame) {
                stage.onServer(server -> BrewingRuntime.splash(server.overworld(),
                        BrewItemStacks.create(BrewKind.CHARM, BrewQuality.SUPERIOR, BrewUse.SPLASH),
                        pillager.position(), witch));
            }
            stage.command("tick step 1");
            stage.pause(STEP_SETTLE_TICKS);
            stage.shoot(String.format(Locale.ROOT, "charm_prelaunch_%04d", frame));
            Facts facts = stage.computeOnServer(server -> facts(server, pillager));
            if (hitFrame == NOT_SEEN && facts.health() < healthBefore) {
                hitFrame = frame;
            }
            if (wokeFrame == NOT_SEEN && frame > charmFrame && !facts.controlled()) {
                wokeFrame = frame;
            }
            rows.add(frame + "\t" + facts.row());
        }
        write("张\t伤害药\t掠夺者\t掠夺者血\n" + String.join("\n", rows) + "\n");
        verify(charmFrame, hitFrame, wokeFrame);
    }

    private static void throwHarm(MinecraftServer server, Witch witch, Pillager pillager) {
        // 真的药瓶、真的抛物线，归属是女巫：按飞行 tick 数配水平速度，竖直速度补上这段时间里重力要拉掉的。
        ItemStack stack = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HARMING);
        var potion = new ThrownSplashPotion(server.overworld(), witch, stack);
        Vec3 offset = pillager.position().subtract(potion.position());
        double rise = offset.y / FLIGHT_TICKS + THROWN_GRAVITY * FLIGHT_TICKS / 2;
        potion.setDeltaMovement(offset.x / FLIGHT_TICKS, rise, offset.z / FLIGHT_TICKS);
        server.overworld().addFreshEntity(potion);
    }

    private static Facts facts(MinecraftServer server, Pillager pillager) {
        // 这一 tick 的事实：伤害药还在不在天上、掠夺者受没受控、掠夺者的血。
        boolean flying = !server.overworld().getEntitiesOfClass(ThrownSplashPotion.class,
                pillager.getBoundingBox().inflate(PILLAGER_FEET.z)).isEmpty();
        return new Facts(flying, BrewCharmSessions.controlled(pillager.getUUID()), pillager.getHealth());
    }

    private static void verify(int charmFrame, int hitFrame, int wokeFrame) {
        // 这一场要拍到的先后：魅惑先到，先前那瓶后落地并且真伤到了，落地以后还控着。
        if (hitFrame == NOT_SEEN) {
            throw new AssertionError("伤害药没有伤到掠夺者");
        }
        if (hitFrame <= charmFrame) {
            throw new AssertionError(
                    "伤害药在魅惑之前就落地了：第 " + hitFrame + " 张，魅惑在第 " + charmFrame + " 张");
        }
        if (wokeFrame != NOT_SEEN && wokeFrame < hitFrame + HELD_AFTER_HIT_TICKS) {
            throw new AssertionError(
                    "先前那瓶药落地后掠夺者醒了：第 " + hitFrame + " 张落地，第 " + wokeFrame + " 张醒");
        }
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

    private static void write(String content) {
        try {
            Files.writeString(FabricLoader.getInstance().getGameDir().resolve(RECORD), content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** 一张截图对应的事实。 */
    private record Facts(boolean flying, boolean controlled, float health) {
        String row() {
            String potion = flying ? "在天上" : "不在天上";
            String state = controlled ? "受控" : "自由";
            return String.format(Locale.ROOT, "%s\t%s\t%.2f", potion, state, health);
        }
    }
}
