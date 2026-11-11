package dev.kizuna.meridian.brewing.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 九格晒药匾每一格各自的晒制进度。
 *
 * <p>方块实体每 tick 把每格放的物品和数量报上来：格子空了或换了物品就清零；
 * 同种物品补多了按数量摊薄（旧秒数 × 旧数量 / 新数量）；拿走一部分时进度不变。</p>
 */
public final class HerbCrateState {
    public static final int SLOT_COUNT = 9;
    private final List<SlotState> slots = new ArrayList<>(Collections.nCopies(SLOT_COUNT, SlotState.EMPTY));

    public List<SlotState> slots() {
        return List.copyOf(slots);
    }

    public double dryingSeconds(int slot) {
        return slots.get(slot).dryingSeconds();
    }

    /** 记下这一格现在放的东西；物品 ID 为空表示空格。 */
    public void observe(int slot, String itemId, int count) {
        SlotState previous = slots.get(slot);
        if (itemId == null || itemId.isBlank() || count <= 0) {
            slots.set(slot, SlotState.EMPTY);
            return;
        }
        if (!itemId.equals(previous.itemId())) {
            slots.set(slot, new SlotState(itemId, count, 0.0));
            return;
        }
        double seconds = previous.dryingSeconds();
        if (count > previous.count()) {
            seconds = previous.dryingSeconds() * previous.count() / count;
        }
        slots.set(slot, new SlotState(itemId, count, seconds));
    }

    /** 白天、不下雨时，每格要晒的生药各自加时间。 */
    public void tick(double seconds, DryingConditions conditions) {
        if (!Double.isFinite(seconds) || seconds < 0.0 || conditions == null) {
            throw new IllegalArgumentException("晒料推进参数非法");
        }
        if (!conditions.canDry()) {
            return;
        }
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            SlotState state = slots.get(slot);
            if (sunDryRecipe(state.itemId()).isPresent()) {
                slots.set(slot, new SlotState(state.itemId(), state.count(), state.dryingSeconds() + seconds));
            }
        }
    }

    /** 这一格晒够了时返回晒成的配方。 */
    public Optional<PreparationRecipe> readyRecipe(int slot) {
        SlotState state = slots.get(slot);
        return sunDryRecipe(state.itemId())
                .filter(recipe -> state.dryingSeconds() >= recipe.durationSeconds());
    }

    public void restore(List<SlotState> restored) {
        if (restored == null || restored.size() != SLOT_COUNT) {
            throw new IllegalArgumentException("晒药匾快照必须正好九格");
        }
        slots.clear();
        slots.addAll(restored);
    }

    /** 只收要晒的生药。 */
    public static boolean acceptsRaw(String itemId) {
        return sunDryRecipe(itemId).isPresent();
    }

    private static Optional<PreparationRecipe> sunDryRecipe(String itemId) {
        return Optional.ofNullable(PreparationRecipes.all().get(itemId))
                .filter(recipe -> recipe.method() == PreparationMethod.SUN_DRY);
    }

    /** 一格的物品、数量和已经晒了多少秒；空格的物品 ID 是空串。 */
    public record SlotState(String itemId, int count, double dryingSeconds) {
        public static final SlotState EMPTY = new SlotState("", 0, 0.0);

        public SlotState {
            if (itemId == null || count < 0 || !Double.isFinite(dryingSeconds) || dryingSeconds < 0.0) {
                throw new IllegalArgumentException("晒药匾格子状态非法");
            }
        }
    }
}
