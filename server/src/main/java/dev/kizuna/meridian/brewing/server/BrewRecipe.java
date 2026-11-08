package dev.kizuna.meridian.brewing.server;

/** 巫釜熬制一锅药的静态药方。 */
public record BrewRecipe(BrewKind kind, FireKind fireKind, HeatBand heatBand, IngredientPlan ingredients) {
    public BrewRecipe {
        // 药方的火种必须和药水身份一致，避免资源配置产生相反的阴阳火要求。
        if (kind == null || fireKind == null || heatBand == null || ingredients == null) {
            throw new IllegalArgumentException("药方字段不完整");
        }
        if (kind.fireKind() != fireKind) {
            throw new IllegalArgumentException("药方火种与药水身份不一致：" + kind);
        }
    }
}
