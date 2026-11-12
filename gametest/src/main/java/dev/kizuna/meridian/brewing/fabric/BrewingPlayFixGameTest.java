package dev.kizuna.meridian.brewing.fabric;

import com.google.gson.JsonObject;
import dev.kizuna.backpack3d.item.ItemMetadata;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewRecipe;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewStructure;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.FireKind;
import dev.kizuna.meridian.brewing.server.HeatSourceKind;
import dev.kizuna.meridian.brewing.server.HerbCrateState;
import dev.kizuna.meridian.brewing.server.IngredientPlan;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItem;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** 生存可玩修正：晒药匾的界面和存档、药渣、瓶子返还、女巫掉落、创造页和物品重量。 */
public final class BrewingPlayFixGameTest {
    private static final int TIMEOUT = 100;
    private static final int OBSERVE_TICKS = 2;
    private static final int BIRTH_TICKS = 2;
    /** 原版受击后 20 tick 无敌，过了一半才吃满新的一下。 */
    private static final int REHIT_TICKS = 11;
    /** 第二下必死：已毁的手臂再挨一下按 0.7 整体蔓延，也超过剩下的血；第三下只是余量。 */
    private static final int MAX_HITS = 3;
    private static final int KILL_TICKS = BIRTH_TICKS + REHIT_TICKS * (MAX_HITS - 1) + 3;
    private static final int SPAWN_HEIGHT = 2;
    private static final BlockPos FIRST = new BlockPos(1, SPAWN_HEIGHT, 1);
    private static final BlockPos SECOND = new BlockPos(4, SPAWN_HEIGHT, 1);
    private static final BlockPos THIRD = new BlockPos(1, SPAWN_HEIGHT, 4);
    private static final int DISPENSER_SLOTS = 9;
    private static final int PLAYER_SLOTS = 36;
    private static final int LEGACY_MOSS = 3;
    private static final int DRY_MOSS = 2;
    private static final double DRYING_SECONDS = 300.0;
    private static final long PREPARED_DAY = 4L;
    private static final int LOOT_ROLLS = 200;
    private static final int LAST_HURT_TICKS = 100;
    private static final float LETHAL_DAMAGE = 1_000.0F;
    private static final int QUALITY_BREWS = 28;
    private static final int WEIGHED_ITEMS = 32;
    private static final double INSIDE_WINDOW = 0.5;
    private static final String CAULDRON = "witch_cauldron";
    private static final String STOVE = "iron_stove";
    private static final String COPPER = "copper_cauldron";
    private static final String SCRAP_KEY = "message.meridian_brewing.cauldron.collect.scrap";
    private static final BrewStructure COLD = BrewStructure.built(HeatSourceKind.NONE);
    private static final Map<UUID, Boolean> CULTIVATOR_AT_DEATH = new HashMap<>();
    private static final Map<UUID, List<String>> WITCH_HITS = new ConcurrentHashMap<>();
    private static boolean listening;
    private static boolean observing;

    @GameTest(maxTicks = TIMEOUT)
    public void herbCrateOpensNineSlotDispenserMenu(GameTestHelper context) {
        // 原来用了三行箱子界面，打开时服务端报「容器只有 9 格，比 27 格小」，界面打不开。
        ServerPlayer player = BrewingTestPlayers.create(context);
        HerbCrateBlockEntity crate = placeCrate(context, FIRST);
        player.openMenu(crate);
        context.assertTrue(player.containerMenu instanceof DispenserMenu,
                "晒药匾没有打开九格界面：" + player.containerMenu);
        context.assertTrue(player.containerMenu.slots.size() == DISPENSER_SLOTS + PLAYER_SLOTS,
                "九格界面的格数不对：" + player.containerMenu.slots.size());
        player.closeContainer();
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void brokenHerbCrateDropsItselfOnceWithItsHerbs(GameTestHelper context) {
        // 生存打掉只掉一个晒药匾，匾里的药照原版容器掉出来。
        ServerLevel level = context.getLevel();
        ServerPlayer player = BrewingTestPlayers.create(context);
        HerbCrateBlockEntity crate = placeCrate(context, FIRST);
        crate.setItem(0, new ItemStack(Items.MOSS_BLOCK, LEGACY_MOSS));
        BlockPos pos = crate.getBlockPos();
        context.assertTrue(player.gameMode.destroyBlock(pos), "生存玩家没能打掉晒药匾");
        context.runAfterDelay(OBSERVE_TICKS, () -> {
            int crates = droppedCount(level, pos, BrewingBlocks.HERB_CRATE.asItem());
            context.assertTrue(crates == 1, "打掉晒药匾掉了 " + crates + " 个匾");
            // 原版容器掉出的东西没有拾取延迟，站在旁边的玩家当 tick 就会捡走。
            int pickedUp = player.getInventory().countItem(Items.MOSS_BLOCK);
            int moss = droppedCount(level, pos, Items.MOSS_BLOCK) + pickedUp;
            context.assertTrue(moss == LEGACY_MOSS, "匾里的苔藓块没有全掉出来：" + moss);
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void herbCrateSavesPreparedHerbsAndDryingProgress(GameTestHelper context) {
        // 晒好的干药和半干的生药都要存下来；旧版只存了生药，干药在重进世界后没了。
        ServerLevel level = context.getLevel();
        HerbCrateBlockEntity crate = placeCrate(context, FIRST);
        ItemStack dryMoss = new ItemStack(BrewingItems.item("dry_moss"), DRY_MOSS);
        dryMoss.set(BrewingComponents.PREPARED_DAY, PREPARED_DAY);
        crate.setItem(0, dryMoss);
        crate.setItem(1, new ItemStack(Items.MOSS_BLOCK, LEGACY_MOSS));
        List<HerbCrateState.SlotState> slots = new ArrayList<>(
                Collections.nCopies(HerbCrateState.SLOT_COUNT, HerbCrateState.SlotState.EMPTY));
        slots.set(0, new HerbCrateState.SlotState("meridian_brewing:dry_moss", DRY_MOSS, 0.0));
        slots.set(1, new HerbCrateState.SlotState("minecraft:moss_block", LEGACY_MOSS, DRYING_SECONDS));
        crate.crate().restore(slots);
        HerbCrateBlockEntity restored = reload(level, crate, save(level, crate));
        ItemStack restoredDry = restored.getItem(0);
        context.assertTrue(ItemStack.isSameItemSameComponents(restoredDry, dryMoss)
                && restoredDry.getCount() == DRY_MOSS, "晒好的干苔没有存下来：" + restoredDry);
        context.assertTrue(restored.getItem(1).is(Items.MOSS_BLOCK), "半干的苔藓块没有存下来");
        context.assertTrue(restored.crate().dryingSeconds(1) == DRYING_SECONDS,
                "晒制进度没有存下来：" + restored.crate().dryingSeconds(1));
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void legacyHerbCrateSaveGivesRawHerbsBackOnce(GameTestHelper context) {
        // 旧格式只有 slots 列表：生药按顺序放回前几格，进度从 0 起；不是生药的条目丢掉；再存就是新格式。
        ServerLevel level = context.getLevel();
        HerbCrateBlockEntity crate = placeCrate(context, FIRST);
        CompoundTag legacy = new CompoundTag();
        ListTag slots = new ListTag();
        slots.add(legacySlot("minecraft:dirt", 1));
        slots.add(legacySlot("minecraft:moss_block", LEGACY_MOSS));
        legacy.put("slots", slots);
        HerbCrateBlockEntity restored = reload(level, crate, legacy);
        ItemStack first = restored.getItem(0);
        context.assertTrue(first.is(Items.MOSS_BLOCK) && first.getCount() == LEGACY_MOSS,
                "旧存档里的苔藓块没有放回第一格：" + first);
        context.assertTrue(restored.getItem(1).isEmpty(), "不是生药的旧条目被放了回来");
        context.assertTrue(restored.crate().dryingSeconds(0) == 0.0, "旧存档的生药没有从 0 秒起晒");
        CompoundTag saved = save(level, restored);
        context.assertTrue(!saved.contains("slots"), "再存一次还写旧格式");
        HerbCrateBlockEntity again = reload(level, crate, saved);
        context.assertTrue(again.getItem(0).getCount() == LEGACY_MOSS && again.getItem(1).isEmpty(),
                "新格式读回来又多放了一遍旧条目");
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void scrapBrewGivesResidueThroughTheCauldron(GameTestHelper context) {
        // 只下主料和底料、火候一直不在带里、凉透了才收：总分不到 20，舀出来是一份药渣，不再抛异常。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewCauldronBlockEntity entity = placeCauldron(context, FIRST, CAULDRON);
        prepare(entity, player, BrewKind.QI_GATHER);
        BrewCauldron cauldron = entity.cauldron();
        context.assertTrue(cauldron.startBrew(fired(BrewKind.QI_GATHER)).accepted(), "生药没有起熬");
        cauldron.tick(BrewCauldron.BREW_SECONDS, COLD);
        context.assertTrue(cauldron.state() == BrewCauldronState.BREWED, "冷熬 30 秒没有熬成");
        use(entity, player, new ItemStack(Items.GLASS_BOTTLE));
        context.runAfterDelay(OBSERVE_TICKS, () -> {
            Item residue = BrewingItems.item("brew_residue");
            context.assertTrue(player.getInventory().countItem(residue) == 1, "低分的药没有舀出药渣");
            context.assertTrue(player.getInventory().countItem(Items.GLASS_BOTTLE) == 0, "舀药渣没有用掉玻璃瓶");
            boolean told = BrewingTestPlayers.overlays(player).stream()
                    .anyMatch(line -> BrewingTestPlayers.translationKeys(line.text()).contains(SCRAP_KEY));
            context.assertTrue(told, "舀出药渣时动作栏没有说分数不够");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void waterBottleAddsOneUnitAndGivesTheBottleBack(GameTestHelper context) {
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewCauldronBlockEntity entity = placeCauldron(context, FIRST, CAULDRON);
        use(entity, player, PotionContents.createItemStack(Items.POTION, Potions.WATER));
        context.assertTrue(entity.cauldron().waterUnits() == 1, "一瓶水不是一份：" + entity.cauldron().waterUnits());
        context.assertTrue(player.getInventory().countItem(Items.GLASS_BOTTLE) == 1, "倒完水瓶没有还玻璃瓶");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void mainHandStirrerLeavesCauldronAndFireToTheOffHand(GameTestHelper context) {
        // 熬药时主手拿木棍、副手下料调火；主手木棍对釜和火源要返回 PASS，原版右键才会接着试副手。
        ServerPlayer player = BrewingTestPlayers.create(context);
        for (String block : List.of(CAULDRON, STOVE)) {
            BlockPos pos = context.absolutePos(FIRST);
            Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, block);
            context.getLevel().setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.getValue(id).defaultBlockState());
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            InteractionResult result = context.getLevel().getBlockState(pos).useItemOn(
                    new ItemStack(Items.STICK), context.getLevel(), player, InteractionHand.MAIN_HAND, hit);
            context.assertTrue(result instanceof InteractionResult.Pass,
                    block + " 把主手木棍当成了一次交互，副手就下不了料：" + result);
        }
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void bottledIngredientsGiveTheirBottlesBack(GameTestHelper context) {
        // 蜂蜜瓶是苦黄膏的辅料、龙息是腐甲水的引药，下进釜里各还一个玻璃瓶。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewCauldronBlockEntity honey = brewing(context, player, FIRST, CAULDRON, BrewKind.BITTER_YELLOW);
        BrewCauldronBlockEntity dragon = brewing(context, player, SECOND, CAULDRON, BrewKind.ARMOR_ROT);
        player.getInventory().clearContent();
        IngredientPlan honeyPlan = BrewRecipes.require(BrewKind.BITTER_YELLOW).ingredients();
        double honeySeconds = honeyPlan.accessoryWindow().startSeconds() + INSIDE_WINDOW;
        honey.cauldron().tick(honeySeconds, fired(BrewKind.BITTER_YELLOW));
        use(honey, player, new ItemStack(Items.HONEY_BOTTLE));
        context.assertTrue(honey.cauldron().ingredients().containsKey(IngredientSlot.ACCESSORY), "蜂蜜瓶没有下成辅料");
        // 还回来的瓶子落在刚空出来的主手格，下一次 use 会换掉主手，所以每下一样就数一次。
        int honeyBottles = player.getInventory().countItem(Items.GLASS_BOTTLE);
        context.assertTrue(honeyBottles == 1, "蜂蜜瓶应还一个玻璃瓶，实际 " + honeyBottles);
        player.getInventory().clearContent();
        IngredientPlan dragonPlan = BrewRecipes.require(BrewKind.ARMOR_ROT).ingredients();
        double dragonSeconds = dragonPlan.catalystWindow().startSeconds() + INSIDE_WINDOW;
        dragon.cauldron().tick(dragonSeconds, fired(BrewKind.ARMOR_ROT));
        use(dragon, player, new ItemStack(Items.DRAGON_BREATH));
        context.assertTrue(dragon.cauldron().ingredients().containsKey(IngredientSlot.CATALYST), "龙息没有下成引药");
        int dragonBottles = player.getInventory().countItem(Items.GLASS_BOTTLE);
        context.assertTrue(dragonBottles == 1, "龙息应还一个玻璃瓶，实际 " + dragonBottles);
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void honeycombIsTheCatalystWhileCopperBrews(GameTestHelper context) {
        // 蜜脾在铜釜熬招虫水时是引药；不熬的时候才是上蜡。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewCauldronBlockEntity entity = brewing(context, player, FIRST, COPPER, BrewKind.INSECT_LURE);
        IngredientPlan plan = BrewRecipes.require(BrewKind.INSECT_LURE).ingredients();
        entity.cauldron().tick(plan.catalystWindow().startSeconds() + INSIDE_WINDOW, fired(BrewKind.INSECT_LURE));
        use(entity, player, new ItemStack(Items.HONEYCOMB));
        context.assertTrue(entity.cauldron().ingredients().containsKey(IngredientSlot.CATALYST),
                "熬招虫水时蜜脾没有下成引药");
        context.assertTrue(!entity.cauldron().copperState().waxed(), "熬招虫水时蜜脾把铜釜上了蜡");
        BrewCauldronBlockEntity idle = placeCauldron(context, SECOND, COPPER);
        use(idle, player, new ItemStack(Items.HONEYCOMB));
        context.assertTrue(idle.cauldron().copperState().waxed(), "不熬的时候蜜脾没有给铜釜上蜡");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void cultivatorWitchLootNeedsTrueQiAndPlayerKill(GameTestHelper context) {
        // 原版掉落入口抽 200 次：真炁女巫被玩家打死才掉药方和熬药卷轴；凡人、游炁女巫和非玩家击杀一张不掉。
        ServerLevel level = context.getLevel();
        ServerPlayer player = BrewingTestPlayers.create(context);
        Witch trueQi = spawnWitch(context, "true_qi", FIRST);
        Witch mortal = spawnWitch(context, "mortal", SECOND);
        Witch wandering = spawnWitch(context, "wandering_qi", THIRD);
        context.runAfterDelay(BIRTH_TICKS, () -> {
            assertRealm(context, trueQi, true);
            assertRealm(context, mortal, false);
            assertRealm(context, wandering, false);
            DamageSource byPlayer = level.damageSources().playerAttack(player);
            LootCount playerKill = roll(level, trueQi, byPlayer, player);
            context.assertTrue(playerKill.recipes() > 0 && playerKill.scrolls() > 0,
                    "真炁女巫被玩家打死 200 次没掉齐药方和卷轴：" + playerKill);
            LootCount mobKill = roll(level, trueQi, level.damageSources().mobAttack(mortal), null);
            context.assertTrue(mobKill.total() == 0, "不是玩家打死的真炁女巫也掉了：" + mobKill);
            LootCount mortalKill = roll(level, mortal, byPlayer, player);
            context.assertTrue(mortalKill.total() == 0, "凡人女巫也掉了：" + mortalKill);
            LootCount wanderingKill = roll(level, wandering, byPlayer, player);
            context.assertTrue(wanderingKill.total() == 0, "游炁女巫也掉了：" + wanderingKill);
            System.out.println("[炼药掉落] 真炁女巫玩家击杀 200 次：" + playerKill);
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void killedTrueQiWitchStillHasItsBodyWhenLootRolls(GameTestHelper context) {
        // 真打死一只真炁女巫，抽掉落那一刻身体还认领着、境界读得到真炁。
        // 1000 点近战超出测试玩家手臂的做功，按力竭只剩四分之一；打在手臂上时余量按 0.7 蔓延，
        // 女巫还剩四成血。所以没死就等原版无敌帧过去再补一刀，致命一击始终是玩家近战。
        listenForWitchDeaths();
        observeWitchHits();
        ServerLevel level = context.getLevel();
        ServerPlayer player = BrewingTestPlayers.create(context);
        Witch witch = spawnWitch(context, "true_qi", FIRST);
        context.runAfterDelay(BIRTH_TICKS, () -> assertRealm(context, witch, true));
        for (int hit = 0; hit < MAX_HITS; hit++) {
            context.runAfterDelay(BIRTH_TICKS + (long) hit * REHIT_TICKS, () -> {
                if (witch.isAlive()) {
                    witch.hurtServer(level, level.damageSources().playerAttack(player), LETHAL_DAMAGE);
                }
            });
        }
        context.runAfterDelay(KILL_TICKS, () -> {
            List<String> hits = WITCH_HITS.getOrDefault(witch.getUUID(), List.of());
            System.out.println("[女巫补刀] " + hits.size() + " 刀：" + hits);
            context.assertTrue(!witch.isAlive(), "补了 " + MAX_HITS + " 刀还没打死真炁女巫：" + hits);
            Boolean cultivator = CULTIVATOR_AT_DEATH.get(witch.getUUID());
            context.assertTrue(cultivator != null, "打死女巫时没有抽原版女巫掉落表");
            context.assertTrue(cultivator, "抽掉落时女巫的身体已经不在或不是真炁");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void creativeTabsHideQualitylessBrewsAndBrewingTabListsQualities(GameTestHelper context) {
        // 像客户端那样把所有页建一遍：原版页和搜索页里没有无品质的巫药，炼药页有 7 种形态 × 4 档。
        ServerLevel level = context.getLevel();
        CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(), true /* hasPermissions */,
                level.registryAccess());
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            boolean shown = tab.getDisplayItems().stream().anyMatch(BrewingCreativeTab::qualitylessBrew);
            boolean searched = tab.getSearchTabDisplayItems().stream().anyMatch(BrewingCreativeTab::qualitylessBrew);
            context.assertTrue(!shown && !searched, "创造页里还有无品质的巫药：" + tab.getDisplayName().getString());
        }
        CreativeModeTab brewing = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(BrewingCreativeTab.ID);
        long brews = brewing.getDisplayItems().stream().filter(BrewItemStacks::isBrew).count();
        context.assertTrue(brews == QUALITY_BREWS, "炼药页的成药数不对：" + brews);
        Item recipe = BrewingItems.item("brew_recipe");
        List<String> recipeKinds = brewing.getDisplayItems().stream()
                .filter(stack -> stack.is(recipe))
                .map(stack -> String.valueOf(stack.get(BrewingComponents.RECIPE_KIND)))
                .toList();
        List<String> everyKind = Arrays.stream(BrewKind.values()).map(BrewKind::path).toList();
        context.assertTrue(recipeKinds.equals(everyKind), "炼药页没有按药方各列一张：" + recipeKinds);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void recipeTooltipNamesItsBrew(GameTestHelper context) {
        // 每张药方的提示框写它记的是哪一味药，语言键指向这味药的药名；认不出的药方写字迹模糊。
        for (BrewKind kind : BrewKind.values()) {
            List<Component> lines = BrewTooltipLines.lines(RecipeScrollItem.stack(kind));
            context.assertTrue(lines.size() == 1, "药方提示应只有一行：" + kind + " " + lines);
            var keys = BrewingTestPlayers.translationKeys(lines.get(0));
            context.assertTrue(keys.contains("tooltip.meridian_brewing.recipe")
                            && keys.contains("potion.meridian_brewing_" + kind.path()),
                    "药方提示没写药名：" + kind + " " + keys);
            String chinese = BrewTexts.chinese(BrewCauldron.recipeName(kind));
            context.assertFalse(chinese.contains("potion."), "药名没有中文：" + chinese);
        }
        ItemStack faded = RecipeScrollItem.stack(BrewKind.QI_GATHER);
        faded.set(BrewingComponents.RECIPE_KIND, "unknown_brew");
        var fadedKeys = BrewingTestPlayers.translationKeys(BrewTooltipLines.lines(faded).get(0));
        context.assertTrue(fadedKeys.contains("tooltip.meridian_brewing.recipe.illegible"),
                "认不出的药方没写字迹模糊：" + fadedKeys);
        context.assertTrue(BrewTooltipLines.lines(new ItemStack(Items.GLASS_BOTTLE)).isEmpty(), "玻璃瓶也加了提示行");
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void everyBrewingItemCarriesAWeight(GameTestHelper context) {
        // 熬药卷轴是功法库生成的，不在这里登记。
        Map<Item, Integer> grams = BrewingWeights.grams();
        int weighed = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id.getNamespace().equals(BrewingFabric.MOD_ID) && !(item instanceof TechniqueScrollItem)) {
                ItemMetadata metadata = ItemMetadata.of(new ItemStack(item));
                Optional<JsonObject> weight = metadata.optional(BrewingWeights.WEIGHT_FIELD);
                context.assertTrue(weight.isPresent(), "物品没有重量：" + id);
                int recorded = weight.orElseThrow().get("grams").getAsInt();
                context.assertTrue(recorded == grams.get(item), "物品重量和表不符：" + id + " " + recorded);
                weighed++;
            }
        }
        context.assertTrue(weighed == WEIGHED_ITEMS, "登记了重量的物品数不对：" + weighed);
        context.succeed();
    }

    private static HerbCrateBlockEntity placeCrate(GameTestHelper context, BlockPos relative) {
        BlockPos pos = context.absolutePos(relative);
        context.getLevel().setBlockAndUpdate(pos, BrewingBlocks.HERB_CRATE.defaultBlockState());
        return (HerbCrateBlockEntity) context.getLevel().getBlockEntity(pos);
    }

    private static BrewCauldronBlockEntity placeCauldron(GameTestHelper context, BlockPos relative, String block) {
        BlockPos pos = context.absolutePos(relative);
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, block);
        context.getLevel().setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.getValue(id).defaultBlockState());
        return (BrewCauldronBlockEntity) context.getLevel().getBlockEntity(pos);
    }

    /** 经方块交互注水、下主料和底料（只能溅的药再下火药），再按规则状态机起熬。 */
    private static BrewCauldronBlockEntity brewing(GameTestHelper context, ServerPlayer player, BlockPos relative,
                                                   String block, BrewKind kind) {
        BrewCauldronBlockEntity entity = placeCauldron(context, relative, block);
        prepare(entity, player, kind);
        BrewStructure structure = fired(kind);
        if (!entity.cauldron().startBrew(structure).accepted()) {
            throw new IllegalStateException("生药没有起熬：" + kind + " " + entity.cauldron().state());
        }
        return entity;
    }

    private static void prepare(BrewCauldronBlockEntity entity, ServerPlayer player, BrewKind kind) {
        BrewRecipe recipe = BrewRecipes.require(kind);
        use(entity, player, new ItemStack(Items.WATER_BUCKET));
        use(entity, player, new ItemStack(BrewingItems.item(recipe.ingredients().preparedPrimary())));
        use(entity, player, new ItemStack(Items.GLOWSTONE_DUST));
        if (!kind.supports(BrewUse.DRINK)) {
            use(entity, player, new ItemStack(Items.GUNPOWDER));
        }
    }

    /** 火种对得上这味药的整套结构；火够旺，起熬后很快进火候带。 */
    private static BrewStructure fired(BrewKind kind) {
        HeatSourceKind heat = HeatSourceKind.SOUL_BRAZIER_4;
        if (kind.fireKind() == FireKind.SUN_FIRE) {
            heat = HeatSourceKind.SUN_BRAZIER_4;
        }
        return BrewStructure.built(heat);
    }

    private static void use(BrewCauldronBlockEntity entity, ServerPlayer player, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        entity.use(player, InteractionHand.MAIN_HAND);
    }

    private static int droppedCount(ServerLevel level, BlockPos pos, Item item) {
        AABB area = new AABB(pos).inflate(2);
        return level.getEntitiesOfClass(ItemEntity.class, area).stream()
                .filter(entity -> entity.getItem().is(item))
                .mapToInt(entity -> entity.getItem().getCount())
                .sum();
    }

    private static CompoundTag legacySlot(String itemId, int count) {
        CompoundTag slot = new CompoundTag();
        slot.putString("item_id", itemId);
        slot.putInt("count", count);
        return slot;
    }

    private static CompoundTag save(ServerLevel level, BlockEntity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        entity.saveCustomOnly(output);
        return output.buildResult();
    }

    /** 用存档数据新建一个同位置的晒药匾，和重进世界时一样从空匾读起。 */
    private static HerbCrateBlockEntity reload(ServerLevel level, HerbCrateBlockEntity crate, CompoundTag tag) {
        HerbCrateBlockEntity restored = new HerbCrateBlockEntity(crate.getBlockPos(), crate.getBlockState());
        restored.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        return restored;
    }

    /** 走类人仓的正式生成命令，按档位生出有身体的女巫。 */
    private static Witch spawnWitch(GameTestHelper context, String tier, BlockPos relative) {
        ServerLevel level = context.getLevel();
        Vec3 position = context.absoluteVec(Vec3.atBottomCenterOf(relative));
        CommandSourceStack source = level.getServer().createCommandSourceStack()
                .withLevel(level)
                .withSuppressedOutput();
        String command = String.format(Locale.ROOT, "meridian humanoid spawn minecraft:witch %s %.2f %.2f %.2f",
                tier, position.x, position.y, position.z);
        level.getServer().getCommands().performPrefixedCommand(source, command);
        List<Witch> witches = level.getEntitiesOfClass(Witch.class, new AABB(BlockPos.containing(position)));
        if (witches.size() != 1) {
            throw new IllegalStateException("生成命令没有在原地生出一只女巫：" + command + " → " + witches);
        }
        Witch witch = witches.getFirst();
        witch.setNoAi(true);
        return witch;
    }

    private static void assertRealm(GameTestHelper context, Witch witch, boolean trueQi) {
        Optional<Realm> realm = BrewBodyHost.observe(witch).found().map(binding -> binding.body().getRealm());
        context.assertTrue(realm.isPresent(), "生成的女巫没有身体");
        context.assertTrue((realm.orElseThrow() == Realm.TRUE_QI) == trueQi, "女巫的境界不对：" + realm.orElseThrow());
    }

    /** 用原版的掉落参数抽 {@link #LOOT_ROLLS} 次；{@code killer} 为空时算作非玩家击杀。 */
    private static LootCount roll(ServerLevel level, Witch witch, DamageSource source, ServerPlayer killer) {
        boolean playerKilled = killer != null;
        if (playerKilled) {
            witch.setLastHurtByPlayer(killer, LAST_HURT_TICKS);
        }
        ResourceKey<LootTable> table = ResourceKey.create(Registries.LOOT_TABLE, BrewingLoot.WITCH_TABLE);
        List<ItemStack> drops = new ArrayList<>();
        for (int roll = 0; roll < LOOT_ROLLS; roll++) {
            witch.dropFromLootTable(level, source, playerKilled, table, drops::add);
        }
        Item recipe = BrewingItems.item("brew_recipe");
        Item scroll = BuiltInRegistries.ITEM.getValue(BrewingLoot.SCROLL_ID);
        int recipes = (int) drops.stream().filter(stack -> stack.is(recipe)).count();
        int scrolls = (int) drops.stream().filter(stack -> stack.is(scroll)).count();
        return new LootCount(recipes, scrolls);
    }

    private static synchronized void listenForWitchDeaths() {
        // 掉落表算完以后在同一份掉落上下文里再判一次修炼者条件，记下这只女巫当时的身体情况。
        if (listening) {
            return;
        }
        LootTableEvents.MODIFY_DROPS.register((table, lootContext, drops) -> {
            boolean witchTable = table.unwrapKey()
                    .map(key -> key.identifier().equals(BrewingLoot.WITCH_TABLE))
                    .orElse(false);
            if (witchTable && lootContext.getOptionalParameter(LootContextParams.THIS_ENTITY) instanceof Witch witch) {
                boolean cultivator = BrewingLoot.CultivatorCondition.INSTANCE.test(lootContext);
                CULTIVATOR_AT_DEATH.put(witch.getUUID(), cultivator);
            }
        });
        listening = true;
    }

    private static synchronized void observeWitchHits() {
        // 只记女巫挨的每一下：命中部位、原版剂量、进身体的剂量和剩下的部位总血。
        if (observing) {
            return;
        }
        VanillaDamageFabric.registerDamageObserver(event -> {
            if (event.target() instanceof Witch witch) {
                String part = event.input().hit().map(HealthTarget::getPartId).orElse("?");
                double left = event.result().body().getPartStatesList().stream().mapToDouble(PartState::getHp).sum();
                String line = String.format(Locale.ROOT, "%s 原版 %.0f → 身体 %.1f，剩 %.1f%s", part,
                        event.input().rawDamage(), event.input().damageAfterEffects(), left,
                        event.result().body().getVitality().getDead() ? "（死）" : "");
                WITCH_HITS.computeIfAbsent(witch.getUUID(), ignored -> new CopyOnWriteArrayList<>()).add(line);
            }
        });
        observing = true;
    }

    private record LootCount(int recipes, int scrolls) {
        int total() {
            return recipes + scrolls;
        }
    }
}
