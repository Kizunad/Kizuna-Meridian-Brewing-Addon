package dev.kizuna.meridian.brewing.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kizuna.meridian.brewing.fabric.mixin.StructureTemplateAccessor;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.BrewTowerController;
import dev.kizuna.meridian.brewing.server.FrameClass;
import java.io.IOException;
import java.io.Reader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * 三份熬药结构的定义：{@code data/meridian_brewing/brew_structure/*.json} 写 NBT 本身看不出的格子
 * （必须空、燃料位、可换成漏斗等自动化方块的外框格），NBT 里其余的方块按种类分成核心格和外框格，空气不管。
 *
 * <p>按服务器缓存：第一次用时读，数据包重载成功后作废，下次用时重读；数据包放同名文件就能覆盖。</p>
 */
final class BrewStructureDefinitions {
    /** 三阶是自动药塔，开工要的部件在加载时就查。 */
    static final int TOWER_TIER = 3;
    private static final String DIRECTORY = "brew_structure";
    private static final String JSON_SUFFIX = ".json";
    private static final Logger LOGGER = System.getLogger("meridian_brewing_structure");
    private static final Comparator<BlockPos> BOTTOM_UP = Comparator.<BlockPos>comparingInt(Vec3i::getY)
            .thenComparingInt(Vec3i::getZ).thenComparingInt(Vec3i::getX);
    private static final List<CoreKind> CORE_KINDS = List.of(
            new CoreKind("campfire", state -> state.is(BlockTags.CAMPFIRES)),
            new CoreKind("barrel", state -> state.is(Blocks.BARREL)),
            new CoreKind("copper_chest", state -> state.is(BlockTags.COPPER_CHESTS)),
            new CoreKind("copper_bulb", state -> state.is(BrewingTags.COPPER_BULBS)),
            new CoreKind("water", state -> state.is(Blocks.WATER) && state.getFluidState().isSource()));
    private static List<Definition> cached;

    private BrewStructureDefinitions() {
    }

    static void register() {
        // 重载成功后才作废：失败的重载原版会回滚到旧数据，旧缓存仍然对。
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) {
                invalidate();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> invalidate());
    }

    /** 这台服务器上读到的全部定义；第一次调用时读。 */
    static synchronized List<Definition> all(MinecraftServer server) {
        // 结构管理器挂在服务器上，所以不在资源重载监听里读，第一次用到时才读。
        if (cached == null) {
            cached = load(server);
        }
        return cached;
    }

    /** 锚点格是这种方块的定义，例如铜釜只配一阶。 */
    static List<Definition> anchoredOn(MinecraftServer server, Block anchor) {
        // 釜只拿和自己同种的定义去比，不会把铜釜认成二阶。
        return all(server).stream().filter(definition -> definition.anchorBlock() == anchor).toList();
    }

    private static synchronized void invalidate() {
        // 下次用到时重读，数据包覆盖的 NBT 在 /reload 后生效。
        cached = null;
    }

    private static List<Definition> load(MinecraftServer server) {
        // 一份坏了只跳过那一份并打错误日志，别的结构照常能用。
        Map<Identifier, Resource> files = server.getResourceManager().listResources(DIRECTORY,
                id -> id.getPath().endsWith(JSON_SUFFIX));
        List<Definition> definitions = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> file : files.entrySet()) {
            try {
                definitions.add(read(server, file.getKey(), file.getValue()));
            } catch (IOException | RuntimeException invalid) {
                LOGGER.log(Level.ERROR, "熬药结构定义 " + file.getKey() + " 读不了，这份结构不会被认", invalid);
            }
        }
        definitions.sort(Comparator.comparingInt(Definition::tier));
        return List.copyOf(definitions);
    }

    private static Definition read(MinecraftServer server, Identifier file, Resource resource) throws IOException {
        // 先读 JSON，再按里面的 template 取 NBT；NBT 走原版结构管理器，旧版本文件由原版升级。
        JsonObject json;
        try (Reader reader = resource.openAsReader()) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }
        Identifier templateId = Identifier.parse(json.get("template").getAsString());
        StructureTemplate template = server.getStructureManager().get(templateId)
                .orElseThrow(() -> new IllegalArgumentException("找不到结构 NBT：" + templateId));
        int tier = json.get("tier").getAsInt();
        BlockPos anchor = position(json.getAsJsonArray("anchor"));
        Set<BlockPos> requiredAir = positions(json.getAsJsonArray("required_air"));
        Set<BlockPos> fuel = positions(json.getAsJsonArray("fuel"));
        Set<BlockPos> automation = positions(json.getAsJsonArray("automation"));
        return classify(new Source(file, templateId, tier, template, anchor, requiredAir, fuel, automation));
    }

    private record Source(Identifier file, Identifier templateId, int tier, StructureTemplate template,
                          BlockPos anchor, Set<BlockPos> requiredAir, Set<BlockPos> fuel,
                          Set<BlockPos> automation) {
    }

    private static Definition classify(Source source) {
        // JSON 点名的格子以 JSON 为准；其余非空气方块先看是不是核心，再看属于哪个外框类，
        // 都不是就按核心严格比这一种方块，并打警告，免得换了冷门方块却悄悄放宽。
        List<StructureTemplate.Palette> palettes =
                ((StructureTemplateAccessor) source.template()).meridianBrewing$palettes();
        if (palettes.size() != 1) {
            throw new IllegalArgumentException("熬药结构只认单个调色板：" + source.templateId());
        }
        requireListedCellsInTemplate(source, palettes.getFirst());
        List<Cell> cores = new ArrayList<>();
        List<Cell> air = new ArrayList<>();
        List<Cell> fuel = new ArrayList<>();
        List<Cell> frames = new ArrayList<>();
        Set<BlockPos> automationSeen = new HashSet<>();
        Block anchorBlock = null;
        for (StructureTemplate.StructureBlockInfo info : palettes.getFirst().blocks()) {
            BlockPos local = info.pos().immutable();
            BlockState state = info.state();
            if (local.equals(source.anchor())) {
                anchorBlock = state.getBlock();
            }
            if (source.requiredAir().contains(local)) {
                requireAirInTemplate(local, state);
                air.add(new Cell(local, Rule.air()));
            } else if (source.fuel().contains(local)) {
                fuel.add(new Cell(local, Rule.fuel()));
            } else if (!state.isAir()) {
                addSolid(solidCell(source, local, state), cores, frames, automationSeen);
            }
        }
        if (anchorBlock == null || anchorBlock == Blocks.AIR) {
            throw new IllegalArgumentException("锚点格不在结构里或是空气：" + source.anchor());
        }
        Set<BlockPos> automationMissing = new HashSet<>(source.automation());
        automationMissing.removeAll(automationSeen);
        if (!automationMissing.isEmpty()) {
            throw new IllegalArgumentException("automation 里有格子不是外框：" + automationMissing);
        }
        requireTowerParts(source, cores, fuel);
        return new Definition(source.templateId(), source.tier(), source.template().getSize(), source.anchor(),
                anchorBlock, sorted(cores), sorted(air), sorted(fuel), sorted(frames));
    }

    private static void addSolid(Cell cell, List<Cell> cores, List<Cell> frames, Set<BlockPos> automationSeen) {
        // 外框格只进外框（复审第 2 条：再算进核心，外框容差就形同虚设）；能换漏斗的外框格另记下，最后核对 JSON。
        if (cell.rule().kind() == RuleKind.AUTOMATION_FRAME) {
            automationSeen.add(cell.local());
        }
        if (cell.rule().isFrame()) {
            frames.add(cell);
        } else {
            cores.add(cell);
        }
    }

    private static void requireListedCellsInTemplate(Source source, StructureTemplate.Palette palette) {
        // JSON 点名的必须空、燃料格都要在 NBT 里有一格；带结构空位的模板会把它们漏掉，比对时就少比几格。
        Set<BlockPos> missing = new HashSet<>(source.requiredAir());
        missing.addAll(source.fuel());
        for (StructureTemplate.StructureBlockInfo info : palette.blocks()) {
            missing.remove(info.pos());
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("required_air、fuel 里有格子 NBT 里没有：" + missing);
        }
    }

    private static void requireTowerParts(Source source, List<Cell> cores, List<Cell> fuel) {
        // 药塔从木桶取料、往铜箱子出药、烧四格火室；缺一样就拒掉这份定义，别等每秒推进时才抛异常（复审第 6 条）。
        if (source.tier() != TOWER_TIER) {
            return;
        }
        if (cores.stream().noneMatch(cell -> cell.rule().shown().is(BlockTags.COPPER_CHESTS))) {
            throw new IllegalArgumentException("三阶结构没有铜箱子：" + source.templateId());
        }
        if (cores.stream().noneMatch(cell -> cell.rule().shown().is(Blocks.BARREL))) {
            throw new IllegalArgumentException("三阶结构没有木桶：" + source.templateId());
        }
        if (fuel.size() != BrewTowerController.FUEL_CELLS) {
            throw new IllegalArgumentException("三阶结构的燃料格要 " + BrewTowerController.FUEL_CELLS + " 格，"
                    + source.templateId() + " 只有 " + fuel.size() + " 格");
        }
    }

    private static void requireAirInTemplate(BlockPos local, BlockState state) {
        // 必须空的格在 NBT 里也得是空气，不然 JSON 和 NBT 说的不是同一座结构。
        if (!state.isAir()) {
            throw new IllegalArgumentException("required_air 的格子在 NBT 里不是空气：" + local);
        }
    }

    private static Cell solidCell(Source source, BlockPos local, BlockState state) {
        // 本仓的釜和火源只认同一种方块；原版的几种核心按表；其余按外框类。
        Block block = state.getBlock();
        if (block == BrewingBlocks.COPPER_CAULDRON || block == BrewingBlocks.WITCH_CAULDRON
                || block == BrewingBlocks.IRON_STOVE || block == BrewingBlocks.IRON_BRAZIER) {
            return new Cell(local, Rule.core(block, candidate -> candidate.is(block)));
        }
        for (CoreKind core : CORE_KINDS) {
            if (core.accepts().test(state)) {
                Block shown = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(core.shownPath()));
                return new Cell(local, Rule.core(shown, core.accepts()));
            }
        }
        for (FrameClass frame : FrameClass.values()) {
            TagKey<Block> tag = BrewingTags.FRAMES.get(frame);
            if (state.is(tag) && source.automation().contains(local)) {
                return new Cell(local, Rule.automationFrame(frame, tag, state));
            }
            if (state.is(tag)) {
                return new Cell(local, Rule.frame(frame, tag, state));
            }
        }
        LOGGER.log(Level.WARNING, "熬药结构 " + source.templateId() + " 的 " + local + " 是 "
                + BuiltInRegistries.BLOCK.getKey(block) + "，不在任何外框材料类里，按核心严格比对");
        return new Cell(local, Rule.core(block, candidate -> candidate.is(block)));
    }

    private static List<Cell> sorted(List<Cell> cells) {
        // 同一类里从下往上比，报的第一处不符总是最低的那格。
        cells.sort(Comparator.comparing(Cell::local, BOTTOM_UP));
        return List.copyOf(cells);
    }

    private static BlockPos position(JsonArray array) {
        // 坐标相对 NBT 原点，和结构方块里看到的一样。
        if (array.size() != 3) {
            throw new IllegalArgumentException("格子坐标要写成 [x, y, z]：" + array);
        }
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    private static Set<BlockPos> positions(JsonArray array) {
        // 写重了的格子只算一次。
        Set<BlockPos> positions = new HashSet<>();
        for (JsonElement element : array) {
            positions.add(position(element.getAsJsonArray()));
        }
        return Set.copyOf(positions);
    }

    /** 原版方块里当核心的几种：显示名用哪一种原版方块（铜箱子不论氧化、涂蜡都叫「铜箱子」），世界里哪些状态算对。 */
    private record CoreKind(String shownPath, Predicate<BlockState> accepts) {
    }

    /**
     * 一份结构：NBT 的 id、等级、尺寸、锚点（相对 NBT 原点）和锚点那格的方块，以及按类别分好、
     * 各自从下往上排好的格子。
     */
    record Definition(Identifier id, int tier, Vec3i size, BlockPos anchor, Block anchorBlock,
                      List<Cell> cores, List<Cell> requiredAir, List<Cell> fuel, List<Cell> frames) {
        /** 锚点名，例如「铜釜」「药池」；不符时“铜釜北 1”说的就是它。 */
        BrewText anchorName() {
            // 一阶、二阶说釜，三阶说药池。
            return BrewText.message("structure.anchor.tier" + tier);
        }

    }

    /** 结构里要查的一格：相对 NBT 原点的位置和这一格的规则。 */
    record Cell(BlockPos local, Rule rule) {
    }

    /** {@code AUTOMATION_FRAME}：结构 JSON 的 {@code automation} 里那几格外框，也收漏斗、投掷器等。 */
    enum RuleKind { CORE, AIR, FUEL, FRAME, AUTOMATION_FRAME }

    /**
     * 一格的规则：世界里什么方块算对，不对时“应为”怎么说，标记粒子显示哪个方块。
     * 外框格可以带“允许换成漏斗、投掷器等自动化方块”。
     */
    record Rule(RuleKind kind, Predicate<BlockState> accepts, BrewText expected, BlockState shown) {
        static Rule core(Block shown, Predicate<BlockState> accepts) {
            // 核心格“应为”写方块名，标记粒子也显示这种方块。
            String id = BuiltInRegistries.BLOCK.getKey(shown).toString();
            return new Rule(RuleKind.CORE, accepts,
                    BrewText.message("structure.expect.block", new BrewText.BlockName(id)),
                    shown.defaultBlockState());
        }

        static Rule air() {
            // 空气、洞穴空气都算空；标记显示屏障。
            return new Rule(RuleKind.AIR, BlockState::isAir, BrewText.message("structure.expect.air"),
                    Blocks.BARRIER.defaultBlockState());
        }

        static Rule fuel() {
            // 燃料位：空着，或塔认的燃料；流动熔岩、灵魂火按空格处理，也算对。
            return new Rule(RuleKind.FUEL, state -> state.isAir() || state.is(BrewingTags.TOWER_FUEL)
                    || state.is(Blocks.LAVA) || state.is(Blocks.SOUL_FIRE),
                    BrewText.message("structure.expect.fuel"), Blocks.COAL_BLOCK.defaultBlockState());
        }

        static Rule frame(FrameClass frame, TagKey<Block> tag, BlockState original) {
            // 同一材料类的任一方块都算对，形状朝向不比；标记显示 NBT 里原来那块。
            return new Rule(RuleKind.FRAME, state -> state.is(tag), frame.expected(), original);
        }

        static Rule automationFrame(FrameClass frame, TagKey<Block> tag, BlockState original) {
            // 同上，另外漏斗、投掷器等自动化方块也算对（D5）；“应为”仍写材料类。
            return new Rule(RuleKind.AUTOMATION_FRAME, state -> frameOrAutomation(state, tag), frame.expected(),
                    original);
        }

        boolean isFrame() {
            // 两种外框格都按材料类比，校验和标记一样处理。
            return kind == RuleKind.FRAME || kind == RuleKind.AUTOMATION_FRAME;
        }

        private static boolean frameOrAutomation(BlockState state, TagKey<Block> tag) {
            // 本格材料类，或自动化方块标签里的任一方块。
            return state.is(tag) || state.is(BrewingTags.STRUCTURE_AUTOMATION);
        }
    }
}
