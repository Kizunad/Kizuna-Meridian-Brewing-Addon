package dev.kizuna.meridian.brewing.client;

import dev.kizuna.backpack3d.client.PlacedModels;
import dev.kizuna.meridian.brewing.fabric.BrewTowerMenu;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 炼药客户端入口：铁火盆、铁炉座和药刀用 Bong 的 .bbmodel 经 BS3D 放置模型绘制，枯木按方块颜色压暗，
 * 本机换成药刀时当场播甩腕开刃，药方讲解和《熬药总纲》用原版书本界面翻开，三阶药塔的窗口在这里登记。
 *
 * <p>放置模型与方块、物品同 ID；药刀开合由模型里的 open 剪辑按进度画。方块和方块实体类型都在 fabric 模块注册，
 * 这里只按注册 ID 查找。
 */
public final class BrewingClient implements ClientModInitializer {
    static final String MOD_ID = "meridian_brewing";
    private static final String CLIENT_MOD_ID = "meridian_brewing_client";
    private static final String MODEL_DIRECTORY = "bs3d/meridian_brewing/";
    private static final String MODEL_SUFFIX = ".bbmodel";
    private static final float BLOCK_CENTER_PIXELS = 8;
    // 器具模型以底面中心为原点画；药刀按物品方块格画，原点取格心底面，开合由模型里的 open 剪辑按进度画。
    private static final Vector3fc CENTERED = new Vector3f();
    private static final Vector3fc BLOCK_FRAME = new Vector3f(BLOCK_CENTER_PIXELS, 0, BLOCK_CENTER_PIXELS);
    private static final Map<String, Vector3fc> MODELS = Map.of(
            "iron_brazier", CENTERED,
            "iron_stove", CENTERED,
            "herb_knife", BLOCK_FRAME);

    @Override
    public void onInitializeClient() {
        ModContainer mod = FabricLoader.getInstance().getModContainer(CLIENT_MOD_ID).orElseThrow();
        MODELS.forEach((path, origin) -> PlacedModels.register(mod, id(path), MODEL_DIRECTORY + path + MODEL_SUFFIX,
                origin));
        // 火盆和炉座共用一种方块实体，按方块 ID 选模型。
        PlacedModels.renderBlockEntity(blockEntityType("brew_fire"), BrewingClient::modelOf);
        DeadWoodTint.register();
        HerbKnifeDrawClient.register();
        BrewTooltips.register();
        BrewLoreBook.register();
        MenuScreens.register(BrewTowerMenu.type(), BrewTowerScreen::new);
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static Identifier modelOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private static BlockEntityType<?> blockEntityType(String path) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id(path))
                .orElseThrow(() -> new IllegalStateException("炼药方块实体未注册：" + path));
    }
}
