package dev.kizuna.meridian.brewing.clienttest;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 截图场地：世界原点地表上的一片空地。摆方块、召唤实体和移动镜头都走服务端命令，与玩家在游戏里输入的相同；
 * 镜头视角、界面隐藏和快捷栏选择走客户端选项与按键。
 */
final class Stage {
    private static final double EYE_HEIGHT = 1.62;
    private static final int SETTLE_TICKS = 10;
    private static final int CLEAR_RADIUS = 16;
    private static final int CLEAR_HEIGHT = 4;
    private static final boolean CLEAR_SENT_HISTORY = false;
    private final ClientGameTestContext context;
    private final TestSingleplayerContext world;
    private final BlockPos origin;

    private Stage(ClientGameTestContext context, TestSingleplayerContext world, BlockPos origin) {
        this.context = context;
        this.world = world;
        this.origin = origin;
    }

    static Stage prepare(ClientGameTestContext context, TestSingleplayerContext world) {
        // 场地原点取 (0, 0) 处的地表；正午、晴天，旁观模式不画手和方块描边。
        world.getConnection().waitForChunksRender();
        int surface = world.getServer().computeOnServer(
                server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0));
        var stage = new Stage(context, world, new BlockPos(0, surface, 0));
        stage.command("time set noon");
        stage.command("weather clear");
        stage.spectate();
        return stage;
    }

    void spectate() {
        command("gamemode spectator @a");
        hideHud();
    }

    void play() {
        // 手持截图要画手和快捷栏，切回创造模式并显示界面。
        command("gamemode creative @a");
        showHud();
    }

    void hideHud() {
        context.runOnClient(client -> {
            if (!client.gui.hud.isHidden()) {
                client.gui.hud.toggle();
            }
        });
    }

    void showHud() {
        context.runOnClient(client -> {
            if (client.gui.hud.isHidden()) {
                client.gui.hud.toggle();
            }
        });
    }

    void clear() {
        // 场景都摆在原点附近，换场前清掉上一场的方块和玩家以外的实体；被整格方块压过的草会变成泥土，地面一并铺回草。
        BlockPos from = origin.offset(-CLEAR_RADIUS, 0, -CLEAR_RADIUS);
        BlockPos to = origin.offset(CLEAR_RADIUS, CLEAR_HEIGHT, CLEAR_RADIUS);
        command("fill " + coordinates(from) + " " + coordinates(to) + " air");
        command("fill " + coordinates(from.below()) + " " + coordinates(to.atY(origin.getY() - 1)) + " grass_block");
        command("kill @e[type=!player]");
    }

    void place(BlockPos offset, String state) {
        command("setblock " + coordinates(origin.offset(offset)) + " " + state);
    }

    void fill(BlockPos from, BlockPos to, String state) {
        command("fill " + coordinates(origin.offset(from)) + " " + coordinates(origin.offset(to)) + " " + state);
    }

    void drop(Vec3 offset, String item) {
        summon("item", at(offset), "{Item:{id:\"" + item + "\",count:1},PickupDelay:32767}");
    }

    void summon(String entity, Vec3 position, String data) {
        command(String.format(Locale.ROOT, "summon %s %.3f %.3f %.3f %s",
                entity, position.x, position.y, position.z, data));
    }

    Vec3 at(Vec3 offset) {
        return Vec3.atLowerCornerOf(origin).add(offset);
    }

    Vec3 center(BlockPos offset) {
        return Vec3.atCenterOf(origin.offset(offset));
    }

    Vec3 eye(BlockPos offset) {
        return Vec3.atBottomCenterOf(origin.offset(offset)).add(0, EYE_HEIGHT, 0);
    }

    void look(Vec3 eye, Vec3 target) {
        // 原版朝向：偏航 0 朝 +Z，俯仰为正时低头；tp 给的是脚底坐标，眼睛在其上方 1.62 格。
        Vec3 direction = target.subtract(eye);
        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        double pitch = Math.toDegrees(Math.atan2(-direction.y, direction.horizontalDistance()));
        command(String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %.2f %.2f",
                eye.x, eye.y - EYE_HEIGHT, eye.z, yaw, pitch));
        settle();
    }

    void selectHotbar(int slot) {
        pressHotbar(slot);
        settle();
    }

    void pressHotbar(int slot) {
        // 只按键不等，动作场景自己按 tick 拍。
        context.getInput().pressKey(options -> options.keyHotbarSlots[slot]);
    }

    void selectHotbarUnsent(int slot) {
        // 模拟换槽包晚到：只改本机槽位，并把原版记的「已发槽位」一起改掉，原版就不发换槽包，服务端不知道。
        context.runOnClient(client -> {
            client.player.getInventory().setSelectedSlot(slot);
            sentHotbarSlot(client.gameMode, slot);
        });
    }

    void resendHotbar(int previous) {
        // 把「已发槽位」改回换之前的，原版下一 tick 比对不一致，自己补发换槽包。
        context.runOnClient(client -> sentHotbarSlot(client.gameMode, previous));
    }

    private static void sentHotbarSlot(MultiPlayerGameMode gameMode, int slot) {
        // 原版没有公开入口，只在截图夹具里改私有字段。
        try {
            Field field = MultiPlayerGameMode.class.getDeclaredField("carriedIndex");
            field.setAccessible(true);
            field.setInt(gameMode, slot);
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("原版 MultiPlayerGameMode 没有 carriedIndex 字段", missing);
        }
    }

    void hand(String slot, String item) {
        // slot 用原版槽位名，例如 weapon.mainhand、weapon.offhand；清空写 air。
        command("item replace entity @a " + slot + " with " + item);
    }

    void use() {
        // 右键点一下：按下一 tick 就松开，与玩家点一下鼠标右键相同。
        context.getInput().pressKey(options -> options.keyUse);
    }

    void container(BlockPos offset, int slot, String item) {
        command("item replace block " + coordinates(origin.offset(offset)) + " container." + slot + " with " + item);
    }

    BlockPos block(BlockPos offset) {
        return origin.offset(offset);
    }

    <T> T computeOnServer(Function<MinecraftServer, T> action) {
        return world.getServer().computeOnServer(action::apply);
    }

    int guiScale(int scale) {
        // 返回原界面缩放，拍完换回。
        int previous = context.computeOnClient(client -> client.options.guiScale().get());
        context.runOnClient(client -> {
            client.options.guiScale().set(scale);
            client.resizeGui();
        });
        settle();
        return previous;
    }

    void holdUse() {
        context.getInput().holdKey(options -> options.keyUse);
    }

    void releaseUse() {
        context.getInput().releaseKey(options -> options.keyUse);
    }

    void turn(float yaw, float pitch) {
        // 服务端命令的 ~ 相对命令源，要先 at 到玩家自己，才是原地转头。
        command(String.format(Locale.ROOT, "execute as @a at @s run tp @s ~ ~ ~ %.2f %.2f", yaw, pitch));
        settle();
    }

    void face(float bodyYaw, float headYaw) {
        // 拍前直接定身体和头的朝向，连同插值用的上一帧值；不推进 tick，镜头和身体都不会再被原版改动。
        // 第三人称正面镜头跟着头转，头比身体多转一点就能拍到身体斜前方。
        context.runOnClient(client -> {
            LocalPlayer player = client.player;
            player.setYRot(headYaw);
            player.yRotO = headYaw;
            player.setYHeadRot(headYaw);
            player.yHeadRotO = headYaw;
            player.setYBodyRot(bodyYaw);
            player.yBodyRotO = bodyYaw;
        });
    }

    void onServer(Consumer<MinecraftServer> action) {
        world.getServer().runOnServer(action::accept);
    }

    void camera(CameraType type) {
        context.runOnClient(client -> client.options.setCameraType(type));
        settle();
    }

    int fov(int degrees) {
        // 返回原视野，拍完换回。
        int previous = context.computeOnClient(client -> client.options.fov().get());
        context.runOnClient(client -> client.options.fov().set(degrees));
        settle();
        return previous;
    }

    void command(String command) {
        world.getServer().runCommand(command);
    }

    void settle() {
        // 等服务端结果同步到客户端、手持切换动画走完，再等区块网格重建。
        context.waitTicks(SETTLE_TICKS);
        world.getConnection().waitForChunksRender();
    }

    void pause(int ticks) {
        context.waitTicks(ticks);
    }

    void shoot(String name) {
        // 命令回显会留在聊天栏里盖住快捷栏，配方解锁提示会盖住右上角，拍之前都清掉；玩家发过的消息历史不清。
        context.runOnClient(client -> {
            client.gui.hud.getChat().clearMessages(CLEAR_SENT_HISTORY);
            client.gui.toastManager().clear();
        });
        context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
