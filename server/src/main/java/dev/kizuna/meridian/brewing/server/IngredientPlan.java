package dev.kizuna.meridian.brewing.server;

import java.util.Optional;

/** 一张药方的主料、辅料、引药和投料时窗。 */
public record IngredientPlan(
        String preparedPrimary,
        String accessory,
        TimingWindow accessoryWindow,
        String catalyst,
        TimingWindow catalystWindow) {
    public IngredientPlan {
        // 允许没有引药的药方，但主料、辅料和对应时窗必须完整。
        requireText(preparedPrimary, "主料");
        requireText(accessory, "辅料");
        requireText(catalyst, "引药");
    }

    public Optional<TimingWindow> window(IngredientSlot slot) {
        return switch (slot) {
            case ACCESSORY -> Optional.of(accessoryWindow);
            case CATALYST -> Optional.of(catalystWindow);
            default -> Optional.empty();
        };
    }

    private static void requireText(String value, String label) {
        // 药方身份来自资源，空字符串会让下料校验失去确定性。
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
    }
}
