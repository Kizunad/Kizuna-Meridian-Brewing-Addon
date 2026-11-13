package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.test.BrewSites;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 「修行」页的炼药线：熬药式、药方、头一锅、三座药炉各由自己的事件完成，事件之前都还没完成；
 * 本仓每条配方都有一条拿到主材料就解锁的配方进度。
 */
public final class BrewingAdvancementGameTest {
    private static final int TIMEOUT = 100;
    private static final String TOWER_BATCH = "meridian_brewing_test:tower";
    private static final Identifier BASICS = id("brewing/basics");
    private static final Identifier RECIPE = id("brewing/recipe");
    private static final Identifier FIRST_BREW = id("brewing/first_brew");
    private static final Identifier FURNACE_1 = id("brewing/furnace_1");
    private static final Identifier FURNACE_2 = id("brewing/furnace_2");
    private static final Identifier FURNACE_3 = id("brewing/furnace_3");
    private static final Identifier RECIPE_ROOT = Identifier.parse("minecraft:recipes/root");
    private static final String RECIPE_UNLOCK_PREFIX = "recipes/";
    private static final int BREWING_RECIPES = 27;
    /** 药塔北面那块认塔用的玻璃，NBT 里的相对位置；和药塔测试点的是同一格。 */
    private static final BlockPos ENTRY_GLASS = new BlockPos(6, 6, 5);
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

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV1_SITE)
    public void lookingAtShippedLv1CompletesFirstFurnaceOnly(GameTestHelper context) {
        // 铜釜上压着石头时空手看釜不算；搬开再看一次，一阶完成、二阶仍未完成。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV1);
        ServerPlayer player = standingPlayer(context, site);
        BrewCauldronBlockEntity cauldron = cauldron(context, site);
        BlockPos above = site.cauldron().above();
        try {
            context.getLevel().setBlock(above, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            cauldron.use(player, InteractionHand.MAIN_HAND);
            requireOpen(context, player, FURNACE_1, "结构不符时看釜就完成了一阶药炉");
            context.getLevel().setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            cauldron.use(player, InteractionHand.MAIN_HAND);
            requireDone(context, player, FURNACE_1, "守着整座一阶药炉看釜没完成一阶药炉");
            requireOpen(context, player, FURNACE_2, "一阶药炉完成了二阶药炉");
        } finally {
            BrewingTestPlayers.close(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void lookingAtShippedLv2CompletesSecondFurnaceOnly(GameTestHelper context) {
        // 二阶只算二阶，不顺带完成一阶。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV2);
        ServerPlayer player = standingPlayer(context, site);
        try {
            requireOpen(context, player, FURNACE_2, "没看釜就完成了二阶药炉");
            cauldron(context, site).use(player, InteractionHand.MAIN_HAND);
            requireDone(context, player, FURNACE_2, "守着整座二阶药炉看釜没完成二阶药炉");
            requireOpen(context, player, FURNACE_1, "二阶药炉完成了一阶药炉");
        } finally {
            BrewingTestPlayers.close(player);
        }
        context.succeed();
    }

    @GameTest(environment = TOWER_BATCH, maxTicks = TIMEOUT, structure = BrewSites.LV3_SITE)
    public void openingShippedTowerCompletesThirdFurnace(GameTestHelper context) {
        // 空手右键塔上的玻璃认塔开窗才完成；完了删掉这座塔的记录。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV3);
        ServerPlayer player = standingPlayer(context, site);
        ServerLevel level = context.getLevel();
        Set<UUID> before = towerIds(level);
        try {
            requireOpen(context, player, FURNACE_3, "没开药塔就完成了三阶药塔");
            InteractionResult used = rightClickNorth(player, site.world(ENTRY_GLASS));
            context.assertTrue(player.containerMenu instanceof BrewTowerMenu, "右键玻璃没开药塔窗口：" + used);
            requireDone(context, player, FURNACE_3, "打开药塔没完成三阶药塔");
        } finally {
            Set<UUID> added = towerIds(level);
            added.removeAll(before);
            added.forEach(BrewTowerSavedData.of(level)::remove);
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

    private static ServerPlayer standingPlayer(GameTestHelper context, BrewSites.Site site) {
        // 站在结构里够得着釜或塔的那一格，主手空着。
        ServerPlayer player = BrewingTestPlayers.create(context);
        Vec3 stand = Vec3.atBottomCenterOf(site.stand());
        player.setPos(stand.x, stand.y, stand.z);
        return player;
    }

    private static BrewCauldronBlockEntity cauldron(GameTestHelper context, BrewSites.Site site) {
        return (BrewCauldronBlockEntity) context.getLevel().getBlockEntity(site.cauldron());
    }

    private static InteractionResult rightClickNorth(ServerPlayer player, BlockPos pos) {
        // 空手对着这格的北面右键，和站在塔北面点玻璃一样。
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false /* inside */);
        return player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
                hit);
    }

    private static Set<UUID> towerIds(ServerLevel level) {
        // 这个维度现有的塔号；认塔前后一比就知道新建的是哪座。
        Set<UUID> ids = new HashSet<>();
        for (BrewTowerSavedData.Tower tower : BrewTowerSavedData.of(level).towers()) {
            ids.add(tower.id());
        }
        return ids;
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
