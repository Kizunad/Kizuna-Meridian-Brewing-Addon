package dev.kizuna.meridian.brewing.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kizuna.meridian.brewing.fabric.mixin.StructureTemplateAccessor;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** 图样使用净化后的原版结构；旁表只标参与比对的部位和要留出的空间。 */
final class FurnacePatterns {
    enum Role {
        BODY, RIM, FIRES, INPUTS, OUTPUTS, LIQUID, FEED, CLEAR, HEARTH
    }

    record Cell(BlockPos offset, Role role, BlockState shown) {}

    record Pattern(Identifier id, int version, int tier, List<Cell> cells, List<Cell> fires,
                   Bounds bounds, Map<Block, List<Cell>> entries) {}

    record Bounds(BlockPos min, BlockPos max) {}

    private static final String DIRECTORY = "furnace_pattern";
    private static final int ROW_LENGTH = 4;
    private static List<Pattern> cached;

    private FurnacePatterns() {}

    static void register() {
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) {
                cached = null;
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> cached = null);
    }

    static List<Pattern> all(MinecraftServer server) {
        if (cached == null) {
            List<Pattern> patterns = new ArrayList<>();
            var files = server.getResourceManager().listResources(DIRECTORY, id -> id.getPath().endsWith(".json"));
            for (var entry : files.entrySet()) {
                patterns.add(read(server, entry.getKey(), entry.getValue()));
            }
            patterns.sort(Comparator.comparing(Pattern::id));
            cached = List.copyOf(patterns);
        }
        return cached;
    }

    private static Pattern read(MinecraftServer server, Identifier file, Resource resource) {
        try (Reader reader = resource.openAsReader()) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            Identifier id = Identifier.parse(json.get("id").getAsString());
            if (!file.equals(id.withPath(DIRECTORY + "/" + id.getPath() + ".json"))) {
                throw new IllegalArgumentException("炉型标识与资源路径不符 " + id);
            }
            Map<BlockPos, BlockState> states = templateStates(server, id);
            JsonArray xyz = json.getAsJsonArray("anchor");
            BlockPos anchor = new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
            Map<BlockPos, Cell> cells = new LinkedHashMap<>();
            for (Role role : Role.values()) {
                String key = role.name().toLowerCase(Locale.ROOT);
                for (var row : json.getAsJsonArray(key)) {
                    addRow(cells, states, anchor, role, row.getAsJsonArray());
                }
            }
            var fires = cells.values().stream().filter(cell -> cell.role() == Role.FIRES).toList();
            int tier = json.get("tier").getAsInt();
            int version = json.get("version").getAsInt();
            if (version < 1 || fires.isEmpty() || !cells.containsKey(BlockPos.ZERO)) {
                throw new IllegalArgumentException("炉型缺少版本、锚点或火位 " + id);
            }
            BlockPos min = cells.keySet().stream().reduce(BlockPos.ZERO, BlockPos::min);
            BlockPos max = cells.keySet().stream().reduce(BlockPos.ZERO, BlockPos::max);
            var bounds = new Bounds(min, max);
            return new Pattern(id, version, tier, List.copyOf(cells.values()), fires, bounds, entries(cells));
        } catch (IOException failure) {
            throw new IllegalStateException("读炉型失败 " + file, failure);
        }
    }

    private static Map<Block, List<Cell>> entries(Map<BlockPos, Cell> cells) {
        Map<Block, List<Cell>> byMaterial = new HashMap<>();
        for (var cell : cells.values()) {
            if (cell.role() == Role.BODY || cell.role() == Role.RIM || cell.role() == Role.FIRES) {
                Block base = FurnaceLayout.base(cell.shown().getBlock());
                byMaterial.computeIfAbsent(base, ignored -> new ArrayList<>()).add(cell);
            }
        }
        return Map.copyOf(byMaterial);
    }

    private static Map<BlockPos, BlockState> templateStates(MinecraftServer server, Identifier id) {
        StructureTemplate template = server.getStructureManager().get(id).orElseThrow();
        var palette = ((StructureTemplateAccessor) template).meridianBrewing$palettes().getFirst();
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (var block : palette.blocks()) {
            result.put(block.pos(), block.state());
        }
        return result;
    }

    private static void addRow(Map<BlockPos, Cell> cells, Map<BlockPos, BlockState> states, BlockPos anchor,
                               Role role, JsonArray row) {
        // 每行依次是高度、南北行、起始和终止列（含两端）。液面、投料区和气泡净空可重合。
        if (row.size() != ROW_LENGTH) {
            throw new IllegalArgumentException("炉型行宽错误 " + row);
        }
        int y = row.get(0).getAsInt();
        int z = row.get(1).getAsInt();
        int end = row.get(3).getAsInt();
        for (int x = row.get(2).getAsInt(); x <= end; x++) {
            BlockPos local = new BlockPos(x, y, z);
            BlockPos offset = local.subtract(anchor);
            BlockState state = states.getOrDefault(local, Blocks.AIR.defaultBlockState());
            boolean solid = switch (role) {
                case BODY, RIM, FIRES, INPUTS, OUTPUTS -> true;
                default -> false;
            };
            if (solid == state.isAir()) {
                throw new IllegalArgumentException("炉型部位和模板不符 " + role + " " + local);
            }
            cells.putIfAbsent(offset, new Cell(offset, role, state));
        }
    }
}
