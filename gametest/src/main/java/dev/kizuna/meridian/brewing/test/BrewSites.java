package dev.kizuna.meridian.brewing.test;

import dev.kizuna.meridian.brewing.fabric.BrewingFabric;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** 手炼场景只摆单口釜和正下方的火源。 */
public final class BrewSites {
    public static final String LV1_SITE = "meridian_brewing_test:brew_site_lv1";
    public static final String LV2_SITE = "meridian_brewing_test:brew_site_lv2";
    private static final BlockPos SITE_CORNER = new BlockPos(1, 0, 1);

    private BrewSites() {
    }

    public enum Shipped {
        LV1(new BlockPos(2, 2, 2), new BlockPos(2, 1, 0)),
        LV2(new BlockPos(5, 1, 5), new BlockPos(5, 1, 3));

        final BlockPos anchor;
        final BlockPos stand;

        Shipped(BlockPos anchor, BlockPos stand) {
            this.anchor = anchor;
            this.stand = stand;
        }
    }

    /** 釜在测试场地内的位置，供区块边界用例定位。 */
    public static BlockPos anchorInSite(Shipped shipped) {
        return SITE_CORNER.offset(shipped.anchor);
    }

    /** 单独放置火源和釜。 */
    public static Site place(GameTestHelper context, Shipped shipped) {
        ServerLevel level = context.getLevel();
        Site site = new Site(shipped, context.absolutePos(SITE_CORNER));
        Block pot;
        Block fire;
        if (shipped == Shipped.LV1) {
            pot = block("copper_cauldron");
            fire = Blocks.CAMPFIRE;
        } else {
            pot = block("witch_cauldron");
            fire = block("iron_stove");
        }
        level.setBlockAndUpdate(site.fire(), fire.defaultBlockState());
        level.setBlockAndUpdate(site.cauldron(), pot.defaultBlockState());
        return site;
    }

    private static Block block(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, path);
        return BuiltInRegistries.BLOCK.getValue(id);
    }

    public record Site(Shipped shipped, BlockPos origin) {
        public BlockPos world(BlockPos local) {
            return origin.offset(local);
        }

        /** 场地里的相对位置 (x, y, z) 在世界里是哪一格。 */
        public BlockPos world(int x, int y, int z) {
            return world(new BlockPos(x, y, z));
        }

        /** 锚点格：铜釜或巫釜。 */
        public BlockPos cauldron() {
            return world(shipped.anchor);
        }

        /** 釜正下方的火：营火或铁炉座。 */
        public BlockPos fire() {
            return cauldron().below();
        }

        /** 玩家站这格脚下有地、离釜不到 3 格。 */
        public BlockPos stand() {
            return world(shipped.stand);
        }
    }
}
