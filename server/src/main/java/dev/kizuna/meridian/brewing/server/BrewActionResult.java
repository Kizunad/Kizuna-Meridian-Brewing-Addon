package dev.kizuna.meridian.brewing.server;

import java.util.Optional;

/** 一次可由右键触发的巫釜操作结果；退回的投料交给交互层返还。 */
public record BrewActionResult(
        BrewActionStatus status,
        BrewText message,
        Optional<IngredientBatch> returnedIngredient) {
    public BrewActionResult {
        if (status == null || message == null || returnedIngredient == null) {
            throw new IllegalArgumentException("巫釜操作结果不完整");
        }
        if (status == BrewActionStatus.ACCEPTED && returnedIngredient.isPresent()) {
            throw new IllegalArgumentException("接受操作不能携带退回投料");
        }
    }

    public boolean accepted() {
        return status == BrewActionStatus.ACCEPTED;
    }

    public boolean returned() {
        return status == BrewActionStatus.RETURNED;
    }

    public static BrewActionResult accepted(BrewText message) {
        return new BrewActionResult(BrewActionStatus.ACCEPTED, message, Optional.empty());
    }

    public static BrewActionResult returned(BrewText message) {
        return new BrewActionResult(BrewActionStatus.RETURNED, message, Optional.empty());
    }

    public static BrewActionResult returned(BrewText message, IngredientBatch ingredient) {
        return new BrewActionResult(BrewActionStatus.RETURNED, message, Optional.ofNullable(ingredient));
    }
}
