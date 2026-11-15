package dev.kizuna.meridian.brewing.fabric;

import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import dev.kizuna.meridian.origin.fabric.Origins;
import java.util.EnumSet;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 出身天赋 {@code meridian_brewing:starting_recipes}：选定出身时把写着的药方并进玩家会的药方，原来会的不丢；
 * 发过一次就记下，进服不再补发；认不出的药方和空列表在读出身数据时就拒。
 */
public final class BrewingOriginGameTest {
    private static final int TIMEOUT = 100;
    /** 出身附属在进服后的 tick 里读身体；等几 tick 让它读完。 */
    private static final int SETTLE_TICKS = 5;
    private static final String ORIGIN = "meridian_brewing_test:apprentice";

    @GameTest(maxTicks = TIMEOUT)
    public void startingRecipesJoinRecipesAlreadyKnown(GameTestHelper context) {
        // 先照玩家的做法读一张魅惑药水，再定出身：两张开局药方并进来，魅惑药水还在。
        ServerPlayer player = BrewingTestPlayers.create(context);
        try {
            BrewingTestPlayers.readRecipes(player, BrewKind.CHARM);
            choose(context, player);
            requireKnown(context, player, EnumSet.of(BrewKind.CHARM, BrewKind.QI_GATHER, BrewKind.BITTER_YELLOW),
                    "定了出身，开局药方没并进已会的药方");
        } finally {
            BrewingTestPlayers.close(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void startingRecipesAreGrantedOnce(GameTestHelper context) {
        // 发过以后把药方清掉再进服：一次性天赋已经记下，出身附属读完身体也不会再补发。
        ServerPlayer player = BrewingTestPlayers.create(context);
        choose(context, player);
        BrewRecipeBook.replace(player, EnumSet.noneOf(BrewKind.class));
        BrewingTestPlayers.close(player);
        ServerPlayer rejoined = BrewingTestPlayers.rejoin(context, player);
        context.runAfterDelay(SETTLE_TICKS, () -> {
            try {
                context.assertTrue(Origins.chosen(rejoined).isPresent(), Component.literal("重进服以后出身附属还没读到身体"));
                requireKnown(context, rejoined, EnumSet.noneOf(BrewKind.class), "开局药方发过一次，进服又补发了");
            } finally {
                BrewingTestPlayers.close(rejoined);
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void startingRecipesRejectUnknownOrEmptyList(GameTestHelper context) {
        // 读出身数据时就拒：药方名写错的、一张都没写的。
        requireRejected(context, "{\"recipes\":[\"qi_gather\",\"no_such_recipe\"]}", "认不出的药方没被拒");
        requireRejected(context, "{\"recipes\":[]}", "空的药方列表没被拒");
        context.succeed();
    }

    private static void choose(GameTestHelper context, ServerPlayer player) {
        // 走出身附属的管理指令定出身，和玩家选定一样发一次性天赋。
        MinecraftServer server = context.getLevel().getServer();
        String command = "meridian debug origin set " + player.getScoreboardName() + " " + ORIGIN;
        try {
            int result = server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
            context.assertTrue(result > 0, Component.literal("定出身的指令没成：" + command));
        } catch (CommandSyntaxException error) {
            context.fail(Component.literal("定出身的指令读不懂：" + error.getMessage()));
        }
    }

    private static void requireKnown(GameTestHelper context, ServerPlayer player, Set<BrewKind> expected,
            String message) {
        // 读 Human 的正式身体，和女巫、羊皮纸读的是同一份药方。
        Set<BrewKind> known = RecipeKnowledge.read(BrewingTestPlayers.body(player));
        context.assertTrue(known.equals(expected), Component.literal(message + "：" + known));
    }

    private static void requireRejected(GameTestHelper context, String json, String message) {
        // 参数解码失败就说明出身数据包会在这一条上读取失败。
        var result = BrewOrigins.PARAMS.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
        context.assertTrue(result.isError(), Component.literal(message + "：" + json));
    }
}
