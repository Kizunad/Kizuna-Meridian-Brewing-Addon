package dev.kizuna.meridian.brewing.server;

import java.util.Locale;
import java.util.Optional;

public record BrewStructure(HeatSourceKind heatSource) {
    public static final double LIT_MAXIMUM = 100.0;

    public BrewStructure {
        if (heatSource == null) {
            throw new IllegalArgumentException("缺少火源状态");
        }
    }

    /** 釜下火源为 {@code heatSource} 的快照；规则单测和熬好的釜冷却时用。 */
    public static BrewStructure built(HeatSourceKind heatSource) {
        return new BrewStructure(heatSource);
    }

    /** 按药方所需火种检查釜下的实际火源。 */
    public Optional<BrewText> problemFor(BrewKind kind, FireKind expectedFire) {
        if (heatSource == HeatSourceKind.NONE) {
            return Optional.of(BrewText.message("structure.no_fire"));
        }
        if (heatSource.fireKind() != expectedFire) {
            return Optional.of(BrewText.message("structure.wrong_fire",
                    BrewCauldron.recipeName(kind), fireName(expectedFire)));
        }
        return Optional.empty();
    }

    /** 火种名，例如「阳火」。 */
    public static BrewText fireName(FireKind fireKind) {
        return BrewText.message("fire." + fireKind.name().toLowerCase(Locale.ROOT));
    }

    /** 没火就是环境温度；有火时温度只受火源档位限制。 */
    public double maximumTemperature() {
        if (heatSource == HeatSourceKind.NONE) {
            return heatSource.equilibriumTemperature();
        }
        return LIT_MAXIMUM;
    }
}
