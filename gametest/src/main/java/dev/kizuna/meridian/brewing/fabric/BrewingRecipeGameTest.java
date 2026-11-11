package dev.kizuna.meridian.brewing.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.kizuna.meridian.brewing.server.BrewActionResult;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** 学过药方才能熬：药方记在身体进度里，旧玩家存档并入一次，指令能列、能学、能忘。 */
public final class BrewingRecipeGameTest {
    private static final int TIMEOUT = 100;
    private static final BlockPos CAULDRON = new BlockPos(2, 2, 2);
    private static final int FUEL = 3;
    private static final int FILLING_BUCKETS = 2;
    private static final String NOT_LEARNED = "未学此方：聚气汤。先读药方羊皮纸";
    private static final String LEARNED_KEY = "message.meridian_brewing.recipe.learned";
    private static final String KNOWN_KEY = "message.meridian_brewing.recipe.already_known";
    private static final String ILLEGIBLE_KEY = "message.meridian_brewing.recipe.illegible";
    private static final String LEGACY_PROGRESS = "addon_progress";
    private static final int OVERLAY_TICKS = 1;

    @GameTest(maxTicks = TIMEOUT)
    public void unlearnedRecipeRefusesToStartEvenInCreative(GameTestHelper context) {
        // 釜这边全齐，施法者没读过聚气汤药方：创造模式也起不了熬，读过羊皮纸才行。
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.setGameMode(GameType.CREATIVE);
        BrewCauldronBlockEntity cauldron = preparedQiGather(context, player);

        BrewActionResult refused = cauldron.startBrew(player);
        context.assertFalse(refused.accepted(), "没读过药方也起熬了");
        context.assertTrue(BrewTexts.chinese(refused.message()).equals(NOT_LEARNED),
                "未学被拒的提示不对：" + BrewTexts.chinese(refused.message()));
        context.assertTrue(cauldron.cauldron().state() == BrewCauldronState.RAW,
                "被拒以后釜不该进熬制");

        BrewingTestPlayers.readRecipes(player, BrewKind.QI_GATHER);
        context.assertTrue(known(player).equals(EnumSet.of(BrewKind.QI_GATHER)),
                "读过羊皮纸后身体进度里的药方不对：" + known(player));
        BrewingTestPlayers.stockQi(player);
        context.assertTrue(cauldron.startBrew(player).accepted(), "读过药方还是起不了熬");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void recipeScrollMessagesAndRejoin(GameTestHelper context) {
        // 读一次学会，再读提示早已读过；字迹模糊的不学；重进世界药方还在，玩家存档不再写药方。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewingTestPlayers.readRecipes(player, BrewKind.CHARM);
        BrewingTestPlayers.readRecipes(player, BrewKind.CHARM);
        ItemStack illegible = BrewingTestPlayers.recipeScroll(BrewKind.DEPLETION);
        illegible.remove(BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(
                Identifier.parse("meridian_brewing:recipe_kind")));
        player.setItemInHand(InteractionHand.MAIN_HAND, illegible);
        illegible.getItem().use(player.level(), player, InteractionHand.MAIN_HAND);
        player.closeContainer();
        // 动作栏包在服务器 tick 末尾才从测试连接里取出来，等一 tick 再看。
        context.runAfterDelay(OVERLAY_TICKS, () -> assertScrollMessages(context, player));
    }

    private static void assertScrollMessages(GameTestHelper context, ServerPlayer player) {
        // 三次提示依次是学会、早已读过、字迹模糊；身体上只有魅惑药方。
        List<String> keys = BrewingTestPlayers.overlays(player).stream()
                .map(sent -> BrewingTestPlayers.translationKeys(sent.text()))
                .map(found -> found.contains(LEARNED_KEY) ? LEARNED_KEY
                        : found.contains(KNOWN_KEY) ? KNOWN_KEY
                        : found.contains(ILLEGIBLE_KEY) ? ILLEGIBLE_KEY : "other")
                .filter(key -> !key.equals("other")).toList();
        context.assertTrue(keys.equals(List.of(LEARNED_KEY, KNOWN_KEY, ILLEGIBLE_KEY)),
                "三次读羊皮纸的提示不对：" + keys);
        context.assertTrue(known(player).equals(EnumSet.of(BrewKind.CHARM)),
                "字迹模糊的羊皮纸不该教会任何药方：" + known(player));
        context.assertFalse(playerSave(player).contains(LEGACY_PROGRESS), "药方又写进了玩家存档");

        BrewingTestPlayers.close(player);
        ServerPlayer rejoined = BrewingTestPlayers.rejoin(context, player);
        context.assertTrue(known(rejoined).equals(EnumSet.of(BrewKind.CHARM)),
                "重进世界后身体上的药方不对：" + known(rejoined));
        BrewingTestPlayers.close(rejoined);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void legacyPlayerRecipesMergeIntoBodyOnce(GameTestHelper context) {
        // 旧版存在玩家存档里的药方读档时挂在实体上，并入身体之前照原样写回，并入以后不再写。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewingTestPlayers.readRecipes(player, BrewKind.QI_GATHER);
        CompoundTag saved = playerSave(player);
        CompoundTag legacy = new CompoundTag();
        legacy.putBoolean(BrewKind.CHARM.path(), true);
        legacy.putBoolean(BrewKind.DEPLETION.path(), true);
        legacy.putBoolean(BrewKind.ARMOR_ROT.path(), false);
        CompoundTag progress = new CompoundTag();
        progress.put(RecipeKnowledge.PROGRESS_KEY, legacy);
        saved.put(LEGACY_PROGRESS, progress);
        player.load(TagValueInput.create(ProblemReporter.DISCARDING, player.level().registryAccess(), saved));

        Set<BrewKind> pending = ((LegacyRecipeHolder) player).meridianBrewing$legacyRecipes();
        context.assertTrue(pending.equals(EnumSet.of(BrewKind.CHARM, BrewKind.DEPLETION)),
                "旧存档的药方没有读出来：" + pending);
        CompoundTag beforeMerge = playerSave(player).getCompoundOrEmpty(LEGACY_PROGRESS)
                .getCompoundOrEmpty(RecipeKnowledge.PROGRESS_KEY);
        context.assertTrue(beforeMerge.getBooleanOr(BrewKind.CHARM.path(), false)
                        && beforeMerge.getBooleanOr(BrewKind.DEPLETION.path(), false),
                "并入身体之前旧记录没有原样写回：" + beforeMerge);

        BrewRecipeBook.Lookup merged = BrewRecipeBook.lookup(player);
        context.assertTrue(merged.known().equals(EnumSet.of(BrewKind.QI_GATHER, BrewKind.CHARM, BrewKind.DEPLETION)),
                "旧记录没有和身体上的药方取并集：" + merged.known());
        context.assertTrue(known(player).equals(merged.known()), "并入的药方没有写进身体进度");
        context.assertTrue(((LegacyRecipeHolder) player).meridianBrewing$legacyRecipes().isEmpty(),
                "并入以后实体上还留着旧记录");
        context.assertFalse(playerSave(player).contains(LEGACY_PROGRESS), "并入以后还在写玩家存档");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void recipeCommandsListLearnForgetAndComplete(GameTestHelper context) throws CommandSyntaxException {
        // 管理员学会全部、忘掉一种；玩家自己能看、不能学；药方名和 all 都能补全。
        ServerPlayer player = BrewingTestPlayers.create(context);
        ServerLevel level = context.getLevel();
        CommandDispatcher<CommandSourceStack> dispatcher = level.getServer().getCommands().getDispatcher();
        CommandSourceStack admin = level.getServer().createCommandSourceStack().withSuppressedOutput();
        String name = player.getGameProfile().name();

        int learned = dispatcher.execute("meridian progression recipes learn " + name + " all", admin);
        context.assertTrue(learned == 1 && known(player).equals(EnumSet.allOf(BrewKind.class)),
                "learn all 没有学会全部药方：" + known(player));
        int forgot = dispatcher.execute("meridian progression recipes forget " + name + " charm", admin);
        EnumSet<BrewKind> expected = EnumSet.allOf(BrewKind.class);
        expected.remove(BrewKind.CHARM);
        context.assertTrue(forgot == 1 && known(player).equals(expected), "forget 没有忘掉魅惑药方：" + known(player));
        context.assertTrue(dispatcher.execute("meridian progression recipes " + name, admin) == 1,
                "管理员列不出别人的药方");

        CommandSourceStack self = player.createCommandSourceStack().withSuppressedOutput();
        context.assertTrue(dispatcher.execute("meridian progression recipes", self) == 1, "玩家看不了自己的药方");
        boolean selfLearnRefused = false;
        try {
            dispatcher.execute("meridian progression recipes learn all", self);
        } catch (CommandSyntaxException refused) {
            selfLearnRefused = true;
        }
        context.assertTrue(selfLearnRefused, "没有权限的玩家也能给自己学药方");
        context.assertTrue(known(player).equals(expected), "没有权限的学药方改了身体进度");

        List<String> suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(
                "meridian progression recipes learn @s ", admin)).join().getList().stream()
                .map(Suggestion::getText).toList();
        context.assertTrue(suggestions.contains("charm") && suggestions.contains("all"),
                "药方参数补全不全：" + suggestions);
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    static BrewCauldronBlockEntity preparedQiGather(GameTestHelper context, ServerPlayer player) {
        // 和世界熬药测试同一套：巫釜坐铁炉座、三块煤、两桶水、聚气汤主料和底料。
        ServerLevel level = context.getLevel();
        BlockPos cauldronPos = context.absolutePos(CAULDRON);
        level.setBlockAndUpdate(cauldronPos, BuiltInRegistries.BLOCK.getValue(
                Identifier.parse("meridian_brewing:witch_cauldron")).defaultBlockState());
        level.setBlockAndUpdate(cauldronPos.below(), BuiltInRegistries.BLOCK.getValue(
                Identifier.parse("meridian_brewing:iron_stove")).defaultBlockState());
        level.setBlockAndUpdate(cauldronPos.north(), Blocks.POTTED_RED_MUSHROOM.defaultBlockState());
        player.setPos(cauldronPos.getX() + 0.5, cauldronPos.getY() + 1.0, cauldronPos.getZ() + 0.5);
        BrewFireBlockEntity fire = (BrewFireBlockEntity) level.getBlockEntity(cauldronPos.below());
        for (int fuel = 0; fuel < FUEL; fuel++) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL));
            fire.use(player, InteractionHand.MAIN_HAND);
        }
        BrewCauldronBlockEntity cauldron = (BrewCauldronBlockEntity) level.getBlockEntity(cauldronPos);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET, FILLING_BUCKETS));
        for (int bucket = 0; bucket < FILLING_BUCKETS; bucket++) {
            cauldron.use(player, InteractionHand.MAIN_HAND);
        }
        use(cauldron, player, new ItemStack(BuiltInRegistries.ITEM.getValue(
                Identifier.parse("meridian_brewing:dried_pufferfish"))));
        use(cauldron, player, new ItemStack(Items.GLOWSTONE_DUST));
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return cauldron;
    }

    private static void use(BrewCauldronBlockEntity cauldron, ServerPlayer player, ItemStack stack) {
        // 主手拿着料右键釜，和玩家下料是同一个入口。
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        cauldron.use(player, InteractionHand.MAIN_HAND);
    }

    private static Set<BrewKind> known(ServerPlayer player) {
        // 直接读 Human 的权威身体进度，不经过药方书本的并入流程。
        return RecipeKnowledge.read(BrewingTestPlayers.body(player));
    }

    private static CompoundTag playerSave(ServerPlayer player) {
        // 玩家实体按原版存档写出来的样子。
        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, player.level().registryAccess());
        player.saveWithoutId(output);
        return output.buildResult();
    }
}
