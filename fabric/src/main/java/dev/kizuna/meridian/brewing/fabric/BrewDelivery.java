package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewPotency;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewPartGroup;
import java.util.List;

/** 一次用药的强度和部位选择，残药催发可以显式限定原来的部位。 */
record BrewDelivery(BrewPotency potency, BrewPartGroup bias, List<String> fixedParts) {
    static BrewDelivery ordinary(BrewQuality quality) {
        // 普通物品仍按距离抽部位，不附加功法偏向。
        return new BrewDelivery(BrewPotency.ordinary(quality), BrewPartGroup.BREW_PART_GROUP_UNSPECIFIED, List.of());
    }
}
