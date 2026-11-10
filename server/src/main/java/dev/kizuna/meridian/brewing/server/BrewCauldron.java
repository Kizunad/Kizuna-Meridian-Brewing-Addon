package dev.kizuna.meridian.brewing.server;

import java.util.EnumMap;
import java.util.EnumSet;
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

    public BrewActionResult waxCopper() {
        if (vessel != VesselKind.COPPER_CAULDRON) {
            return BrewActionResult.returned("巫釜不能上蜡");
        }
        if (copperState.waxed()) {
            return BrewActionResult.returned("铜釜已经上蜡");
        }
        copperState = copperState.wax();
        return BrewActionResult.accepted("铜釜已上蜡");
    }

    public BrewActionResult scrapeCopper() {
        if (vessel != VesselKind.COPPER_CAULDRON) {
            return BrewActionResult.returned("巫釜不能刮锈");
        }
        CopperCauldronState scrapedState = copperState.scrape();
        if (scrapedState.equals(copperState)) {
            return BrewActionResult.returned("铜釜没有可刮的锈");
        }
        copperState = scrapedState;
        return BrewActionResult.accepted("铜釜已刮掉一级锈");
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
        if (units <= 0 || state == BrewCauldronState.SCORCHED || state == BrewCauldronState.BREWED) {
            return BrewActionResult.returned("当前巫釜不能添水");
        }
        if (waterUnits + units > vessel.waterCapacity()) {
            return BrewActionResult.returned("添水超过巫釜容量");
        }
        waterUnits += units;
        temperature = Math.max(0.0, temperature - vessel.waterCoolingDegrees() * units);
        if (state == BrewCauldronState.EMPTY) {
            state = BrewCauldronState.WATER;
        }
        return BrewActionResult.accepted("已添水");
    }

    public BrewActionResult scrubWithWater() {
        if (state != BrewCauldronState.SCORCHED) {
            return BrewActionResult.returned("巫釜没有糊锅");
        }
        resetBrewState();
        return BrewActionResult.accepted("巫釜已刷净");
    }

    public BrewActionResult addIngredient(IngredientSlot slot, IngredientBatch batch) {
        BrewActionResult stageResult = validateIngredientStage(slot, batch);
        if (!stageResult.accepted()) {
            return stageResult;
        }
        if (slot == IngredientSlot.PRIMARY && recipe == null) {
            Optional<BrewRecipe> resolvedRecipe = BrewRecipes.forPreparedPrimary(batch.itemId());
            if (resolvedRecipe.isEmpty()) {
                return BrewActionResult.returned("主料不对应任何药方", batch);
            }
            recipe = resolvedRecipe.orElseThrow();
        }
        String ingredientError = ingredientError(slot, batch.itemId());
        if (ingredientError != null) {
            return BrewActionResult.returned(ingredientError, batch);
        }
        if (slot == IngredientSlot.PROPELLANT) {
            form = BrewForm.SPLASH;
        }
        ingredients.put(slot, batch);
        if (state == BrewCauldronState.WATER) {
            state = BrewCauldronState.RAW;
        }
        if (state == BrewCauldronState.BREWING) {
            ingredientTimes.put(slot, brewElapsedSeconds);
        }
        return BrewActionResult.accepted("已下料：" + slot);
    }

    public BrewActionResult startBrew(BrewStructure structure) {
        if (structure == null || state != BrewCauldronState.RAW) {
            return BrewActionResult.returned("巫釜还没有可熬的生药");
        }
        if (recipe == null) {
            return BrewActionResult.returned("尚未由主料确定药方");
        }
        String missingIngredients = missingIngredientsReason();
        if (missingIngredients != null) {
            return BrewActionResult.returned(missingIngredients);
        }
        if (!structure.accepts(recipe.fireKind())) {
            return BrewActionResult.returned("结构或火种不满足药方");
        }
        state = BrewCauldronState.BREWING;
        brewElapsedSeconds = 0.0;
        inBandSeconds = 0.0;
        overheatSeconds = 0.0;
        evaporationSeconds = 0.0;
        scorchCount = 0;
        remainingBottles = vessel.waterCapacity();
        return BrewActionResult.accepted("开始熬制");
    }

    public BrewActionResult interruptBrew() {
        // 已投材料与浸泡累计保留，重新起熬时必须重新计算火候和投料时窗。
        if (state != BrewCauldronState.BREWING) {
            return BrewActionResult.returned("巫釜当前没有正在进行的熬制");
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
        return BrewActionResult.accepted("熬制已中断，投料和浸泡分保留");
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
        if (state == BrewCauldronState.SCORCHED) {
            return new BrewCollectionResult(
                    BrewCollectionKind.NOTHING, Optional.empty(), 0, "水已熬干，必须刷锅");
        }
        if (state != BrewCauldronState.BREWED || remainingBottles <= 0) {
            return new BrewCollectionResult(BrewCollectionKind.NOTHING, Optional.empty(),
                    remainingBottles, "巫釜还没有熬成可收的药");
        }
        BrewScoreInput input = toInput(currentGameDay);
        BrewScore score = BrewScoring.calculate(input);
        remainingBottles--;
        BrewCollectionKind collectionKind = collectionKind(input);
        if (remainingBottles == 0) {
            clearAfterCollection();
        }
        String reason = collectionKind == BrewCollectionKind.SCRAP ? "总分低于药渣阈值" : "成品药水";
        return new BrewCollectionResult(collectionKind, Optional.of(score), remainingBottles, reason);
    }

    private BrewActionResult validateIngredientStage(IngredientSlot slot, IngredientBatch batch) {
        if (slot == null || batch == null) {
            return BrewActionResult.returned("投料信息不完整");
        }
        if (state == BrewCauldronState.EMPTY || state == BrewCauldronState.BREWED
                || state == BrewCauldronState.SCORCHED) {
            return BrewActionResult.returned("当前巫釜不能下料", batch);
        }
        if (ingredients.containsKey(slot)) {
            return BrewActionResult.returned("该槽位已经下过料：" + slot, batch);
        }
        boolean startIngredient = slot == IngredientSlot.PRIMARY
                || slot == IngredientSlot.BASE || slot == IngredientSlot.PROPELLANT;
        if (state == BrewCauldronState.BREWING && startIngredient) {
            return BrewActionResult.returned("熬制中只能下辅料或引药", batch);
        }
        if (state != BrewCauldronState.BREWING && !startIngredient) {
            return BrewActionResult.returned("辅料和引药必须在熬制中下入", batch);
        }
        return BrewActionResult.accepted("投料阶段允许");
    }

    private String ingredientError(IngredientSlot slot, String itemId) {
        if (recipe == null && slot != IngredientSlot.PRIMARY) {
            return "尚未由主料确定药方";
        }
        IngredientPlan plan = recipe.ingredients();
        String expected = switch (slot) {
            case PRIMARY -> plan.preparedPrimary();
            case ACCESSORY -> plan.accessory();
            case CATALYST -> plan.catalyst();
            case BASE -> BASE_INGREDIENT_ID;
            case PROPELLANT -> PROPELLANT_INGREDIENT_ID;
        };
        if (!BrewRecipes.sameItemId(expected, itemId)) {
            return "药方不接受该投料：" + itemId;
        }
        if (slot == IngredientSlot.PROPELLANT && !recipe.kind().supports(BrewUse.SPLASH)) {
            return "饮用药不能下火药";
        }
        return null;
    }

    private String missingIngredientsReason() {
        EnumSet<IngredientSlot> required = EnumSet.of(
                IngredientSlot.PRIMARY, IngredientSlot.BASE);
        if (form == BrewForm.SPLASH || !recipe.kind().supports(BrewUse.DRINK)) {
            required.add(IngredientSlot.PROPELLANT);
        }
        if (!ingredients.keySet().containsAll(required)) {
            return "主料、底料或火药未齐";
        }
        return null;
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

    private BrewCollectionKind collectionKind(BrewScoreInput input) {
        if (BrewScoring.isScrap(input)) {
            return BrewCollectionKind.SCRAP;
        }
        return BrewCollectionKind.POTION;
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

    private void clearAfterCollection() {
        resetBrewState();
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
    }
}
