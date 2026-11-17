package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewStructureDefinitions.Cell;
import dev.kizuna.meridian.brewing.fabric.BrewStructureDefinitions.Definition;
import dev.kizuna.meridian.brewing.fabric.BrewStructureDefinitions.RuleKind;
import dev.kizuna.meridian.brewing.fabric.BrewStructureMatcher.Placement;
import dev.kizuna.meridian.brewing.fabric.BrewStructureMatcher.Result;
import dev.kizuna.meridian.brewing.fabric.BrewTowerSavedData.Site;
import dev.kizuna.meridian.brewing.fabric.BrewTowerSavedData.Tower;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.BrewTowerController;
import dev.kizuna.meridian.brewing.server.BrewTowerSettings;
import dev.kizuna.meridian.brewing.server.FrameClass;
import dev.kizuna.meridian.brewing.server.HeatBand;
import dev.kizuna.meridian.brewing.server.StructureMatch;
import dev.kizuna.meridian.brewing.server.TowerNeed;
import java.lang.System.Logger;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 三阶药塔的宿主：读药塔配置；每 20 tick 推进每座塔一秒（区块没全加载的整座暂停）；玩家空手右键塔上的玻璃或铜灯时
 * 认塔、开窗口；窗口的按钮和整数表也从这里读写塔。
 *
 * <p>只有点的那格属于一座有效的塔才拦截右键，否则交给原版。认出来的塔记进维度存档，以后只复查记下的那个摆法；
 * 结构不对、锅里也空了、火室里没有点着的燃料才删记录，下次右键再认。数据包重载后三阶定义没了的塔暂停，
 * 定义回来接着走。点着的燃料拆下来、舀起来都拿不回东西。</p>
 */
public final class BrewTowers {
    private static final Logger LOGGER = System.getLogger("meridian_brewing_tower");
    private static final int TICKS_PER_SECOND = 20;
    private static final double VIEW_DISTANCE_SQR = 8.0 * 8.0;
    private static final int TENTHS = 10;
    private static final int PERCENT = 100;
    private static final int NONE = -1;
    private static final BrewKind[] KINDS = BrewKind.towerKinds();
    /** 每座塔上一次告诉选药方的人的不符原因：同一个原因只说一次。只在内存里，重启后再说一次无妨。 */
    private static final Map<UUID, BrewText> REPORTED = new HashMap<>();
    /** 定义暂时找不到、正停着的塔：只在停下那一刻记一次日志。只在内存里。 */
    private static final Set<UUID> PAUSED = new HashSet<>();
    private static BrewTowerSettings settings;

    private BrewTowers() {
    }

    static void initialize(Path configPath) {
        // 配置文件由档位配置装好；旧导出的文件缺药塔的键时用默认值并提示。
        settings = BrewTowerSettings.load(configPath);
    }

    static BrewTowerSettings settings() {
        // 读档时恢复控制器要用。
        return settings;
    }

    static void register() {
        // 服务端每个维度各推进自己的塔；右键在两端都会触发，只在服务端处理。
        ServerTickEvents.END_LEVEL_TICK.register(BrewTowers::tick);
        UseBlockCallback.EVENT.register(BrewTowers::use);
        PlayerBlockBreakEvents.BEFORE.register(BrewTowers::breakFuel);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            REPORTED.clear();
            PAUSED.clear();
        });
    }

    private static void tick(ServerLevel level) {
        // 一秒一步；塔表是这个维度的，第一次用到时读盘。
        if (level.getGameTime() % TICKS_PER_SECOND != 0) {
            return;
        }
        BrewTowerSavedData data = BrewTowerSavedData.of(level);
        for (Tower tower : data.towers()) {
            step(level, data, tower);
        }
    }

    private static void step(ServerLevel level, BrewTowerSavedData data, Tower tower) {
        // 复查有效就推进一秒并冒火苗；不符按失效处理，告诉选药方的人第一处不符；失效且锅空、灯闪完就删记录。
        // 定义没了（数据包重载删了、坏了）时整座停着，锅里的、燃料都不动，等定义回来。
        Optional<Placement> placement = placement(level, tower.site());
        if (placement.isEmpty()) {
            if (PAUSED.add(tower.id())) {
                LOGGER.log(System.Logger.Level.WARNING, "药塔 " + tower.id() + " 的结构定义 "
                        + tower.site().definitionId() + " 找不到，先停着，定义回来再接着走");
            }
            return;
        }
        PAUSED.remove(tower.id());
        Result result = BrewStructureMatcher.matchPlacement(level, placement.orElseThrow());
        StructureMatch match = result.match();
        if (match.status() == StructureMatch.Status.UNLOADED) {
            return;
        }
        BrewTowerWorld world = world(level, placement.orElseThrow(), tower.id());
        BrewTowerController controller = tower.controller();
        if (match.isValid()) {
            controller.second(world);
            REPORTED.remove(tower.id());
            world.showFlames(controller.fire(), controller.level());
        } else {
            BrewText reason = match.problem().orElseThrow();
            controller.broken(world, reason);
            tellChooser(level, tower, reason, result);
        }
        data.changed();
        if (!match.isValid() && controller.disposable()) {
            forget(data, tower);
        }
    }

    private static void forget(BrewTowerSavedData data, Tower tower) {
        // 存档和“说过的原因”一起删。
        data.remove(tower.id());
        REPORTED.remove(tower.id());
        PAUSED.remove(tower.id());
    }

    private static void tellChooser(ServerLevel level, Tower tower, BrewText reason, Result result) {
        // 选药方的人在线才说；同一个原因只说一次。标记粒子画在他所在的维度，所以人在塔这个维度才给。
        Optional<UUID> chooser = tower.controller().chooser();
        if (chooser.isEmpty() || reason.equals(REPORTED.get(tower.id()))) {
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(chooser.orElseThrow());
        if (player == null) {
            return;
        }
        REPORTED.put(tower.id(), reason);
        BrewTexts.sendOverlay(player, reason);
        if (player.level() == level) {
            BrewStructureMarkers.show(player, result);
        }
    }

    private static InteractionResult use(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        // 只管服务端、主手空着、点的是玻璃或铜灯；先找记下的塔，再试着认新塔，都不是就交给原版。
        if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer viewer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        BlockPos clicked = hit.getBlockPos();
        if (!entryBlock(serverLevel.getBlockState(clicked)) || !serverLevel.mayInteract(viewer, clicked)) {
            return InteractionResult.PASS;
        }
        BrewTowerSavedData data = BrewTowerSavedData.of(serverLevel);
        Optional<Tower> known = towerAt(serverLevel, data, clicked);
        if (known.isPresent()) {
            return openKnown(viewer, serverLevel, known.orElseThrow());
        }
        return recognize(viewer, serverLevel, data, clicked);
    }

    private static InteractionResult openKnown(ServerPlayer viewer, ServerLevel level, Tower tower) {
        // 记下的塔：复查有效就开窗；不符就说第一处不符并标出来，不开窗。
        Placement placement = placement(level, tower.site()).orElseThrow();
        Result result = BrewStructureMatcher.matchPlacement(level, placement);
        if (!result.match().isValid()) {
            BrewTexts.sendOverlay(viewer, result.match().problem().orElseThrow());
            BrewStructureMarkers.show(viewer, result);
            return InteractionResult.SUCCESS;
        }
        open(viewer, level, tower);
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult recognize(ServerPlayer viewer, ServerLevel level, BrewTowerSavedData data,
                                               BlockPos clicked) {
        // 点的这格让每份三阶定义的每个入口格各落一次、每个朝向各比一次；认出来才建记录、开窗。
        List<Definition> towers = BrewStructureDefinitions.all(level.getServer()).stream()
                .filter(definition -> definition.tier() == BrewStructureDefinitions.TOWER_TIER)
                .toList();
        Result result = BrewStructureMatcher.matchAround(level, clicked, towers, BrewTowers::entryCell);
        if (!result.match().isValid()) {
            return InteractionResult.PASS;
        }
        Placement placement = result.placement().orElseThrow();
        Site site = new Site(placement.definition().id(), placement.origin(), placement.rotation());
        Tower tower = data.add(site, new BrewTowerController(settings, BrewQiPayment.settings()));
        open(viewer, level, tower);
        return InteractionResult.SUCCESS;
    }

    private static void open(ServerPlayer viewer, ServerLevel level, Tower tower) {
        // 学会的药方在开窗时查一次；窗口开着期间新学的，关了重开才显示。
        FurnaceBuiltTrigger.TRIGGER.trigger(viewer, BrewStructureDefinitions.TOWER_TIER);
        Session session = new Session(level, tower.id(), knownMask(viewer));
        Component title = Component.translatable("container." + BrewingFabric.MOD_ID + ".brew_tower");
        viewer.openMenu(new SimpleMenuProvider((containerId, inventory, player) ->
                new BrewTowerMenu(containerId, session), title));
    }

    private static int knownMask(ServerPlayer viewer) {
        // 第 i 位是 KINDS[i]；没有身体时什么都没学会。
        BrewRecipeBook.Lookup book = BrewRecipeBook.lookup(viewer);
        int mask = 0;
        for (int index = 0; index < KINDS.length; index++) {
            if (book.knows(KINDS[index])) {
                mask |= 1 << index;
            }
        }
        return mask;
    }

    /** 窗口按钮：选药方（只能选自己学会的）、喝或泼、单批或连续、停。做不了返回 false。 */
    static boolean click(Session session, ServerPlayer player, int button) {
        // 塔已经被删、或这人现在不许动塔所在的地方（出生点保护、冒险模式等，开窗以后才变的）就什么都不做；每次改完标脏。
        Optional<Tower> found = BrewTowerSavedData.of(session.level()).find(session.towerId());
        if (found.isEmpty() || !mayUse(session, found.orElseThrow(), player)) {
            return false;
        }
        boolean accepted = apply(session, found.orElseThrow(), player, button);
        if (accepted) {
            BrewTowerSavedData.of(session.level()).changed();
        }
        return accepted;
    }

    private static boolean mayUse(Session session, Tower tower, ServerPlayer player) {
        // 和开窗时同一条规则，按水池那格查；定义暂时没了的塔按不动。
        Optional<Placement> placement = placement(session.level(), tower.site());
        return placement.isPresent() && session.level().mayInteract(player, placement.orElseThrow().anchorWorld());
    }

    private static boolean apply(Session session, Tower tower, ServerPlayer player, int button) {
        // 0–5 是药方，其余按钮各管一件事。
        BrewTowerController controller = tower.controller();
        int recipe = button - BrewTowerMenu.BUTTON_FIRST_RECIPE;
        if (recipe >= 0 && recipe < KINDS.length) {
            return select(controller, player, KINDS[recipe]);
        }
        switch (button) {
            case BrewTowerMenu.BUTTON_DRINK -> controller.chooseForm(BrewForm.DRINK);
            case BrewTowerMenu.BUTTON_SPLASH -> controller.chooseForm(BrewForm.SPLASH);
            case BrewTowerMenu.BUTTON_ONCE -> controller.runOnce();
            case BrewTowerMenu.BUTTON_CONTINUOUS -> controller.runContinuously();
            case BrewTowerMenu.BUTTON_STOP -> stop(session.level(), tower);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean select(BrewTowerController controller, ServerPlayer player, BrewKind kind) {
        // 和熬药式同一条规则：读过药方才能熬，创造模式也不跳过。
        if (!BrewRecipeBook.lookup(player).knows(kind)) {
            BrewTexts.sendOverlay(player, BrewText.message("recipe.not_learned", BrewCauldron.recipeName(kind)));
            return false;
        }
        controller.select(kind, player.getUUID());
        return true;
    }

    private static void stop(ServerLevel level, Tower tower) {
        // 停要退料，所以要摆法；按钮前已查过摆法在，这里再找一次只是拿出来用。
        Optional<Placement> placement = placement(level, tower.site());
        if (placement.isEmpty()) {
            return;
        }
        tower.controller().stop(world(level, placement.orElseThrow(), tower.id()));
    }

    /** 按塔现状填窗口的整数表；塔没了就不动，原版会因 stillValid 不成立关窗。 */
    static void fill(Session session, ContainerData data) {
        // 下标见 BrewTowerMenu 的 DATA_ 常量；整数都在 16 位以内。
        Optional<Tower> found = BrewTowerSavedData.of(session.level()).find(session.towerId());
        if (found.isEmpty()) {
            return;
        }
        Tower tower = found.orElseThrow();
        BrewTowerController controller = tower.controller();
        data.set(BrewTowerMenu.DATA_STATUS, controller.status().ordinal());
        data.set(BrewTowerMenu.DATA_TEMPERATURE_TENTHS, (int) Math.round(controller.temperature() * TENTHS));
        fillBand(data, controller);
        data.set(BrewTowerMenu.DATA_LEVEL, controller.level());
        data.set(BrewTowerMenu.DATA_FIRE, controller.fire().ordinal());
        data.set(BrewTowerMenu.DATA_FUEL_SECONDS, (int) Math.floor(controller.litSeconds()));
        data.set(BrewTowerMenu.DATA_BOTTLES, controller.bottles());
        data.set(BrewTowerMenu.DATA_PROGRESS_TENTHS, (int) Math.floor(controller.brewElapsedSeconds() * TENTHS));
        data.set(BrewTowerMenu.DATA_QI_PERCENT, (int) Math.floor(controller.qiPaidFraction() * PERCENT));
        data.set(BrewTowerMenu.DATA_NEEDS, needMask(controller));
        data.set(BrewTowerMenu.DATA_CHEST_FREE, chestFree(session.level(), tower));
        data.set(BrewTowerMenu.DATA_KNOWN, session.knownMask());
        data.set(BrewTowerMenu.DATA_SELECTED, controller.selected().map(Enum::ordinal).orElse(NONE));
        data.set(BrewTowerMenu.DATA_FORM, controller.form().ordinal());
        data.set(BrewTowerMenu.DATA_CONTINUOUS,
                controller.continuous() ? BrewTowerMenu.FLAG_ON : BrewTowerMenu.FLAG_OFF);
        data.set(BrewTowerMenu.DATA_PHASE, controller.phase().ordinal());
        data.set(BrewTowerMenu.DATA_ARMED, controller.armed() ? BrewTowerMenu.FLAG_ON : BrewTowerMenu.FLAG_OFF);
        data.set(BrewTowerMenu.DATA_FUEL_WANTED, controller.fuelWanted());
    }

    private static void fillBand(ContainerData data, BrewTowerController controller) {
        // 正在熬的药方，没在熬时是选好的；都没有时 -1。
        Optional<BrewKind> kind = controller.batchKind();
        if (kind.isEmpty()) {
            data.set(BrewTowerMenu.DATA_BAND_MIN, NONE);
            data.set(BrewTowerMenu.DATA_BAND_MAX, NONE);
            return;
        }
        HeatBand band = BrewRecipes.require(kind.orElseThrow()).heatBand();
        data.set(BrewTowerMenu.DATA_BAND_MIN, (int) Math.round(band.minimumDegrees()));
        data.set(BrewTowerMenu.DATA_BAND_MAX, (int) Math.round(band.maximumDegrees()));
    }

    private static int needMask(BrewTowerController controller) {
        // 在等的每一样各占一位。
        int mask = 0;
        for (TowerNeed need : controller.needs().keySet()) {
            mask |= need.bit();
        }
        return mask;
    }

    private static int chestFree(ServerLevel level, Tower tower) {
        // 摆法找不到时没有箱子可言。
        Optional<Placement> placement = placement(level, tower.site());
        if (placement.isEmpty()) {
            return 0;
        }
        return world(level, placement.orElseThrow(), tower.id()).chestFreeSlots();
    }


    /** 塔还在，人离药池不超过 8 格。 */
    static boolean stillValid(Session session, Player player) {
        // 药池是塔的中心；站在阳台上点玻璃时离它两三格。
        Optional<Tower> found = BrewTowerSavedData.of(session.level()).find(session.towerId());
        if (found.isEmpty() || player.level() != session.level()) {
            return false;
        }
        Optional<Placement> placement = placement(session.level(), found.orElseThrow().site());
        if (placement.isEmpty()) {
            return false;
        }
        Vec3 pool = Vec3.atCenterOf(placement.orElseThrow().anchorWorld());
        return player.distanceToSqr(pool) <= VIEW_DISTANCE_SQR;
    }

    private static boolean breakFuel(Level level, Player player, BlockPos pos, BlockState state, BlockEntity entity) {
        // 点着的燃料已经在烧：拆下来不掉东西，免得拿回一块整的再放回去重新点满（复审第 1 条）。
        if (!(level instanceof ServerLevel serverLevel) || !litFuelAt(serverLevel, pos)) {
            return true;
        }
        serverLevel.destroyBlock(pos, false /* drop */, player);
        return false;
    }

    /** 这一格是不是某座塔火室里点着的那格；拆方块、用桶舀熔岩时都问这里。 */
    public static boolean litFuelAt(ServerLevel level, BlockPos pos) {
        // 塔不多，每座按记下的摆法把四格燃料的位置算出来比；定义暂时没了的塔不算。
        for (Tower tower : BrewTowerSavedData.of(level).towers()) {
            Optional<Placement> placement = placement(level, tower.site());
            if (placement.isEmpty()) {
                continue;
            }
            List<Cell> fuel = placement.orElseThrow().definition().fuel();
            for (int cell = 0; cell < fuel.size(); cell++) {
                boolean here = placement.orElseThrow().world(fuel.get(cell).local()).equals(pos);
                if (here && tower.controller().fuelLit(cell)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Optional<Tower> towerAt(ServerLevel level, BrewTowerSavedData data, BlockPos clicked) {
        // 点的这格是哪座记下的塔的玻璃或铜灯。
        for (Tower tower : data.towers()) {
            Optional<Placement> placement = placement(level, tower.site());
            if (placement.isPresent() && entryOf(placement.orElseThrow(), clicked)) {
                return Optional.of(tower);
            }
        }
        return Optional.empty();
    }

    private static boolean entryOf(Placement placement, BlockPos clicked) {
        // 只比入口格：玻璃外框和铜灯。
        Definition definition = placement.definition();
        for (Cell cell : definition.cores()) {
            if (entryCell(cell) && placement.world(cell.local()).equals(clicked)) {
                return true;
            }
        }
        for (Cell cell : definition.frames()) {
            if (entryCell(cell) && placement.world(cell.local()).equals(clicked)) {
                return true;
            }
        }
        return false;
    }

    private static boolean entryCell(Cell cell) {
        // 入口：玻璃类的外框格，或铜灯核心格（D9）。
        boolean glass = cell.rule().isFrame()
                && cell.rule().shown().is(BrewingTags.FRAMES.get(FrameClass.GLASS));
        boolean bulb = cell.rule().kind() == RuleKind.CORE && cell.rule().shown().is(BrewingTags.COPPER_BULBS);
        return glass || bulb;
    }

    private static boolean entryBlock(BlockState state) {
        // 先粗筛世界里这格方块，别的方块不去比结构。
        return state.is(BrewingTags.FRAMES.get(FrameClass.GLASS)) || state.is(BrewingTags.COPPER_BULBS);
    }

    private static Optional<Placement> placement(ServerLevel level, Site site) {
        // 按存档里的定义 ID 找回定义；数据包删了它就找不到。
        return BrewStructureDefinitions.all(level.getServer()).stream()
                .filter(definition -> definition.id().equals(site.definitionId()))
                .findFirst()
                .map(definition -> new Placement(definition, site.origin(), site.rotation()));
    }

    private static BrewTowerWorld world(ServerLevel level, Placement placement, UUID towerId) {
        // 环境取用的键带塔号，同一维度两座塔、同一批号也不会撞。
        return new BrewTowerWorld(level, placement, BrewingFabric.MOD_ID + ":tower/" + towerId + "/");
    }

    /** 一个开着的窗口：哪个维度的哪座塔、看的人开窗时学会了哪几个药方。 */
    record Session(ServerLevel level, UUID towerId, int knownMask) {
    }
}
