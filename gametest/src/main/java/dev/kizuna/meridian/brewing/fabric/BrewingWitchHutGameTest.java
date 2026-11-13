package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutPiece;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 女巫小屋的药方箱：原版小屋结构件搭出来带一只朝门的箱子，箱子的战利品表六张药方等概率，还可能有一本《熬药总纲》。 */
public final class BrewingWitchHutGameTest {
    private static final String SITE = "meridian_brewing_test:witch_hut_site";
    private static final int TIMEOUT = 40;
    /** 场地 24 × 24，四座小屋各占一格 12 × 12；小屋最宽 9 格。 */
    private static final int CELL = 12;
    private static final int MARGIN = 1;
    private static final int CELLS_PER_ROW = 2;
    private static final List<Direction> ORIENTATIONS =
            List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final int SEED_SEARCH = 256;
    private static final long HUT_SEED = 20_261_111L;
    /** 门廊在箱子正前方第 6 格（小屋本地 z 6 → 0），地板比站人的一层低一格。 */
    private static final int PORCH_STEPS = 6;
    private static final int CAULDRON_STEPS = 2;
    private static final int ENTITY_MARGIN = 2;
    private static final int LOOT_ROLLS = 200;
    private static final long LOOT_SEED = 20_261_112L;
    /** 占位概率 0.75：200 箱期望 150，标准差约 6；上下各留 30。 */
    private static final int MIN_RECIPE_CHESTS = 120;
    private static final int MAX_RECIPE_CHESTS = 180;
    /** 熬药卷轴占位 0.25：200 箱期望 50，标准差约 6，放宽到 30–70。 */
    private static final int MIN_SCROLL_CHESTS = 30;
    private static final int MAX_SCROLL_CHESTS = 70;
    /** 《熬药总纲》占位 0.5：200 箱期望 100，标准差约 7，放宽到 70–130。 */
    private static final int MIN_MANUAL_CHESTS = 70;
    private static final int MAX_MANUAL_CHESTS = 130;
    /** 每种药方期望 25 张，标准差约 4.6。 */
    private static final int MIN_PER_RECIPE = 10;

    /**
     * 原版小屋第一次搭时按地面挪到地面高度，记下的高度 ≥ 0 才算记住；测试场地在 y 0 以下，原版每跑一次都按
     * 刚搭好的地板再抬一次。真实的沼泽地面在海平面附近，只挪一次，这里也只挪第一次，其余照原版。
     */
    private static final class TestSiteHut extends SwampHutPiece {
        private boolean grounded;

        private TestSiteHut(RandomSource random, int west, int north) {
            // 朝向、尺寸照原版构造时的随机数定。
            super(random, west, north);
        }

        @Override
        protected boolean updateAverageGroundHeight(LevelAccessor level, BoundingBox box, int offset) {
            // 第一次照原版找地面、挪高度，之后停在那里。
            if (!grounded) {
                grounded = super.updateAverageGroundHeight(level, box, offset);
            }
            return grounded;
        }
    }

    @GameTest(maxTicks = TIMEOUT, structure = SITE)
    public void newWitchHutHasRecipeChestFacingTheDoor(GameTestHelper context) {
        // 用原版结构件搭四座小屋，四个朝向各一座；每座分东西两半各跑一次，像跨两块区块生成，再整座补跑一次。
        // 每座后墙一排是箱子、工作台、炼药锅，箱子背靠后墙、正面朝门廊，挂本仓的战利品表；补跑不再放、不重抽。
        ServerLevel level = context.getLevel();
        List<ItemStack> opened = new ArrayList<>();
        for (int index = 0; index < ORIENTATIONS.size(); index++) {
            BlockPos corner = context.absolutePos(new BlockPos(
                    MARGIN + index % CELLS_PER_ROW * CELL, 0, MARGIN + index / CELLS_PER_ROW * CELL));
            SwampHutPiece hut = hut(context, corner, ORIENTATIONS.get(index));
            BlockPos chest = buildInHalves(context, level, hut, corner);
            assertBesideCraftingTable(context, level, hut, chest);
            if (index == 0) {
                opened.addAll(open(level, chest));
            }
            clearMobs(level, hut);
        }
        context.assertFalse(opened.isEmpty(), "打开的小屋箱子是空的");
        List<String> unexpected = opened.stream()
                .filter(stack -> !stack.is(BrewingItems.item("brew_recipe")) && !stack.is(scroll())
                        && !stack.is(BrewingItems.item("brew_manual")) && !materials().contains(stack.getItem()))
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .toList();
        context.assertTrue(unexpected.isEmpty(), "小屋箱子里有药方、熬药卷轴、熬药总纲和炼药材料以外的东西：" + unexpected);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void witchHutChestDrawsSixRecipesEvenly(GameTestHelper context) {
        // 抽 200 箱：有药方的箱数落在占位概率附近，六张药方都出、各自不少；
        // 有熬药卷轴的箱数落在它的占位概率附近，一箱最多一卷；总纲另有用例数；别的都是炼药材料。
        Item recipe = BrewingItems.item("brew_recipe");
        Item manual = BrewingItems.item("brew_manual");
        Map<String, Integer> recipes = new TreeMap<>();
        Map<String, Integer> materials = new TreeMap<>();
        List<String> unexpected = new ArrayList<>();
        int chestsWithRecipe = 0;
        int chestsWithScroll = 0;
        int mostScrolls = 0;
        for (List<ItemStack> chest : drawChests(context)) {
            boolean hasRecipe = false;
            int scrolls = 0;
            for (ItemStack stack : chest) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (stack.is(recipe)) {
                    recipes.merge(String.valueOf(stack.get(BrewingComponents.RECIPE_KIND)), 1, Integer::sum);
                    hasRecipe = true;
                } else if (stack.is(scroll())) {
                    scrolls += stack.getCount();
                } else if (materials().contains(stack.getItem())) {
                    materials.merge(id, 1, Integer::sum);
                } else if (!stack.is(manual)) {
                    unexpected.add(id);
                }
            }
            if (hasRecipe) {
                chestsWithRecipe++;
            }
            if (scrolls > 0) {
                chestsWithScroll++;
            }
            mostScrolls = Math.max(mostScrolls, scrolls);
        }
        System.out.println("[女巫小屋箱子] 抽 " + LOOT_ROLLS + " 箱：有药方 " + chestsWithRecipe + " 箱；药方 " + recipes
                + "；有熬药卷轴 " + chestsWithScroll + " 箱；材料（组数）" + materials);
        context.assertTrue(unexpected.isEmpty(),
                "小屋箱子抽出了药方、熬药卷轴、熬药总纲和炼药材料以外的东西：" + unexpected);
        context.assertTrue(chestsWithScroll >= MIN_SCROLL_CHESTS && chestsWithScroll <= MAX_SCROLL_CHESTS,
                "有熬药卷轴的箱数偏离占位概率 0.25：" + chestsWithScroll + " / " + LOOT_ROLLS);
        context.assertTrue(mostScrolls == 1, "一箱里不该有不止一卷熬药卷轴：" + mostScrolls);
        Set<String> kinds = Arrays.stream(BrewKind.values()).map(BrewKind::path).collect(Collectors.toSet());
        context.assertTrue(recipes.keySet().equals(kinds), "六张药方没有都出现，或出了未登记的药方：" + recipes);
        context.assertTrue(chestsWithRecipe >= MIN_RECIPE_CHESTS && chestsWithRecipe <= MAX_RECIPE_CHESTS,
                "有药方的箱数偏离占位概率 0.75：" + chestsWithRecipe + " / " + LOOT_ROLLS);
        int fewest = recipes.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        context.assertTrue(fewest >= MIN_PER_RECIPE, "六张药方不是等概率，最少的只出 " + fewest + " 张：" + recipes);
        context.assertTrue(materials.keySet().size() == materials().size(),
                "200 箱里没有出齐每种炼药材料：" + materials);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void witchHutChestHoldsTheManualAtItsChance(GameTestHelper context) {
        // 抽 200 箱：有《熬药总纲》的箱数落在它的占位概率附近，一箱最多一本。
        Item manual = BrewingItems.item("brew_manual");
        int chestsWithManual = 0;
        int mostManuals = 0;
        for (List<ItemStack> chest : drawChests(context)) {
            int manuals = chest.stream().filter(stack -> stack.is(manual)).mapToInt(ItemStack::getCount).sum();
            if (manuals > 0) {
                chestsWithManual++;
            }
            mostManuals = Math.max(mostManuals, manuals);
        }
        System.out.println("[女巫小屋箱子] 抽 " + LOOT_ROLLS + " 箱：有熬药总纲 " + chestsWithManual + " 箱");
        context.assertTrue(chestsWithManual >= MIN_MANUAL_CHESTS && chestsWithManual <= MAX_MANUAL_CHESTS,
                "有熬药总纲的箱数偏离占位概率 0.5：" + chestsWithManual + " / " + LOOT_ROLLS);
        context.assertTrue(mostManuals == 1, "一箱里不该有不止一本熬药总纲：" + mostManuals);
        context.succeed();
    }

    private static List<List<ItemStack>> drawChests(GameTestHelper context) {
        // 照原版开箱的参数，用固定种子抽 200 箱，每箱一份物品表。
        ServerLevel level = context.getLevel();
        LootTable table = level.getServer().reloadableRegistries().getLootTable(BrewWitchHut.CHEST_TABLE);
        context.assertFalse(table == LootTable.EMPTY, "没有加载小屋箱子的战利品表：" + BrewWitchHut.CHEST_TABLE);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(context.absolutePos(BlockPos.ZERO)))
                .create(LootContextParamSets.CHEST);
        RandomSource random = RandomSource.create(LOOT_SEED);
        List<List<ItemStack>> chests = new ArrayList<>();
        for (int roll = 0; roll < LOOT_ROLLS; roll++) {
            chests.add(table.getRandomItems(params, random));
        }
        return chests;
    }

    private static SwampHutPiece hut(GameTestHelper context, BlockPos corner, Direction orientation) {
        // 原版结构件的朝向由构造时的随机数定：按种子找一座朝向对的。相邻小整数种子的第一个随机数几乎一样，
        // 所以种子本身从一个固定的随机数源里取。
        RandomSource seeds = RandomSource.create(HUT_SEED);
        for (int attempt = 0; attempt < SEED_SEARCH; attempt++) {
            SwampHutPiece hut = new TestSiteHut(RandomSource.create(seeds.nextLong()), corner.getX(), corner.getZ());
            if (hut.getOrientation() == orientation) {
                return hut;
            }
        }
        throw context.assertionException(
                Component.literal("前 " + SEED_SEARCH + " 个种子里没有朝 " + orientation + " 的小屋"));
    }

    private static BlockPos buildInHalves(GameTestHelper context, ServerLevel level, SwampHutPiece hut,
                                          BlockPos corner) {
        // 生成时传给结构件的是一块区块的范围，竖直方向是整个世界高度。这里按东西两半各跑一次，
        // 箱子只该在它所在那一半放下；再整座补跑一次，已经是箱子的格不再放，战利品种子不变。
        BoundingBox footprint = hut.getBoundingBox();
        int split = (footprint.minX() + footprint.maxX()) / 2;
        BoundingBox west = column(level, footprint.minX(), split, footprint);
        BoundingBox east = column(level, split + 1, footprint.maxX(), footprint);
        RandomSource random = RandomSource.create(corner.asLong());
        postProcess(level, hut, west, random, corner);
        List<BlockPos> afterWest = chests(level, hut.getBoundingBox());
        postProcess(level, hut, east, random, corner);
        List<BlockPos> chests = chests(level, hut.getBoundingBox());
        context.assertTrue(chests.size() == 1, "朝 " + hut.getOrientation() + " 的小屋里应有一只箱子：" + chests);
        BlockPos chest = chests.getFirst();
        context.assertTrue(afterWest.isEmpty() == !west.isInside(chest),
                "只跑西半边时，箱子应当只在它所在那一半放下：" + afterWest + "，箱子在 " + chest);
        long seed = chestEntity(context, level, chest).getLootTableSeed();
        postProcess(level, hut, column(level, footprint.minX(), footprint.maxX(), footprint), random, corner);
        List<BlockPos> rerun = chests(level, hut.getBoundingBox());
        context.assertTrue(rerun.equals(List.of(chest)),
                "朝 " + hut.getOrientation() + " 的小屋整座补跑以后箱子变了：" + chest + " → " + rerun);
        context.assertTrue(chestEntity(context, level, chest).getLootTableSeed() == seed, "整座补跑以后箱子重抽了");
        return chest;
    }

    private static BoundingBox column(ServerLevel level, int minX, int maxX, BoundingBox footprint) {
        // 像一块区块那样竖直方向占满整个世界高度，水平方向只取小屋的这几列。
        return new BoundingBox(minX, level.getMinY(), footprint.minZ(), maxX, level.getMaxY(), footprint.maxZ());
    }

    private static void postProcess(ServerLevel level, SwampHutPiece hut, BoundingBox box, RandomSource random,
                                    BlockPos corner) {
        // 和区块生成时一样调用原版结构件，补箱子的 mixin 跟着跑。
        hut.postProcess(level, level.structureManager(), level.getChunkSource().getGenerator(), random, box,
                ChunkPos.containing(corner), corner);
    }

    private static void assertBesideCraftingTable(GameTestHelper context, ServerLevel level, SwampHutPiece hut,
                                                  BlockPos chest) {
        // 箱子背靠后墙、正面朝门廊那头；一侧挨着工作台，再过去是炼药锅；挂的是本仓的战利品表。
        Direction facing = level.getBlockState(chest).getValue(ChestBlock.FACING);
        String where = "朝 " + hut.getOrientation() + " 的小屋，箱子朝 " + facing;
        assertBlock(context, level, chest.relative(facing.getOpposite()), Blocks.SPRUCE_PLANKS, where + "，背后不是后墙");
        assertBlock(context, level, chest.relative(facing), Blocks.AIR, where + "，正面被挡住");
        BlockPos porch = chest.relative(facing, PORCH_STEPS);
        assertBlock(context, level, porch.below(), Blocks.SPRUCE_PLANKS, where + "，正前方不是门廊");
        boolean besideTable = List.of(facing.getClockWise(), facing.getCounterClockWise()).stream()
                .anyMatch(side -> level.getBlockState(chest.relative(side)).is(Blocks.CRAFTING_TABLE)
                        && level.getBlockState(chest.relative(side, CAULDRON_STEPS)).is(Blocks.CAULDRON));
        context.assertTrue(besideTable, where + "，旁边不是工作台和炼药锅");
        var table = chestEntity(context, level, chest).getLootTable();
        context.assertTrue(BrewWitchHut.CHEST_TABLE.equals(table), where + "，战利品表不对：" + table);
    }

    private static void assertBlock(GameTestHelper context, ServerLevel level, BlockPos pos, Block block,
                                    String message) {
        // 失败时把那一格实际的方块状态带进消息里。
        context.assertTrue(level.getBlockState(pos).is(block), message + "：" + level.getBlockState(pos));
    }

    private static List<BlockPos> chests(ServerLevel level, BoundingBox box) {
        // 数小屋范围里所有箱子方块，补跑以后用来比对有没有多放、挪位。
        return BlockPos.betweenClosedStream(box)
                .filter(pos -> level.getBlockState(pos).is(Blocks.CHEST))
                .map(BlockPos::immutable)
                .toList();
    }

    private static ChestBlockEntity chestEntity(GameTestHelper context, ServerLevel level, BlockPos chest) {
        // 箱子方块必须带方块实体，战利品表和种子都记在它身上。
        if (level.getBlockEntity(chest) instanceof ChestBlockEntity entity) {
            return entity;
        }
        throw context.assertionException(Component.literal("箱子格没有箱子方块实体：" + chest));
    }

    private static List<ItemStack> open(ServerLevel level, BlockPos chest) {
        // 和漏斗抽箱子一样不带玩家展开战利品，再把格子里的东西读出来。
        ChestBlockEntity entity = (ChestBlockEntity) level.getBlockEntity(chest);
        entity.unpackLootTable(null);
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < entity.getContainerSize(); slot++) {
            if (!entity.getItem(slot).isEmpty()) {
                items.add(entity.getItem(slot).copy());
            }
        }
        return items;
    }

    private static void clearMobs(ServerLevel level, SwampHutPiece hut) {
        // 原版小屋会刷一只女巫和一只猫，留着会去打隔壁测试里的假玩家。
        AABB area = AABB.of(hut.getBoundingBox()).inflate(ENTITY_MARGIN);
        level.getEntitiesOfClass(Witch.class, area).forEach(Entity::discard);
        level.getEntitiesOfClass(Cat.class, area).forEach(Entity::discard);
    }

    private static Item scroll() {
        // 熬药卷轴是功法库按目录生成的物品，和女巫掉落的是同一件。
        return BuiltInRegistries.ITEM.getValue(BrewingLoot.SCROLL_ID);
    }

    private static Set<Item> materials() {
        // 和战利品表 chests/witch_hut.json 最后一个池子一一对应。
        return Set.of(Items.GLASS_BOTTLE, Items.GLOWSTONE_DUST, Items.SUGAR, Items.REDSTONE, Items.COBWEB,
                Items.FERMENTED_SPIDER_EYE, Items.HONEY_BOTTLE, BrewingItems.item("dry_moss"),
                BrewingItems.item("dry_poppy"));
    }
}
