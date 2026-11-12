package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 方块实体只在存档内容真变了时才弄脏区块。单独一个测试环境，这一批只有它，别的用例改方块不会弄脏同一个区块。
 */
public final class BrewingSaveGameTest {
    private static final String QUIET_BATCH = "meridian_brewing_test:quiet_chunk";
    private static final int TIMEOUT = 300;
    private static final long SETTLE_TICKS = 30;
    private static final long WATCH_TICKS = 60;
    private static final int BURNING_MARKS_MIN = 2;
    private static final int BURNING_MARKS_MAX = 4;
    private static final int COAL = 3;
    private static final int DRIED_MOSS = 4;
    private static final BlockPos STOVE = new BlockPos(1, 1, 1);
    private static final BlockPos WITCH_CAULDRON = new BlockPos(1, 2, 1);
    private static final BlockPos CAMPFIRE = new BlockPos(3, 1, 1);
    private static final BlockPos COPPER_CAULDRON = new BlockPos(3, 2, 1);
    private static final BlockPos EMPTY_CRATE = new BlockPos(5, 1, 1);
    private static final BlockPos DRIED_CRATE = new BlockPos(5, 1, 3);

    @GameTest(environment = QUIET_BATCH, maxTicks = TIMEOUT)
    public void idleBlocksLeaveTheChunkSavedAndBurningFireMarksOncePerSecond(GameTestHelper context) {
        // 坐在点着的营火上的铜釜（温度一直在起伏）、熄着的铁炉座和上面的巫釜、空匾、只放干药的匾：闲着三秒，
        // 区块一次都不该变脏；炉座加炭以后只有燃料秒数在走，三秒里每秒标一次，不是每 tick。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewFireBlockEntity stove = (BrewFireBlockEntity) place(context, STOVE, "iron_stove");
        place(context, WITCH_CAULDRON, "witch_cauldron");
        context.getLevel().setBlockAndUpdate(context.absolutePos(CAMPFIRE), Blocks.CAMPFIRE.defaultBlockState());
        place(context, COPPER_CAULDRON, "copper_cauldron");
        place(context, EMPTY_CRATE, "herb_crate");
        HerbCrateBlockEntity dried = (HerbCrateBlockEntity) place(context, DRIED_CRATE, "herb_crate");
        ItemStack moss = new ItemStack(BuiltInRegistries.ITEM.getValue(
                Identifier.parse("meridian_brewing:dry_moss")), DRIED_MOSS);
        dried.setItem(0, moss);
        List<LevelChunk> chunks = chunks(context, List.of(STOVE, CAMPFIRE, EMPTY_CRATE, DRIED_CRATE));
        AtomicInteger settling = new AtomicInteger();
        AtomicInteger idleDirty = new AtomicInteger();
        AtomicInteger burningDirty = new AtomicInteger();
        AtomicReference<AtomicInteger> counting = new AtomicReference<>(settling);
        AtomicReference<Double> fuelAtStart = new AtomicReference<>();
        // 每 tick 的计数要在开测时就排好：在排好的事件里再排会改正在遍历的事件表。放好头 30 tick 记进不看的计数。
        context.onEachTick(() -> countDirty(chunks, counting.get()));
        context.runAfterDelay(SETTLE_TICKS, () -> {
            chunks.forEach(LevelChunk::tryMarkSaved);
            counting.set(idleDirty);
        });
        context.runAfterDelay(SETTLE_TICKS + WATCH_TICKS, () -> {
            context.assertTrue(idleDirty.get() == 0, "闲着的釜、炉座、晒药匾弄脏了区块：" + idleDirty.get() + " 个 tick");
            for (int coal = 0; coal < COAL; coal++) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL));
                stove.use(player, InteractionHand.MAIN_HAND);
            }
            // 加炭是右键，当场标脏，不算在燃着那一段里。
            chunks.forEach(LevelChunk::tryMarkSaved);
            fuelAtStart.set(stove.fire().fuelSeconds());
            counting.set(burningDirty);
        });
        context.runAfterDelay(SETTLE_TICKS + WATCH_TICKS + WATCH_TICKS, () -> {
            context.assertTrue(stove.fire().level() == COAL, "炉座没烧到三档：" + stove.fire().snapshot());
            int marks = burningDirty.get();
            context.assertTrue(marks >= BURNING_MARKS_MIN && marks <= BURNING_MARKS_MAX,
                    "燃着的炉座三秒里该标两到四次，实际 " + marks + " 次");
            context.assertTrue(stove.fire().fuelSeconds() < fuelAtStart.get(), "炉座加炭后燃料秒数没在走");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    private static void countDirty(List<LevelChunk> chunks, AtomicInteger counter) {
        // 这一 tick 只要有一个区块脏了就记一次，然后标回已存，下一 tick 重新看。
        boolean dirty = false;
        for (LevelChunk chunk : chunks) {
            if (chunk.isUnsaved()) {
                dirty = true;
                chunk.tryMarkSaved();
            }
        }
        if (dirty) {
            counter.incrementAndGet();
        }
    }

    private static List<LevelChunk> chunks(GameTestHelper context, List<BlockPos> relatives) {
        // 摆的方块可能跨两个区块，逐个去重。
        List<LevelChunk> chunks = new ArrayList<>();
        for (BlockPos relative : relatives) {
            LevelChunk chunk = context.getLevel().getChunkAt(context.absolutePos(relative));
            if (!chunks.contains(chunk)) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    private static BlockEntity place(GameTestHelper context, BlockPos relative, String block) {
        // 只摆单个方块，不搭结构：闲着与否和结构对不对无关。
        BlockPos pos = context.absolutePos(relative);
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, block);
        context.getLevel().setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.getValue(id).defaultBlockState());
        return context.getLevel().getBlockEntity(pos);
    }
}
