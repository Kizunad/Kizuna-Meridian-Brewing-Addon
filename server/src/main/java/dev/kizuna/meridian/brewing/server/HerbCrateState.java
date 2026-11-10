package dev.kizuna.meridian.brewing.server;

import java.util.ArrayList;
import java.util.List;

/** 九格晒药匾的服务端晒料状态。 */
public final class HerbCrateState {
    public static final int SLOT_COUNT = 9;
    private final List<IngredientBatch> slots = new ArrayList<>();
    private double dryingSeconds;

    public List<IngredientBatch> slots() {
        return List.copyOf(slots);
    }

    public double dryingSeconds() {
        return dryingSeconds;
    }

    public BrewActionResult insert(IngredientBatch batch) {
        if (batch == null) {
            return BrewActionResult.returned("晒药匾不能放入空投料");
        }
        if (slots.size() >= SLOT_COUNT) {
            return BrewActionResult.returned("晒药匾已满", batch);
        }
        PreparationRecipe recipe = PreparationRecipes.all().get(batch.itemId());
        if (batch.preparation() != PreparationKind.RAW || recipe == null
                || recipe.method() != PreparationMethod.SUN_DRY) {
            return BrewActionResult.returned("晒药匾只接受需要晒制的生料", batch);
        }
        slots.add(batch);
        return BrewActionResult.accepted("药材已放入晒药匾");
    }

    public void tick(double seconds, DryingConditions conditions) {
        if (!Double.isFinite(seconds) || seconds < 0.0 || conditions == null) {
            throw new IllegalArgumentException("晒料推进参数非法");
        }
        if (conditions.canDry()) {
            dryingSeconds += seconds;
        }
    }

    public List<IngredientBatch> collectPrepared(long preparedGameDay) {
        if (preparedGameDay < 0) {
            throw new IllegalArgumentException("制成日不能为负数");
        }
        List<IngredientBatch> prepared = new ArrayList<>();
        List<IngredientBatch> remaining = new ArrayList<>();
        for (IngredientBatch batch : slots) {
            PreparationRecipe recipe = PreparationRecipes.all().get(batch.itemId());
            if (recipe != null && recipe.method() == PreparationMethod.SUN_DRY
                    && dryingSeconds >= recipe.durationSeconds()) {
                prepared.add(IngredientBatch.prepared(recipe.outputItemId(), preparedGameDay,
                        batch.count()));
            } else {
                remaining.add(batch);
            }
        }
        slots.clear();
        slots.addAll(remaining);
        return List.copyOf(prepared);
    }

    public Snapshot snapshot() {
        return new Snapshot(slots, dryingSeconds);
    }

    public void restore(Snapshot snapshot) {
        if (snapshot == null || !Double.isFinite(snapshot.dryingSeconds())
                || snapshot.dryingSeconds() < 0.0 || snapshot.slots().size() > SLOT_COUNT) {
            throw new IllegalArgumentException("晒药匾快照不完整");
        }
        slots.clear();
        slots.addAll(snapshot.slots());
        dryingSeconds = snapshot.dryingSeconds();
    }

    public record Snapshot(List<IngredientBatch> slots, double dryingSeconds) {
        public Snapshot {
            if (slots == null) {
                throw new IllegalArgumentException("晒药匾槽位不能为空");
            }
            slots = List.copyOf(slots);
        }
    }
}
