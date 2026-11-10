package dev.kizuna.meridian.brewing.test;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewCauldronBlockEntity;
import dev.kizuna.meridian.brewing.fabric.BrewFireBlockEntity;
import dev.kizuna.meridian.brewing.fabric.HerbCrateBlockEntity;
import dev.kizuna.meridian.brewing.fabric.BrewingCreativeTab;
import dev.kizuna.meridian.brewing.fabric.BrewingRuntime;
import dev.kizuna.meridian.brewing.fabric.RecipeKnowledgeStore;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItem;
import dev.kizuna.meridian.brewing.server.BrewActionResult;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewCollectionKind;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewStructure;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.BrewFireSource;
import dev.kizuna.meridian.brewing.server.CopperExposure;
import dev.kizuna.meridian.brewing.server.CopperOxidation;
import dev.kizuna.meridian.brewing.server.FireKind;
import dev.kizuna.meridian.brewing.server.HeatSourceKind;
import dev.kizuna.meridian.brewing.server.HerbState;
import dev.kizuna.meridian.brewing.server.HerbCrateState;
import dev.kizuna.meridian.brewing.server.IngredientBatch;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import dev.kizuna.meridian.brewing.server.SmokeVentState;
import dev.kizuna.meridian.brewing.server.VesselKind;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.registry.StrippableBlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

/** 炼药模组的真实 Fabric 注册、Potion 内容和品质组件检查。 */
public final class BrewingGameTest {
    private static final int DEFAULT_TIMEOUT = 100;
    private static final String MOD_ID = "meridian_brewing";
    private static final List<String> EFFECT_IDS = List.of(
            "depletion", "armor_rot", "insect_lure", "qi_gather", "bitter_yellow",
            "charm_stagger", "charm_control");
    private static final List<String> POTION_IDS = List.of(
            "depletion", "bitter_yellow", "armor_rot", "insect_lure", "qi_gather", "charm");
    private static final List<String> COMPONENT_IDS = List.of(
            "quality", "prepared_day", "recipe_kind");
    private static final List<String> BLOCK_IDS = List.of(
            "witch_cauldron", "copper_cauldron",
            "dead_oak_log", "dead_oak_wood",
            "dead_spruce_log", "dead_spruce_wood",
            "dead_birch_log", "dead_birch_wood",
            "dead_jungle_log", "dead_jungle_wood",
            "dead_acacia_log", "dead_acacia_wood",
            "dead_dark_oak_log", "dead_dark_oak_wood",
            "dead_mangrove_log", "dead_mangrove_wood",
            "dead_cherry_log", "dead_cherry_wood",
            "dead_pale_oak_log", "dead_pale_oak_wood");
    private static final List<String> ITEM_IDS = List.of(
            "withered_bush_ash", "dry_moss", "dry_poppy", "dried_pufferfish",
            "poison_potato_slice", "berry_mash", "brew_residue", "brew_recipe", "herb_knife");
    private static final String OXIDATION = "oxidation";
    private static final int STRUCTURE_Y = 2;
    private static final int STRUCTURE_X = 2;
    private static final int STRUCTURE_Z = 2;
    private static final int OBSERVE_TICKS = 2;
    private static final int WITHERING_OBSERVE_TICKS = 8;
    private static final double HEAT_OBSERVE_SECONDS = 6.0;
    private static final double COLLECTION_COOLING_SECONDS = 1.25;
    private static final long CURRENT_GAME_DAY = 3L;
    private static final int COMPLETE_BREW_TIMEOUT = 760;
    private static final long ACCESSORY_TICK = 160L;
    private static final long CATALYST_TICK = 440L;
    private static final long COLLECTION_TICK = 660L;
    private static final double READY_DRYING_SECONDS = 1_200.0;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void registersEffectsAndPotions(GameTestHelper context) {
        assertRegistered(context, BuiltInRegistries.MOB_EFFECT, EFFECT_IDS, "状态效果");
        assertRegistered(context, BuiltInRegistries.POTION, POTION_IDS, "Potion");
        assertRegistered(context, BuiltInRegistries.DATA_COMPONENT_TYPE, COMPONENT_IDS, "数据组件");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void registersCauldronsDeadWoodAndPreparedItems(GameTestHelper context) {
        assertRegistered(context, BuiltInRegistries.BLOCK, BLOCK_IDS, "方块");
        assertRegistered(context, BuiltInRegistries.ITEM, ITEM_IDS, "炮制物品");
        for (String path : BLOCK_IDS) {
            assertItemRegistered(context, path, "方块物品");
            assertBlockItemName(context, path);
        }
        assertStrippedBlock(context, "dead_oak_log", "stripped_oak_log");
        assertStrippedBlock(context, "dead_pale_oak_wood", "stripped_pale_oak_wood");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void creativeTabListsEveryBrewingItem(GameTestHelper context) {
        // 本模组注册的每个物品都要能从炼药页取到，搭巫釜结构不必用命令给物品。
        CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(BrewingCreativeTab.ID);
        context.assertTrue(tab != null, "炼药创造栏未注册");
        var world = context.getLevel();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(world.enabledFeatures(),
                false /* hasPermissions */, world.registryAccess()));
        var displayed = tab.getDisplayItems().stream()
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()))
                .collect(Collectors.toSet());
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            // 功法卷由功法库按目录生成，列在功法页，不归炼药页。
            boolean scroll = BuiltInRegistries.ITEM.getValue(id) instanceof TechniqueScrollItem;
            if (id.getNamespace().equals(MOD_ID) && !scroll) {
                context.assertTrue(displayed.contains(id), "炼药创造栏缺物品：" + id);
            }
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void potionContentsRoundTripKindAndQuality(GameTestHelper context) {
        for (BrewKind kind : BrewKind.values()) {
            BrewUse use = preferredUse(kind);
            ItemStack stack = BrewItemStacks.create(kind, BrewQuality.SUPERIOR, use);
            context.assertTrue(BrewItemStacks.isBrew(stack), "药水未被识别为巫药：" + kind);
            context.assertTrue(BrewItemStacks.kind(stack) == kind, "Potion 种类解析错误：" + kind);
            context.assertTrue(BrewItemStacks.quality(stack) == BrewQuality.SUPERIOR,
                    "品质组件解析错误：" + kind);
            Item expectedItem = expectedItem(use);
            context.assertTrue(stack.is(expectedItem), "药水物品类型错误：" + kind);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void everyQualityHasStableComponentValue(GameTestHelper context) {
        for (BrewQuality quality : BrewQuality.values()) {
            ItemStack stack = BrewItemStacks.create(
                    BrewKind.BITTER_YELLOW, quality, BrewUse.DRINK);
            context.assertTrue(BrewItemStacks.quality(stack) == quality,
                    "品质组件往返失败：" + quality);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void worldCauldronRecognizesFourHearthDirections(GameTestHelper context) {
        // 实际世界方块搭出营火、四向灶石、药苗和烟口，验证方块实体
        // 扫描出的完整结构。
        ServerLevel level = context.getLevel();
        BlockPos cauldronPos = context.absolutePos(
                new BlockPos(STRUCTURE_X, STRUCTURE_Y, STRUCTURE_Z));
        level.setBlockAndUpdate(cauldronPos,
                BuiltInRegistries.BLOCK.getValue(id("witch_cauldron")).defaultBlockState());
        BlockPos firePos = cauldronPos.below();
        level.setBlockAndUpdate(firePos,
                Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos hearthPos = firePos.relative(direction);
            level.setBlockAndUpdate(hearthPos, Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(cauldronPos.north(), Blocks.POTTED_RED_MUSHROOM.defaultBlockState());
        context.runAfterDelay(OBSERVE_TICKS, () -> {
            BlockEntity entity = level.getBlockEntity(cauldronPos);
            context.assertTrue(entity instanceof BrewCauldronBlockEntity, "巫釜方块实体未创建");
            BrewCauldronBlockEntity cauldron = (BrewCauldronBlockEntity) entity;
            context.assertTrue(cauldron.structure().hearthStoneCount() == 4,
                    "四向灶石未被完整识别：" + cauldron.structure());
            context.assertTrue(cauldron.structure().maximumTemperature() == 100.0,
                    "完整结构最高温度错误：" + cauldron.structure());
            context.assertTrue(cauldron.structure().accepts(FireKind.SUN_FIRE),
                    "完整阳火结构没有通过验收");
            context.succeed();
        });
    }

    @GameTest(maxTicks = COMPLETE_BREW_TIMEOUT)
    public void worldCauldronStartsThroughBlockEntityAndCollectsPotion(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        ServerPlayer player = BrewingTestPlayers.create(context);
        BlockPos cauldronPos = context.absolutePos(new BlockPos(STRUCTURE_X, STRUCTURE_Y, STRUCTURE_Z));
        level.setBlockAndUpdate(cauldronPos,
                BuiltInRegistries.BLOCK.getValue(id("witch_cauldron")).defaultBlockState());
        level.setBlockAndUpdate(cauldronPos.below(),
                BuiltInRegistries.BLOCK.getValue(id("iron_stove")).defaultBlockState());
        level.setBlockAndUpdate(cauldronPos.north(), Blocks.POTTED_RED_MUSHROOM.defaultBlockState());
        player.setPos(cauldronPos.getX() + 0.5, cauldronPos.getY() + 1.0, cauldronPos.getZ() + 0.5);

        BrewFireBlockEntity fire = (BrewFireBlockEntity) level.getBlockEntity(cauldronPos.below());
        for (int fuel = 0; fuel < 3; fuel++) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL));
            fire.use(player, InteractionHand.MAIN_HAND);
        }
        BrewCauldronBlockEntity cauldron = (BrewCauldronBlockEntity) level.getBlockEntity(cauldronPos);
        ItemStack waterBuckets = new ItemStack(Items.WATER_BUCKET, 3);
        player.setItemInHand(InteractionHand.MAIN_HAND, waterBuckets);
        for (int bucket = 0; bucket < 3; bucket++) {
            cauldron.use(player, InteractionHand.MAIN_HAND);
        }
        // 最后一桶会原位变成空桶；模拟切换物品前先把容器收入背包。
        ItemStack returnedBucket = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.addItem(returnedBucket);
        useCauldronItem(cauldron, player, itemStack("meridian_brewing:dried_pufferfish"));
        useCauldronItem(cauldron, player, new ItemStack(Items.GLOWSTONE_DUST));
        context.assertTrue(cauldron.startBrew(player).accepted(), "方块实体入口没有开始熬制");

        context.runAfterDelay(ACCESSORY_TICK, () -> useCauldronItem(
                cauldron, player, new ItemStack(Items.PRISMARINE_SHARD)));
        context.runAfterDelay(CATALYST_TICK, () -> useCauldronItem(
                cauldron, player, new ItemStack(Items.NAUTILUS_SHELL)));
        context.runAfterDelay(COLLECTION_TICK, () -> {
            useCauldronItem(cauldron, player, new ItemStack(Items.GLASS_BOTTLE));
            ItemStack potion = findItem(player, Items.POTION);
            context.assertTrue(BrewItemStacks.isBrew(potion), "完整世界流程没有产出巫药");
            context.assertTrue(BrewItemStacks.quality(potion) != null, "产出药水缺少品质组件");
            context.assertTrue(player.getInventory().countItem(Items.BUCKET) >= 3,
                    "水桶没有换回空桶");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void worldInteractionsReturnWrongItemsAndDamageOnlyAxe(GameTestHelper context) {
        // 错误投料原物退回，斧头与普通工具使用真实玩家物品结算。
        ServerLevel level = context.getLevel();
        ServerPlayer player = BrewingTestPlayers.create(context);
        BlockPos pos = context.absolutePos(new BlockPos(STRUCTURE_X, STRUCTURE_Y, STRUCTURE_Z));
        level.setBlockAndUpdate(pos,
                BuiltInRegistries.BLOCK.getValue(id("copper_cauldron")).defaultBlockState());
        BrewCauldronBlockEntity cauldron = (BrewCauldronBlockEntity) level.getBlockEntity(pos);
        ItemStack wrong = new ItemStack(Items.DIRT, 4);
        player.setItemInHand(InteractionHand.MAIN_HAND, wrong);
        cauldron.use(player, InteractionHand.MAIN_HAND);
        context.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 4,
                "错误投料没有原样留在手中");
        cauldron.cauldron().ageCopper(CopperExposure.EXPOSED);
        ItemStack axe = new ItemStack(Items.IRON_AXE);
        player.setItemInHand(InteractionHand.MAIN_HAND, axe);
        cauldron.use(player, InteractionHand.MAIN_HAND);
        ItemStack remainingAxe = player.getItemInHand(InteractionHand.MAIN_HAND);
        context.assertTrue(remainingAxe.is(Items.IRON_AXE), "刮锈吞掉了斧子");
        context.assertTrue(remainingAxe.getDamageValue() == 1, "刮锈没有只扣一级耐久");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void copperOxidationShowsOnBlockStateAndPlacedLookSeedsRules(GameTestHelper context) {
        // 规则层老化后下一 tick 同步到方块外观且不重建方块实体；带外观放下的铜釜按外观氧化级起算。
        ServerLevel level = context.getLevel();
        BlockPos pos = context.absolutePos(new BlockPos(STRUCTURE_X, STRUCTURE_Y, STRUCTURE_Z));
        BlockState copper = BuiltInRegistries.BLOCK.getValue(id("copper_cauldron")).defaultBlockState();
        level.setBlockAndUpdate(pos, copper);
        BrewCauldronBlockEntity cauldron = (BrewCauldronBlockEntity) level.getBlockEntity(pos);
        cauldron.cauldron().ageCopper(CopperExposure.EXPOSED);
        BlockPos placedPos = pos.east();
        level.setBlockAndUpdate(placedPos, withValue(copper, OXIDATION, "weathered"));
        BrewCauldronBlockEntity placed = (BrewCauldronBlockEntity) level.getBlockEntity(placedPos);
        context.assertTrue(placed.cauldron().copperState().oxidation() == CopperOxidation.WEATHERED,
                "带外观放下的铜釜没有按外观氧化级起算");
        context.runAfterDelay(OBSERVE_TICKS, () -> {
            context.assertTrue(valueName(level.getBlockState(pos), OXIDATION).equals("exposed"),
                    "铜釜老化没有同步到方块外观");
            context.assertTrue(level.getBlockEntity(pos) == cauldron, "同步外观重建了铜釜方块实体");
            context.assertTrue(valueName(level.getBlockState(placedPos), OXIDATION).equals("weathered"),
                    "规则状态把放下时的外观改回了未氧化");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void blockEntitiesRoundTripAndCrateRejectsNonSunDryItem(GameTestHelper context) {
        // 原版方块实体读写保留药材、火种和釜状态，晒药匾拒收不适用的材料。
        ServerLevel level = context.getLevel();
        ServerPlayer player = BrewingTestPlayers.create(context);
        BlockPos base = context.absolutePos(new BlockPos(STRUCTURE_X, STRUCTURE_Y, STRUCTURE_Z));
        BlockPos cauldronPos = base;
        BlockPos firePos = base.east();
        BlockPos cratePos = base.south();
        level.setBlockAndUpdate(cauldronPos,
                BuiltInRegistries.BLOCK.getValue(id("witch_cauldron")).defaultBlockState());
        level.setBlockAndUpdate(firePos,
                BuiltInRegistries.BLOCK.getValue(id("iron_stove")).defaultBlockState());
        level.setBlockAndUpdate(cratePos,
                BuiltInRegistries.BLOCK.getValue(id("herb_crate")).defaultBlockState());
        BrewCauldronBlockEntity cauldron = (BrewCauldronBlockEntity) level.getBlockEntity(cauldronPos);
        useCauldronItem(cauldron, player, new ItemStack(Items.WATER_BUCKET));
        useCauldronItem(cauldron, player, itemStack("meridian_brewing:dried_pufferfish"));
        BrewFireBlockEntity fire = (BrewFireBlockEntity) level.getBlockEntity(firePos);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL));
        fire.use(player, InteractionHand.MAIN_HAND);
        HerbCrateBlockEntity crate = (HerbCrateBlockEntity) level.getBlockEntity(cratePos);
        crate.setItem(0, new ItemStack(Items.MOSS_BLOCK, 2));
        context.assertTrue(!crate.canPlaceItem(1, new ItemStack(Items.PUFFERFISH)),
                "非晒制药材仍能进入晒药匾");

        BrewCauldronBlockEntity restoredCauldron = new BrewCauldronBlockEntity(
                cauldronPos, level.getBlockState(cauldronPos), VesselKind.WITCH_CAULDRON);
        restoredCauldron.loadCustomOnly(input(level, save(level, cauldron)));
        context.assertTrue(restoredCauldron.cauldron().waterUnits() == 1,
                "巫釜水量没有经过存档往返");
        BrewFireBlockEntity restoredFire = new BrewFireBlockEntity(
                firePos, level.getBlockState(firePos), BrewFireSource.STOVE);
        restoredFire.loadCustomOnly(input(level, save(level, fire)));
        context.assertTrue(restoredFire.fire().level() == 1, "火盆档位没有经过存档往返");
        HerbCrateBlockEntity restoredCrate = new HerbCrateBlockEntity(
                cratePos, level.getBlockState(cratePos));
        restoredCrate.loadCustomOnly(input(level, save(level, crate)));
        context.assertTrue(restoredCrate.getItem(0).getCount() == 2,
                "晒药匾库存没有经过存档往返");
        level.destroyBlock(cratePos, true, player);
        context.runAfterDelay(2, () -> {
            context.assertItemEntityPresent(Items.MOSS_BLOCK);
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void worldHerbCrateConvertsSunDryStackWithPreparedDay(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        BlockPos cratePos = context.absolutePos(new BlockPos(STRUCTURE_X, STRUCTURE_Y, STRUCTURE_Z));
        level.setBlockAndUpdate(cratePos,
                BuiltInRegistries.BLOCK.getValue(id("herb_crate")).defaultBlockState());
        HerbCrateBlockEntity crate = (HerbCrateBlockEntity) level.getBlockEntity(cratePos);
        ItemStack rawStack = new ItemStack(Items.MOSS_BLOCK, 2);
        crate.setItem(0, rawStack);
        crate.crate().restore(new HerbCrateState.Snapshot(
                List.of(IngredientBatch.raw("minecraft:moss_block", 2)), READY_DRYING_SECONDS));
        context.runAfterDelay(OBSERVE_TICKS, () -> {
            ItemStack prepared = crate.getItem(0);
            Item expected = BuiltInRegistries.ITEM.getValue(id("dry_moss"));
            context.assertTrue(prepared.is(expected), "晒制完成后没有替换为干苔");
            context.assertTrue(prepared.getCount() == 2, "晒制没有保留药材堆叠数量");
            context.assertTrue(prepared.get(BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(
                    id("prepared_day"))) != null, "干苔没有写入制成日");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void worldPreparationUsesRecipeMethods(GameTestHelper context) {
        // 炮制走实际配方入口；药刀按住切药另见 HerbKnifeGameTest。
        ServerLevel level = context.getLevel();
        ServerPlayer player = BrewingTestPlayers.create(context);
        BlockPos relativeGrindstone = new BlockPos(STRUCTURE_X, STRUCTURE_Y, STRUCTURE_Z);
        BlockPos grindstonePos = context.absolutePos(relativeGrindstone);
        level.setBlockAndUpdate(grindstonePos, Blocks.GRINDSTONE.defaultBlockState());
        player.setPos(grindstonePos.getX() + 0.5, grindstonePos.getY() + 1.0,
                grindstonePos.getZ() + 0.5);

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PUFFERFISH, 2));
        invokeUseBlockCallback(player, level, grindstonePos);
        context.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 2,
                "砂轮错误研制配方仍消耗了河豚");

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SWEET_BERRIES, 2));
        invokeUseBlockCallback(player, level, grindstonePos);
        Item berryMash = BuiltInRegistries.ITEM.getValue(id("berry_mash"));
        context.assertTrue(findItem(player, berryMash).getCount() == 1,
                "砂轮没有产出浆果泥或没有消耗一份甜浆果");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void worldPotionDrinkSplashAndMilkFollowEffectLifecycle(GameTestHelper context) {
        // 玩家经 Human 登录绑定身体，饮用、喷溅和牛奶共用正式状态生命周期。
        ServerLevel level = context.getLevel();
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.setGameMode(GameType.SURVIVAL);
        Holder.Reference<MobEffect> bitterEffect = effect("bitter_yellow");
        Holder.Reference<MobEffect> depletionEffect = effect("depletion");

        player.setHealth(1.0F);
        ItemStack bitter = BrewItemStacks.create(
                BrewKind.BITTER_YELLOW, BrewQuality.FINE, BrewUse.DRINK);
        context.assertTrue(BrewingRuntime.consume(level, player, bitter), "饮用入口没有识别巫药");
        context.assertTrue(!BrewingTestPlayers.body(player).getId().isBlank(), "药效没有使用真实身体");
        context.assertTrue(player.hasEffect(bitterEffect), "苦黄膏饮用没有添加状态效果");

        var target = context.spawn(EntityTypes.ZOMBIE,
                new BlockPos(STRUCTURE_X, STRUCTURE_Y, STRUCTURE_Z));
        float initialHealth = target.getHealth();
        ItemStack depletion = BrewItemStacks.create(
                BrewKind.DEPLETION, BrewQuality.FINE, BrewUse.SPLASH);
        BrewingRuntime.splash(level, depletion, target.position(), player);
        context.assertTrue(target.getHealth() < initialHealth, "枯竭喷溅没有伤害实体");
        context.assertTrue(!target.hasEffect(depletionEffect), "无身体物种不应产生组织修正");

        new ItemStack(Items.MILK_BUCKET).finishUsingItem(level, player);
        context.assertTrue(!player.hasEffect(bitterEffect), "牛奶没有清除巫药状态");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void recipeScrollRoundTripsAddonProgress(GameTestHelper context) {
        // 药方知识经真实玩家进度保存，并验证重复阅读不会消耗第二次。
        ServerLevel level = context.getLevel();
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.setGameMode(GameType.SURVIVAL);
        Item scroll = BuiltInRegistries.ITEM.getValue(id("brew_recipe"));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(scroll));
        scroll.use(level, player, InteractionHand.MAIN_HAND);
        player.closeContainer();

        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, level.registryAccess());
        RecipeKnowledgeStore.save(player, output);
        CompoundTag saved = output.buildResult();
        RecipeKnowledgeStore.load(player, input(level, saved));
        ValueInput progress = input(level, saved).childOrEmpty("addon_progress");
        ValueInput recipes = progress.childOrEmpty("meridian_brewing:recipes");
        context.assertTrue(recipes.getBooleanOr("depletion", false),
                "药方羊皮纸没有写入 addon_progress");
        context.succeed();
    }

    private static Holder.Reference<MobEffect> effect(String path) {
        return BuiltInRegistries.MOB_EFFECT.get(id(path)).orElseThrow();
    }

    private static void invokeUseBlockCallback(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit);
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void cauldronRejectsWrongPrimaryAndConvergesHeat(GameTestHelper context) {
        BrewCauldron cauldron = new BrewCauldron(VesselKind.WITCH_CAULDRON, BrewForm.DRINK);
        cauldron.addWater(VesselKind.WITCH_CAULDRON.waterCapacity());
        IngredientBatch wrongPrimary = IngredientBatch.prepared("meridian_brewing:unknown", 0);
        BrewActionResult returned = cauldron.addIngredient(IngredientSlot.PRIMARY, wrongPrimary);
        context.assertTrue(!returned.accepted(), "错误主料被接受");
        context.assertTrue(returned.returnedIngredient().orElseThrow() == wrongPrimary,
                "错误主料没有原样退回");

        cauldron.addIngredient(IngredientSlot.PRIMARY, IngredientBatch.prepared("dry_moss", 0));
        cauldron.addIngredient(IngredientSlot.BASE,
                IngredientBatch.raw("minecraft:glowstone_dust"));
        BrewStructure structure = new BrewStructure(
                HeatSourceKind.SUN_BRAZIER_2,
                BrewStructure.REQUIRED_HEARTH_STONES,
                SmokeVentState.OPEN,
                HerbState.PRESENT);
        context.assertTrue(cauldron.startBrew(structure).accepted(), "合法药方没有开始熬制");
        double initialTemperature = cauldron.temperature();
        cauldron.tick(HEAT_OBSERVE_SECONDS, structure);
        context.assertTrue(cauldron.temperature() > initialTemperature, "火候没有向热源收敛");
        context.assertTrue(cauldron.temperature() <= structure.maximumTemperature(),
                "温度超过结构上限");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void cauldronCollectionCarriesQualityAndScorchPenalty(GameTestHelper context) {
        BrewCauldron cauldron = new BrewCauldron(VesselKind.WITCH_CAULDRON, BrewForm.DRINK);
        cauldron.addWater(VesselKind.WITCH_CAULDRON.waterCapacity());
        cauldron.addIngredient(IngredientSlot.PRIMARY,
                IngredientBatch.prepared("dry_moss", 0));
        cauldron.addIngredient(IngredientSlot.BASE,
                IngredientBatch.raw("minecraft:glowstone_dust"));
        BrewStructure stableHeat = new BrewStructure(
                HeatSourceKind.SUN_BRAZIER_2,
                BrewStructure.REQUIRED_HEARTH_STONES,
                SmokeVentState.OPEN,
                HerbState.PRESENT);
        cauldron.startBrew(stableHeat);
        cauldron.tick(8.0, stableHeat);
        cauldron.addIngredient(IngredientSlot.ACCESSORY,
                IngredientBatch.raw("honey_bottle"));
        cauldron.tick(12.0, stableHeat);
        cauldron.addIngredient(IngredientSlot.CATALYST,
                IngredientBatch.raw("cobweb"));
        cauldron.tick(10.0, stableHeat);
        cauldron.tick(COLLECTION_COOLING_SECONDS, new BrewStructure(
                HeatSourceKind.NONE, 0, SmokeVentState.OPEN, HerbState.PRESENT));
        var collection = cauldron.collectBottle(CURRENT_GAME_DAY);
        context.assertTrue(collection.kind() == BrewCollectionKind.POTION,
                "高分熬制没有产出药水");
        context.assertTrue(collection.score().orElseThrow().quality() == BrewQuality.SUPERIOR,
                "舀出的品质没有保留");

        BrewCauldron scorched = new BrewCauldron(VesselKind.WITCH_CAULDRON, BrewForm.SPLASH);
        scorched.addWater(1);
        scorched.addIngredient(IngredientSlot.PRIMARY,
                IngredientBatch.prepared("withered_bush_ash", 0));
        scorched.addIngredient(IngredientSlot.BASE,
                IngredientBatch.raw("minecraft:glowstone_dust"));
        scorched.addIngredient(IngredientSlot.PROPELLANT,
                IngredientBatch.raw("minecraft:gunpowder"));
        BrewStructure soulHeat = new BrewStructure(
                HeatSourceKind.SOUL_CAMPFIRE,
                BrewStructure.REQUIRED_HEARTH_STONES,
                SmokeVentState.OPEN,
                HerbState.PRESENT);
        scorched.startBrew(soulHeat);
        scorched.addIngredient(IngredientSlot.ACCESSORY,
                IngredientBatch.raw("fermented_spider_eye"));
        scorched.addIngredient(IngredientSlot.CATALYST,
                IngredientBatch.raw("wither_rose"));
        scorched.tick(21.0, soulHeat);
        context.assertTrue(scorched.state() == BrewCauldronState.SCORCHED
                        || scorched.scorchCount() > 0,
                "过热没有记录焦罚或糊锅");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void brewingStandRejectsQualityBrew(GameTestHelper context) {
        // 真实酿造台方块实体调用 canPlaceItem，验证 mixin 只拒绝本仓巫药。
        ServerLevel level = context.getLevel();
        BlockPos standPos = context.absolutePos(new BlockPos(STRUCTURE_X, STRUCTURE_Y, STRUCTURE_Z));
        level.setBlockAndUpdate(standPos, Blocks.BREWING_STAND.defaultBlockState());
        context.runAfterDelay(OBSERVE_TICKS, () -> {
            BlockEntity entity = level.getBlockEntity(standPos);
            context.assertTrue(entity instanceof BrewingStandBlockEntity, "酿造台方块实体未创建");
            BrewingStandBlockEntity stand = (BrewingStandBlockEntity) entity;
            ItemStack brew = BrewItemStacks.create(
                    BrewKind.DEPLETION, BrewQuality.FINE, BrewUse.SPLASH);
            context.assertTrue(!stand.canPlaceItem(0, brew), "酿造台接受了巫药");
            context.assertTrue(stand.canPlaceItem(0, new ItemStack(Items.POTION)),
                    "酿造台拒绝了原版药水");
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void depletionWitheringUsesPerTickBudget(GameTestHelper context) {
        // 真实喷溅入口把十个草方块排入枯萎队列，验证第一批不超过服务端预算。
        ServerLevel level = context.getLevel();
        BlockPos origin = context.absolutePos(new BlockPos(STRUCTURE_X, STRUCTURE_Y, STRUCTURE_Z));
        List<BlockPos> candidates = new ArrayList<>();
        int[] lateralOffsets = {-2, -1, 0, 1, 2};
        int[] depthOffsets = {-1, 1};
        for (int lateralOffset : lateralOffsets) {
            for (int depthOffset : depthOffsets) {
                BlockPos candidate = origin.offset(lateralOffset, 0, depthOffset);
                level.setBlockAndUpdate(candidate, Blocks.GRASS_BLOCK.defaultBlockState());
                candidates.add(candidate);
            }
        }
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(origin.getX() + 0.5, origin.getY() + 1.0, origin.getZ() + 0.5);
        ItemStack brew = BrewItemStacks.create(BrewKind.DEPLETION, BrewQuality.POOR, BrewUse.SPLASH);
        BrewingRuntime.splash(level, brew, Vec3.atCenterOf(origin), player);
        context.runAfterDelay(WITHERING_OBSERVE_TICKS, () -> {
            int changed = 0;
            for (BlockPos candidate : candidates) {
                if (level.getBlockState(candidate).is(Blocks.DIRT)) {
                    changed++;
                }
            }
            context.assertTrue(changed > 0, "枯萎喷溅没有转换草方块");
            context.assertTrue(changed <= 8, "单 tick 枯萎超过预算：" + changed);
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void depletionWitheringRespectsWorldPermissions(GameTestHelper context) {
        // 落点中心的草必定命中，分别验证生物规则和玩家原版建造权限。
        ServerLevel level = context.getLevel();
        BlockPos origin = context.absolutePos(new BlockPos(STRUCTURE_X, STRUCTURE_Y, STRUCTURE_Z));
        ItemStack brew = BrewItemStacks.create(BrewKind.DEPLETION, BrewQuality.POOR, BrewUse.SPLASH);
        level.setBlockAndUpdate(origin, Blocks.GRASS_BLOCK.defaultBlockState());
        var zombie = context.spawn(EntityTypes.ZOMBIE, new BlockPos(STRUCTURE_X, STRUCTURE_Y + 1, STRUCTURE_Z));
        boolean previousMobGriefing = level.getGameRules().get(GameRules.MOB_GRIEFING);
        level.getGameRules().set(GameRules.MOB_GRIEFING, false /* mobGriefing */, level.getServer());
        BrewingRuntime.splash(level, brew, Vec3.atCenterOf(origin), zombie);
        context.runAfterDelay(WITHERING_OBSERVE_TICKS, () -> {
            try {
                context.assertTrue(level.getBlockState(origin).is(Blocks.GRASS_BLOCK),
                        "mobGriefing=false 时生物来源枯萎改了方块");
            } finally {
                level.getGameRules().set(GameRules.MOB_GRIEFING, previousMobGriefing, level.getServer());
            }
            ServerPlayer player = BrewingTestPlayers.create(context);
            player.setGameMode(GameType.ADVENTURE);
            player.setPos(origin.getX() + 0.5, origin.getY() + 1.0, origin.getZ() + 0.5);
            BrewingRuntime.splash(level, brew, Vec3.atCenterOf(origin), player);
            context.runAfterDelay(WITHERING_OBSERVE_TICKS, () -> {
                context.assertTrue(level.getBlockState(origin).is(Blocks.GRASS_BLOCK),
                        "冒险模式玩家来源枯萎改了方块");
                player.setGameMode(GameType.SURVIVAL);
                BrewingRuntime.splash(level, brew, Vec3.atCenterOf(origin), player);
                context.runAfterDelay(WITHERING_OBSERVE_TICKS, () -> {
                    context.assertTrue(level.getBlockState(origin).is(Blocks.DIRT),
                            "生存模式玩家来源枯萎没有修改方块");
                    context.succeed();
                });
            });
        });
    }

    private static List<BlockPos> placeWitheringGrass(ServerLevel level, BlockPos origin,
                                                      int lateralRadius) {
        // 放置真实可枯萎方块，保留位置供异步处理后的世界状态断言使用。
        List<BlockPos> candidates = new ArrayList<>();
        for (int offset = -lateralRadius; offset <= lateralRadius; offset++) {
            BlockPos candidate = origin.offset(offset, 0, 1);
            level.setBlockAndUpdate(candidate, Blocks.GRASS_BLOCK.defaultBlockState());
            candidates.add(candidate);
        }
        return candidates;
    }

    private static void assertUnchanged(GameTestHelper context, ServerLevel level,
                                        List<BlockPos> candidates, String message) {
        // 逐格检查权限拒绝后的世界状态，不能只检查权限对象的计算结果。
        for (BlockPos candidate : candidates) {
            context.assertTrue(level.getBlockState(candidate).is(Blocks.GRASS_BLOCK), message);
        }
    }

    private static <T> void assertRegistered(GameTestHelper context,
                                             Registry<T> registry,
                                             List<String> paths,
                                             String kind) {
        for (String path : paths) {
            Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, path);
            context.assertTrue(registry.containsKey(id), kind + "未注册：" + id);
        }
    }

    private static void useCauldronItem(BrewCauldronBlockEntity cauldron, ServerPlayer player,
                                        ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        cauldron.use(player, InteractionHand.MAIN_HAND);
    }

    private static ItemStack itemStack(String itemId) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        return new ItemStack(item);
    }

    private static ItemStack findItem(Player player, Item item) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static CompoundTag save(ServerLevel level, BlockEntity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, level.registryAccess());
        entity.saveCustomOnly(output);
        return output.buildResult();
    }

    private static ValueInput input(ServerLevel level, CompoundTag tag) {
        return TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag);
    }

    private static void assertItemRegistered(GameTestHelper context, String path, String kind) {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, path);
        context.assertTrue(BuiltInRegistries.ITEM.containsKey(id), kind + "未注册：" + id);
    }

    private static void assertBlockItemName(GameTestHelper context, String path) {
        // 方块物品显示方块的名字，语言文件只写 block.<命名空间>.<路径> 一份。
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, path);
        String blockKey = BuiltInRegistries.BLOCK.getValue(id).getDescriptionId();
        String itemKey = BuiltInRegistries.ITEM.getValue(id).getDescriptionId();
        context.assertTrue(itemKey.equals(blockKey), "方块物品的翻译键应与方块相同：" + itemKey + " ≠ " + blockKey);
    }

    private static void assertStrippedBlock(GameTestHelper context, String sourcePath,
                                            String targetPath) {
        Identifier sourceId = Identifier.fromNamespaceAndPath(MOD_ID, sourcePath);
        Identifier targetId = Identifier.fromNamespaceAndPath("minecraft", targetPath);
        var source = BuiltInRegistries.BLOCK.getValue(sourceId);
        var stripped = StrippableBlockRegistry.getStrippedBlockState(source.defaultBlockState());
        context.assertTrue(stripped.is(BuiltInRegistries.BLOCK.getValue(targetId)),
                "枯木去皮目标错误：" + sourceId);
    }

    private static BlockState withValue(BlockState state, String property, String value) {
        return withParsedValue(state, state.getBlock().getStateDefinition().getProperty(property), value);
    }

    private static <T extends Comparable<T>> BlockState withParsedValue(
            BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow());
    }

    private static String valueName(BlockState state, String property) {
        return propertyValueName(state, state.getBlock().getStateDefinition().getProperty(property));
    }

    private static <T extends Comparable<T>> String propertyValueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static BrewUse preferredUse(BrewKind kind) {
        if (kind.supports(BrewUse.DRINK)) {
            return BrewUse.DRINK;
        }
        return BrewUse.SPLASH;
    }

    private static Item expectedItem(BrewUse use) {
        if (use == BrewUse.DRINK) {
            return Items.POTION;
        }
        return Items.SPLASH_POTION;
    }
}
