package dev.kizuna.meridian.brewing.test;

import dev.kizuna.meridian.brewing.fabric.BrewingFabric;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * 在 GameTest 场地里摆随 JAR 发的熬药结构，摆法和玩家用 {@code /place template}、结构放置器一样：
 * 绕 (sx/2, 0, sz/2) 转，不镜像。转完的包围盒最小角落在场地 (1, 0, 1)，四周留一格。
 */
public final class BrewSites {
    public static final String LV1_SITE = "meridian_brewing_test:brew_site_lv1";
    public static final String LV2_SITE = "meridian_brewing_test:brew_site_lv2";
    public static final String LV3_SITE = "meridian_brewing_test:brew_site_lv3";
    private static final BlockPos SITE_CORNER = new BlockPos(1, 0, 1);

    private BrewSites() {
    }

    /** 三份随包结构：模板 ID、NBT 里的锚点格（釜或药池）、玩家站着够得着釜的一格。 */
    public enum Shipped {
        LV1("potion-lv1", new BlockPos(2, 2, 2), new BlockPos(2, 1, 0)),
        LV2("potion-lv2", new BlockPos(5, 1, 5), new BlockPos(5, 1, 3)),
        LV3("potion-lv3-auto", new BlockPos(6, 5, 6), new BlockPos(6, 1, 0));

        private final String template;
        private final BlockPos anchor;
        private final BlockPos stand;

        Shipped(String template, BlockPos anchor, BlockPos stand) {
            this.template = template;
            this.anchor = anchor;
            this.stand = stand;
        }

        /** 模板的资源 ID，例如 {@code meridian_brewing:potion-lv2}。 */
        public Identifier id() {
            return Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, template);
        }
    }

    /** 不转摆时锚点格在场地里的相对位置；要在摆之前知道釜落在哪个区块时用。 */
    public static BlockPos anchorInSite(Shipped shipped) {
        return SITE_CORNER.offset(shipped.anchor);
    }

    /** 不转，直接摆。 */
    public static Site place(GameTestHelper context, Shipped shipped) {
        return place(context, shipped, Rotation.NONE);
    }

    /** 按 {@code rotation} 摆一份随包结构；NBT 里营火本来就点着，炉座要自己加炭。 */
    public static Site place(GameTestHelper context, Shipped shipped, Rotation rotation) {
        // 模板走服务器的结构管理器，和数据包里的定义读的是同一份 NBT。
        ServerLevel level = context.getLevel();
        StructureTemplate template = level.getStructureManager().get(shipped.id())
                .orElseThrow(() -> new IllegalStateException("随包结构没加载：" + shipped.id()));
        Vec3i size = template.getSize();
        BlockPos pivot = new BlockPos(size.getX() / 2, 0, size.getZ() / 2);
        BoundingBox turned = template.getBoundingBox(BlockPos.ZERO, rotation, pivot, Mirror.NONE);
        BlockPos origin = context.absolutePos(SITE_CORNER)
                .subtract(new BlockPos(turned.minX(), turned.minY(), turned.minZ()));
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setRotationPivot(pivot)
                .setMirror(Mirror.NONE);
        if (!template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_ALL)) {
            throw new IllegalStateException("随包结构没摆上：" + shipped.id());
        }
        return new Site(shipped, origin, rotation, pivot, size);
    }

    /** 摆好的一份结构：NBT 原点落在世界哪、转了多少、NBT 多大，用来把 NBT 相对坐标换成世界坐标。 */
    public record Site(Shipped shipped, BlockPos origin, Rotation rotation, BlockPos pivot, Vec3i size) {
        /** NBT 里的相对位置在世界里是哪一格。 */
        public BlockPos world(BlockPos local) {
            return origin.offset(StructureTemplate.transform(local, Mirror.NONE, rotation, pivot));
        }

        /** NBT 里的相对位置 (x, y, z) 在世界里是哪一格。 */
        public BlockPos world(int x, int y, int z) {
            return world(new BlockPos(x, y, z));
        }

        /** 锚点格：铜釜、巫釜或药池。 */
        public BlockPos cauldron() {
            return world(shipped.anchor);
        }

        /** 釜正下方的火：营火或铁炉座。 */
        public BlockPos fire() {
            return cauldron().below();
        }

        /** NBT 里每一格换到世界里的位置，按 NBT 的 x、y、z 顺序。 */
        public List<BlockPos> cells() {
            // 换外框材料的测试要逐格看，转过的结构也照 NBT 的格子走，不会摸到结构外面。
            List<BlockPos> cells = new ArrayList<>();
            for (int x = 0; x < size.getX(); x++) {
                for (int y = 0; y < size.getY(); y++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        cells.add(world(x, y, z));
                    }
                }
            }
            return cells;
        }

        /** 玩家站这格脚下有地、离釜不到 3 格。 */
        public BlockPos stand() {
            return world(shipped.stand);
        }
    }
}
