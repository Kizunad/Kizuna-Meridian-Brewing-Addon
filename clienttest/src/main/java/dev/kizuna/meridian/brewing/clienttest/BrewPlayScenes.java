package dev.kizuna.meridian.brewing.clienttest;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import dev.kizuna.meridian.brewing.fabric.BrewCauldronBlockEntity;
import dev.kizuna.meridian.brewing.fabric.BrewFireBlockEntity;
import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewingTechniqueContract;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQiTally;
import dev.kizuna.meridian.brewing.server.BrewScoring;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueCastEnded;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * 炼药可玩性修正的真实客户端场景：晒药匾界面；漏斗链接晒药匾；创造模式里照现在的规矩熬一锅苦黄膏，每一步一张，动作栏拍开熬、辅料时窗、
 * 引药时窗和快熬成四个时刻（行末带这锅的耗元），施放中副手下辅料、副手加炭升火再拨灰降回；舀出成药和熬糊后舀出药渣的提示；药瓶提示里的品质和药效；炼药页末尾带品质的巫药；
 * 七个状态效果在屏幕右上和背包里。
 *
 * <p>学式用 {@code /meridian technique learn}，起手经功法库公开入口，与 GameTest 相同；注水、下料、加炭、拨灰、舀药都是
 * 真实右键。每步之后把釜的状态写进日志，截图只摆场景，不比对像素。</p>
 */
final class BrewPlayScenes {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String BREWING = "meridian_brewing:";
    private static final String VOLUME = BREWING + "brewing_basics";
    private static final Identifier CREATIVE_TAB = Identifier.fromNamespaceAndPath("meridian_brewing", "brewing");
    private static final String MAIN_HAND = "weapon.mainhand";
    private static final String OFF_HAND = "weapon.offhand";
    private static final String EMPTY = "minecraft:air";
    // 熬药式要求手持物品，搅棍类（木棍）优先。
    private static final String STIRRER = "minecraft:stick";
    private static final String WATER_BOTTLE =
            "minecraft:potion[minecraft:potion_contents={potion:\"minecraft:water\"}]";
    private static final List<String> EFFECTS = List.of("depletion", "armor_rot", "insect_lure", "qi_gather",
            "bitter_yellow", "charm_stagger", "charm_control");
    private static final int EFFECT_SECONDS = 300;
    /** 巫釜在铁炉座上方，镜头从南面看釜。 */
    private static final BlockPos CAULDRON = new BlockPos(0, 1, 0);
    private static final BlockPos STOVE_STAND = new BlockPos(0, 0, 2);
    private static final BlockPos STOVE_OVERVIEW = new BlockPos(2, 0, 3);
    private static final int WITCH_WATER = 2;
    private static final BlockPos CRATE = BlockPos.ZERO;
    private static final Vec3 CRATE_EYE = new Vec3(0.5, 1.62, 2.3);
    /** 漏斗链：箱子、漏斗、晒药匾自上而下，底下的漏斗和出口箱子埋在地面里，旁边一块红石块先锁住底下的漏斗。 */
    private static final BlockPos CHAIN_SUPPLY = new BlockPos(0, 2, 0);
    private static final BlockPos CHAIN_FEED = new BlockPos(0, 1, 0);
    private static final BlockPos CHAIN_DRAIN = new BlockPos(0, -1, 0);
    private static final BlockPos CHAIN_LOCK = new BlockPos(-1, -1, 0);
    private static final BlockPos CHAIN_OUTPUT = new BlockPos(0, -1, 1);
    private static final BlockPos CHAIN_READER = new BlockPos(1, 0, 0);
    private static final BlockPos CHAIN_LAMP = new BlockPos(2, 0, 0);
    private static final List<BlockPos> CHAIN = List.of(CHAIN_SUPPLY, CHAIN_FEED, CRATE, CHAIN_DRAIN, CHAIN_OUTPUT);
    private static final Vec3 CHAIN_EYE = new Vec3(4.0, 2.2, 4.6);
    private static final String DRY_MOSS = BREWING + "dry_moss[" + BREWING + "prepared_day=4L]";
    /** 漏斗 8 tick 搬一件：六块苔藓块、两块泥土要搬八轮，五份干苔要搬五轮，都留出余量。 */
    private static final int CHAIN_TICKS = 100;
    private static final int TICKS_PER_SECOND = 20;
    /** 药渣场景先用四档火把釜烧热，再起熬。 */
    private static final int HEAT_TICKS = 15 * TICKS_PER_SECOND;
    /** 四档火 100°：比火候带上沿高 8° 以上就每 3 秒焦一次，用来熬出药渣。 */
    private static final int SCORCH_COAL = 4;
    private static final int BREW_TIMEOUT_TICKS = 45 * TICKS_PER_SECOND;
    private static final int COOL_TIMEOUT_TICKS = 20 * TICKS_PER_SECOND;
    private static final int POLL_TICKS = 2;
    /** 动作栏每秒换一行，下料后的提示只停留不到一秒，按完右键稍等就拍。 */
    // 目录里熬药式的 recovery_millis 是 1000。
    private static final int RECOVERY_TICKS = 20;
    private static final int MESSAGE_TICKS = 4;
    private static final double STARTED_SECONDS = 1.0;
    private static final double ACCESSORY_SECONDS = 7.0;
    private static final double FIRE_UP_SECONDS = 9.0;
    /** 升火、降火各占一个推进点，两张截图的动作栏不挤在同一秒。 */
    private static final double FIRE_DOWN_SECONDS = 11.0;
    private static final double CATALYST_SECONDS = 19.0;
    private static final double ALMOST_DONE_SECONDS = 28.0;
    private static final double COLLECT_BELOW_DEGREES = 78.0;
    private static final int CREATIVE_WIDTH = 195;
    private static final int CREATIVE_HEIGHT = 136;
    private static final int SLOT_CENTER = 8;
    private static final double SCROLL_TO_END = -20.0;
    /** 背包旁边要放得下效果名字，界面缩放调到 2。 */
    private static final int WIDE_GUI_SCALE = 2;

    private BrewPlayScenes() {
    }

    static void run(ClientGameTestContext context, Stage stage) {
        crate(context, stage);
        hopperChain(context, stage);
        stage.command("meridian technique learn @a " + VOLUME);
        // 学过药方才能熬；这里用调试指令一次学会，读羊皮纸的路径由 GameTest 覆盖。
        stage.command("meridian progression recipes learn @a all");
        // 施放结束的原因写进日志：熬药式被拒时，釜上看不出是哪一条没满足。
        Consumer<TechniqueCastEnded> ended = event -> LOGGER.info("[炼药截图] 熬药式结束：{}，{}",
                event.reason(), event.outcome().getReason());
        TechniqueFabric.service().addCastEndListener(ended);
        try {
            bitterYellow(context, stage);
            scrap(stage);
        } finally {
            TechniqueFabric.service().removeCastEndListener(ended);
        }
        creativePotions(context, stage);
        // 状态效果按秒结算，放最后拍，拍完清掉。
        effects(context, stage);
    }

    private static void crate(ClientGameTestContext context, Stage stage) {
        // 生存模式空手右键晒药匾：打开 9 格界面，三格里分别晒着苔藓块、虞美人和河豚。
        stage.clear();
        stage.play();
        stage.command("clear @a");
        stage.command("gamemode survival @a");
        stage.place(CRATE, BREWING + "herb_crate");
        stage.container(CRATE, 0, "minecraft:moss_block 8");
        stage.container(CRATE, 4, "minecraft:poppy 4");
        stage.container(CRATE, 8, "minecraft:pufferfish 2");
        stage.look(stage.at(CRATE_EYE), stage.center(CRATE));
        stage.hand(MAIN_HAND, EMPTY);
        stage.use();
        context.waitForScreen(DispenserScreen.class);
        context.getInput().setCursorPos(0, 0);
        stage.settle();
        stage.shoot("crate_menu");
        context.setScreen(() -> null);
        stage.command("gamemode creative @a");
    }

    private static void hopperChain(ClientGameTestContext context, Stage stage) {
        // 上面的箱子里两块泥土、六块苔藓块：漏斗把苔藓块送进匾，泥土匾不收，留在漏斗里。匾里先放两格晒好的干苔，
        // 底下的漏斗被红石块锁着，东边的比较器读到 2，点亮红石灯；拿掉红石块，干苔被抽进出口箱子，生的苔藓块留在匾里。
        stage.clear();
        stage.play();
        stage.command("clear @a");
        stage.command("gamemode survival @a");
        stage.place(CHAIN_SUPPLY, "minecraft:chest");
        stage.place(CHAIN_FEED, "minecraft:hopper[facing=down]");
        stage.place(CRATE, BREWING + "herb_crate");
        stage.place(CHAIN_DRAIN, "minecraft:hopper[facing=south]");
        stage.place(CHAIN_LOCK, "minecraft:redstone_block");
        stage.place(CHAIN_OUTPUT, "minecraft:chest[facing=south]");
        stage.place(CHAIN_READER, "minecraft:comparator[facing=west]");
        stage.place(CHAIN_LAMP, "minecraft:redstone_lamp");
        stage.container(CHAIN_SUPPLY, 0, "minecraft:dirt 2");
        stage.container(CHAIN_SUPPLY, 1, "minecraft:moss_block 6");
        stage.container(CRATE, 7, DRY_MOSS + " 3");
        stage.container(CRATE, 8, DRY_MOSS + " 2");
        stage.pause(CHAIN_TICKS);
        chainShot(stage, "chain_01_locked");
        stage.place(CHAIN_LOCK, "minecraft:grass_block");
        stage.pause(CHAIN_TICKS);
        chainShot(stage, "chain_02_drained");
        stage.hand(MAIN_HAND, EMPTY);
        openShot(context, stage, CHAIN_OUTPUT, ContainerScreen.class, "chain_03_output_chest");
        openShot(context, stage, CRATE, DispenserScreen.class, "chain_04_crate_menu");
        stage.command("gamemode creative @a");
    }

    private static void chainShot(Stage stage, String shot) {
        stage.hideHud();
        // 对着匾上面的漏斗看，最上面的箱子和埋在地里的出口箱子都在画面里。
        stage.look(stage.at(CHAIN_EYE), stage.center(CHAIN_FEED));
        stage.settle();
        stage.shoot(shot);
        stage.showHud();
        LOGGER.info("[炼药截图] {}：{}", shot, describeChain(stage));
    }

    private static void openShot(ClientGameTestContext context, Stage stage, BlockPos block,
                                 Class<? extends AbstractContainerScreen<?>> screen, String shot) {
        stage.look(stage.at(CRATE_EYE), stage.center(block));
        stage.settle();
        stage.use();
        context.waitForScreen(screen);
        context.getInput().setCursorPos(0, 0);
        stage.settle();
        stage.shoot(shot);
        context.setScreen(() -> null);
    }

    /** 自上而下每个容器里有什么，再加比较器的信号。 */
    private static String describeChain(Stage stage) {
        return stage.computeOnServer(server -> {
            StringBuilder text = new StringBuilder();
            for (BlockPos offset : CHAIN) {
                Container container = (Container) server.overworld().getBlockEntity(stage.block(offset));
                text.append(BuiltInRegistries.BLOCK.getKey(server.overworld().getBlockState(stage.block(offset))
                        .getBlock()).getPath()).append(' ');
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty()) {
                        text.append(stack.getCount()).append('×')
                                .append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()).append(' ');
                    }
                }
                text.append("| ");
            }
            var reader = (ComparatorBlockEntity) server.overworld().getBlockEntity(stage.block(CHAIN_READER));
            return text.append("比较器 ").append(reader.getOutputSignal()).toString();
        });
    }

    private static void bitterYellow(ClientGameTestContext context, Stage stage) {
        // 地面上的铁炉座托着巫釜，玩家站在炉南两格，注水、下料和拨灰都点釜身。
        buildStove(stage);
        stage.hideHud();
        stage.look(stage.eye(STOVE_OVERVIEW), stage.center(CAULDRON));
        stage.shoot("brew_01_portable_witch_cauldron");
        stage.showHud();
        faceCauldron(stage);
        useWith(stage, WATER_BOTTLE, "brew_02_water_bottle");
        requireWater(stage, 1);
        useWith(stage, "minecraft:water_bucket", "brew_03_fill_with_bucket");
        requireWater(stage, WITCH_WATER);
        // 主料定药方，底料要在主料之后下。
        useWith(stage, BREWING + "dry_moss", "brew_04_main");
        useWith(stage, "minecraft:glowstone_dust", "brew_05_base");
        useWith(stage, "minecraft:coal", "brew_06_fire_one");
        waitCauldron(stage, "泡足十秒", BREW_TIMEOUT_TICKS, pot -> pot.soakSeconds() >= BrewScoring.SOAK_SECONDS);
        useWith(stage, "minecraft:coal", "brew_07_fire_two");
        waitCauldron(stage, "进入苦黄膏火候带", BREW_TIMEOUT_TICKS,
                pot -> pot.recipe().orElseThrow().heatBand().contains(pot.temperature()));
        cast(stage);
        progressShot(stage, STARTED_SECONDS, "brew_08_started");
        progressShot(stage, ACCESSORY_SECONDS, "brew_09_accessory_window");
        offHand(stage, "minecraft:honey_bottle", "brew_10_accessory_added");
        // 施放中主手还拿着木棍，副手加一块炭升到三档、两秒后拨灰降回二档；三档 93° 只烧两秒，釜温仍在 82–90° 的火候带里。
        offHandAtFire(stage, FIRE_UP_SECONDS, "minecraft:coal", "brew_10b_fire_up_offhand");
        offHandAtFire(stage, FIRE_DOWN_SECONDS, "minecraft:iron_shovel", "brew_10c_fire_down_offhand");
        progressShot(stage, CATALYST_SECONDS, "brew_11_catalyst_window");
        offHand(stage, "minecraft:cobweb", "brew_12_catalyst_added");
        progressShot(stage, ALMOST_DONE_SECONDS, "brew_13_almost_done");
        brewedShot(stage, "brew_14_brewed");
        // 熬成后熬药式还有 1 秒收势，这期间主手仍被锁住；等它收完再拨灰降回一档，釜温落到 79° 以下再舀，收药分拿满。
        stage.pause(RECOVERY_TICKS);
        useWith(stage, "minecraft:iron_shovel", "brew_15_fire_down");
        waitCauldron(stage, "釜温降到可舀", COOL_TIMEOUT_TICKS,
                cauldronState -> cauldronState.temperature() < COLLECT_BELOW_DEGREES);
        useWith(stage, "minecraft:glass_bottle", "brew_16_collect_potion");
        requireEmptyPot(stage);
        potionTooltip(context, stage);
    }

    private static void scrap(Stage stage) {
        // 同样的料，四档火直接开熬、不下辅料引药，熬糊了趁热舀：总分不到 20，舀出来是药渣。
        buildStove(stage);
        faceCauldron(stage);
        useWith(stage, "minecraft:water_bucket", "scrap_01_fill_with_bucket");
        requireWater(stage, WITCH_WATER);
        useWith(stage, BREWING + "dry_moss", "scrap_03_main");
        useWith(stage, "minecraft:glowstone_dust", "scrap_04_base");
        for (int coal = 0; coal < SCORCH_COAL; coal++) {
            stage.hand(MAIN_HAND, "minecraft:coal");
            stage.use();
            stage.settle();
        }
        stage.shoot("scrap_05_fire_four");
        stage.pause(HEAT_TICKS);
        cast(stage);
        progressShot(stage, ACCESSORY_SECONDS, "scrap_06_scorching");
        brewedShot(stage, "scrap_07_brewed");
        // 和苦黄膏一样，等熬药式 1 秒收势结束、主手解锁再舀。
        stage.pause(RECOVERY_TICKS);
        useWith(stage, "minecraft:glass_bottle", "scrap_08_collect_residue");
        requireEmptyPot(stage);
        stage.onServer(server -> {
            var inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
            var residue = BuiltInRegistries.ITEM.getOptional(Identifier.parse(BREWING + "brew_residue")).orElseThrow();
            if (inventory.countItem(residue) != 1) {
                throw new AssertionError("舀完这锅应拿到一份药渣");
            }
        });
    }

    private static void buildStove(Stage stage) {
        stage.clear();
        stage.play();
        stage.command("clear @a");
        stage.place(CAULDRON.below(), BREWING + "iron_stove");
        stage.place(CAULDRON, BREWING + "witch_cauldron");
    }

    private static void faceCauldron(Stage stage) {
        stage.look(stage.eye(STOVE_STAND), stage.center(CAULDRON));
    }

    private static void requireWater(Stage stage, int expected) {
        if (stage.computeOnServer(server -> cauldron(stage, server).waterUnits()) != expected) {
            throw new AssertionError("添水后应有 " + expected + " 份水，实际为 " + describe(stage));
        }
    }

    private static void brewedShot(Stage stage, String shot) {
        waitCauldron(stage, "巫釜熬成一瓶", BREW_TIMEOUT_TICKS,
                pot -> pot.state() == BrewCauldronState.BREWED && pot.remainingBottles() == 1);
        stage.pause(MESSAGE_TICKS);
        stage.shoot(shot);
        log(stage, shot);
    }

    private static void requireEmptyPot(Stage stage) {
        if (stage.computeOnServer(server -> cauldron(stage, server).state()) != BrewCauldronState.EMPTY) {
            throw new AssertionError("舀出唯一一瓶后应当清锅，实际为 " + describe(stage));
        }
    }

    private static void useWith(Stage stage, String item, String shot) {
        // 主手换上这件东西，对着准星右键一下，等提示同步后拍照并记下釜的状态。
        // 换手是服务端命令，要等它同步到客户端再点：不然客户端以为还拿着木棍，会接着替空的副手再点一下。
        stage.hand(MAIN_HAND, item);
        stage.pause(POLL_TICKS);
        stage.use();
        stage.settle();
        stage.shoot(shot);
        log(stage, shot);
    }

    private static void offHand(Stage stage, String item, String shot) {
        // 主手仍拿着起手用的木棍，副手拿料，对着釜右键一下。
        stage.hand(OFF_HAND, item);
        stage.pause(POLL_TICKS);
        stage.use();
        stage.pause(MESSAGE_TICKS);
        stage.shoot(shot);
        log(stage, shot);
    }

    private static void offHandAtFire(Stage stage, double seconds, String item, String shot) {
        // 副手拿炭或铲子点釜，操作会转给釜下的铁炉座。
        waitCauldron(stage, "熬到第 " + seconds + " 秒", BREW_TIMEOUT_TICKS,
                cauldronState -> cauldronState.brewElapsedSeconds() >= seconds);
        stage.hand(OFF_HAND, item);
        stage.pause(POLL_TICKS);
        stage.use();
        stage.pause(POLL_TICKS);
        stage.shoot(shot);
        log(stage, shot);
    }

    private static void progressShot(Stage stage, double seconds, String shot) {
        waitCauldron(stage, "熬到第 " + seconds + " 秒", BREW_TIMEOUT_TICKS,
                cauldronState -> cauldronState.brewElapsedSeconds() >= seconds);
        stage.shoot(shot);
        log(stage, shot);
    }

    private static void cast(Stage stage) {
        // 主手拿木棍对着釜，经功法库公开入口起手；身体版本和式的版本从服务端取，和 GameTest 相同。
        stage.hand(MAIN_HAND, STIRRER);
        stage.settle();
        stage.onServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            var definition = TechniqueFabric.service().registry()
                    .findLatest(BrewingTechniqueContract.TECHNIQUE_ID)
                    .orElseThrow();
            TechniqueReference reference = TechniqueReference.newBuilder()
                    .setId(definition.getId())
                    .setVersion(definition.getVersion())
                    .build();
            CastRequest request = CastRequest.newBuilder()
                    .setCastId("clienttest_brew_" + UUID.randomUUID())
                    .setBodyId(VanillaDamageFabric.inspectBody(player).orElseThrow().getId())
                    .setTechnique(reference)
                    .build();
            CastOutcome outcome = TechniqueFabric.service().cast(player.getUUID(), request);
            if (outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
                throw new AssertionError("熬药式起手失败：" + outcome.getReason());
            }
        });
    }

    private static void waitCauldron(Stage stage, String what, int timeoutTicks, Predicate<BrewCauldron> done) {
        for (int waited = 0; waited < timeoutTicks; waited += POLL_TICKS) {
            if (stage.computeOnServer(server -> done.test(cauldron(stage, server)))) {
                return;
            }
            stage.pause(POLL_TICKS);
        }
        throw new AssertionError("等「" + what + "」超时：" + describe(stage));
    }

    private static BrewCauldron cauldron(Stage stage, MinecraftServer server) {
        return cauldronEntity(stage, server).cauldron();
    }

    private static BrewCauldronBlockEntity cauldronEntity(Stage stage, MinecraftServer server) {
        return (BrewCauldronBlockEntity) server.overworld().getBlockEntity(stage.block(CAULDRON));
    }

    private static void log(Stage stage, String shot) {
        LOGGER.info("[炼药截图] {}：{}", shot, describe(stage));
    }

    private static String describe(Stage stage) {
        return stage.computeOnServer(server -> {
            BrewCauldronBlockEntity entity = cauldronEntity(stage, server);
            BrewCauldron cauldron = entity.cauldron();
            var fire = (BrewFireBlockEntity) server.overworld().getBlockEntity(stage.block(CAULDRON).below());
            String qi = entity.qiTally().map(BrewPlayScenes::describeQi).orElse("无");
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            return String.format(Locale.ROOT, "%s，%.1f°，%d 份水，熬了 %.1f 秒，料 %s，焦 %d 次；火 %d 档；耗元 %s；主手 %s，副手 %s",
                    cauldron.state(), cauldron.temperature(), cauldron.waterUnits(), cauldron.brewElapsedSeconds(),
                    cauldron.ingredients().keySet(), cauldron.scorchCount(), fire.fire().level(), qi,
                    player.getMainHandItem(), player.getOffhandItem());
        });
    }

    private static String describeQi(BrewQiTally qi) {
        return String.format(Locale.ROOT, "%.2f/%.2f（环境 %.2f · 自身 %.2f）", qi.paid(), qi.total(), qi.ambient(), qi.self());
    }

    private static void potionTooltip(ClientGameTestContext context, Stage stage) {
        // 光标指着刚舀出的苦黄膏，看提示里的品质和这一档药效。生存背包的界面被 UI 附属改了布局，
        // 这里用创造物品栏的「物品栏」页，同一份物品、原版布局，格子坐标好算。
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitForScreen(CreativeModeInventoryScreen.class);
        selectTab(context, BuiltInRegistries.CREATIVE_MODE_TAB.getValueOrThrow(CreativeModeTabs.INVENTORY));
        hover(context, CREATIVE_WIDTH, CREATIVE_HEIGHT, stack -> stack.is(Items.POTION)
                && BrewItemStacks.isBrew(stack) && BrewItemStacks.kind(stack) == BrewKind.BITTER_YELLOW);
        stage.settle();
        stage.shoot("brew_17_potion_tooltip");
        context.setScreen(() -> null);
    }

    private static void creativePotions(ClientGameTestContext context, Stage stage) {
        // 炼药页滚到底：28 瓶带品质的巫药排在器具和药材后面；光标指一瓶喷溅药看提示。
        stage.clear();
        stage.play();
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitForScreen(CreativeModeInventoryScreen.class);
        selectTab(context, BuiltInRegistries.CREATIVE_MODE_TAB.getOptional(CREATIVE_TAB).orElseThrow());
        context.getInput().setCursorPos(0, 0);
        context.getInput().scroll(SCROLL_TO_END);
        stage.settle();
        stage.shoot("creative_tab_potions");
        hover(context, CREATIVE_WIDTH, CREATIVE_HEIGHT, stack -> stack.is(Items.SPLASH_POTION));
        stage.settle();
        stage.shoot("creative_tab_tooltip_potion");
        context.setScreen(() -> null);
    }

    private static void effects(ClientGameTestContext context, Stage stage) {
        // 七个状态效果一起给上：屏幕右上一排图标；背包旁边放大界面缩放，图标后面写名字和剩余时间。
        // 命令不带隐藏粒子那一项：原版隐藏粒子时也不在屏幕右上画图标。
        stage.clear();
        stage.play();
        stage.command("gamemode survival @a");
        for (String effect : EFFECTS) {
            stage.command("effect give @a " + BREWING + effect + " " + EFFECT_SECONDS);
        }
        stage.settle();
        stage.shoot("effects_hud");
        int defaultScale = stage.guiScale(WIDE_GUI_SCALE);
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitForScreen(InventoryScreen.class);
        context.getInput().setCursorPos(0, 0);
        stage.settle();
        stage.shoot("effects_inventory");
        context.setScreen(() -> null);
        stage.guiScale(defaultScale);
        stage.command("effect clear @a");
        stage.command("gamemode creative @a");
    }

    private static void selectTab(ClientGameTestContext context, CreativeModeTab tab) {
        boolean selected = context.computeOnClient(
                client -> ((FabricCreativeModeInventoryScreen) client.gui.screen()).setSelectedTab(tab));
        if (!selected) {
            throw new AssertionError("创造物品栏没有切到 " + tab.getDisplayName().getString());
        }
    }

    private static void hover(ClientGameTestContext context, int width, int height, Predicate<ItemStack> wanted) {
        // 光标移到界面里第一格符合条件的物品中间。格子坐标相对界面左上角，界面在窗口里居中，换算成窗口坐标。
        Vec2 cursor = context.computeOnClient(client -> {
            Window window = client.getWindow();
            var screen = (AbstractContainerScreen<?>) client.gui.screen();
            Slot slot = screen.getMenu().slots.stream()
                    .filter(candidate -> wanted.test(candidate.getItem()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("界面里没有要指的物品"));
            float scale = (float) window.getScreenWidth() / window.getGuiScaledWidth();
            int left = (window.getGuiScaledWidth() - width) / 2;
            int top = (window.getGuiScaledHeight() - height) / 2;
            return new Vec2((left + slot.x + SLOT_CENTER) * scale, (top + slot.y + SLOT_CENTER) * scale);
        });
        context.getInput().setCursorPos(cursor.x, cursor.y);
    }
}
