package dev.kizuna.meridian.brewing.server;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 巫釜的纯服务端状态机；Fabric 方块实体只负责把世界状态转换成快照。 */
public final class BrewCauldron {
    public static final String BASE_INGREDIENT_ID = "minecraft:glowstone_dust";
    public static final String PROPELLANT_INGREDIENT_ID = "minecraft:gunpowder";
    public static final double MIN_SOAK_TEMPERATURE = 40.0;
    public static final double MAX_SOAK_TEMPERATURE = 79.0;
    public static final double COLLECTION_MAX_TEMPERATURE = 79.0;
    public static final double SCORCH_TEMPERATURE_MARGIN = 8.0;
    public static final double SCORCH_INTERVAL_SECONDS = 3.0;
    public static final double BREW_SECONDS = 30.0;
    public static final double EVAPORATION_START_TEMPERATURE = 90.0;
    public static final double EVAPORATION_INTERVAL_SECONDS = 20.0;

    private BrewRecipe recipe;
    private final VesselKind vessel;
    private BrewForm form;
    private final EnumMap<IngredientSlot, IngredientBatch> ingredients =
            new EnumMap<>(IngredientSlot.class);
    private final EnumMap<IngredientSlot, Double> ingredientTimes =
            new EnumMap<>(IngredientSlot.class);
    private BrewCauldronState state = BrewCauldronState.EMPTY;
    private int waterUnits;
    private double temperature;
    private double soakSeconds;
    private double brewElapsedSeconds;
    private double inBandSeconds;
    private double overheatSeconds;
    private double evaporationSeconds;
    private double heatPhaseSeconds;
    private int scorchCount;
    private int remainingBottles;
    private CopperCauldronState copperState =
            new CopperCauldronState(CopperOxidation.UNAFFECTED, false);

    public BrewCauldron(VesselKind vessel, BrewForm form) {
        if (vessel == null || form == null) {
            throw new IllegalArgumentException("巫釜参数不完整");
        }
        this.vessel = vessel;
        this.form = form;
    }

    public Optional<BrewRecipe> recipe() {
        return Optional.ofNullable(recipe);
    }

    public CopperCauldronState copperState() {
        return copperState;
    }

    public BrewCauldronState state() {
        return state;
    }

    public BrewForm form() {
        return form;
    }

    public double temperature() {
        return temperature;
    }

    public int waterUnits() {
        return waterUnits;
    }

    public double brewElapsedSeconds() {
        return brewElapsedSeconds;
    }

    public int scorchCount() {
        return scorchCount;
    }

    /** 已经泡够的秒数，满 {@link BrewScoring#SOAK_SECONDS} 就拿满浸泡分。 */
    public double soakSeconds() {
        return soakSeconds;
    }

    /** 熬成后还能舀几瓶；没熬成时是 0。 */
    public int remainingBottles() {
        return remainingBottles;
    }

    public BrewActionResult waxCopper() {
        if (vessel != VesselKind.COPPER_CAULDRON) {
            return BrewActionResult.returned(BrewText.message("cauldron.wax.not_copper"));
        }
        if (copperState.waxed()) {
            return BrewActionResult.returned(BrewText.message("cauldron.wax.already"));
        }
        copperState = copperState.wax();
        return BrewActionResult.accepted(BrewText.message("cauldron.wax.done"));
    }

    public BrewActionResult scrapeCopper() {
        if (vessel != VesselKind.COPPER_CAULDRON) {
            return BrewActionResult.returned(BrewText.message("cauldron.scrape.not_copper"));
        }
        CopperCauldronState scrapedState = copperState.scrape();
        if (scrapedState.equals(copperState)) {
            return BrewActionResult.returned(BrewText.message("cauldron.scrape.nothing"));
        }
        copperState = scrapedState;
        return BrewActionResult.accepted(BrewText.message("cauldron.scrape.done"));
    }

    public void restoreCopperOxidation(CopperOxidation oxidation) {
        // 新放下的铜釜按方块外观带来的氧化级起算，未上蜡；读档时随后由 restore 覆盖。
        if (vessel != VesselKind.COPPER_CAULDRON || oxidation == null) {
            throw new IllegalArgumentException("只有铜釜能按外观恢复氧化级");
        }
        copperState = new CopperCauldronState(oxidation, false);
    }

    public void ageCopper(CopperExposure exposure) {
        if (vessel == VesselKind.COPPER_CAULDRON && exposure != null) {
            copperState = copperState.age(exposure);
        }
    }

    public Map<IngredientSlot, IngredientBatch> ingredients() {
        return Map.copyOf(ingredients);
    }

    public Optional<IngredientSlot> ingredientSlotFor(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Optional.empty();
        }
        if (recipe == null) {
            return BrewRecipes.forPreparedPrimary(itemId).map(value -> IngredientSlot.PRIMARY);
        }
        IngredientPlan plan = recipe.ingredients();
        if (BrewRecipes.sameItemId(plan.preparedPrimary(), itemId)) {
            return Optional.of(IngredientSlot.PRIMARY);
        }
        if (BrewRecipes.sameItemId(plan.accessory(), itemId)) {
            return Optional.of(IngredientSlot.ACCESSORY);
        }
        if (BrewRecipes.sameItemId(plan.catalyst(), itemId)) {
            return Optional.of(IngredientSlot.CATALYST);
        }
        if (BrewRecipes.sameItemId(BASE_INGREDIENT_ID, itemId)) {
            return Optional.of(IngredientSlot.BASE);
        }
        if (BrewRecipes.sameItemId(PROPELLANT_INGREDIENT_ID, itemId)) {
            return Optional.of(IngredientSlot.PROPELLANT);
        }
        return Optional.empty();
    }

    public BrewActionResult addWater(int units) {
        if (units <= 0) {
            throw new IllegalArgumentException("添水份数必须为正数");
        }
        if (state == BrewCauldronState.SCORCHED || state == BrewCauldronState.BREWED) {
            return BrewActionResult.returned(stateBlocksIngredients());
        }
        if (waterUnits >= vessel.waterCapacity()) {
            return BrewActionResult.returned(BrewText.message("cauldron.water.full", vessel.waterCapacity()));
        }
        // 一桶水比剩下的空位多时只倒到满，多出来的不算。
        int poured = Math.min(units, vessel.waterCapacity() - waterUnits);
        waterUnits += poured;
        temperature = Math.max(0.0, temperature - vessel.waterCoolingDegrees() * poured);
        if (state == BrewCauldronState.EMPTY) {
            state = BrewCauldronState.WATER;
        }
        return BrewActionResult.accepted(BrewText.message("cauldron.water.added",
                poured, waterUnits, vessel.waterCapacity()));
    }

    /** 备料退回后只清药材，保留原有的水和温度。 */
    public void reclaimIngredients() {
        if (state != BrewCauldronState.RAW) {
            throw new IllegalStateException("只能退回尚未开熬的料");
        }
        ingredients.clear();
        ingredientTimes.clear();
        recipe = null;
        form = BrewForm.DRINK;
        soakSeconds = 0;
        state = BrewCauldronState.WATER;
    }

    public BrewActionResult scrubWithWater() {
        if (state != BrewCauldronState.SCORCHED) {
            return BrewActionResult.returned(BrewText.message("cauldron.scrub.not_scorched"));
        }
        resetBrewState();
        return BrewActionResult.accepted(BrewText.message("cauldron.scrub.done"));
    }

    /**
     * 这份料现在能不能下进 {@code slot}；能下返回空。只看不改：漏斗先问能不能放再真的放，
     * 问的那一步不能定下药方。
     */
    public Optional<BrewText> ingredientProblem(IngredientSlot slot, IngredientBatch batch) {
        // 阶段和占格先查；还没定药方时按主料推一张药方来比，但不记下。
        Optional<BrewActionResult> stageRejection = ingredientStageRejection(slot, batch);
        if (stageRejection.isPresent()) {
            return Optional.of(stageRejection.orElseThrow().message());
        }
        Optional<BrewRecipe> target = Optional.ofNullable(recipe);
        if (slot == IngredientSlot.PRIMARY && target.isEmpty()) {
            target = BrewRecipes.forPreparedPrimary(batch.itemId());
            if (target.isEmpty()) {
                return Optional.of(BrewText.message("cauldron.primary.unknown",
                        new BrewText.ItemName(batch.itemId())));
            }
        }
        return ingredientError(target, slot, batch.itemId());
    }

    public BrewActionResult addIngredient(IngredientSlot slot, IngredientBatch batch) {
        // 能不能下和真的下用同一套判断；第一味主料下进去时才定药方。
        Optional<BrewText> problem = ingredientProblem(slot, batch);
        if (problem.isPresent()) {
            return BrewActionResult.returned(problem.orElseThrow(), batch);
        }
        if (slot == IngredientSlot.PRIMARY && recipe == null) {
            recipe = BrewRecipes.forPreparedPrimary(batch.itemId()).orElseThrow();
        }
        if (slot == IngredientSlot.PROPELLANT) {
            form = BrewForm.SPLASH;
        }
        IngredientBatch previous = ingredients.get(slot);
        IngredientBatch combined = batch;
        if (previous != null) {
            long oldest = Math.min(previous.preparedGameDay(), batch.preparedGameDay());
            int total = previous.count() + batch.count();
            combined = new IngredientBatch(batch.itemId(), batch.preparation(), oldest, total);
        }
        ingredients.put(slot, combined);
        if (state == BrewCauldronState.WATER) {
            state = BrewCauldronState.RAW;
        }
        if (state == BrewCauldronState.BREWING) {
            ingredientTimes.put(slot, brewElapsedSeconds);
        }
        return BrewActionResult.accepted(BrewText.message("cauldron.ingredient.added",
                slotName(slot), new BrewText.ItemName(batch.itemId())));
    }

    public Optional<BrewText> startProblem(BrewStructure structure) {
        // 釜自己这一侧能不能起熬：状态、药方、料、结构；施法者学没学过、真元够不够由宿主另查。
        if (structure == null) {
            throw new IllegalArgumentException("起熬结构不能为空");
        }
        if (state == BrewCauldronState.BREWING) {
            return Optional.of(BrewText.message("cauldron.start.already"));
        }
        if (state != BrewCauldronState.RAW) {
            return Optional.of(stateBlocksStart());
        }
        if (recipe == null) {
            return Optional.of(BrewText.message("cauldron.start.no_recipe"));
        }
        Optional<BrewText> missingIngredients = missingIngredients();
        if (missingIngredients.isPresent()) {
            return missingIngredients;
        }
        if (vessel == VesselKind.AUTOMATIC_FURNACE) {
            return Optional.of(BrewText.message("furnace.worker"));
        }
        return structure.problemFor(recipe.kind(), recipe.fireKind());
    }

    public BrewActionResult startBrew(BrewStructure structure) {
        // 起熬前再按同一套规则查一遍，规则测试和宿主走同一个入口。
        Optional<BrewText> problem = startProblem(structure);
        if (problem.isPresent()) {
            return BrewActionResult.returned(problem.orElseThrow());
        }
        state = BrewCauldronState.BREWING;
        brewElapsedSeconds = 0.0;
        inBandSeconds = 0.0;
        overheatSeconds = 0.0;
        evaporationSeconds = 0.0;
        scorchCount = 0;
        remainingBottles = 0;
        return BrewActionResult.accepted(BrewText.message("cauldron.start.done", recipeName(recipe.kind())));
    }

    public BrewActionResult interruptBrew() {
        // 已投材料与浸泡累计保留，重新起熬时必须重新计算火候和投料时窗。
        if (state != BrewCauldronState.BREWING) {
            return BrewActionResult.returned(BrewText.message("cauldron.interrupt.not_brewing"));
        }
        state = BrewCauldronState.RAW;
        brewElapsedSeconds = 0.0;
        inBandSeconds = 0.0;
        overheatSeconds = 0.0;
        evaporationSeconds = 0.0;
        heatPhaseSeconds = 0.0;
        scorchCount = 0;
        remainingBottles = 0;
        ingredientTimes.clear();
        return BrewActionResult.accepted(BrewText.message("cauldron.interrupt.done"));
    }

    public BrewTickResult tick(double seconds, BrewStructure structure) {
        if (!Double.isFinite(seconds) || seconds <= 0 || structure == null) {
            throw new IllegalArgumentException("巫釜推进参数非法");
        }
        updateTemperature(seconds, structure);
        if (state == BrewCauldronState.RAW) {
            updateSoak(seconds);
        }
        if (state == BrewCauldronState.BREWING) {
            updateBrew(seconds);
        }
        return tickResult();
    }

    public BrewCollectionResult collectBottle(long currentGameDay) {
        return collectBottle(currentGameDay, BrewQuality.SUPERIOR);
    }

    public BrewCollectionResult collectBottle(long currentGameDay, BrewQuality ceiling) {
        if (state == BrewCauldronState.SCORCHED) {
            return new BrewCollectionResult(BrewCollectionKind.NOTHING, Optional.empty(), 0,
                    BrewText.message("cauldron.state_blocks.scorched"));
        }
        if (state != BrewCauldronState.BREWED || remainingBottles <= 0) {
            return new BrewCollectionResult(BrewCollectionKind.NOTHING, Optional.empty(),
                    remainingBottles, BrewText.message("cauldron.collect.not_ready"));
        }
        BrewKind kind = recipe.kind();
        BrewForm collectedForm = form;
        BrewScore score = BrewScoring.calculate(toInput(currentGameDay)).limitedTo(ceiling);
        remainingBottles--;
        waterUnits = Math.max(0, waterUnits - 1);
        int remaining = remainingBottles;
        if (remainingBottles == 0) {
            resetBrewState();
        }
        if (score.quality().isEmpty()) {
            return new BrewCollectionResult(BrewCollectionKind.SCRAP, Optional.of(score), remaining,
                    BrewText.message("cauldron.collect.scrap", score.totalScore(),
                            BrewScoring.SCRAP_THRESHOLD, remaining));
        }
        return new BrewCollectionResult(BrewCollectionKind.POTION, Optional.of(score), remaining,
                BrewText.message("cauldron.collect.potion", brewName(kind, collectedForm),
                        score.quality().orElseThrow(), remaining));
    }

    private Optional<BrewActionResult> ingredientStageRejection(IngredientSlot slot, IngredientBatch batch) {
        if (slot == null || batch == null) {
            throw new IllegalArgumentException("投料信息不完整");
        }
        if (state == BrewCauldronState.EMPTY || state == BrewCauldronState.BREWED
                || state == BrewCauldronState.SCORCHED) {
            return Optional.of(BrewActionResult.returned(stateBlocksIngredients(), batch));
        }
        if (batch.count() > needed(slot)) {
            return Optional.of(BrewActionResult.returned(
                    BrewText.message("cauldron.ingredient.slot_taken", slotName(slot)), batch));
        }
        boolean startIngredient = slot == IngredientSlot.PRIMARY
                || slot == IngredientSlot.BASE || slot == IngredientSlot.PROPELLANT;
        if (state == BrewCauldronState.BREWING && startIngredient) {
            return Optional.of(BrewActionResult.returned(
                    BrewText.message("cauldron.ingredient.brewing_only_late", slotName(slot)), batch));
        }
        if (state != BrewCauldronState.BREWING && !startIngredient) {
            return Optional.of(BrewActionResult.returned(
                    BrewText.message("cauldron.ingredient.late_needs_brewing", slotName(slot)), batch));
        }
        if (!startIngredient) {
            IngredientPlan plan = recipe.ingredients();
            TimingWindow window = plan.accessoryWindow();
            if (slot == IngredientSlot.CATALYST) {
                window = plan.catalystWindow();
            }
            if (brewElapsedSeconds < window.startSeconds()) {
                return Optional.of(BrewActionResult.returned(BrewText.message("cauldron.ingredient.early"), batch));
            }
        }
        return Optional.empty();
    }

    private Optional<BrewText> ingredientError(Optional<BrewRecipe> target, IngredientSlot slot, String itemId) {
        if (target.isEmpty()) {
            return Optional.of(BrewText.message("cauldron.start.no_recipe"));
        }
        BrewRecipe chosen = target.orElseThrow();
        IngredientPlan plan = chosen.ingredients();
        String expected = switch (slot) {
            case PRIMARY -> plan.preparedPrimary();
            case ACCESSORY -> plan.accessory();
            case CATALYST -> plan.catalyst();
            case BASE -> BASE_INGREDIENT_ID;
            case PROPELLANT -> PROPELLANT_INGREDIENT_ID;
        };
        if (!BrewRecipes.sameItemId(expected, itemId)) {
            return Optional.of(BrewText.message("cauldron.ingredient.wrong",
                    new BrewText.ItemName(itemId), recipeName(chosen.kind())));
        }
        if (slot == IngredientSlot.PROPELLANT && !recipe.kind().supports(BrewUse.SPLASH)) {
            return Optional.of(BrewText.message("cauldron.ingredient.drink_only",
                    recipeName(chosen.kind())));
        }
        return Optional.empty();
    }

    public int needed(IngredientSlot slot) {
        IngredientBatch present = ingredients.get(slot);
        if (present == null) {
            return vessel.batch().sets();
        }
        return Math.max(0, vessel.batch().sets() - present.count());
    }

    private Optional<BrewText> missingIngredients() {
        boolean splash = form == BrewForm.SPLASH || !recipe.kind().supports(BrewUse.DRINK);
        EnumSet<IngredientSlot> required = EnumSet.of(
                IngredientSlot.PRIMARY, IngredientSlot.BASE);
        if (splash) {
            required.add(IngredientSlot.PROPELLANT);
        }
        if (required.stream().allMatch(slot -> needed(slot) == 0)) {
            return Optional.empty();
        }
        String missing = splash ? "cauldron.start.missing_splash" : "cauldron.start.missing_drink";
        return Optional.of(BrewText.message(missing, recipeName(recipe.kind())));
    }

    private BrewText stateBlocksIngredients() {
        return switch (state) {
            case EMPTY -> BrewText.message("cauldron.state_blocks.empty");
            case BREWED -> BrewText.message("cauldron.state_blocks.brewed");
            case SCORCHED -> BrewText.message("cauldron.state_blocks.scorched");
            case WATER, RAW, BREWING -> throw new IllegalStateException("这个阶段可以下料：" + state);
        };
    }

    private BrewText stateBlocksStart() {
        return switch (state) {
            case EMPTY -> BrewText.message("cauldron.state_blocks.empty");
            case WATER -> BrewText.message("cauldron.start.no_recipe");
            case BREWED -> BrewText.message("cauldron.state_blocks.brewed");
            case SCORCHED -> BrewText.message("cauldron.state_blocks.scorched");
            case RAW, BREWING -> throw new IllegalStateException("这个阶段不该走到这里：" + state);
        };
    }

    /** 槽位名，例如「辅料」。 */
    public static BrewText slotName(IngredientSlot slot) {
        return BrewText.message("slot." + slot.name().toLowerCase(Locale.ROOT));
    }

    /** 药方名，例如「聚气汤」。 */
    public static BrewText recipeName(BrewKind kind) {
        return BrewText.key("potion.meridian_brewing_" + kind.path());
    }

    /** 成药按形态的名字，例如「喷溅苦黄膏」，和原版药水物品名同一个键。 */
    public static BrewText brewName(BrewKind kind, BrewForm form) {
        String item = form == BrewForm.SPLASH ? "splash_potion" : "potion";
        return BrewText.key("item.minecraft." + item + ".effect.meridian_brewing_" + kind.path());
    }

    private void updateTemperature(double seconds, BrewStructure structure) {
        double maximumTemperature = structure.maximumTemperature();
        double baseTemperature = Math.min(maximumTemperature,
                structure.heatSource().equilibriumTemperature());
        double fluctuation = structure.heatSource().fluctuationDegrees();
        double targetTemperature = baseTemperature
                + Math.sin(heatPhaseSeconds) * fluctuation;
        targetTemperature = Math.max(0.0, Math.min(maximumTemperature, targetTemperature));
        double convergenceRate = vessel.convergencePerSecond(copperState.oxidation());
        double convergence = Math.min(1.0, convergenceRate * seconds);
        temperature += (targetTemperature - temperature) * convergence;
        temperature = Math.max(0.0, temperature);
        heatPhaseSeconds += seconds;
    }

    private void updateSoak(double seconds) {
        IngredientBatch primary = ingredients.get(IngredientSlot.PRIMARY);
        boolean inSoakBand = primary != null
                && temperature >= MIN_SOAK_TEMPERATURE && temperature <= MAX_SOAK_TEMPERATURE;
        if (inSoakBand) {
            soakSeconds = Math.min(BrewScoring.SOAK_SECONDS, soakSeconds + seconds);
        }
    }

    private void updateBrew(double seconds) {
        double nextElapsed = Math.min(BREW_SECONDS, brewElapsedSeconds + seconds);
        double elapsed = nextElapsed - brewElapsedSeconds;
        if (recipe.heatBand().contains(temperature)) {
            inBandSeconds += elapsed;
        }
        updateScorch(elapsed);
        updateEvaporation(elapsed);
        brewElapsedSeconds = nextElapsed;
        if (state == BrewCauldronState.BREWING && brewElapsedSeconds >= BREW_SECONDS) {
            remainingBottles = Math.min(waterUnits, vessel.batch().bottles());
            state = BrewCauldronState.BREWED;
        }
    }

    private void updateScorch(double seconds) {
        if (temperature > recipe.heatBand().maximumDegrees() + SCORCH_TEMPERATURE_MARGIN) {
            overheatSeconds += seconds;
            while (overheatSeconds >= SCORCH_INTERVAL_SECONDS) {
                scorchCount++;
                overheatSeconds -= SCORCH_INTERVAL_SECONDS;
            }
        } else {
            overheatSeconds = 0.0;
        }
    }

    private void updateEvaporation(double seconds) {
        if (temperature <= EVAPORATION_START_TEMPERATURE) {
            return;
        }
        evaporationSeconds += seconds;
        while (evaporationSeconds >= EVAPORATION_INTERVAL_SECONDS && waterUnits > 0) {
            evaporationSeconds -= EVAPORATION_INTERVAL_SECONDS;
            waterUnits--;
        }
        if (waterUnits == 0) {
            state = BrewCauldronState.SCORCHED;
        }
    }

    private BrewScoreInput toInput(long currentGameDay) {
        IngredientBatch primary = ingredients.get(IngredientSlot.PRIMARY);
        IngredientBatch accessory = ingredients.get(IngredientSlot.ACCESSORY);
        IngredientBatch catalyst = ingredients.get(IngredientSlot.CATALYST);
        int ingredientScore = BrewScoring.ingredientScore(primary, accessory, catalyst, currentGameDay);
        int soakScore = (int) Math.round(BrewScoring.SOAK_MAX * soakSeconds / BrewScoring.SOAK_SECONDS);
        int heatScore = (int) Math.round(BrewScoring.HEAT_MAX * inBandSeconds / BREW_SECONDS);
        Double catalystTime = ingredientTimes.get(IngredientSlot.CATALYST);
        int timingScore = BrewScoring.timingScore(recipe.ingredients().accessoryWindow(),
                ingredientTimes.get(IngredientSlot.ACCESSORY),
                recipe.ingredients().catalystWindow(), catalystTime);
        int collectionScore = collectionScore();
        CatalystRequirement requirement = catalystRequirement(catalyst);
        return new BrewScoreInput(ingredientScore, soakScore, heatScore, timingScore,
                collectionScore, scorchCount, requirement);
    }

    private int collectionScore() {
        if (temperature >= MIN_SOAK_TEMPERATURE && temperature <= COLLECTION_MAX_TEMPERATURE) {
            return BrewScoring.COLLECTION_MAX;
        }
        return 0;
    }

    private CatalystRequirement catalystRequirement(IngredientBatch catalyst) {
        if (catalyst == null) {
            return CatalystRequirement.OMITTED;
        }
        return CatalystRequirement.INCLUDED;
    }

    private void resetBrewState() {
        state = BrewCauldronState.EMPTY;
        recipe = null;
        form = BrewForm.DRINK;
        ingredients.clear();
        ingredientTimes.clear();
        waterUnits = 0;
        temperature = 0.0;
        soakSeconds = 0.0;
        brewElapsedSeconds = 0.0;
        inBandSeconds = 0.0;
        overheatSeconds = 0.0;
        evaporationSeconds = 0.0;
        heatPhaseSeconds = 0.0;
        scorchCount = 0;
        remainingBottles = 0;
    }

    private BrewTickResult tickResult() {
        return new BrewTickResult(state, temperature, waterUnits, brewElapsedSeconds, scorchCount);
    }

    public Snapshot snapshot() {
        BrewKind recipeKind = null;
        if (recipe != null) {
            recipeKind = recipe.kind();
        }
        return new Snapshot(state, form, recipeKind, ingredients,
                ingredientTimes, waterUnits, temperature, soakSeconds, brewElapsedSeconds,
                inBandSeconds, overheatSeconds, evaporationSeconds, heatPhaseSeconds,
                scorchCount, remainingBottles, copperState);
    }

    public void restore(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("巫釜快照不能为空");
        }
        state = snapshot.state();
        form = snapshot.form();
        recipe = snapshot.recipeKind() == null ? null : BrewRecipes.require(snapshot.recipeKind());
        ingredients.clear();
        ingredients.putAll(snapshot.ingredients());
        ingredientTimes.clear();
        ingredientTimes.putAll(snapshot.ingredientTimes());
        waterUnits = snapshot.waterUnits();
        temperature = snapshot.temperature();
        soakSeconds = snapshot.soakSeconds();
        brewElapsedSeconds = snapshot.brewElapsedSeconds();
        inBandSeconds = snapshot.inBandSeconds();
        overheatSeconds = snapshot.overheatSeconds();
        evaporationSeconds = snapshot.evaporationSeconds();
        heatPhaseSeconds = snapshot.heatPhaseSeconds();
        scorchCount = snapshot.scorchCount();
        remainingBottles = snapshot.remainingBottles();
        copperState = snapshot.copperState();
    }

    public record Snapshot(
            BrewCauldronState state,
            BrewForm form,
            BrewKind recipeKind,
            Map<IngredientSlot, IngredientBatch> ingredients,
            Map<IngredientSlot, Double> ingredientTimes,
            int waterUnits,
            double temperature,
            double soakSeconds,
            double brewElapsedSeconds,
            double inBandSeconds,
            double overheatSeconds,
            double evaporationSeconds,
            double heatPhaseSeconds,
            int scorchCount,
            int remainingBottles,
            CopperCauldronState copperState) {
        public Snapshot {
            if (state == null || form == null || ingredients == null || ingredientTimes == null
                    || copperState == null) {
                throw new IllegalArgumentException("巫釜快照字段不完整");
            }
            ingredients = Map.copyOf(ingredients);
            ingredientTimes = Map.copyOf(ingredientTimes);
        }

        /**
         * 去掉温度、火苗相位和各段计时以后的内容：状态、剂型、药方、料和下料时刻、水、糊锅次数、剩几瓶、铜釜状态。
         * 方块实体拿它判断釜里有没有离散的变化，有就当 tick 存。
         */
        public Snapshot withoutProgress() {
            // 连续量一律记成 0，剩下的字段原样比相等。
            return new Snapshot(state, form, recipeKind, ingredients, ingredientTimes, waterUnits, 0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0, 0.0, scorchCount, remainingBottles, copperState);
        }

        /** 影响分数、要按秒存的那几段计时；温度和火苗相位只跟着别的变化一起存。 */
        public Progress progress() {
            // 只取会改分的计时，温度一直在火苗起伏里变，不当成要存的变化。
            return new Progress(soakSeconds, brewElapsedSeconds, inBandSeconds, overheatSeconds, evaporationSeconds);
        }
    }

    /** 浸泡、熬制、火候内、过火、蒸发各走了多少秒。 */
    public record Progress(double soakSeconds, double brewElapsedSeconds, double inBandSeconds,
                           double overheatSeconds, double evaporationSeconds) {
    }
}
