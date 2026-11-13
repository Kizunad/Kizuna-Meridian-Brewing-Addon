package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewActionResult;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
 * 游戏里的讲解和药炉提示：药方羊皮纸翻开自己那一篇，字迹模糊的不翻；《熬药总纲》翻开总纲、能合成；
 * 釜不在药炉里时，提示接上它该进哪一座药炉、结构蓝图里照哪张图样摆。
 *
 * <p>翻没翻开看服务端真正发给客户端的翻书包，客户端怎么排页由截图场景看。</p>
 */
public final class BrewingLoreGameTest {
    private static final int TIMEOUT = 40;
    private static final int PACKET_TICKS = 1;
    private static final String LEARNED_KEY = "message.meridian_brewing.recipe.learned";
    private static final String ILLEGIBLE_KEY = "message.meridian_brewing.recipe.illegible";
    private static final String DESCRIBE_KEY = "message.meridian_brewing.cauldron.describe";
    private static final String HINT_KEY = "message.meridian_brewing.structure.hint.tier";
    private static final BlockPos CAULDRON = new BlockPos(1, 1, 1);
    private static final int COPPER_TIER = 1;
    private static final int WITCH_TIER = 2;
    private static final int COPPER_BUCKETS = 1;
    private static final int WITCH_BUCKETS = 2;

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
    public void loneCopperCauldronNamesTheTierOneFurnace(GameTestHelper context) {
        // 铜釜孤零零放在地上：空手看釜和起锅被拒，第一处不符后面都接上「该坐进一阶药炉」和蓝图图样。
        loneCauldronNamesItsFurnace(context, BrewingBlocks.COPPER_CAULDRON, COPPER_BUCKETS, COPPER_TIER);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void loneWitchCauldronNamesTheTierTwoFurnace(GameTestHelper context) {
        // 巫釜孤零零放在地上：同上，说的是二阶药炉。
        loneCauldronNamesItsFurnace(context, BrewingBlocks.WITCH_CAULDRON, WITCH_BUCKETS, WITCH_TIER);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void apparatusTooltipsNameTheirFurnace(GameTestHelper context) {
        // 铜釜、巫釜写属于哪一座药炉和蓝图图样，铁炉座写是二阶的火，铁火盆写只用来合成铁炉座；
        // 铜釜另写氧化和上蜡，晒药匾写什么时候才晒。
        assertTooltip(context, BrewingBlocks.COPPER_CAULDRON,
                List.of("apparatus.copper_cauldron", "apparatus.copper_cauldron.layout",
                        "apparatus.copper_cauldron.oxidation", "apparatus.copper_cauldron.wax"));
        assertTooltip(context, BrewingBlocks.WITCH_CAULDRON,
                List.of("apparatus.witch_cauldron", "apparatus.witch_cauldron.layout"));
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

    private static void loneCauldronNamesItsFurnace(GameTestHelper context, Block block, int buckets, int tier) {
        // 先空手看一眼，再下水、下聚气汤的料、读药方起锅；两句话都要带这一阶的提示键。
        context.setBlock(CAULDRON, block);
        BrewCauldronBlockEntity cauldron = (BrewCauldronBlockEntity) context.getLevel()
                .getBlockEntity(context.absolutePos(CAULDRON));
        ServerPlayer player = BrewingTestPlayers.create(context);
        String hintKey = HINT_KEY + tier;
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        cauldron.use(player, InteractionHand.MAIN_HAND);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET, buckets));
        for (int bucket = 0; bucket < buckets; bucket++) {
            cauldron.use(player, InteractionHand.MAIN_HAND);
        }
        useOn(cauldron, player, item("meridian_brewing:dried_pufferfish"));
        useOn(cauldron, player, new ItemStack(Items.GLOWSTONE_DUST));
        BrewingTestPlayers.readRecipes(player, BrewKind.QI_GATHER);
        BrewActionResult refused = cauldron.startBrew(player);
        context.assertFalse(refused.accepted(), "孤零零的釜起锅了");
        String refusedText = BrewTexts.chinese(refused.message());
        String furnace = BrewTexts.chinese(BrewText.message("structure.hint.tier" + tier));
        context.assertTrue(refusedText.endsWith(furnace), "起锅被拒没说该进哪一座药炉：" + refusedText);
        context.runAfterDelay(PACKET_TICKS, () -> {
            List<Set<String>> described = BrewingTestPlayers.overlays(player).stream()
                    .map(sent -> BrewingTestPlayers.translationKeys(sent.text()))
                    .filter(keys -> keys.contains(DESCRIBE_KEY))
                    .toList();
            context.assertTrue(described.size() == 1, "空手看釜该说一次釜的状态：" + described);
            context.assertTrue(described.getFirst().contains(hintKey),
                    "空手看釜没说该进哪一座药炉：" + described.getFirst());
            BrewingTestPlayers.close(player);
            context.succeed();
        });
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

    private static void useOn(BrewCauldronBlockEntity cauldron, ServerPlayer player, ItemStack stack) {
        // 主手拿着对釜右键，走方块实体的投料入口。
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        cauldron.use(player, InteractionHand.MAIN_HAND);
    }

    private static ItemStack item(String id) {
        // 按注册 ID 取一份物品，炮制药材在 fabric 模块里注册。
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
    }
}
