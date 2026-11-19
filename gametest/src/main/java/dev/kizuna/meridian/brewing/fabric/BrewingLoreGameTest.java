package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * 游戏里的药方、总纲和单口釜提示。
 *
 * <p>翻没翻开看服务端真正发给客户端的翻书包，客户端怎么排页由截图场景看。</p>
 */
public final class BrewingLoreGameTest {
    private static final long PREPARED_ON = 10;
    private static final int TIMEOUT = 40;
    private static final int PACKET_TICKS = 1;
    private static final String LEARNED_KEY = "message.meridian_brewing.recipe.learned";
    private static final String ILLEGIBLE_KEY = "message.meridian_brewing.recipe.illegible";

    @GameTest(maxTicks = TIMEOUT)
    public void everyRecipeScrollOpensItsOwnLore(GameTestHelper context) {
        // 六张药方各读一次：照旧学会、照旧在动作栏说学会了，同时翻开的正好是这一张的讲解，按读的先后。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewingTestPlayers.readAllRecipes(player);
        context.runAfterDelay(PACKET_TICKS, () -> {
            List<String> everyKind = Arrays.stream(BrewKind.values()).map(BrewKind::path).toList();
            List<String> opened = BrewingTestPlayers.loreOpened(player);
            context.assertTrue(opened.equals(everyKind), "药方没有各自翻开自己的讲解：" + opened);
            Set<BrewKind> known = RecipeKnowledge.read(BrewingTestPlayers.body(player));
            context.assertTrue(known.equals(EnumSet.allOf(BrewKind.class)), "读过六张药方没有都学会：" + known);
            long learned = BrewingTestPlayers.overlays(player).stream()
                    .filter(sent -> BrewingTestPlayers.translationKeys(sent.text()).contains(LEARNED_KEY))
                    .count();
            context.assertTrue(learned == everyKind.size(), "动作栏没有每张都说学会了：" + learned);
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void illegibleScrollOpensNoLore(GameTestHelper context) {
        // 没写药方的、写了认不出的药方的羊皮纸：动作栏照旧说字迹模糊，不翻开任何讲解。
        ServerPlayer player = BrewingTestPlayers.create(context);
        ItemStack blank = RecipeScrollItem.stack(BrewKind.DEPLETION);
        blank.remove(BrewingComponents.RECIPE_KIND);
        ItemStack unknown = RecipeScrollItem.stack(BrewKind.DEPLETION);
        unknown.set(BrewingComponents.RECIPE_KIND, "unknown_brew");
        List<ItemStack> faded = List.of(blank, unknown);
        faded.forEach(stack -> use(player, stack));
        context.runAfterDelay(PACKET_TICKS, () -> {
            List<String> opened = BrewingTestPlayers.loreOpened(player);
            context.assertTrue(opened.isEmpty(), "字迹模糊的羊皮纸也翻开了讲解：" + opened);
            long illegible = BrewingTestPlayers.overlays(player).stream()
                    .filter(sent -> BrewingTestPlayers.translationKeys(sent.text()).contains(ILLEGIBLE_KEY))
                    .count();
            context.assertTrue(illegible == faded.size(), "两张都该说字迹模糊：" + illegible);
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void manualOpensOverviewAndIsCraftable(GameTestHelper context) {
        // 拿着总纲用一下翻开总纲那一篇，书留在手里；一本书加一份萤石粉无序合成一本总纲。
        ServerPlayer player = BrewingTestPlayers.create(context);
        ItemStack manual = new ItemStack(BrewingItems.item("brew_manual"));
        use(player, manual);
        context.assertTrue(player.getMainHandItem().is(BrewingItems.item("brew_manual")), "读完总纲书不见了");
        ServerLevel level = context.getLevel();
        assertCrafts(context, level, List.of(new ItemStack(Items.BOOK), new ItemStack(Items.GLOWSTONE_DUST)));
        assertCrafts(context, level, List.of(new ItemStack(Items.GLOWSTONE_DUST), new ItemStack(Items.BOOK)));
        context.runAfterDelay(PACKET_TICKS, () -> {
            List<String> opened = BrewingTestPlayers.loreOpened(player);
            context.assertTrue(opened.equals(List.of(BrewLore.OVERVIEW)), "总纲没有翻开总纲那一篇：" + opened);
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void apparatusTooltipsDescribeStandaloneUse(GameTestHelper context) {
        assertTooltip(context, BrewingBlocks.COPPER_CAULDRON,
                List.of("apparatus.copper_cauldron",
                        "apparatus.copper_cauldron.oxidation", "apparatus.copper_cauldron.wax"));
        assertTooltip(context, BrewingBlocks.WITCH_CAULDRON,
                List.of("apparatus.witch_cauldron"));
        assertTooltip(context, BrewingBlocks.IRON_STOVE, List.of("apparatus.iron_stove"));
        assertTooltip(context, BrewingBlocks.IRON_BRAZIER, List.of("apparatus.iron_brazier"));
        assertTooltip(context, BrewingBlocks.HERB_CRATE, List.of("apparatus.herb_crate"));
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void charmBrewTooltipTellsWhoItFollowsAndWhatWakesIt(GameTestHelper context) {
        // 魅惑药水除了秒数，还写受惑者跟谁、打谁，什么时候醒。
        ItemStack charm = BrewItemStacks.create(BrewKind.CHARM, BrewQuality.COMMON, BrewUse.SPLASH);
        List<String> keys = BrewTooltipLines.lines(charm).stream()
                .map(line -> String.join(",", BrewingTestPlayers.translationKeys(line)))
                .toList();
        context.assertTrue(keys.contains("tooltip.meridian_brewing.charm.follow")
                && keys.contains("tooltip.meridian_brewing.charm.wake"), "魅惑药水的提示没写规矩：" + keys);
        context.succeed();
    }

    private static void assertCrafts(GameTestHelper context, ServerLevel level, List<ItemStack> ingredients) {
        // 走服务器的配方表找配方，再按这份料合出来，和工作台一样。
        CraftingInput input = CraftingInput.of(ingredients.size(), 1, ingredients);
        Optional<RecipeHolder<CraftingRecipe>> found = level.getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level);
        context.assertTrue(found.isPresent(), "这份料合不出东西：" + ingredients);
        ItemStack result = found.orElseThrow().value().assemble(input);
        context.assertTrue(result.is(BrewingItems.item("brew_manual")) && result.getCount() == 1,
                "书加萤石粉合出来的不是一本熬药总纲：" + result);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void preparedHerbTooltipTellsItsDayAndPotency(GameTestHelper context) {
        // 炮制药材写炮制了几天、药性还剩几成，再写一行怎么退；数字照药性曲线来。
        ItemStack moss = new ItemStack(BrewingItems.item("dry_moss"));
        moss.set(BrewingComponents.PREPARED_DAY, PREPARED_ON);
        assertPrepared(context, moss, 0, "prepared.today", List.of());
        assertPrepared(context, moss, 2, "prepared.fresh", List.of(2L));
        assertPrepared(context, moss, 3, "prepared.fresh", List.of(3L));
        assertPrepared(context, moss, 5, "prepared.fading", List.of(5L, 87L));
        assertPrepared(context, moss, 9, "prepared.spent", List.of(9L, 60L));
        assertPrepared(context, moss, 20, "prepared.spent", List.of(20L, 60L));
        List<Component> rule = BrewTooltipLines.preparedLines(moss, PREPARED_ON);
        var ruleText = (TranslatableContents) rule.get(1).getContents();
        context.assertTrue(ruleText.getKey().equals("tooltip.meridian_brewing.prepared.rule")
                && List.of(ruleText.getArgs()).equals(List.of(3L, 9L, 60L)), "药性怎么退那一行不对：" + ruleText);

        // 还没记上炮制日的、日期对不上的、不是炮制药材的，都不写。
        context.assertTrue(BrewTooltipLines.preparedLines(new ItemStack(BrewingItems.item("dry_moss")), PREPARED_ON)
                .isEmpty(), "没有炮制日的药材写了天数");
        context.assertTrue(BrewTooltipLines.preparedLines(moss, PREPARED_ON - 1).isEmpty(), "炮制日在今天之后还写了天数");
        ItemStack stick = new ItemStack(Items.STICK);
        stick.set(BrewingComponents.PREPARED_DAY, PREPARED_ON);
        context.assertTrue(BrewTooltipLines.preparedLines(stick, PREPARED_ON).isEmpty(), "不是炮制药材的物品写了天数");
        context.succeed();
    }

    private static void assertPrepared(GameTestHelper context, ItemStack stack, long age, String suffix,
                                       List<Long> arguments) {
        // 第一行是天数和药性，语言键和参数都要对。
        List<Component> lines = BrewTooltipLines.preparedLines(stack, PREPARED_ON + age);
        context.assertTrue(lines.size() == 2, "第 " + age + " 天的提示不是两行：" + lines);
        var day = (TranslatableContents) lines.get(0).getContents();
        context.assertTrue(day.getKey().equals("tooltip.meridian_brewing." + suffix)
                && List.of(day.getArgs()).equals(arguments), "第 " + age + " 天的提示不对：" + day);
    }

    private static void assertTooltip(GameTestHelper context, Block block, List<String> suffixes) {
        // 提示每一行一个语言键，按顺序和期望的一样。
        List<Component> lines = BrewTooltipLines.lines(new ItemStack(block));
        List<String> keys = lines.stream()
                .map(line -> String.join(",", BrewingTestPlayers.translationKeys(line)))
                .toList();
        List<String> expected = suffixes.stream().map(suffix -> "tooltip.meridian_brewing." + suffix).toList();
        context.assertTrue(keys.equals(expected), block + " 的提示行不对：" + keys);
    }

    private static void use(ServerPlayer player, ItemStack stack) {
        // 主手拿着用一下，和玩家右键空气一样走物品的使用入口。
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        stack.getItem().use(player.level(), player, InteractionHand.MAIN_HAND);
    }
}
