package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.protocol.pb.BrewTimeline;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceRequest;
import dev.kizuna.meridian.brewing.protocol.pb.FurnaceView;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewRecipes;
import dev.kizuna.meridian.brewing.server.BrewSteps;
import dev.kizuna.meridian.brewing.server.RecipeKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/** 原版菜单 ID 绑定本次打开的炉、点击部件和观看者；物品仍从世界交接。 */
public final class FurnaceMenu extends AbstractContainerMenu {
    private static final int VIEW_INTERVAL = 10;
    private static MenuType<FurnaceMenu> type;
    private final ServerPlayer viewer;
    private final BrewStation ports;
    private final BlockPos clicked;
    private FurnaceView previous;

    private FurnaceMenu(int id, ServerPlayer viewer, BrewStation ports, BlockPos clicked) {
        super(type, id);
        this.viewer = viewer;
        this.ports = ports;
        this.clicked = clicked;
    }

    static void register() {
        var id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "furnace");
        var menu = new MenuType<FurnaceMenu>((index, inventory) -> new FurnaceMenu(index, null, null, null),
                FeatureFlags.VANILLA_SET);
        type = Registry.register(BuiltInRegistries.MENU, id, menu);
    }

    public static MenuType<FurnaceMenu> type() {
        return type;
    }

    static void open(ServerPlayer player, FurnaceLayout layout, BlockPos clicked) {
        var ports = FurnacePorts.of(player.level(), layout);
        var title = Component.translatable(layout.pattern().id().toLanguageKey("structure"));
        open(player, ports, clicked, title);
    }

    static void open(ServerPlayer player, BrewStation ports, BlockPos clicked, Component title) {
        player.openMenu(new SimpleMenuProvider((id, inventory, human) -> new FurnaceMenu(id, player, ports, clicked),
                title));
        if (player.containerMenu instanceof FurnaceMenu menu) {
            menu.sync();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return viewer == null || player == viewer && viewer.level() == ports.level() && !viewer.isSpectator()
                && viewer.isWithinBlockInteractionRange(clicked, 0) && ports.level().mayInteract(viewer, clicked)
                && ports.level().hasChunkAt(clicked);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (viewer != null && viewer.containerMenu == this && viewer.level().getGameTime() % VIEW_INTERVAL == 0) {
            if (validStructure()) {
                sync();
            } else {
                viewer.closeContainer();
            }
        }
    }

    private boolean validStructure() {
        return stillValid(viewer) && ports.available();
    }

    FurnaceView snapshot() {
        return FurnaceReadout.view(containerId, ports, BrewRecipeBook.lookup(viewer).known());
    }

    private void sync() {
        var current = snapshot();
        var delta = FurnaceReadout.changes(previous, current);
        if (delta.hasOrders() || delta.hasCatalog() || delta.hasClock()) {
            FurnaceNetwork.sendView(viewer, delta);
        }
        previous = current;
    }

    void handle(FurnaceRequest request) {
        if (viewer.containerMenu != this || request.getMenu() != containerId || !validStructure()) {
            return;
        }
        var batch = ports.batch();
        if (request.getVersion() != batch.version) {
            previous = null;
            sync();
            return;
        }
        if (request.hasFurnaceSteps()) {
            var steps = timeline();
            if (steps != null) {
                FurnaceNetwork.sendSteps(viewer, steps);
            }
            return;
        }
        if (request.hasSelect()) {
            select(request);
        } else if (request.hasReclaim() && request.getReclaim()) {
            var kind = batch.cauldron.recipe();
            if (kind.isPresent() && BrewRecipeBook.lookup(viewer).knows(kind.orElseThrow().kind())) {
                batch.reclaim(viewer);
            }
        }
        ports.changed();
        sync();
    }

    private void select(FurnaceRequest request) {
        var choice = request.getSelect();
        var kind = RecipeKnowledge.kind(choice.getRecipe());
        var form = BrewCauldronSnapshots.enumValueOrNull(choice.getForm(), BrewForm.class);
        if (kind.isEmpty() || form == null) {
            return;
        }
        var order = new FurnaceOrder(kind.orElseThrow(), form, viewer.getUUID());
        String error = ports.batch().select(viewer, order);
        if (!error.isEmpty()) {
            viewer.sendOverlayMessage(Component.translatable("message.meridian_brewing.furnace." + error));
        } else if (ports.batch().manualBrew()) {
            viewer.sendOverlayMessage(Component.translatable("message.meridian_brewing.furnace.manual_in_charge"));
        }
    }

    BrewTimeline timeline() {
        var view = snapshot();
        var orders = view.getOrders();
        // 当前批次被遮住时不能把下一方错当成当前批次来播放。
        String current = view.getClock().getCurrentStep();
        if (current.isEmpty()) {
            return null;
        }
        var choice = orders.getNext();
        if (orders.hasActual()) {
            choice = orders.getActual();
        }
        var recipe = BrewRecipes.require(RecipeKnowledge.kind(choice.getRecipe()).orElseThrow());
        var steps = BrewSteps.timeline(recipe, ports.batch().vessel, BrewForm.valueOf(choice.getForm()));
        return steps.toBuilder()
                .setMenu(containerId)
                .setBatch(orders.getBatch())
                .setVersion(orders.getVersion())
                .setCurrentStep(current)
                .setManual(view.getClock().getManual())
                .build();
    }
}
