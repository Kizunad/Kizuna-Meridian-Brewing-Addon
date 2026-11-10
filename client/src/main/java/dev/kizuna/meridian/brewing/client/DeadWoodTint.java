package dev.kizuna.meridian.brewing.client;

import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * 枯木的方块颜色：原版原木贴图乘一档灰褐色，侧面与年轮端面各一档。
 *
 * <p>枯原木模型的侧面是 tintindex 0、端面是 1；枯木头六面都是侧面。物品定义 {@code items/dead_*.json}
 * 写同一组值，由测试核对。颜色是 ARGB。
 */
enum DeadWoodTint {
    OAK("oak", 0xFFA5BCFF, 0xFF90A0DC),
    SPRUCE("spruce", 0xFFB2F3FF, 0xFFA6C7FF),
    BIRCH("birch", 0xFFAB9C88, 0xFFA09BAC),
    JUNGLE("jungle", 0xFFC7DEFF, 0xFF89A6D5),
    ACACIA("acacia", 0xFFADA69D, 0xFF7AB4F0),
    DARK_OAK("dark_oak", 0xFFC7E5FF, 0xFFB6F0FF),
    MANGROVE("mangrove", 0xFFCCE4FF, 0xFF9FFFFF),
    CHERRY("cherry", 0xFFBAFFB0, 0xFF91A994),
    PALE_OAK("pale_oak", 0xFFCCCFB7, 0xFFA69E88);

    private final String species;
    private final int side;
    private final int end;

    DeadWoodTint(String species, int side, int end) {
        this.species = species;
        this.side = side;
        this.end = end;
    }

    static void register() {
        for (DeadWoodTint tint : values()) {
            var sideTint = BlockTintSources.constant(tint.side);
            BlockColorRegistry.register(List.of(sideTint, BlockTintSources.constant(tint.end)), block(tint.log()));
            BlockColorRegistry.register(List.of(sideTint), block(tint.wood()));
        }
    }

    String log() {
        return "dead_" + species + "_log";
    }

    String wood() {
        return "dead_" + species + "_wood";
    }

    int side() {
        return side;
    }

    int end() {
        return end;
    }

    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.getOptional(BrewingClient.id(path))
                .orElseThrow(() -> new IllegalStateException("枯木方块未注册：" + path));
    }
}
