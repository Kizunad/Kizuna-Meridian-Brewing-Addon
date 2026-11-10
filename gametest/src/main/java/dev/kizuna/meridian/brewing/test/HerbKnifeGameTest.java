package dev.kizuna.meridian.brewing.test;

import dev.kizuna.meridian.brewing.test.BrewingTestPlayers.AnimationSent;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 药刀按住切药与甩腕开刃：走原版使用、松手和切快捷栏入口，断言实际发出的 PA 包和声音包。 */
public final class HerbKnifeGameTest {
    private static final int TIMEOUT = 100;
    private static final int CUT_TICKS = 20;
    private static final int SETTLE_TICK = 11;
    private static final int OBSERVE_TICKS = 3;
    private static final String CUT = "herb_knife_cut";
    private static final String DRAW = "herb_knife_draw";
    private static final int POTATOES = 2;
    private static final int EMPTY_SLOT = 0;
    private static final int KNIFE_SLOT = 1;

    @GameTest(maxTicks = TIMEOUT)
    public void holdingUseCutsOnTheEleventhTickAndPlaysTheCutClip(GameTestHelper context) {
        ServerPlayer player = cutter(context);
        long started = context.getLevel().getGameTime();
        context.assertTrue(use(player) == InteractionResult.CONSUME && player.isUsingItem(), "主手药刀、副手药材没有开始使用");
        // 每 tick 核对：用到第 10 tick 还没出片，第 11 tick 起有一片且药材少一份。
        context.onEachTick(() -> {
            if (!player.isUsingItem()) {
                return;
            }
            int used = player.getTicksUsingItem();
            int slices = count(player, item("poison_potato_slice"));
            boolean settled = used >= SETTLE_TICK;
            context.assertTrue(slices == (settled ? 1 : 0), "第 " + used + " tick 的毒薯片数不符：" + slices);
            context.assertTrue(player.getOffhandItem().getCount() == POTATOES - (settled ? 1 : 0),
                    "第 " + used + " tick 的副手药材数不符");
        });
        context.runAfterDelay(CUT_TICKS + OBSERVE_TICKS, () -> {
            context.assertTrue(!player.isUsingItem(), "用满 20 tick 没有结束使用");
            context.assertTrue(count(player, item("poison_potato_slice")) == 1, "一刀没有恰好出一片");
            context.assertTrue(player.getMainHandItem().getDamageValue() == 1, "一刀没有让药刀耗损 1");
            context.assertTrue(plays(player, CUT).equals(List.of(started)), "切药剪辑没有从开始使用的 tick 播一次："
                    + BrewingTestPlayers.animations(player));
            long cutSounds = BrewingTestPlayers.sounds(player).stream().filter(sound -> sound.packet().getSound()
                    .value().location().toString().equals("meridian_brewing:knife.cut")).count();
            context.assertTrue(cutSounds == 1, "出片没有恰好响一声 knife.cut：" + cutSounds);
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void releasingOnTheTenthTickGivesNothingAndStopsTheClip(GameTestHelper context) {
        ServerPlayer player = cutter(context);
        use(player);
        boolean[] released = {false};
        context.onEachTick(() -> {
            if (!released[0] && player.getTicksUsingItem() == SETTLE_TICK - 1) {
                // 与原版松开使用键的包同一入口。
                player.releaseUsingItem();
                released[0] = true;
            }
        });
        context.runAfterDelay(CUT_TICKS + OBSERVE_TICKS, () -> {
            context.assertTrue(released[0] && !player.isUsingItem(), "第 10 tick 没有松手");
            context.assertTrue(count(player, item("poison_potato_slice")) == 0, "第 10 tick 松手仍出了片");
            context.assertTrue(player.getOffhandItem().getCount() == POTATOES, "第 10 tick 松手仍扣了药材");
            context.assertTrue(player.getMainHandItem().getDamageValue() == 0, "第 10 tick 松手仍耗损了药刀");
            context.assertTrue(BrewingTestPlayers.animations(player).stream()
                    .anyMatch(sent -> !sent.play() && sent.animationId().equals(CUT)), "松手没有停止切药剪辑");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void offhandKnifeOrMissingHerbDoesNotStartCutting(GameTestHelper context) {
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.POISONOUS_POTATO, POTATOES));
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(item("herb_knife")));
        player.gameMode.useItem(player, context.getLevel(), player.getOffhandItem(), InteractionHand.OFF_HAND);
        context.assertTrue(!player.isUsingItem(), "副手拿药刀也开始了切药");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item("herb_knife")));
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SWEET_BERRIES));
        context.assertTrue(use(player) != InteractionResult.CONSUME && !player.isUsingItem(), "副手不是药材也开始了切药");
        context.runAfterDelay(OBSERVE_TICKS, () -> {
            context.assertTrue(plays(player, CUT).isEmpty(), "没开始切药也播了切药剪辑");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void switchingTheMainHandToTheKnifePlaysTheDrawEachTime(GameTestHelper context) {
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.getInventory().setItem(KNIFE_SLOT, new ItemStack(item("herb_knife")));
        context.runAfterDelay(1, () -> select(player, KNIFE_SLOT));
        context.runAfterDelay(1 + OBSERVE_TICKS, () -> {
            context.assertTrue(plays(player, DRAW).size() == 1, "切到药刀没有播一次开刃："
                    + BrewingTestPlayers.animations(player));
            select(player, EMPTY_SLOT);
        });
        context.runAfterDelay(1 + 2 * OBSERVE_TICKS, () -> select(player, KNIFE_SLOT));
        context.runAfterDelay(1 + 3 * OBSERVE_TICKS, () -> {
            context.assertTrue(plays(player, DRAW).size() == 2, "换走再切回药刀没有再播开刃");
            // 副手拿药刀不算拔刀。
            select(player, EMPTY_SLOT);
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(item("herb_knife")));
        });
        context.runAfterDelay(1 + 4 * OBSERVE_TICKS, () -> {
            context.assertTrue(plays(player, DRAW).size() == 2, "副手拿药刀也播了开刃");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void joiningWithTheKnifeAlreadyInHandDoesNotPlayTheDraw(GameTestHelper context) {
        ServerPlayer first = BrewingTestPlayers.create(context);
        first.getInventory().setItem(KNIFE_SLOT, new ItemStack(item("herb_knife")));
        select(first, KNIFE_SLOT);
        context.runAfterDelay(OBSERVE_TICKS, () -> {
            BrewingTestPlayers.close(first);
            ServerPlayer rejoined = BrewingTestPlayers.rejoin(context, first);
            context.assertTrue(rejoined.getMainHandItem().is(item("herb_knife")), "重新登录没有恢复主手药刀");
            context.runAfterDelay(OBSERVE_TICKS, () -> {
                context.assertTrue(plays(rejoined, DRAW).isEmpty(), "上线时主手已是药刀也播了开刃");
                BrewingTestPlayers.close(rejoined);
                context.succeed();
            });
        });
    }

    private static ServerPlayer cutter(GameTestHelper context) {
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item("herb_knife")));
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.POISONOUS_POTATO, POTATOES));
        return player;
    }

    private static InteractionResult use(ServerPlayer player) {
        // 与服务端处理使用物品包同一入口。
        return player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
    }

    private static void select(ServerPlayer player, int slot) {
        // 与客户端切快捷栏发来的包同一处理。
        player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));
    }

    private static List<Long> plays(ServerPlayer player, String animation) {
        return BrewingTestPlayers.animations(player).stream()
                .filter(sent -> sent.play() && sent.animationId().equals(animation))
                .map(AnimationSent::startTick).toList();
    }

    private static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static Item item(String path) {
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("meridian_brewing", path));
    }
}
