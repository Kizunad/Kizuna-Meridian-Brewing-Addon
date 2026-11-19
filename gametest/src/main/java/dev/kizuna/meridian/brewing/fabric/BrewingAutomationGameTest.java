package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewFireSource;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewRecipe;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewStructure;
import dev.kizuna.meridian.brewing.server.FireKind;
import dev.kizuna.meridian.brewing.server.HeatSourceKind;
import dev.kizuna.meridian.brewing.server.HerbCrateState;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import dev.kizuna.meridian.brewing.test.BrewSites;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import java.util.EnumSet;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

/** 自动化：真的漏斗、投掷器、发射器和比较器对着晒药匾、火源和釜。 */
public final class BrewingAutomationGameTest {
    private static final int TIMEOUT = 200;
    private static final int HOPPER_TICKS = 60;
    private static final int PULSE_TICKS = 2;
    private static final int DISPENSE_TICKS = 8;
    private static final int COMPARATOR_TICKS = 6;
    private static final int RAW_MOSS = 4;
    private static final int DRIED_MOSS = 3;
    private static final int HOPPER_COAL = 6;
    private static final int WITCH_CAULDRON_WATER = 2;
    private static final int FIRE_SIGNAL = 3;
    private static final long PREPARED_DAY = 4L;
    private static final String CAULDRON = "witch_cauldron";
    private static final String BRAZIER = "iron_brazier";
    private static final BrewStructure COLD = BrewStructure.built(HeatSourceKind.NONE);
    private static final int LAST_SLOT = HerbCrateState.SLOT_COUNT - 1;
    private static final Set<IngredientSlot> PRIMARY_AND_BASE =
            EnumSet.of(IngredientSlot.PRIMARY, IngredientSlot.BASE);

    @GameTest(maxTicks = TIMEOUT)
    public void hoppersAndDroppersFeedRawHerbsAndOnlyDriedOnesLeaveFromBelow(GameTestHelper context) {
        // 上面的漏斗先是泥土、后是苔藓块：泥土不收，留在漏斗里；苔藓块进匾。下面的漏斗抽不走生的苔藓块。
        BlockPos cratePos = new BlockPos(2, 3, 2);
        HerbCrateBlockEntity crate = place(context, cratePos, BrewingBlocks.HERB_CRATE.defaultBlockState(),
                HerbCrateBlockEntity.class);
        Container above = hopper(context, cratePos.above(), Direction.DOWN);
        above.setItem(0, new ItemStack(Items.DIRT));
        above.setItem(1, new ItemStack(Items.MOSS_BLOCK, RAW_MOSS));
        Container below = hopper(context, cratePos.below(), Direction.EAST);
        // 旁边的投掷器同样只送生药：先投一块苔藓块，再试一块泥土。
        BlockPos dropperPos = cratePos.west();
        Container dropper = facing(context, dropperPos, Blocks.DROPPER, Direction.EAST);
        dropper.setItem(0, new ItemStack(Items.MOSS_BLOCK));
        context.pulseRedstone(dropperPos.west(), PULSE_TICKS);
        context.runAfterDelay(DISPENSE_TICKS, () -> {
            dropper.setItem(0, new ItemStack(Items.DIRT));
            context.pulseRedstone(dropperPos.west(), PULSE_TICKS);
        });
        context.runAfterDelay(HOPPER_TICKS, () -> {
            context.assertTrue(count(crate, Items.MOSS_BLOCK) == RAW_MOSS + 1,
                    "漏斗和投掷器送进匾的苔藓块不对：" + count(crate, Items.MOSS_BLOCK));
            context.assertTrue(count(above, Items.DIRT) == 1, "漏斗把泥土送进了晒药匾");
            context.assertTrue(count(dropper, Items.DIRT) == 1, "投掷器把泥土送进或吐出了晒药匾");
            context.assertTrue(count(below, Items.MOSS_BLOCK) == 0, "下面的漏斗抽走了还没晒的苔藓块");
            context.assertTrue(droppedItems(context, cratePos) == 0, "有东西被吐到了地上");
            ItemStack dried = new ItemStack(BrewingItems.item("dry_moss"), DRIED_MOSS);
            dried.set(BrewingComponents.PREPARED_DAY, PREPARED_DAY);
            crate.setItem(LAST_SLOT, dried);
        });
        context.runAfterDelay(HOPPER_TICKS * 2, () -> {
            Item dryMoss = BrewingItems.item("dry_moss");
            context.assertTrue(count(below, dryMoss) == DRIED_MOSS,
                    "下面的漏斗没有抽走晒好的干苔：" + count(below, dryMoss));
            context.assertTrue(count(crate, Items.MOSS_BLOCK) == RAW_MOSS + 1, "抽干药时把生药也带走了");
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void hoppersAndDroppersStokeTheFireUpToFourLevels(GameTestHelper context) {
        // 漏斗里六块煤：烧到四档就不再收，剩下两块留在漏斗里。投掷器投一块木炭，火升一档。
        BlockPos firePos = new BlockPos(1, 2, 1);
        BrewFireBlockEntity fire = place(context, firePos, block(BRAZIER), BrewFireBlockEntity.class);
        Container hopper = hopper(context, firePos.east(), Direction.WEST);
        hopper.setItem(0, new ItemStack(Items.COAL, HOPPER_COAL));
        BlockPos otherFirePos = new BlockPos(1, 2, 4);
        BrewFireBlockEntity otherFire = place(context, otherFirePos, block(BRAZIER), BrewFireBlockEntity.class);
        BlockPos dropperPos = otherFirePos.east();
        Container dropper = facing(context, dropperPos, Blocks.DROPPER, Direction.WEST);
        dropper.setItem(0, new ItemStack(Items.CHARCOAL));
        context.pulseRedstone(dropperPos.east(), PULSE_TICKS);
        context.runAfterDelay(HOPPER_TICKS, () -> {
            context.assertTrue(fire.fire().level() == 4, "漏斗加炭没有把火烧到四档：" + fire.fire().level());
            context.assertTrue(count(hopper, Items.COAL) == HOPPER_COAL - 4,
                    "四档以后漏斗里该剩两块煤：" + count(hopper, Items.COAL));
            context.assertTrue(fire.isEmpty(), "四档以后火源里还压着炭");
            context.assertTrue(otherFire.fire().level() == 1, "投掷器投的木炭没有升一档：" + otherFire.fire().level());
            context.assertTrue(dropper.isEmpty(), "投掷器里的木炭没有投进去");
            context.assertTrue(droppedItems(context, otherFirePos) == 0, "投掷器把木炭吐到了地上");
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void pendingFuelSurvivesSavingAndDropsWhenBroken(GameTestHelper context) {
        // 区块卸载时待烧的那块炭跟着存档；火源被打掉时掉出来，不吞。
        BlockPos firePos = new BlockPos(1, 2, 1);
        BrewFireBlockEntity fire = place(context, firePos, block(BRAZIER), BrewFireBlockEntity.class);
        for (int level = 0; level < 4; level++) {
            fire.fire().addFuel("minecraft:coal");
        }
        fire.setItem(0, new ItemStack(Items.CHARCOAL));
        ServerLevel level = context.getLevel();
        BrewFireBlockEntity restored = new BrewFireBlockEntity(fire.getBlockPos(), fire.getBlockState(),
                BrewFireSource.BRAZIER);
        restored.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(),
                save(level, fire)));
        context.assertTrue(restored.getItem(0).is(Items.CHARCOAL), "待烧的木炭没有经过存档往返");
        context.assertTrue(restored.fire().level() == 4, "火档没有经过存档往返");
        level.destroyBlock(context.absolutePos(firePos), false);
        context.runAfterDelay(1, () -> {
            context.assertTrue(droppedCount(context, firePos, Items.CHARCOAL) == 1, "打掉火源时待烧的木炭没掉出来");
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void dispenserPoursWaterAndBottlesTheBrewIntoItself(GameTestHelper context) {
        // 空釜不收玻璃瓶：瓶子留在发射器里，不吐出来。接着水桶、水瓶注水，空桶和玻璃瓶回到发射器；
        // 熬成以后玻璃瓶舀一瓶，舀出来的东西也放在发射器里。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BlockPos cauldronPos = new BlockPos(1, 2, 1);
        BrewCauldronBlockEntity entity = place(context, cauldronPos, block(CAULDRON), BrewCauldronBlockEntity.class);
        BlockPos dispenserPos = cauldronPos.east();
        BlockPos switchPos = dispenserPos.east();
        Container dispenser = facing(context, dispenserPos, Blocks.DISPENSER, Direction.WEST);
        dispenser.setItem(0, new ItemStack(Items.GLASS_BOTTLE));
        context.pulseRedstone(switchPos, PULSE_TICKS);
        context.runAfterDelay(DISPENSE_TICKS, () -> {
            context.assertTrue(count(dispenser, Items.GLASS_BOTTLE) == 1, "空釜把发射器的玻璃瓶用掉或吐掉了");
            context.assertTrue(droppedItems(context, cauldronPos) == 0, "发射器把玻璃瓶吐到了地上");
            dispenser.setItem(0, PotionContents.createItemStack(Items.POTION, Potions.WATER));
            context.pulseRedstone(switchPos, PULSE_TICKS);
        });
        context.runAfterDelay(DISPENSE_TICKS * 2, () -> {
            context.assertTrue(entity.cauldron().waterUnits() == 1, "发射器的水瓶没有注一份水");
            context.assertTrue(count(dispenser, Items.GLASS_BOTTLE) == 1, "空瓶没有回到发射器");
            dispenser.setItem(0, new ItemStack(Items.WATER_BUCKET));
            context.pulseRedstone(switchPos, PULSE_TICKS);
        });
        context.runAfterDelay(DISPENSE_TICKS * 3, () -> {
            context.assertTrue(entity.cauldron().waterUnits() == WITCH_CAULDRON_WATER,
                    "发射器的水桶没有补满铁锅：" + entity.cauldron().waterUnits());
            context.assertTrue(count(dispenser, Items.BUCKET) == 1, "水桶倒完的空桶没有回到发射器");
            dispenser.setItem(0, new ItemStack(Items.GLASS_BOTTLE));
            brewCold(entity, player, BrewKind.QI_GATHER);
            context.assertTrue(entity.cauldron().state() == BrewCauldronState.BREWED, "冷熬 30 秒没有熬成");
            context.pulseRedstone(switchPos, PULSE_TICKS);
        });
        context.runAfterDelay(DISPENSE_TICKS * 4, () -> {
            Item residue = BrewingItems.item("brew_residue");
            context.assertTrue(count(dispenser, residue) == 1, "舀出来的药渣没有放在发射器里");
            context.assertTrue(count(dispenser, Items.GLASS_BOTTLE) == 0, "舀药没有用掉玻璃瓶");
            context.assertTrue(droppedItems(context, cauldronPos) == 0, "发射器把东西吐到了地上");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void comparatorsReadDriedSlotsFireLevelAndCauldronStage(GameTestHelper context) {
        // 晒药匾两格晒好出 2；火源三档出 3；有水的釜出 1，熬成的釜出 4。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BlockPos cratePos = new BlockPos(1, 2, 1);
        HerbCrateBlockEntity crate = place(context, cratePos, BrewingBlocks.HERB_CRATE.defaultBlockState(),
                HerbCrateBlockEntity.class);
        BlockPos crateReader = comparator(context, cratePos);
        for (int slot = 0; slot < 2; slot++) {
            ItemStack dried = new ItemStack(BrewingItems.item("dry_moss"));
            dried.set(BrewingComponents.PREPARED_DAY, PREPARED_DAY);
            crate.setItem(slot, dried);
        }
        BlockPos firePos = new BlockPos(1, 2, 3);
        BrewFireBlockEntity fire = place(context, firePos, block(BRAZIER), BrewFireBlockEntity.class);
        BlockPos fireReader = comparator(context, firePos);
        for (int level = 0; level < FIRE_SIGNAL; level++) {
            fire.fire().addFuel("minecraft:coal");
        }
        BlockPos waterPos = new BlockPos(1, 2, 5);
        BrewCauldronBlockEntity water = place(context, waterPos, block(CAULDRON), BrewCauldronBlockEntity.class);
        BlockPos waterReader = comparator(context, waterPos);
        use(water, player, new ItemStack(Items.WATER_BUCKET));
        BlockPos brewedPos = new BlockPos(4, 2, 1);
        BrewCauldronBlockEntity brewed = place(context, brewedPos, block(CAULDRON), BrewCauldronBlockEntity.class);
        BlockPos brewedReader = comparator(context, brewedPos);
        use(brewed, player, new ItemStack(Items.WATER_BUCKET));
        brewCold(brewed, player, BrewKind.QI_GATHER);
        context.runAfterDelay(COMPARATOR_TICKS, () -> {
            assertSignal(context, crateReader, 2, "晒药匾两格晒好");
            assertSignal(context, fireReader, FIRE_SIGNAL, "火源三档");
            assertSignal(context, waterReader, 1, "釜里只有水");
            assertSignal(context, brewedReader, 4, "釜已熬成");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV1_SITE)
    public void hopperNorthOfLv1CopperCauldronFeedsPrimaryAndBase(GameTestHelper context) {
        // 一阶铜釜北边那格 NBT 里是空气，放个漏斗朝南对着釜：主料、底料一件一件下锅，结构照样认。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV1);
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewCauldronBlockEntity entity = cauldronAt(context, site);
        use(entity, player, new ItemStack(Items.WATER_BUCKET));
        BrewRecipe recipe = BrewRecipes.require(BrewKind.QI_GATHER);
        Container hopper = hopper(context, inTest(context, site.world(2, 2, 1)), Direction.SOUTH);
        hopper.setItem(0, new ItemStack(BrewingItems.item(recipe.ingredients().preparedPrimary())));
        hopper.setItem(1, new ItemStack(Items.GLOWSTONE_DUST));
        context.runAfterDelay(HOPPER_TICKS, () -> {
            context.assertTrue(entity.cauldron().ingredients().keySet().equals(PRIMARY_AND_BASE),
                    "漏斗没把主料、底料送进铜釜：" + entity.cauldron().ingredients().keySet());
            context.assertTrue(hopper.isEmpty(), "漏斗里还剩东西");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV2_SITE)
    public void lv2SideHopperAndDropperFeedStartIngredientsButKeepTheAccessory(GameTestHelper context) {
        // 巫釜北边的漏斗投入主料和底料，辅料留在漏斗里。西边的投掷器每个脉冲投一份火药。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV2);
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewCauldronBlockEntity entity = cauldronAt(context, site);
        use(entity, player, new ItemStack(Items.WATER_BUCKET));
        BrewRecipe recipe = BrewRecipes.require(BrewKind.INSECT_LURE);
        Item accessory = vanillaItem(recipe.ingredients().accessory());
        Container hopper = hopper(context, inTest(context, site.world(5, 1, 4)), Direction.SOUTH);
        hopper.setItem(0, new ItemStack(BrewingItems.item(recipe.ingredients().preparedPrimary())));
        hopper.setItem(1, new ItemStack(accessory));
        hopper.setItem(2, new ItemStack(Items.GLOWSTONE_DUST));
        BlockPos dropperPos = inTest(context, site.world(4, 1, 5));
        Container dropper = facing(context, dropperPos, Blocks.DROPPER, Direction.EAST);
        dropper.setItem(0, new ItemStack(Items.GUNPOWDER));
        // 火药要等主料定下药方才收，所以漏斗送完再给投掷器脉冲；红石块放在投掷器上面那格空气里。
        context.runAfterDelay(HOPPER_TICKS, () -> context.pulseRedstone(dropperPos.above(), PULSE_TICKS));
        context.runAfterDelay(HOPPER_TICKS + DISPENSE_TICKS, () -> {
            Set<IngredientSlot> expected = EnumSet.of(IngredientSlot.PRIMARY, IngredientSlot.BASE,
                    IngredientSlot.PROPELLANT);
            context.assertTrue(entity.cauldron().ingredients().keySet().equals(expected),
                    "漏斗、投掷器没把开熬前的三样送进巫釜：" + entity.cauldron().ingredients().keySet());
            context.assertTrue(count(hopper, accessory) == 1, "辅料不该从漏斗里送进去");
            context.assertTrue(dropper.isEmpty(), "投掷器里的火药没投进去");
            context.assertTrue(droppedItems(context, inTest(context, site.cauldron())) == 0,
                    "有东西被吐到了地上");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT, structure = BrewSites.LV1_SITE)
    public void brewingCauldronTakesNothingFromTheHopper(GameTestHelper context) {
        // 熬制中漏斗什么都送不进去，连时窗里手动能下的辅料、引药也不收。
        BrewSites.Site site = BrewSites.place(context, BrewSites.Shipped.LV1);
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewCauldronBlockEntity entity = cauldronAt(context, site);
        use(entity, player, new ItemStack(Items.WATER_BUCKET));
        BrewRecipe recipe = BrewRecipes.require(BrewKind.QI_GATHER);
        use(entity, player, new ItemStack(BrewingItems.item(recipe.ingredients().preparedPrimary())));
        use(entity, player, new ItemStack(Items.GLOWSTONE_DUST));
        context.assertTrue(entity.cauldron().startBrew(fired(BrewKind.QI_GATHER)).accepted(), "生药没有起熬");
        Item accessory = vanillaItem(recipe.ingredients().accessory());
        Item catalyst = vanillaItem(recipe.ingredients().catalyst());
        Container hopper = hopper(context, inTest(context, site.world(2, 2, 1)), Direction.SOUTH);
        hopper.setItem(0, new ItemStack(accessory));
        hopper.setItem(1, new ItemStack(catalyst));
        context.runAfterDelay(HOPPER_TICKS, () -> {
            context.assertTrue(entity.cauldron().state() == BrewCauldronState.BREWING,
                    "三秒就不在熬了：" + entity.cauldron().state());
            context.assertTrue(entity.cauldron().ingredients().keySet().equals(PRIMARY_AND_BASE),
                    "熬制中漏斗还送进了东西：" + entity.cauldron().ingredients().keySet());
            context.assertTrue(count(hopper, accessory) == 1 && count(hopper, catalyst) == 1,
                    "辅料、引药该留在漏斗里");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    /** 下主料和底料，起熬后冷熬 30 秒：火候一直不在带里，熬成的是一锅药渣。 */
    private static void brewCold(BrewCauldronBlockEntity entity, ServerPlayer player, BrewKind kind) {
        BrewRecipe recipe = BrewRecipes.require(kind);
        use(entity, player, new ItemStack(BrewingItems.item(recipe.ingredients().preparedPrimary())));
        use(entity, player, new ItemStack(Items.GLOWSTONE_DUST));
        BrewCauldron cauldron = entity.cauldron();
        if (!cauldron.startBrew(fired(kind)).accepted()) {
            throw new IllegalStateException("生药没有起熬：" + cauldron.state());
        }
        cauldron.tick(BrewCauldron.BREW_SECONDS, COLD);
    }

    /** 火种对得上这味药、结构有效的快照，起熬的结构检查能过。 */
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

    private static BrewCauldronBlockEntity cauldronAt(GameTestHelper context, BrewSites.Site site) {
        return (BrewCauldronBlockEntity) context.getLevel().getBlockEntity(site.cauldron());
    }

    private static Item vanillaItem(String id) {
        // 药方里辅料、引药写的是原版物品 ID，查不到时直接报错，免得拿空气去比。
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        if (item == Items.AIR) {
            throw new IllegalStateException("没有这种物品：" + id);
        }
        return item;
    }

    private static <T extends BlockEntity> T place(GameTestHelper context, BlockPos pos, BlockState state,
                                                   Class<T> type) {
        context.setBlock(pos, state);
        return context.getBlockEntity(pos, type);
    }

    private static BlockState block(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path))
                .defaultBlockState();
    }

    /**
     * 世界坐标换回测试里的相对坐标。原版 26.2 的 {@code relativePos} 多转了 180°，和 {@code absolutePos}
     * 不互逆，会把方块放到结构外面；这里的测试都不转，直接减测试原点。
     */
    private static BlockPos inTest(GameTestHelper context, BlockPos absolute) {
        return absolute.subtract(context.absolutePos(BlockPos.ZERO));
    }

    private static Container hopper(GameTestHelper context, BlockPos pos, Direction facing) {
        context.setBlock(pos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing));
        return (Container) context.getLevel().getBlockEntity(context.absolutePos(pos));
    }

    private static Container facing(GameTestHelper context, BlockPos pos, Block block, Direction facing) {
        context.setBlock(pos, block.defaultBlockState().setValue(DispenserBlock.FACING, facing));
        return (Container) context.getLevel().getBlockEntity(context.absolutePos(pos));
    }

    /** 在方块东边放一个比较器，背对着方块读它；比较器底下垫一块石头。 */
    private static BlockPos comparator(GameTestHelper context, BlockPos source) {
        BlockPos pos = source.east();
        context.setBlock(pos.below(), Blocks.STONE);
        context.setBlock(pos, Blocks.COMPARATOR.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
        return pos;
    }

    private static void assertSignal(GameTestHelper context, BlockPos pos, int expected, String what) {
        int signal = context.getBlockEntity(pos, ComparatorBlockEntity.class).getOutputSignal();
        context.assertTrue(signal == expected, what + "，比较器该出 " + expected + "，实际 " + signal);
    }

    private static int count(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int droppedItems(GameTestHelper context, BlockPos pos) {
        AABB area = new AABB(context.absolutePos(pos)).inflate(2);
        return context.getLevel().getEntitiesOfClass(ItemEntity.class, area).size();
    }

    private static int droppedCount(GameTestHelper context, BlockPos pos, Item item) {
        AABB area = new AABB(context.absolutePos(pos)).inflate(2);
        return context.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                .filter(entity -> entity.getItem().is(item))
                .mapToInt(entity -> entity.getItem().getCount())
                .sum();
    }

    private static CompoundTag save(ServerLevel level, BlockEntity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        entity.saveCustomOnly(output);
        return output.buildResult();
    }
}
