package dev.kizuna.meridian.brewing.server;

import java.util.Set;

/**
 * 药方对熬药真元的要求。这轮所有药方都是 {@link #NONE}，熬药耗元不读这些字段；
 * 以后要按环境浓度、自身真元密度、釜阶或属性设门槛时，接在这里，不另开一套药方数据。
 */
public record BrewQiRule(Source source, double minAmbientConcentration, double minSelfDensity,
                         int minVesselTier, Set<String> attributes) {
    /** 不要求：环境和自身都能付，不看浓度、密度、釜阶，任何属性都行。 */
    public static final BrewQiRule NONE = new BrewQiRule(Source.ANY, 0, 0, 0, Set.of());

    public BrewQiRule {
        // 门槛都是非负数；属性集合为空表示任何属性。
        if (source == null || attributes == null) {
            throw new IllegalArgumentException("药方真元要求字段不完整");
        }
        if (!(minAmbientConcentration >= 0) || !(minSelfDensity >= 0) || minVesselTier < 0) {
            throw new IllegalArgumentException("药方真元门槛不能为负");
        }
        attributes = Set.copyOf(attributes);
    }

    /** 这锅真元可以从哪里来。 */
    public enum Source {
        ANY,
        AMBIENT_ONLY,
        SELF_ONLY
    }
}
