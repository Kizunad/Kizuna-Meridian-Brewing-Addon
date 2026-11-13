package dev.kizuna.meridian.brewing.fabric;

import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * 三阶药塔的窗口：没有物品格。按钮走原版 {@code clickMenuButton}（切石机、织布机同一路），状态用
 * {@link ContainerData} 的整数同步，不新增网络包。服务端带着它看的那座塔；客户端只有一张整数表，由原版同步填。
 *
 * <p>整数都在 16 位以内（原版按短整数同步）：温度、进度按十分之一，燃料按秒。枚举按序号，药方按
 * {@code BrewKind.values()} 的顺序，“选中的药方”和“形态”没有时是 -1。</p>
 */
public final class BrewTowerMenu extends AbstractContainerMenu {
    /** 0–5：按 {@code BrewKind.values()} 选药方。 */
    public static final int BUTTON_FIRST_RECIPE = 0;
    public static final int BUTTON_DRINK = 6;
    public static final int BUTTON_SPLASH = 7;
    public static final int BUTTON_ONCE = 8;
    public static final int BUTTON_CONTINUOUS = 9;
    public static final int BUTTON_STOP = 10;
    /** {@code TowerStatus} 序号。 */
    public static final int DATA_STATUS = 0;
    public static final int DATA_TEMPERATURE_TENTHS = 1;
    /** 这一批（或选好的）药方火带，度；没有药方时 -1。 */
    public static final int DATA_BAND_MIN = 2;
    public static final int DATA_BAND_MAX = 3;
    /** 这一秒烧着几格，0–4。 */
    public static final int DATA_LEVEL = 4;
    /** {@code FireKind} 序号。 */
    public static final int DATA_FIRE = 5;
    /** 点着的燃料格还能烧几秒（不含木桶里的）。 */
    public static final int DATA_FUEL_SECONDS = 6;
    public static final int DATA_BOTTLES = 7;
    /** 已熬了几十分之一秒；一锅 30 秒是 300。 */
    public static final int DATA_PROGRESS_TENTHS = 8;
    public static final int DATA_QI_PERCENT = 9;
    /** 在等的东西，{@code TowerNeed.bit()} 的或。 */
    public static final int DATA_NEEDS = 10;
    public static final int DATA_CHEST_FREE = 11;
    /** 看窗口的人学会的药方，第 i 位是 {@code BrewKind.values()[i]}。 */
    public static final int DATA_KNOWN = 12;
    /** 选中的药方序号，没选是 -1。 */
    public static final int DATA_SELECTED = 13;
    /** {@code BrewForm} 序号。 */
    public static final int DATA_FORM = 14;
    public static final int DATA_CONTINUOUS = 15;
    /** {@code TowerPhase} 序号。 */
    public static final int DATA_PHASE = 16;
    public static final int DATA_ARMED = 17;
    /** 在等燃料时等的是几档（1–4），和 {@code DATA_NEEDS} 里的缺燃料是一回事；没在等燃料时 0。 */
    public static final int DATA_FUEL_WANTED = 18;
    public static final int DATA_COUNT = 19;
    /** {@code DATA_CONTINUOUS}、{@code DATA_ARMED} 这类开关项的两个值。 */
    public static final int FLAG_OFF = 0;
    public static final int FLAG_ON = 1;
    private static MenuType<BrewTowerMenu> type;
    private final ContainerData data;
    private final Optional<BrewTowers.Session> session;

    /** 客户端：原版收到开窗包时用这个建，整数表等服务端同步。 */
    BrewTowerMenu(int containerId, Inventory inventory) {
        // 客户端不知道是哪座塔，也不需要知道：按钮只发编号。
        this(containerId, new SimpleContainerData(DATA_COUNT), Optional.empty());
    }

    /** 服务端：看 {@code session} 那座塔，开窗前先把整数表填一遍。 */
    BrewTowerMenu(int containerId, BrewTowers.Session session) {
        // 原版开窗时先发一次全部整数，所以构造时就要填好。
        this(containerId, new SimpleContainerData(DATA_COUNT), Optional.of(session));
        BrewTowers.fill(session, data);
    }

    private BrewTowerMenu(int containerId, ContainerData data, Optional<BrewTowers.Session> session) {
        // 只有整数槽，没有物品格。
        super(type, containerId);
        this.data = data;
        this.session = session;
        addDataSlots(data);
    }

    static void register() {
        // 两端都要注册同一个菜单类型，客户端才建得出窗口。
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "brew_tower");
        MenuType<BrewTowerMenu> created = new MenuType<>(BrewTowerMenu::new, FeatureFlags.VANILLA_SET);
        type = Registry.register(BuiltInRegistries.MENU, id, created);
    }

    /** 注册好的菜单类型；客户端登记药塔界面用。 */
    public static MenuType<BrewTowerMenu> type() {
        // Fabric 先跑通用入口再跑客户端入口，客户端来取时已经注册过。
        if (type == null) {
            throw new IllegalStateException("药塔菜单类型还没注册");
        }
        return type;
    }

    /** 整数表第 {@code index} 项；客户端界面读这个。 */
    public int data(int index) {
        // 下标见本类的 DATA_ 常量。
        return data.get(index);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        // 客户端按了按钮，服务端照做；做不了（没学会、塔没了）就不算按下。
        if (session.isEmpty() || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        return BrewTowers.click(session.orElseThrow(), serverPlayer, id);
    }

    @Override
    public void broadcastChanges() {
        // 每 tick 先按塔现状刷新整数表，原版再把变了的那几项发给客户端。
        session.ifPresent(open -> BrewTowers.fill(open, data));
        super.broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        // 没有物品格，Shift 点击什么都不挪。
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        // 塔还在、人还在旁边才开着；客户端由服务端决定什么时候关。
        return session.map(open -> BrewTowers.stillValid(open, player)).orElse(true);
    }
}
