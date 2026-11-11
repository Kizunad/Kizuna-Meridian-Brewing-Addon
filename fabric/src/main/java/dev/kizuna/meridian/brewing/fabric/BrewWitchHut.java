package dev.kizuna.meridian.brewing.fabric;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * 女巫小屋的药方箱。原版沼泽小屋是代码拼的结构件 {@code SwampHutPiece}，没有 NBT 模板；
 * {@code WitchHutChestMixin} 在它搭完一块区块之后补一只箱子，挂本仓的战利品表。
 *
 * <p>结构件只在区块初次生成时跑，已经生成过的区块不会再跑，所以旧世界里已有的小屋不变。
 * 小屋跨两块区块时，箱子格所在的那一块生成时才放。</p>
 */
public final class BrewWitchHut {
    /** 药方和炼药材料；概率、数量是占位，写在数据包 {@code loot_table/chests/witch_hut.json}。 */
    public static final ResourceKey<LootTable> CHEST_TABLE = ResourceKey.create(Registries.LOOT_TABLE,
            Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "chests/witch_hut"));
    /**
     * 小屋本地坐标：x 2–4、z 3–6 是屋里，y 2 是站人的一层；z 小的一头是门和门廊。
     * 后墙一排原版是工作台 (3, 2, 6)、炼药锅 (4, 2, 6)，箱子补在工作台另一边的墙角。
     */
    public static final int CHEST_X = 2;
    public static final int CHEST_Y = 2;
    public static final int CHEST_Z = 6;

    private BrewWitchHut() {
        // 只放常量和换算，不实例化。
    }

    /** 箱子正面朝门。小屋本地朝门的方向，换到世界里是结构件朝向的反方向。 */
    public static BlockState chest(Direction orientation) {
        // 显式给朝向，不让原版按四周实心方块猜：隔壁区块的墙可能还没搭，猜出来会朝墙。
        return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, orientation.getOpposite());
    }
}
