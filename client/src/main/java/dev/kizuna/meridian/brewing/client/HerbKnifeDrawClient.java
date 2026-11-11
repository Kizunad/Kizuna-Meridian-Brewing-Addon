package dev.kizuna.meridian.brewing.client;

import dev.kizuna.playeranimation.animation.PlayerAnimationAction;
import dev.kizuna.playeranimation.client.PlayerAnimations;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * 本机主手从别的物品换成药刀时，本人客户端当场播甩腕开刃，不等服务端的包；服务端那份只发给追踪者。
 *
 * <p>数字键在客户端 tick 里先换槽位，同一 tick 末尾就开播，换刀后画的第一帧已经在这段剪辑里。
 * 本机玩家换了实例（进世界、重生、换维度）只记下当前主手，不算「换成药刀」，和服务端一致；副手拿药刀不播；
 * 换走药刀不停，开刃播满再淡出。
 *
 * <p>新实例的背包要等服务端整包同步才有东西，登录包和背包包之间隔着一串别的包，可能跨帧处理；
 * 同步之前主手是空的，这段时间不算见过这名玩家，免得把背包到达当成换刀。
 */
final class HerbKnifeDrawClient {
    private static final Identifier HERB_KNIFE = BrewingClient.id("herb_knife");
    /** 背包菜单的状态号新建时是 0，服务端第一次整包同步后离开 0。 */
    private static final int UNSYNCED_STATE_ID = 0;
    private static LocalPlayer seen;
    private static boolean holding;

    private HerbKnifeDrawClient() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(HerbKnifeDrawClient::tick);
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || player.inventoryMenu.getStateId() == UNSYNCED_STATE_ID) {
            seen = null;
            return;
        }
        boolean holdingNow = holdsKnife(player);
        boolean switched = player == seen && holdingNow && !holding;
        seen = player;
        holding = holdingNow;
        if (switched) {
            PlayerAnimations.play(player.getUUID(), PlayerAnimationAction.HERB_KNIFE_DRAW);
        }
    }

    private static boolean holdsKnife(LocalPlayer player) {
        return BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).equals(HERB_KNIFE);
    }
}
