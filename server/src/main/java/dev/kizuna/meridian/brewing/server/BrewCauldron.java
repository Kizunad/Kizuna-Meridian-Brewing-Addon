package dev.kizuna.meridian.brewing.server;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

/** 巫釜的纯服务端状态机；Fabric 方块实体只负责把世界状态转换成快照。 */
public final class BrewCauldron {
    private static final String BASE_INGREDIENT_ID = "minecraft:glowstone_dust";
    private static final String PROPELLANT_INGREDIENT_ID = "minecraft:gunpowder";
    public static final double MIN_SOAK_TEMPERATURE = 40.0;
    public static final double MAX_SOAK_TEMPERATURE = 79.0;
    public static final double COLLECTION_MAX_TEMPERATURE = 79.0;
    public static final double SCORCH_TEMPERATURE_MARGIN = 8.0;
    public static final double SCORCH_INTERVAL_SECONDS = 3.0;
    public static final double BREW_SECONDS = 30.0;
    public static final double EVAPORATION_START_TEMPERATURE = 90.0;
    public static final double EVAPORATION_INTERVAL_SECONDS = 20.0;

    private final BrewRecipe recipe;
    private final VesselKind vessel;
    private final BrewForm form;
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
    private int scorchCount;
    private int remainingBottles;

    public BrewCauldron(BrewRecipe recipe, VesselKind vessel, BrewForm form) {
        if (recipe == null || vessel == null || form == null) {
            throw new IllegalArgumentException("巫釜参数不完整");
        }
        this.recipe = recipe;
        this.vessel = vessel;
        this.form = form;
    }

    public BrewCauldronState state() {
        return state;
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

    public Map<IngredientSlot, IngredientBatch> ingredients() {
        return Map.copyOf(ingredients);
    }

    public void addWater(int units) {
        if (units <= 0 || state == BrewCauldronState.SCORCHED || state == BrewCauldronState.BREWED) {
            throw new IllegalArgumentException("当前巫釜不能添水");
        }
        if (waterUnits + units > vessel.waterCapacity()) {
            throw new IllegalArgumentException("添水超过巫釜容量");
        }
        waterUnits += units;
        temperature = Math.max(0.0, temperature - vessel.waterCoolingDegrees() * units);
        if (state == BrewCauldronState.EMPTY) {
            state = BrewCauldronState.WATER;
        }
    }

    public void addIngredient(IngredientSlot slot, IngredientBatch batch) {
        if (slot == null || batch == null || state == BrewCauldronState.EMPTY
                || state == BrewCauldronState.BREWED || state == BrewCauldronState.SCORCHED) {
            throw new IllegalArgumentException("当前巫釜不能下料");
        }
        if (ingredients.containsKey(slot)) {
            throw new IllegalArgumentException("该槽位已经下过料：" + slot);
        }
        if (state != BrewCauldronState.BREWING
                && (slot == IngredientSlot.ACCESSORY || slot == IngredientSlot.CATALYST)) {
            throw new IllegalArgumentException("辅料和引药必须在熬制中下入");
        }
        validateIngredient(slot, batch.itemId());
        if (state == BrewCauldronState.BREWING && slot != IngredientSlot.ACCESSORY
                && slot != IngredientSlot.CATALYST) {
            throw new IllegalArgumentException("熬制中只能下辅料或引药");
        }
        ingredients.put(slot, batch);
        if (state == BrewCauldronState.WATER) {
            state = BrewCauldronState.RAW;
        }
        if (state == BrewCauldronState.BREWING) {
            ingredientTimes.put(slot, brewElapsedSeconds);
        }
    }

    public void startBrew(BrewStructure structure) {
        if (structure == null || state != BrewCauldronState.RAW) {
            throw new IllegalStateException("巫釜还没有可熬的生药");
        }
        requireIngredientsBeforeStart();
        if (!structure.accepts(recipe.fireKind())) {
            throw new IllegalStateException("结构或火种不满足药方");
        }
        state = BrewCauldronState.BREWING;
        brewElapsedSeconds = 0.0;
        inBandSeconds = 0.0;
        overheatSeconds = 0.0;
        evaporationSeconds = 0.0;
        scorchCount = 0;
        remainingBottles = vessel.waterCapacity();
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
        return snapshot();
    }

    public BrewCollectionResult collectBottle(long currentGameDay) {
        if (state == BrewCauldronState.SCORCHED) {
            return new BrewCollectionResult(BrewCollectionKind.NOTHING, Optional.empty(), 0, "水已熬干，必须刷锅");
        }
        if (state != BrewCauldronState.BREWED || remainingBottles <= 0) {
            throw new IllegalStateException("巫釜还没有熬成可收的药");
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

    private void validateIngredient(IngredientSlot slot, String itemId) {
        IngredientPlan plan = recipe.ingredients();
        String expected = switch (slot) {
            case PRIMARY -> plan.preparedPrimary();
            case ACCESSORY -> plan.accessory();
            case CATALYST -> plan.catalyst();
            case BASE -> BASE_INGREDIENT_ID;
            case PROPELLANT -> PROPELLANT_INGREDIENT_ID;
        };
        if (!expected.equals(itemId)) {
            throw new IllegalArgumentException("药方不接受该投料：" + itemId);
        }
        if (slot == IngredientSlot.PROPELLANT && form != BrewForm.SPLASH) {
            throw new IllegalArgumentException("饮用药不能下火药");
        }
    }

    private void requireIngredientsBeforeStart() {
        EnumSet<IngredientSlot> required = EnumSet.of(
                IngredientSlot.PRIMARY, IngredientSlot.BASE);
        if (form == BrewForm.SPLASH) {
            required.add(IngredientSlot.PROPELLANT);
        }
        if (!ingredients.keySet().containsAll(required)) {
            throw new IllegalStateException("主料、底料、辅料或火药未齐");
        }
    }

    private void updateTemperature(double seconds, BrewStructure structure) {
        double maximumTemperature = structure.maximumTemperature();
        double targetTemperature = Math.min(maximumTemperature, structure.heatSource().equilibriumTemperature());
        double convergence = Math.min(1.0, vessel.convergencePerSecond() * seconds);
        temperature += (targetTemperature - temperature) * convergence;
        temperature = Math.max(0.0, temperature);
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
        state = BrewCauldronState.EMPTY;
        ingredients.clear();
        ingredientTimes.clear();
        waterUnits = 0;
        temperature = 0.0;
        soakSeconds = 0.0;
        brewElapsedSeconds = 0.0;
        inBandSeconds = 0.0;
        overheatSeconds = 0.0;
        evaporationSeconds = 0.0;
        scorchCount = 0;
        remainingBottles = 0;
    }

    private BrewTickResult snapshot() {
        return new BrewTickResult(state, temperature, waterUnits, brewElapsedSeconds, scorchCount);
    }
}
