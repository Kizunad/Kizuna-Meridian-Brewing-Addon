package dev.kizuna.meridian.brewing.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 三阶自动药塔的规则：一口容量 8 的虚拟釜，火室四格燃料，六只木桶当料库，一只铜箱子出药。宿主每秒调一次
 * {@link #second}，结构不对时改调 {@link #broken}；读写世界都经过 {@link TowerWorld}。
 *
 * <p>一批 8 瓶、两套料：虚拟釜照常只收一套，另一套开工时一起扣下。塔自己控火：泡料 1 档；开熬后温度还没到火带下限
 * 就开 4 档，到了回到药方的稳定档（平衡温度连波动都落在火带里的最低档）；熬好降回 1 档，水温降到能收药再装瓶。
 * 辅料、引药在各自时窗正中取。真元只取水池所在区域的环境真元，欠付超过宽限就中断这一锅，等真元够了重新开熬。
 * 出药品质压到配置的上限。</p>
 */
public final class BrewTowerController {
    public static final int FUEL_CELLS = 4;
    public static final int SETS_PER_BATCH = 2;
    public static final String GLASS_BOTTLE_ID = "minecraft:glass_bottle";
    private static final int BOTTLES = VesselKind.TOWER.waterCapacity();
    /** 开工前要在木桶里查的料，按查的顺序。 */
    private static final List<TowerNeed> STOCKED_NEEDS = List.of(TowerNeed.PRIMARY, TowerNeed.BASE,
            TowerNeed.PROPELLANT, TowerNeed.ACCESSORY, TowerNeed.GLASS);
    private static final List<String> SOUL_STARTERS = List.of("minecraft:soul_sand", "minecraft:soul_soil");
    private static final List<String> SUN_STARTERS = List.of("minecraft:sand");
    /** 开熬以后才下锅的两味：取的时候容器已经还了，所以退料时算用掉，不退（复审第 5 条）。 */
    private static final Set<IngredientSlot> LATE_SLOTS = EnumSet.of(IngredientSlot.ACCESSORY, IngredientSlot.CATALYST);
    private static final int SOAK_LEVEL = 1;
    private static final int HOLD_LEVEL = 1;
    private static final int BOOST_LEVEL = 4;
    private static final int TOP_LEVEL = 4;
    private static final double STEP_SECONDS = 1.0;
    private static final int FAILURE_FLASH_SECONDS = 6;
    private static final double LATE_HALF_SECONDS = BrewCauldron.BREW_SECONDS / 2;
    private static final double TOLERANCE = 1e-9;
    private static final int LAMPS_NONE = 0b0000;
    private static final int LAMPS_ONE = 0b0001;
    private static final int LAMPS_TWO = 0b0011;
    private static final int LAMPS_THREE = 0b0111;
    private static final int LAMPS_ALL = 0b1111;
    private static final int LAMPS_DIAGONAL = 0b1001;
    private static final int LAMPS_OTHER_DIAGONAL = 0b0110;

    private final BrewTowerSettings settings;
    private final BrewQiSettings qiSettings;
    private final FuelCell[] fuel = new FuelCell[FUEL_CELLS];
    private final List<IngredientBatch> reserve = new ArrayList<>();
    private final EnumMap<TowerNeed, BrewText> needs = new EnumMap<>(TowerNeed.class);
    private int fuelWanted;
    private BrewKind selected;
    private UUID chooser;
    private BrewForm form = BrewForm.DRINK;
    private boolean continuous;
    private boolean armed;
    private TowerPhase phase = TowerPhase.IDLE;
    private BrewCauldron cauldron = emptyCauldron();
    private FireKind fire = FireKind.SUN_FIRE;
    private BrewQiTally qi;
    private long serial;
    private int flashSeconds;
    private long clock;
    private TowerStatus status = TowerStatus.IDLE;
    private int level;
    private BrewText notice;

    public BrewTowerController(BrewTowerSettings settings, BrewQiSettings qiSettings) {
        // 新认出来的塔：没选药方、阳火、四格燃料都没点着。
        if (settings == null || qiSettings == null) {
            throw new IllegalArgumentException("药塔配置不能为空");
        }
        this.settings = settings;
        this.qiSettings = qiSettings;
        Arrays.fill(fuel, FuelCell.UNLIT);
    }

    /** 读档：照记录恢复；显示用的状态、档数下一秒重算。 */
    public static BrewTowerController restore(BrewTowerSettings settings, BrewQiSettings qiSettings,
                                              BrewTowerRecord record) {
        var controller = new BrewTowerController(settings, qiSettings);
        controller.selected = record.selected();
        controller.chooser = record.chooser();
        controller.form = record.form();
        controller.continuous = record.continuous();
        controller.armed = record.armed();
        controller.phase = record.phase();
        controller.cauldron.restore(record.cauldron());
        controller.fire = record.fire();
        record.fuel().toArray(controller.fuel);
        controller.reserve.addAll(record.reserve());
        controller.qi = record.qi();
        controller.serial = record.serial();
        controller.flashSeconds = record.flashSeconds();
        return controller;
    }

    public BrewTowerRecord record() {
        // 显示用的状态不存，读档后一秒内重算。
        return new BrewTowerRecord(selected, chooser, form, continuous, armed, phase, cauldron.snapshot(), fire,
                List.of(fuel), reserve, qi, serial, flashSeconds);
    }

    /** 选药方并让塔干活；选的人记下来。正在熬的这一批不受影响，下一批才换。 */
    public void select(BrewKind kind, UUID chooser) {
        if (kind == null || chooser == null) {
            throw new IllegalArgumentException("选药方要给药方和选的人");
        }
        if (kind != selected) {
            dropStartNeeds();
        }
        selected = kind;
        this.chooser = chooser;
        armed = true;
    }

    /** 喝还是泼；药方只支持一种时开工按药方来。 */
    public void chooseForm(BrewForm chosen) {
        if (chosen == null) {
            throw new IllegalArgumentException("形态不能为空");
        }
        if (chosen != form) {
            dropStartNeeds();
        }
        form = chosen;
    }

    private void dropStartNeeds() {
        // 没开工时在等的料是按上一张药方、上一个形态查的，换了就不作数，下一秒按新的重查；
        // 窗口这一秒里不能还读到上一张药方的缺料。开了工以后等的是这一批的燃料、真元、箱子和瓶子，和选什么无关。
        if (phase == TowerPhase.IDLE) {
            clearNeeds();
        }
    }

    private void clearNeeds() {
        // 缺料和「缺燃料缺的是几档」一起清，窗口不会读到没有缺项的档数。
        needs.clear();
        fuelWanted = 0;
    }

    private void needFuel(int wanted) {
        // 缺燃料那句话和同步给窗口的档数是同一个数。
        needs.put(TowerNeed.FUEL, BrewText.message("tower.need.fuel", wanted));
        fuelWanted = wanted;
    }

    /** 收完一批接着开下一批。 */
    public void runContinuously() {
        continuous = true;
    }

    /** 收完这一批就停。 */
    public void runOnce() {
        continuous = false;
    }

    /** 停：泡料阶段的料全退回木桶；熬制中的这锅作废；熬好的照收完。 */
    public void stop(TowerWorld world) {
        armed = false;
        if (phase == TowerPhase.SOAKING) {
            refund(world);
            notice = BrewText.message("tower.stopped");
        } else if (phase == TowerPhase.BREWING) {
            discardBatch();
            notice = BrewText.message("tower.stopped");
        }
    }

    /** 结构有效时每秒一步：先认掉被人拿走的燃料，再按阶段推进，最后写铜灯。 */
    public void second(TowerWorld world) {
        clock++;
        clearNeeds();
        dropRemovedFuel(world);
        switch (phase) {
            case IDLE -> idle(world);
            case SOAKING -> soak(world);
            case BREWING -> brew(world);
            case BREWED -> collect(world);
        }
        if (!needs.isEmpty()) {
            status = TowerStatus.WAITING;
        }
        finishSecond(world);
    }

    /**
     * 结构不对时每秒一步：泡料阶段的料退回木桶，熬制中的这批算失败，熬好的留着等结构修好再收；
     * 燃料格剩的秒数留着，被拿走的燃料照样认掉。
     */
    public void broken(TowerWorld world, BrewText reason) {
        clock++;
        clearNeeds();
        level = 0;
        dropRemovedFuel(world);
        if (phase == TowerPhase.SOAKING) {
            refund(world);
        } else if (phase == TowerPhase.BREWING) {
            fail(reason);
        }
        status = TowerStatus.BROKEN;
        if (flashSeconds > 0) {
            status = TowerStatus.FAILED;
        }
        notice = reason;
        finishSecond(world);
    }

    /**
     * 结构不对、锅里没东西、灯闪完了、火室里也没有点着的燃料：宿主可以删掉这条记录，下次右键再重新认。
     * 还有点着的燃料就留着记录，免得重新认塔时把烧剩的燃料又当成新的点满（复审第 1 条）。
     */
    public boolean disposable() {
        // 点着的燃料被拿走后 broken 每秒会认掉，那时记录才能删。
        boolean anyLit = Arrays.stream(fuel).anyMatch(FuelCell::lit);
        return phase == TowerPhase.IDLE && flashSeconds == 0 && !anyLit;
    }

    /** 火室第 {@code cell} 格是不是点着的；宿主拿它判断拆下、舀起的燃料还给不给。 */
    public boolean fuelLit(int cell) {
        // 宿主拆方块、舀熔岩前问这一格还在不在烧。
        return fuel[cell].lit();
    }

    public TowerStatus status() {
        return status;
    }

    public TowerPhase phase() {
        return phase;
    }

    /** 窗口和动作栏显示的那一句：在等的第一样，或者失败、结构不对、停下的原因。 */
    public Optional<BrewText> problem() {
        if (!needs.isEmpty()) {
            return Optional.of(needs.values().iterator().next());
        }
        return Optional.ofNullable(notice);
    }

    /** 在等的每一样，按开工检查的顺序。 */
    public Map<TowerNeed, BrewText> needs() {
        // 拷一份只读的，EnumMap 保住开工检查的顺序。
        return Collections.unmodifiableMap(new EnumMap<>(needs));
    }

    /**
     * 在等燃料时等的是几档：开工前是选好药方的稳定档数，开工以后是这一秒要烧的档数（泡料、保温 1 档，
     * 升温猛火，熬制回稳定档），和 {@link #needs()} 里那句缺燃料说的是同一个数；没在等燃料时是 0。
     */
    public int fuelWanted() {
        return fuelWanted;
    }

    public Optional<BrewKind> selected() {
        return Optional.ofNullable(selected);
    }

    public Optional<UUID> chooser() {
        return Optional.ofNullable(chooser);
    }

    public BrewForm form() {
        return form;
    }

    public boolean continuous() {
        return continuous;
    }

    public boolean armed() {
        return armed;
    }

    public FireKind fire() {
        return fire;
    }

    /** 这一秒实际烧着几格。 */
    public int level() {
        return level;
    }

    public double temperature() {
        return cauldron.temperature();
    }

    public double brewElapsedSeconds() {
        return cauldron.brewElapsedSeconds();
    }

    /** 正在熬的药方，没在熬时是选好的药方。 */
    public Optional<BrewKind> batchKind() {
        Optional<BrewRecipe> recipe = cauldron.recipe();
        if (recipe.isPresent()) {
            return Optional.of(recipe.orElseThrow().kind());
        }
        return selected();
    }

    /** 这一批能出几瓶：熬好后是还剩几瓶，泡料、熬制时按锅里的水算（高温蒸发会少），没开工时是 0。 */
    public int bottles() {
        // 每份水出一瓶，和虚拟釜收药时的瓶数同一个来源。
        return switch (phase) {
            case IDLE -> 0;
            case SOAKING, BREWING -> cauldron.waterUnits();
            case BREWED -> cauldron.remainingBottles();
        };
    }

    /** 点着的几格一共还能烧几秒。 */
    public double litSeconds() {
        double seconds = 0;
        for (FuelCell cell : fuel) {
            seconds += cell.remainingSeconds();
        }
        return seconds;
    }

    /** 这一锅的真元付了几成；没在熬时是 0。 */
    public double qiPaidFraction() {
        if (qi == null || qi.total() <= TOLERANCE) {
            return 0;
        }
        return Math.min(1, qi.paid() / qi.total());
    }

    /** 落在药方火带里的最低档：平衡温度加减波动都不出火带。 */
    public static int stableLevel(BrewRecipe recipe) {
        HeatBand band = recipe.heatBand();
        for (int candidate = 1; candidate <= TOP_LEVEL; candidate++) {
            HeatSourceKind stove = HeatSourceKind.stove(recipe.fireKind(), candidate);
            double low = stove.equilibriumTemperature() - stove.fluctuationDegrees();
            double high = stove.equilibriumTemperature() + stove.fluctuationDegrees();
            if (band.contains(low) && band.contains(high)) {
                return candidate;
            }
        }
        return TOP_LEVEL;
    }

    private void idle(TowerWorld world) {
        // 失败后先闪完灯；没让塔干活就空闲；开工条件缺一样就等，齐了就开工。
        level = 0;
        if (flashSeconds > 0) {
            status = TowerStatus.FAILED;
            return;
        }
        if (!armed) {
            status = TowerStatus.IDLE;
            return;
        }
        notice = null;
        checkStart(world, BrewRecipes.require(selected));
        if (needs.isEmpty()) {
            startBatch(world);
        }
    }

    private void checkStart(TowerWorld world, BrewRecipe recipe) {
        // 开工前一次查齐：铜箱子空格、木桶里的料和玻璃瓶、燃料、火种、环境真元；缺的全记下，窗口一起显示。
        if (world.chestFreeSlots() < BOTTLES) {
            needs.put(TowerNeed.CHEST, BrewText.message("tower.need.chest", BOTTLES));
        }
        for (Requirement requirement : requirements(recipe, form)) {
            if (world.count(requirement.itemId()) < requirement.count()) {
                needs.put(requirement.need(), itemNeed(world, requirement.itemId(), requirement.count()));
            }
        }
        int stable = stableLevel(recipe);
        if (availableFuel(world) < stable) {
            needFuel(stable);
        }
        if (recipe.fireKind() != fire && starter(world, recipe.fireKind()).isEmpty()) {
            String starterKey = "tower.need.starter." + recipe.fireKind().name().toLowerCase(Locale.ROOT);
            needs.put(TowerNeed.FIRE_STARTER, BrewText.message(starterKey));
        }
        ambientProblem(world).ifPresent(problem -> needs.put(TowerNeed.AMBIENT_QI, problem));
    }

    /** 开工时木桶里要有的东西；{@code chosen} 是窗口里选的形态，只能喝或只能泼的药方不看它。 */
    public static List<Requirement> requirements(BrewRecipe recipe, BrewForm chosen) {
        // 两套主料、底料、泼型的火药、辅料，以及收药用的玻璃瓶；引药缺了照熬，只是少分。
        boolean splash = formFor(recipe.kind(), chosen) == BrewForm.SPLASH;
        var required = new ArrayList<Requirement>();
        for (TowerNeed need : STOCKED_NEEDS) {
            if (need != TowerNeed.PROPELLANT || splash) {
                required.add(stocked(recipe, need).orElseThrow());
            }
        }
        return required;
    }

    /**
     * 木桶里的这一样料是什么、一批要几件，不看形态；不是木桶里的料（箱子、燃料、火种、真元）返回空。
     * 窗口画缺料图标用这张表：缺料掩码和选中的药方、形态是分开同步的，可能对不上，所以每一样都要单独查得到。
     */
    public static Optional<Requirement> stocked(BrewRecipe recipe, TowerNeed need) {
        IngredientPlan plan = recipe.ingredients();
        return switch (need) {
            case PRIMARY -> Optional.of(new Requirement(need, plan.preparedPrimary(), SETS_PER_BATCH));
            case BASE -> Optional.of(new Requirement(need, BrewCauldron.BASE_INGREDIENT_ID, SETS_PER_BATCH));
            case PROPELLANT -> Optional.of(new Requirement(need, BrewCauldron.PROPELLANT_INGREDIENT_ID, SETS_PER_BATCH));
            case ACCESSORY -> Optional.of(new Requirement(need, plan.accessory(), SETS_PER_BATCH));
            case GLASS -> Optional.of(new Requirement(need, GLASS_BOTTLE_ID, BOTTLES));
            case CHEST, FUEL, FIRE_STARTER, AMBIENT_QI -> Optional.empty();
        };
    }

    private void startBatch(TowerWorld world) {
        // 开工：要换火种先扣那一块；加满水，两套主料、底料（泼型加火药）各取两份，一份下锅、一份留作第二套。
        BrewRecipe recipe = BrewRecipes.require(selected);
        switchFire(world, recipe.fireKind());
        cauldron = emptyCauldron();
        cauldron.addWater(BOTTLES);
        reserve.clear();
        load(world, IngredientSlot.PRIMARY, recipe.ingredients().preparedPrimary());
        load(world, IngredientSlot.BASE, BrewCauldron.BASE_INGREDIENT_ID);
        if (formFor(recipe.kind(), form) == BrewForm.SPLASH) {
            load(world, IngredientSlot.PROPELLANT, BrewCauldron.PROPELLANT_INGREDIENT_ID);
        }
        phase = TowerPhase.SOAKING;
        status = TowerStatus.SOAKING;
    }

    private void switchFire(TowerWorld world, FireKind wanted) {
        // 同种火连着熬不再扣；换火种从木桶扣一块灵魂沙、灵魂土或沙子，和往火盆里加沙子是同一条规则。
        if (fire == wanted) {
            return;
        }
        world.take(starter(world, wanted).orElseThrow(), 1);
        fire = wanted;
    }

    private void load(TowerWorld world, IngredientSlot slot, String itemId) {
        // 开工前刚查过够数；取到的料下不进虚拟釜说明两边规则对不上，直接报错。
        List<IngredientBatch> taken = world.take(itemId, SETS_PER_BATCH);
        BrewActionResult added = cauldron.addIngredient(slot, taken.getFirst());
        if (!added.accepted()) {
            throw new IllegalStateException("药塔取到的料下不进虚拟釜：" + itemId);
        }
        reserve.addAll(taken.subList(1, taken.size()));
    }

    private void soak(TowerWorld world) {
        // 1 档泡料；泡够、环境真元也够就开熬并开一本新账，第二套料到这里才算用掉。
        warm(world, SOAK_LEVEL);
        status = TowerStatus.SOAKING;
        if (cauldron.soakSeconds() < BrewScoring.SOAK_SECONDS) {
            return;
        }
        Optional<BrewText> qiProblem = ambientProblem(world);
        if (qiProblem.isPresent()) {
            needs.put(TowerNeed.AMBIENT_QI, qiProblem.orElseThrow());
            return;
        }
        BrewActionResult started = cauldron.startBrew(BrewStructure.built(heatSource()));
        if (!started.accepted()) {
            needs.put(TowerNeed.FUEL, started.message());
            return;
        }
        qi = BrewQiTally.start(qiSettings.brewCost(VesselKind.TOWER), qiSettings.graceSeconds());
        serial++;
        reserve.clear();
        phase = TowerPhase.BREWING;
    }

    private void brew(TowerWorld world) {
        // 没到火带下限开猛火，到了回稳定档；推进一秒后到时窗正中就取辅料、引药，再付这一秒的真元。
        BrewRecipe recipe = cauldron.recipe().orElseThrow();
        boolean heating = cauldron.temperature() < recipe.heatBand().minimumDegrees();
        int wanted;
        if (heating) {
            wanted = BOOST_LEVEL;
        } else {
            wanted = stableLevel(recipe);
        }
        warm(world, wanted);
        if (cauldron.state() == BrewCauldronState.SCORCHED) {
            fail(BrewText.message("tower.failed.scorched"));
            return;
        }
        IngredientPlan plan = recipe.ingredients();
        addLate(world, IngredientSlot.ACCESSORY, plan.accessory(), plan.accessoryWindow());
        addLate(world, IngredientSlot.CATALYST, plan.catalyst(), plan.catalystWindow());
        payQi(world);
        if (phase != TowerPhase.BREWING) {
            return;
        }
        if (cauldron.state() == BrewCauldronState.BREWED) {
            phase = TowerPhase.BREWED;
            status = TowerStatus.BREWED;
            return;
        }
        status = heating ? TowerStatus.HEATING : TowerStatus.BREWING;
    }

    private void addLate(TowerWorld world, IngredientSlot slot, String itemId, TimingWindow window) {
        // 时窗正中取两份（一份下锅、一份算第二套）；正中那一秒没货就一直试到时窗结束，过了就跳过这一味。
        if (cauldron.ingredients().containsKey(slot)) {
            return;
        }
        double elapsed = cauldron.brewElapsedSeconds();
        double midpoint = (window.startSeconds() + window.endSeconds()) / 2;
        if (elapsed < midpoint || elapsed > window.endSeconds() || world.count(itemId) < SETS_PER_BATCH) {
            return;
        }
        List<IngredientBatch> taken = world.take(itemId, SETS_PER_BATCH);
        cauldron.addIngredient(slot, taken.getFirst());
    }

    private void payQi(TowerWorld world) {
        // 这一秒该付的（含前面欠的）只向环境取；欠付超过宽限就中断这一锅，回到泡好待熬，等真元够了重新开熬。
        double payment = qi.nextPayment();
        double drawn = 0;
        if (payment > TOLERANCE) {
            drawn = world.drawAmbient(serial + ":" + qi.seconds(), payment);
        }
        qi = qi.pay(drawn, 0);
        if (!qi.beyondGrace()) {
            return;
        }
        cauldron.interruptBrew();
        qi = null;
        phase = TowerPhase.SOAKING;
        needs.put(TowerNeed.AMBIENT_QI, BrewText.message("tower.qi.interrupted"));
    }

    private void collect(TowerWorld world) {
        // 熬好降回 1 档，水温降到能收药再装瓶；铜箱子空格、玻璃瓶都够才一次装完，差一样就保温等。
        warm(world, HOLD_LEVEL);
        status = TowerStatus.BREWED;
        if (cauldron.temperature() > BrewCauldron.COLLECTION_MAX_TEMPERATURE) {
            return;
        }
        int bottles = cauldron.remainingBottles();
        if (world.chestFreeSlots() < bottles) {
            needs.put(TowerNeed.CHEST, BrewText.message("tower.need.chest", bottles));
        }
        if (world.count(GLASS_BOTTLE_ID) < bottles) {
            needs.put(TowerNeed.GLASS, itemNeed(world, GLASS_BOTTLE_ID, bottles));
        }
        if (needs.containsKey(TowerNeed.CHEST) || needs.containsKey(TowerNeed.GLASS)) {
            return;
        }
        world.take(GLASS_BOTTLE_ID, bottles);
        bottleAll(world, bottles);
        qi = null;
        phase = TowerPhase.IDLE;
        if (!continuous) {
            armed = false;
        }
    }

    private void bottleAll(TowerWorld world, int bottles) {
        // 舀完这一锅；成药品质压到上限，分数不到药渣线的出药渣。最后一瓶舀完虚拟釜自己清空。
        BrewKind kind = cauldron.recipe().orElseThrow().kind();
        BrewForm bottled = cauldron.form();
        for (int bottle = 0; bottle < bottles; bottle++) {
            BrewCollectionResult result = cauldron.collectBottle(world.gameDay());
            if (result.kind() == BrewCollectionKind.POTION) {
                BrewQuality quality = result.score().orElseThrow().quality().orElseThrow();
                world.deliverPotion(kind, settings.cap(quality), bottled);
            } else if (result.kind() == BrewCollectionKind.SCRAP) {
                world.deliverResidue();
            }
        }
    }

    private void warm(TowerWorld world, int wanted) {
        // 烧够 wanted 格（不够从木桶补），虚拟釜按实际烧着的档数推进一秒；档数不够就记缺燃料。
        level = burn(world, wanted);
        if (level < wanted) {
            needFuel(wanted);
        }
        cauldron.tick(STEP_SECONDS, BrewStructure.built(heatSource()));
    }

    private int burn(TowerWorld world, int wanted) {
        // 剩得最少的格先烧，烧了一半的不浪费；没被选上的点着的格这一秒不烧，秒数留着。烧完的格变空气。
        lightCells(world, wanted);
        List<Integer> lit = new ArrayList<>();
        for (int cell = 0; cell < FUEL_CELLS; cell++) {
            if (fuel[cell].lit()) {
                lit.add(cell);
            }
        }
        lit.sort(Comparator.comparingDouble(cell -> fuel[cell].remainingSeconds()));
        int burning = Math.min(wanted, lit.size());
        for (int index = 0; index < burning; index++) {
            int cell = lit.get(index);
            fuel[cell] = fuel[cell].burn(STEP_SECONDS);
            if (!fuel[cell].lit()) {
                world.burnOut(cell);
            }
        }
        return burning;
    }

    private void lightCells(TowerWorld world, int wanted) {
        // 先点已经放着燃料的格，还不够再从木桶往空格里补（自动补燃料）。
        int lit = 0;
        for (FuelCell cell : fuel) {
            if (cell.lit()) {
                lit++;
            }
        }
        for (int cell = 0; cell < FUEL_CELLS && lit < wanted; cell++) {
            Optional<TowerFuel> placed = Optional.empty();
            if (!fuel[cell].lit()) {
                placed = world.fuelAt(cell);
            }
            if (placed.isPresent()) {
                fuel[cell] = ignite(placed.orElseThrow());
                lit++;
            }
        }
        for (int cell = 0; cell < FUEL_CELLS && lit < wanted; cell++) {
            Optional<TowerFuel> added = Optional.empty();
            if (!fuel[cell].lit()) {
                added = world.refuel(cell);
            }
            if (added.isPresent()) {
                fuel[cell] = ignite(added.orElseThrow());
                lit++;
            }
        }
    }

    private FuelCell ignite(TowerFuel placed) {
        return new FuelCell(placed, settings.secondsFor(placed));
    }

    private void dropRemovedFuel(TowerWorld world) {
        // 点着的煤炭块、原木、熔岩被人挖走就灭；火在点着时就记了秒数，之后火没了不影响。
        for (int cell = 0; cell < FUEL_CELLS; cell++) {
            FuelCell current = fuel[cell];
            if (current.lit() && current.fuel() != TowerFuel.FIRE
                    && !world.fuelAt(cell).equals(Optional.of(current.fuel()))) {
                fuel[cell] = FuelCell.UNLIT;
            }
        }
    }

    private int availableFuel(TowerWorld world) {
        // 能点着的格数：点着的、放着燃料的，加上木桶里的燃料能补进几个空格。
        int ready = 0;
        int empty = 0;
        for (int cell = 0; cell < FUEL_CELLS; cell++) {
            if (fuel[cell].lit() || world.fuelAt(cell).isPresent()) {
                ready++;
            } else {
                empty++;
            }
        }
        return ready + Math.min(empty, world.barrelFuel());
    }

    /** 换成 {@code wanted} 这种火要扣的东西，按先后：阴火是灵魂沙、灵魂土，阳火是沙子。 */
    public static List<String> starters(FireKind wanted) {
        // 和往火盆里加沙子、灵魂沙是同一套火种。
        if (wanted == FireKind.SOUL_FIRE) {
            return SOUL_STARTERS;
        }
        return SUN_STARTERS;
    }

    private Optional<String> starter(TowerWorld world, FireKind wanted) {
        // 木桶里先有哪种用哪种。
        for (String candidate : starters(wanted)) {
            if (world.count(candidate) > 0) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Optional<BrewText> ambientProblem(TowerWorld world) {
        // 关掉耗元时不查；否则要水池这里的环境估计够一整批。
        double total = qiSettings.brewCost(VesselKind.TOWER);
        if (total <= TOLERANCE) {
            return Optional.empty();
        }
        return world.ambientProblem(total);
    }

    /** 这一批出什么形态：只能泼的药方一定泼，只能喝的一定喝，两样都行的按 {@code chosen}。 */
    public static BrewForm formFor(BrewKind kind, BrewForm chosen) {
        // 和手熬时 BrewCauldron 认形态的规则一致。
        if (!kind.supports(BrewUse.DRINK)) {
            return BrewForm.SPLASH;
        }
        if (!kind.supports(BrewUse.SPLASH)) {
            return BrewForm.DRINK;
        }
        return chosen;
    }

    private void refund(TowerWorld world) {
        // 还在泡的料（锅里一套、留着的第二套）退回木桶。断供回到泡料时锅里还有开熬后下的辅料、引药：
        // 它们取出时蜂蜜瓶、龙息的空瓶已经还了，再整瓶退回就多出一个玻璃瓶，所以算用掉。
        for (Map.Entry<IngredientSlot, IngredientBatch> entry : cauldron.ingredients().entrySet()) {
            if (!LATE_SLOTS.contains(entry.getKey())) {
                world.putBack(entry.getValue());
            }
        }
        for (IngredientBatch batch : reserve) {
            world.putBack(batch);
        }
        discardBatch();
    }

    private void fail(BrewText reason) {
        // 这一批作废，没有产出，铜灯一起闪几下；单批就停。
        discardBatch();
        flashSeconds = FAILURE_FLASH_SECONDS;
        notice = reason;
        status = TowerStatus.FAILED;
        if (!continuous) {
            armed = false;
        }
    }

    private void discardBatch() {
        cauldron = emptyCauldron();
        reserve.clear();
        qi = null;
        phase = TowerPhase.IDLE;
    }

    private void finishSecond(TowerWorld world) {
        // 铜灯按这一秒的状态写；失败闪灯数完就停。
        world.showLamps(lamps());
        if (flashSeconds > 0) {
            flashSeconds--;
        }
    }

    private int lamps() {
        // 失败时四盏一起闪；在等东西两组对角交替闪；其余按进度亮几盏。
        if (flashSeconds > 0) {
            return flashSeconds % 2 == 0 ? LAMPS_ALL : LAMPS_NONE;
        }
        return switch (status) {
            case IDLE, FAILED, BROKEN -> LAMPS_NONE;
            case WAITING -> clock % 2 == 0 ? LAMPS_DIAGONAL : LAMPS_OTHER_DIAGONAL;
            case SOAKING, HEATING -> LAMPS_ONE;
            case BREWING -> cauldron.brewElapsedSeconds() < LATE_HALF_SECONDS ? LAMPS_TWO : LAMPS_THREE;
            case BREWED -> LAMPS_ALL;
        };
    }

    private HeatSourceKind heatSource() {
        return HeatSourceKind.stove(fire, level);
    }

    private BrewText itemNeed(TowerWorld world, String itemId, int count) {
        return BrewText.message("tower.need.item", new BrewText.ItemName(world.itemId(itemId)), count);
    }

    private static BrewCauldron emptyCauldron() {
        return new BrewCauldron(VesselKind.TOWER, BrewForm.DRINK);
    }

    /** 开工时木桶里要有的一样东西：缺了记成哪一项、药方里写的料名、几件。 */
    public record Requirement(TowerNeed need, String itemId, int count) {
    }
}
