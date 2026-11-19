package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Recipe;

/**
 * 「修行」页的炼药线：熬药式、药方、头一锅、三座药炉各由自己的事件完成，事件之前都还没完成；
 * 本仓每条配方都有一条拿到主材料就解锁的配方进度。
 */
public final class BrewingAdvancementGameTest {
    private static final int TIMEOUT = 100;
    private static final Identifier BASICS = id("brewing/basics");
    private static final Identifier RECIPE = id("brewing/recipe");
    private static final Identifier FIRST_BREW = id("brewing/first_brew");
    private static final Identifier RECIPE_ROOT = Identifier.parse("minecraft:recipes/root");
    private static final String RECIPE_UNLOCK_PREFIX = "recipes/";
    private static final int BREWING_RECIPES = 27;
    private static final List<Unlock> SAMPLE_UNLOCKS = List.of(
            new Unlock("minecraft:copper_ingot", "copper_cauldron"),
            new Unlock("minecraft:oak_planks", "herb_crate"),
            new Unlock("meridian_brewing:dead_oak_log", "dead_oak_log_to_planks"));

    @GameTest(maxTicks = TIMEOUT)
    public void readingBasicsScrollCompletesBrewingForm(GameTestHelper context) {
        // 照玩家的做法拿熬药式卷轴右键学会。
        ServerPlayer player = BrewingTestPlayers.create(context);
        try {
            requireOpen(context, player, BASICS, "没学熬药式就完成了熬药式");
            ItemStack scroll = item("meridian_brewing:brewing_basics_scroll");
            player.setItemInHand(InteractionHand.MAIN_HAND, scroll);
            player.gameMode.useItem(player, player.level(), scroll, InteractionHand.MAIN_HAND);
            requireDone(context, player, BASICS, "用卷轴学会熬药式没完成熬药式");
        } finally {
            BrewingTestPlayers.close(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void readingRecipeScrollCompletesRecipe(GameTestHelper context) {
        // 管理命令直接写进身体不算；读一张没学过的羊皮纸才完成。
        ServerPlayer player = BrewingTestPlayers.create(context);
        try {
            requireOpen(context, player, RECIPE, "没读药方就完成了药方");
            MinecraftServer server = player.level().getServer();
            String learn = "meridian progression recipes learn " + player.getScoreboardName() + " charm";
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), learn);
            requireOpen(context, player, RECIPE, "命令写进药方就完成了药方");
            BrewingTestPlayers.readRecipes(player, BrewKind.QI_GATHER);
            requireDone(context, player, RECIPE, "读了聚气汤药方没完成药方");
        } finally {
            BrewingTestPlayers.close(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void brewedPotionInInventoryCompletesFirstBrew(GameTestHelper context) {
        // 原版的水瓶不算；本仓熬出的药一进背包就完成。
        ServerPlayer player = BrewingTestPlayers.create(context);
        try {
            give(player, PotionContents.createItemStack(Items.POTION, Potions.WATER));
            requireOpen(context, player, FIRST_BREW, "拿到水瓶就完成了头一锅");
            give(player, BrewItemStacks.create(BrewKind.QI_GATHER, BrewQuality.COMMON, BrewUse.DRINK));
            requireDone(context, player, FIRST_BREW, "拿到聚气汤没完成头一锅");
        } finally {
            BrewingTestPlayers.close(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void everyBrewingRecipeHasUnlockAdvancement(GameTestHelper context) {
        // 本仓每条配方都正好被一条挂在原版配方根下的进度解锁。
        MinecraftServer server = context.getLevel().getServer();
        Set<ResourceKey<Recipe<?>>> shipped = new HashSet<>();
        server.getRecipeManager().getRecipes().stream()
                .filter(holder -> holder.id().identifier().getNamespace().equals(BrewingFabric.MOD_ID))
                .forEach(holder -> shipped.add(holder.id()));
        Set<ResourceKey<Recipe<?>>> unlocked = new HashSet<>();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            Identifier advancement = holder.id();
            if (!advancement.getNamespace().equals(BrewingFabric.MOD_ID)
                    || !advancement.getPath().startsWith(RECIPE_UNLOCK_PREFIX)) {
                continue;
            }
            context.assertTrue(holder.value().parent().equals(Optional.of(RECIPE_ROOT)),
                    "配方进度没挂在原版配方根下：" + advancement);
            List<ResourceKey<Recipe<?>>> rewards = holder.value().rewards().recipes();
            context.assertTrue(rewards.size() == 1, "一条配方进度应只解锁一条配方：" + advancement);
            context.assertTrue(unlocked.addAll(rewards), "两条进度解锁同一条配方：" + rewards);
        }
        context.assertTrue(shipped.size() == BREWING_RECIPES, "本仓配方条数变了：" + shipped.size());
        context.assertTrue(unlocked.equals(shipped), "配方进度和配方对不上：解锁 " + unlocked + "，配方 " + shipped);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void pickingUpMainMaterialUnlocksRecipe(GameTestHelper context) {
        // 拿到之前配方书里没有；铜锭、任意木板、枯木原木进背包就解锁对应那条。
        ServerPlayer player = BrewingTestPlayers.create(context);
        try {
            for (Unlock unlock : SAMPLE_UNLOCKS) {
                ResourceKey<Recipe<?>> recipe = unlock.recipe();
                context.assertFalse(player.getRecipeBook().contains(recipe), "没拿材料就解锁了 " + recipe);
                give(player, item(unlock.material()));
                context.assertTrue(player.getRecipeBook().contains(recipe), "拿到 " + unlock.material() + " 没解锁 "
                        + recipe);
            }
        } finally {
            BrewingTestPlayers.close(player);
        }
        context.succeed();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
    }

    private static ItemStack item(String itemId) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId)));
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        // 和捡起来一样进背包；背包变动在菜单同步时报给进度。
        player.getInventory().add(stack);
        player.inventoryMenu.broadcastChanges();
    }

    private static void requireDone(GameTestHelper context, ServerPlayer player, Identifier id, String message) {
        // 进度必须已完成。
        context.assertTrue(done(player, id), message);
    }

    private static void requireOpen(GameTestHelper context, ServerPlayer player, Identifier id, String message) {
        // 进度必须还没完成。
        context.assertFalse(done(player, id), message);
    }

    private static boolean done(ServerPlayer player, Identifier id) {
        // 进度文件必须已加载，缺了直接报错而不是当作未完成。
        AdvancementHolder advancement = player.level().getServer().getAdvancements().get(id);
        if (advancement == null) {
            throw new AssertionError("进度没有加载：" + id);
        }
        return player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    /** 一条抽查的配方解锁：拿到什么、解锁本仓哪条配方。 */
    private record Unlock(String material, String recipePath) {
        ResourceKey<Recipe<?>> recipe() {
            return ResourceKey.create(Registries.RECIPE, id(recipePath));
        }
    }
}
