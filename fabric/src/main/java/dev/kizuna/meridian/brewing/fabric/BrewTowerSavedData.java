package dev.kizuna.meridian.brewing.fabric;

import com.mojang.serialization.Codec;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQiTally;
import dev.kizuna.meridian.brewing.server.BrewTowerController;
import dev.kizuna.meridian.brewing.server.BrewTowerRecord;
import dev.kizuna.meridian.brewing.server.CopperCauldronState;
import dev.kizuna.meridian.brewing.server.CopperOxidation;
import dev.kizuna.meridian.brewing.server.FireKind;
import dev.kizuna.meridian.brewing.server.FuelCell;
import dev.kizuna.meridian.brewing.server.IngredientBatch;
import dev.kizuna.meridian.brewing.server.TowerFuel;
import dev.kizuna.meridian.brewing.server.TowerPhase;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 一个维度里认出来的全部药塔。每座塔一条：塔号、摆法（结构定义 ID、NBT 原点、朝向）和控制器状态。
 * 存在这个维度的 {@code data/meridian_brewing/brew_towers.dat}，区块卸载、退出重进都接着跑。
 *
 * <p>存档只有字符串和数，不依赖注册表；读坏了的那一条记一行警告后跳过，其余照读。</p>
 */
final class BrewTowerSavedData extends SavedData {
    private static final Logger LOGGER = System.getLogger("meridian_brewing_tower");
    private static final Codec<BrewTowerSavedData> CODEC =
            CompoundTag.CODEC.xmap(BrewTowerSavedData::read, BrewTowerSavedData::write);
    /** 数据修复类型留空：Fabric 对象构建 API 遇到空类型时原样读回，本仓存档没有原版数据修复。 */
    static final SavedDataType<BrewTowerSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "brew_towers"), BrewTowerSavedData::new, CODEC,
            null);
    private static final CopperCauldronState TOWER_COPPER = new CopperCauldronState(CopperOxidation.UNAFFECTED,
            false);
    private static final int BLOCK_POS_INTS = 3;
    private final Map<UUID, Tower> towers = new LinkedHashMap<>();

    private BrewTowerSavedData() {
        // 新维度、或这个维度还没认过塔。
    }

    /** 这个维度的药塔表；第一次用到时从磁盘读。 */
    static BrewTowerSavedData of(ServerLevel level) {
        // 原版按维度各存一份。
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** 全部塔的一份拷贝，遍历时可以删。 */
    List<Tower> towers() {
        // 宿主推进时会删失效的塔，所以给拷贝。
        return List.copyOf(towers.values());
    }

    /** 新认出一座塔。 */
    Tower add(Site site, BrewTowerController controller) {
        // 塔号随机，环境取用的键也带它，同一维度两座塔不会撞。
        Tower tower = new Tower(UUID.randomUUID(), site, controller);
        towers.put(tower.id(), tower);
        setDirty();
        return tower;
    }

    void remove(UUID id) {
        // 删了才标脏。
        if (towers.remove(id) != null) {
            setDirty();
        }
    }

    Optional<Tower> find(UUID id) {
        // 窗口按塔号找回它看的那座塔。
        return Optional.ofNullable(towers.get(id));
    }

    /** 塔的状态在控制器里变了；宿主每推进一次、每次操作后调用。 */
    void changed() {
        // 控制器不知道存档，由宿主替它标脏。
        setDirty();
    }

    private static BrewTowerSavedData read(CompoundTag tag) {
        // 一条一条读，读坏的跳过。
        BrewTowerSavedData data = new BrewTowerSavedData();
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag);
        for (ValueInput entry : input.childrenListOrEmpty("towers")) {
            readTower(entry).ifPresent(tower -> data.towers.put(tower.id(), tower));
        }
        return data;
    }

    private static CompoundTag write(BrewTowerSavedData data) {
        // 每座塔写塔号、摆法和控制器记录。
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        ValueOutput.ValueOutputList list = output.childrenList("towers");
        for (Tower tower : data.towers.values()) {
            ValueOutput entry = list.addChild();
            entry.putString("id", tower.id().toString());
            entry.putString("definition", tower.site().definitionId().toString());
            BlockPos origin = tower.site().origin();
            entry.putIntArray("origin", new int[] {origin.getX(), origin.getY(), origin.getZ()});
            entry.putString("rotation", tower.site().rotation().name());
            writeRecord(entry.child("state"), tower.controller().record());
        }
        return output.buildResult();
    }

    private static Optional<Tower> readTower(ValueInput entry) {
        // 塔号、定义 ID、原点缺一样都认不回这座塔；控制器记录不合法同样跳过。
        Optional<UUID> id = entry.getString("id").flatMap(BrewTowerSavedData::uuid);
        Optional<Identifier> definition = entry.getString("definition").map(Identifier::tryParse);
        Optional<int[]> origin = entry.getIntArray("origin").filter(values -> values.length == BLOCK_POS_INTS);
        Rotation rotation = BrewCauldronSnapshots.enumValueOrNull(entry.getStringOr("rotation", ""), Rotation.class);
        if (id.isEmpty() || definition.isEmpty() || origin.isEmpty() || rotation == null) {
            LOGGER.log(Level.WARNING, "药塔存档有一条摆法不完整，跳过");
            return Optional.empty();
        }
        int[] xyz = origin.orElseThrow();
        Site site = new Site(definition.orElseThrow(), new BlockPos(xyz[0], xyz[1], xyz[2]), rotation);
        try {
            BrewTowerRecord record = readRecord(entry.childOrEmpty("state"));
            BrewTowerController controller = BrewTowerController.restore(BrewTowers.settings(),
                    BrewQiPayment.settings(), record);
            return Optional.of(new Tower(id.orElseThrow(), site, controller));
        } catch (IllegalArgumentException exception) {
            LOGGER.log(Level.WARNING, "药塔存档 " + id.orElseThrow() + " 的状态读不回来，跳过：" + exception.getMessage());
            return Optional.empty();
        }
    }

    private static void writeRecord(ValueOutput output, BrewTowerRecord record) {
        // 可空的字段（药方、选药方的人、本批真元账）没有就不写。
        output.putString("selected", BrewCauldronSnapshots.enumName(record.selected()));
        if (record.chooser() != null) {
            output.putString("chooser", record.chooser().toString());
        }
        output.putString("form", record.form().name());
        output.putBoolean("continuous", record.continuous());
        output.putBoolean("armed", record.armed());
        output.putString("phase", record.phase().name());
        BrewCauldronSnapshots.write(output.child("cauldron"), record.cauldron());
        output.putString("fire", record.fire().name());
        ValueOutput.ValueOutputList fuel = output.childrenList("fuel");
        for (FuelCell cell : record.fuel()) {
            ValueOutput entry = fuel.addChild();
            entry.putString("fuel", BrewCauldronSnapshots.enumName(cell.fuel()));
            entry.putDouble("remaining_seconds", cell.remainingSeconds());
        }
        ValueOutput.ValueOutputList reserve = output.childrenList("reserve");
        for (IngredientBatch batch : record.reserve()) {
            BrewCauldronSnapshots.writeBatch(reserve.addChild(), batch);
        }
        if (record.qi() != null) {
            writeQi(output.child("qi"), record.qi());
        }
        output.putLong("serial", record.serial());
        output.putInt("flash_seconds", record.flashSeconds());
    }

    private static BrewTowerRecord readRecord(ValueInput input) {
        // 枚举认不出时取默认；燃料格、真元账各自读；字段不合法由记录的构造器拒绝。
        BrewKind selected = BrewCauldronSnapshots.enumValueOrNull(input.getStringOr("selected", ""), BrewKind.class);
        UUID chooser = input.getString("chooser").flatMap(BrewTowerSavedData::uuid).orElse(null);
        BrewForm form = BrewCauldronSnapshots.enumValue(input.getStringOr("form", ""), BrewForm.DRINK);
        TowerPhase phase = BrewCauldronSnapshots.enumValue(input.getStringOr("phase", ""), TowerPhase.IDLE);
        BrewCauldron.Snapshot cauldron = BrewCauldronSnapshots.read(input.childOrEmpty("cauldron"), TOWER_COPPER);
        FireKind fire = BrewCauldronSnapshots.enumValue(input.getStringOr("fire", ""), FireKind.SUN_FIRE);
        List<IngredientBatch> reserve = new ArrayList<>();
        for (ValueInput entry : input.childrenListOrEmpty("reserve")) {
            reserve.add(BrewCauldronSnapshots.readBatch(entry));
        }
        BrewQiTally qi = input.child("qi").map(BrewTowerSavedData::readQi).orElse(null);
        return new BrewTowerRecord(selected, chooser, form, input.getBooleanOr("continuous", false),
                input.getBooleanOr("armed", false), phase, cauldron, fire, readFuel(input), reserve, qi,
                input.getLongOr("serial", 0L), input.getIntOr("flash_seconds", 0));
    }

    private static List<FuelCell> readFuel(ValueInput input) {
        // 认不出的燃料或秒数不为正都当没点着。
        List<FuelCell> cells = new ArrayList<>();
        for (ValueInput entry : input.childrenListOrEmpty("fuel")) {
            TowerFuel fuel = BrewCauldronSnapshots.enumValueOrNull(entry.getStringOr("fuel", ""), TowerFuel.class);
            double seconds = entry.getDoubleOr("remaining_seconds", 0.0);
            if (fuel == null || seconds <= 0) {
                cells.add(FuelCell.UNLIT);
            } else {
                cells.add(new FuelCell(fuel, seconds));
            }
        }
        return cells;
    }

    private static void writeQi(ValueOutput output, BrewQiTally qi) {
        // 账的五个数原样写。
        output.putDouble("total", qi.total());
        output.putDouble("grace_seconds", qi.graceSeconds());
        output.putInt("seconds", qi.seconds());
        output.putDouble("ambient", qi.ambient());
        output.putDouble("self", qi.self());
    }

    private static BrewQiTally readQi(ValueInput input) {
        // 缺的数按零；负数由账的构造器拒绝，整条塔记录跳过。
        return new BrewQiTally(input.getDoubleOr("total", 0.0), input.getDoubleOr("grace_seconds", 0.0),
                input.getIntOr("seconds", 0), input.getDoubleOr("ambient", 0.0), input.getDoubleOr("self", 0.0));
    }

    private static Optional<UUID> uuid(String text) {
        // 存档里的字符串不一定是合法 UUID。
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /** 一座塔的摆法：哪份结构定义、NBT 原点在哪、转了多少。 */
    record Site(Identifier definitionId, BlockPos origin, Rotation rotation) {
    }

    /** 表里的一座塔；控制器本身可变，记录只换引用不换。 */
    record Tower(UUID id, Site site, BrewTowerController controller) {
    }
}
