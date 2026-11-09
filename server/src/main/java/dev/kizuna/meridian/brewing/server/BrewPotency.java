package dev.kizuna.meridian.brewing.server;

import dev.kizuna.meridian.brewing.protocol.pb.BrewEffectMessages.BrewDose;

/** 强度与品质分开保存，药封和反胃仍按药瓶原品质计算。 */
public record BrewPotency(BrewQuality quality, double strength, double duration, double nausea) {
    public BrewPotency {
        // 倍率不能非有限或反向，反胃允许归零以表示外敷。
        if (quality == null || !Double.isFinite(strength) || strength <= 0
                || !Double.isFinite(duration) || duration <= 0 || !Double.isFinite(nausea) || nausea < 0) {
            throw new IllegalArgumentException("药效加成参数非法");
        }
    }

    public static BrewPotency ordinary(BrewQuality quality) {
        // 普通物品用药没有功法加成。
        return new BrewPotency(quality, 1, 1, 1);
    }

    public static BrewQuality quality(BrewDose dose) {
        // 旧存档未声明强度品质时沿用药瓶品质。
        int value = dose.getQuality();
        if (dose.hasStrengthQuality()) {
            value = dose.getStrengthQuality();
        }
        return BrewQuality.fromComponentValue(value);
    }

    public static double strength(BrewDose dose) {
        // optional 保留旧剂量没有倍率字段时的单位强度。
        if (dose.hasStrengthMultiplier()) {
            return dose.getStrengthMultiplier();
        }
        return 1;
    }

    public static double duration(BrewDose dose) {
        // 器官药的正向段按声明倍率伸缩，肺虚代价不随强度升级。
        if (dose.hasDurationMultiplier()) {
            return dose.getDurationMultiplier();
        }
        return 1;
    }
}
