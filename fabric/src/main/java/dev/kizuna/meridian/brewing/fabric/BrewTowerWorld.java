package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewStructureDefinitions.Cell;
import dev.kizuna.meridian.brewing.fabric.BrewStructureDefinitions.Definition;
import dev.kizuna.meridian.brewing.fabric.BrewStructureMatcher.Placement;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.FireKind;
import dev.kizuna.meridian.brewing.server.IngredientBatch;
import dev.kizuna.meridian.brewing.server.PreparationKind;
import dev.kizuna.meridian.brewing.server.TowerFuel;
import dev.kizuna.meridian.brewing.server.TowerWorld;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 三阶药塔在世界里的那一半：按认出来的摆法找到六只木桶、铜大箱子、火室四格、四盏铜灯和水池，实现
 * {@link TowerWorld}。宿主每秒按塔记下的摆法新建一份，用完就丢；方块实体每次现取，不缓存。
 *
 * <p>木桶的“西、东、南”按 NBT 里的方位说：NBT 里 x 最小的一列是西，最大的一列是东，其余是南；
 * 塔转了朝向，取料顺序跟着结构走。</p>
 */
final class BrewTowerWorld implements TowerWorld {
    private static final long DAY_TICKS = 24_000L;
    private static final int WEST_SIDE = 0;
    private static final int EAST_SIDE = 1;
    private static final int SOUTH_SIDE = 2;
    private static final double BLOCK_CENTRE = 0.5;
    private static final double FLAME_HEIGHT = 0.2;
    private static final double FLAME_SPREAD = 0.25;
    private static final double FLAME_RISE_SPREAD = 0.1;
    private static final double FLAME_SPEED = 0.0;
    private static final int FLAMES_PER_CELL = 2;
    private final ServerLevel level;
    private final List<BlockPos> barrels;
    private final BlockPos chest;
    private final BlockPos chestFront;
    private final List<BlockPos> fuelCells;
    private final List<BlockPos> lamps;
    private final BlockPos pool;
    private final String drawPrefix;

    /** 按摆法找齐各部件；{@code drawPrefix} 加在每次环境取用的键前面，区分不同的塔。 */
    BrewTowerWorld(ServerLevel level, Placement placement, String drawPrefix) {
        // 核心格按方块类别分；铜箱子取两半里的第一格，合并容器从哪一半拿都是同一只大箱子，
        // 凹槽在它 NBT 北边那格。方块和方块实体之后每次现取。
        Definition definition = placement.definition();
        Cell chestCell = coresShowing(definition, shown -> shown.is(BlockTags.COPPER_CHESTS)).getFirst();
        this.level = level;
        this.barrels = barrelsInOrder(placement, coresShowing(definition, shown -> shown.is(Blocks.BARREL)));
        this.chest = placement.world(chestCell.local());
        this.chestFront = placement.world(chestCell.local().north());
        this.fuelCells = worldPositions(placement, definition.fuel());
        this.lamps = worldPositions(placement, coresShowing(definition, shown -> shown.is(BrewingTags.COPPER_BULBS)));
        this.pool = placement.anchorWorld();
        this.drawPrefix = drawPrefix;
    }

    private static List<BlockPos> worldPositions(Placement placement, List<Cell> cells) {
        // 保持定义里的顺序：燃料格第 i 格、铜灯第 i 盏都按这个顺序编号。
        return cells.stream()
                .map(cell -> placement.world(cell.local()))
                .toList();
    }

    private static List<Cell> coresShowing(Definition definition, Predicate<BlockState> kind) {
        // 核心格的“应为”方块就是它的类别：铜箱子不论氧化都显示铜箱子，铜灯同理。
        return definition.cores().stream()
                .filter(cell -> kind.test(cell.rule().shown()))
                .toList();
    }

    private static List<BlockPos> barrelsInOrder(Placement placement, List<Cell> barrels) {
        // 西边一列先取，再东边一列，最后南边；同一列从北往南、从西往东。
        int west = barrels.stream().mapToInt(cell -> cell.local().getX()).min().orElseThrow();
        int east = barrels.stream().mapToInt(cell -> cell.local().getX()).max().orElseThrow();
        Comparator<Cell> order = Comparator.<Cell>comparingInt(cell -> side(cell.local().getX(), west, east))
                .thenComparingInt(cell -> cell.local().getZ())
                .thenComparingInt(cell -> cell.local().getX());
        return barrels.stream()
                .sorted(order)
                .map(cell -> placement.world(cell.local()))
                .toList();
    }

    private static int side(int x, int west, int east) {
        // 只按 NBT 里的 x 分三组。
        if (x == west) {
            return WEST_SIDE;
        }
        if (x == east) {
            return EAST_SIDE;
        }
        return SOUTH_SIDE;
    }

    @Override
    public String itemId(String recipeItem) {
        // 和塔窗口显示缺料用同一条换法。
        return BrewItemStacks.recipeItemId(recipeItem).toString();
    }

    @Override
    public int count(String itemId) {
        // 和釜认料同一条比法：路径一样就算同一种。
        return count(stack -> matches(stack, itemId));
    }

    @Override
    public List<IngredientBatch> take(String itemId, int count) {
        // 一件一份；有容器的（蜂蜜瓶、龙息）当场把玻璃瓶放回木桶。
        List<IngredientBatch> taken = new ArrayList<>();
        for (ItemStack unit : removeUpTo(stack -> matches(stack, itemId), count)) {
            taken.add(batch(unit));
            returnRemainder(unit);
        }
        return taken;
    }

    @Override
    public void putBack(IngredientBatch batch) {
        // 主料把炮制日带回去，退回的东西和取出来时一样。
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(batch.itemId()));
        ItemStack stack = new ItemStack(item, batch.count());
        if (batch.preparation() == PreparationKind.PREPARED) {
            stack.set(BrewingComponents.PREPARED_DAY, batch.preparedGameDay());
        }
        store(stack);
    }

    @Override
    public int chestFreeSlots() {
        // 铜箱子被拆了就当没有空格，开不了工；复查会接着报结构不符。
        Optional<Container> container = chestContainer();
        if (container.isEmpty()) {
            return 0;
        }
        Container output = container.orElseThrow();
        int free = 0;
        for (int slot = 0; slot < output.getContainerSize(); slot++) {
            if (output.getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    @Override
    public void deliverPotion(BrewKind kind, BrewQuality quality, BrewForm form) {
        // 和釜里舀出来的是同一种物品和组件。
        BrewUse use = BrewUse.DRINK;
        if (form == BrewForm.SPLASH) {
            use = BrewUse.SPLASH;
        }
        deliver(BrewItemStacks.create(kind, quality, use));
    }

    @Override
    public void deliverResidue() {
        // 药渣和釜里舀出来的同一种物品。
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "brew_residue");
        deliver(new ItemStack(BuiltInRegistries.ITEM.getValue(id)));
    }

    @Override
    public Optional<TowerFuel> fuelAt(int cell) {
        // 流动熔岩、灵魂火和别的方块都不算燃料。
        BlockState state = level.getBlockState(fuelCells.get(cell));
        if (state.is(Blocks.COAL_BLOCK)) {
            return Optional.of(TowerFuel.COAL_BLOCK);
        }
        if (state.is(BrewingTags.LOGS_THAT_BURN)) {
            return Optional.of(TowerFuel.LOG);
        }
        if (state.is(Blocks.LAVA) && state.getFluidState().isSource()) {
            return Optional.of(TowerFuel.LAVA);
        }
        if (state.is(Blocks.FIRE)) {
            return Optional.of(TowerFuel.FIRE);
        }
        return Optional.empty();
    }

    @Override
    public void burnOut(int cell) {
        // 烧完的燃料方块变成空气，下次要火时从木桶补。
        level.setBlock(fuelCells.get(cell), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    @Override
    public int barrelFuel() {
        // 木桶里能放进火室的三种：煤炭块、能烧的原木和木头、熔岩桶。
        return count(BrewTowerWorld::fuelItem);
    }

    @Override
    public Optional<TowerFuel> refuel(int cell) {
        // 只往空格放；煤炭块优先，其次原木，最后熔岩桶（倒成熔岩源，空桶放回木桶）。
        BlockPos pos = fuelCells.get(cell);
        if (!emptyFuelCell(level.getBlockState(pos))) {
            return Optional.empty();
        }
        if (!removeUpTo(stack -> stack.is(Items.COAL_BLOCK), 1).isEmpty()) {
            level.setBlock(pos, Blocks.COAL_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            return Optional.of(TowerFuel.COAL_BLOCK);
        }
        List<ItemStack> log = removeUpTo(stack -> stack.is(ItemTags.LOGS_THAT_BURN), 1);
        if (!log.isEmpty()) {
            Block placed = Block.byItem(log.getFirst().getItem());
            level.setBlock(pos, placed.defaultBlockState(), Block.UPDATE_ALL);
            return Optional.of(TowerFuel.LOG);
        }
        if (!removeUpTo(stack -> stack.is(Items.LAVA_BUCKET), 1).isEmpty()) {
            level.setBlock(pos, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
            store(new ItemStack(Items.BUCKET));
            return Optional.of(TowerFuel.LAVA);
        }
        return Optional.empty();
    }

    @Override
    public Optional<BrewText> ambientProblem(double total) {
        // 量水池中心所在的区域；和熬药式同一套估算。
        return BrewQiPayment.ambientStartProblem(level, pool, total);
    }

    @Override
    public double drawAmbient(String drawKey, double amount) {
        // 取用 ID 带上塔的前缀，不同塔的同一批号不会撞。
        return BrewQiPayment.drawAmbient(drawPrefix + drawKey, level, pool, amount);
    }

    @Override
    public long gameDay() {
        // 和釜、晒药匾一样按主世界时钟算日子。
        return Math.floorDiv(level.getOverworldClockTime(), DAY_TICKS);
    }

    @Override
    public void showLamps(int mask) {
        // 只改 lit，和现状一样就不写，免得每秒都触发方块更新。
        for (int index = 0; index < lamps.size(); index++) {
            BlockPos pos = lamps.get(index);
            BlockState state = level.getBlockState(pos);
            boolean lit = (mask & (1 << index)) != 0;
            if (state.hasProperty(CopperBulbBlock.LIT) && state.getValue(CopperBulbBlock.LIT) != lit) {
                level.setBlock(pos, state.setValue(CopperBulbBlock.LIT, lit), Block.UPDATE_ALL);
            }
        }
    }

    /** 火室里有燃料的格子冒火苗，烧着几档就冒几格；阴火冒灵魂火焰。 */
    void showFlames(FireKind fire, int burning) {
        // 只是给人看的，跟哪几格真在烧不严格对应。
        SimpleParticleType flame = ParticleTypes.FLAME;
        if (fire == FireKind.SOUL_FIRE) {
            flame = ParticleTypes.SOUL_FIRE_FLAME;
        }
        int shown = 0;
        for (int cell = 0; cell < fuelCells.size() && shown < burning; cell++) {
            if (fuelAt(cell).isEmpty()) {
                continue;
            }
            BlockPos pos = fuelCells.get(cell);
            level.sendParticles(flame, pos.getX() + BLOCK_CENTRE, pos.getY() + FLAME_HEIGHT,
                    pos.getZ() + BLOCK_CENTRE, FLAMES_PER_CELL, FLAME_SPREAD, FLAME_RISE_SPREAD, FLAME_SPREAD,
                    FLAME_SPEED);
            shown++;
        }
    }

    private static boolean fuelItem(ItemStack stack) {
        // 和 refuel 认的三种一致。
        return stack.is(Items.COAL_BLOCK) || stack.is(ItemTags.LOGS_THAT_BURN) || stack.is(Items.LAVA_BUCKET);
    }

    private static boolean emptyFuelCell(BlockState state) {
        // 空气、流动熔岩、灵魂火都按空格；放燃料就把它们顶掉。
        boolean flowingLava = state.is(Blocks.LAVA) && !state.getFluidState().isSource();
        return state.isAir() || flowingLava || state.is(Blocks.SOUL_FIRE);
    }

    private static boolean matches(ItemStack stack, String itemId) {
        // 空格子不比；有东西的按注册 ID 比。
        if (stack.isEmpty()) {
            return false;
        }
        return BrewRecipes.sameItemId(itemId, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    private IngredientBatch batch(ItemStack unit) {
        // 主料带炮制日，没记的按今天；其余都是生料。
        String itemId = BuiltInRegistries.ITEM.getKey(unit.getItem()).toString();
        if (BrewRecipes.forPreparedPrimary(itemId).isEmpty()) {
            return IngredientBatch.raw(itemId);
        }
        Long recordedDay = unit.get(BrewingComponents.PREPARED_DAY);
        long preparedDay = gameDay();
        if (recordedDay != null) {
            preparedDay = recordedDay;
        }
        return IngredientBatch.prepared(itemId, preparedDay);
    }

    private void returnRemainder(ItemStack unit) {
        // 蜂蜜瓶、龙息用完剩玻璃瓶，和釜边手投时一样。
        ItemStackTemplate remainder = unit.getItem().getCraftingRemainder();
        if (remainder != null) {
            store(remainder.create());
        }
    }

    private int count(Predicate<ItemStack> wanted) {
        // 六只木桶加起来；被拆掉的那只不算。
        int total = 0;
        for (Container barrel : barrelContainers()) {
            for (int slot = 0; slot < barrel.getContainerSize(); slot++) {
                ItemStack stack = barrel.getItem(slot);
                if (wanted.test(stack)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private List<ItemStack> removeUpTo(Predicate<ItemStack> wanted, int count) {
        // 按木桶顺序、槽位从前到后取，取够或取完为止；返回一件一个的列表。
        List<ItemStack> removed = new ArrayList<>();
        for (Container barrel : barrelContainers()) {
            for (int slot = 0; slot < barrel.getContainerSize() && removed.size() < count; slot++) {
                ItemStack stack = barrel.getItem(slot);
                if (!wanted.test(stack)) {
                    continue;
                }
                int amount = Math.min(stack.getCount(), count - removed.size());
                ItemStack part = barrel.removeItem(slot, amount);
                for (int unit = 0; unit < part.getCount(); unit++) {
                    removed.add(part.copyWithCount(1));
                }
            }
        }
        return removed;
    }

    private void store(ItemStack stack) {
        // 先按取料顺序塞木桶，塞不下的掉在铜箱子前的凹槽里。
        ItemStack left = stack;
        for (Container barrel : barrelContainers()) {
            if (left.isEmpty()) {
                return;
            }
            left = HopperBlockEntity.addItem(null, barrel, left, null);
        }
        dropAtFront(left);
    }

    private void deliver(ItemStack stack) {
        // 开工前已查过空格，正常都放得下；万一放不下（中途被人塞满）就掉在箱子前。
        Optional<Container> container = chestContainer();
        if (container.isEmpty()) {
            dropAtFront(stack);
            return;
        }
        dropAtFront(HopperBlockEntity.addItem(null, container.orElseThrow(), stack, null));
    }

    private void dropAtFront(ItemStack stack) {
        // 空的不掉；掉在凹槽那格正中。
        if (stack.isEmpty()) {
            return;
        }
        Containers.dropItemStack(level, chestFront.getX() + BLOCK_CENTRE, chestFront.getY() + BLOCK_CENTRE,
                chestFront.getZ() + BLOCK_CENTRE, stack);
    }

    private List<Container> barrelContainers() {
        // 每次现取；某只木桶这一秒被拆了就跳过，下一秒复查会报结构不符。
        List<Container> containers = new ArrayList<>();
        for (BlockPos pos : barrels) {
            if (level.getBlockEntity(pos) instanceof BarrelBlockEntity barrel) {
                containers.add(barrel);
            }
        }
        return containers;
    }

    private Optional<Container> chestContainer() {
        // 两半合成一只大箱子；上面压了方块也照样能放（塔是从侧面送进去的）。
        BlockState state = level.getBlockState(chest);
        if (!(state.getBlock() instanceof ChestBlock block)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getContainer(block, state, level, chest, true));
    }
}
