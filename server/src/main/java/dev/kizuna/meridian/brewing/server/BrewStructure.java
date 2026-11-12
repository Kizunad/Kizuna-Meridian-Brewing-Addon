package dev.kizuna.meridian.brewing.server;

import java.util.Locale;
import java.util.Optional;

/**
 * 一次结构检查的纯数据快照：釜下的火源，加上釜所在结构和 NBT 定义比对的结果。
 *
 * <p>结构不符就起不了锅，熬制中坏了就中断；不再有旧灶“缺灶石封顶 70°”的中间状态。</p>
 */
public record BrewStructure(HeatSourceKind heatSource, StructureMatch match) {
    public static final double LIT_MAXIMUM = 100.0;

    public BrewStructure {
        if (heatSource == null || match == null) {
            throw new IllegalArgumentException("结构快照不完整");
        }
    }

    /** 结构有效、火源为 {@code heatSource} 的快照；规则单测和熬好的釜冷却时用。 */
    public static BrewStructure built(HeatSourceKind heatSource) {
        return new BrewStructure(heatSource, StructureMatch.valid());
    }

    /** 按药方要的火种看这套结构缺什么；齐了返回空。先报结构，再报火。 */
    public Optional<BrewText> problemFor(BrewKind kind, FireKind expectedFire) {
        Optional<BrewText> structureProblem = match.problem();
        if (structureProblem.isPresent()) {
            return structureProblem;
        }
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
