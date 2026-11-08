package dev.kizuna.meridian.brewing.server;

/** 根据药种和品质选择药效表，不执行任何世界副作用。 */
public final class BrewEffectPlanner {
    private BrewEffectPlanner() {
        // 计划生成是无状态的纯规则。
    }

    public static BrewEffectPlan depletion(BrewQuality quality, DetoxResult detox) {
        DepletionProfile profile = BrewEffectProfiles.depletion(quality);
        double duration = detox.durationSeconds(profile.durationSeconds());
        return new BrewEffectPlan(BrewKind.DEPLETION, quality, BrewUse.SPLASH,
                EffectTargetScope.BODY_PART, duration, profile.muscleEnduranceMultiplier(),
                profile.partDamageHp(), profile.tempoMultiplier());
    }

    public static BrewEffectPlan armorRot(BrewQuality quality) {
        ArmorRotProfile profile = BrewEffectProfiles.armorRot(quality);
        return new BrewEffectPlan(BrewKind.ARMOR_ROT, quality, BrewUse.SPLASH,
                EffectTargetScope.ARMOR, profile.durationSeconds(),
                profile.durabilityPercentPerSecond(), profile.armorMultiplier(), 0.0);
    }

    public static BrewEffectPlan insectLure(BrewQuality quality) {
        InsectLureProfile profile = BrewEffectProfiles.insectLure(quality);
        return new BrewEffectPlan(BrewKind.INSECT_LURE, quality, BrewUse.SPLASH,
                EffectTargetScope.WHOLE_BODY, profile.durationSeconds(), profile.hearingDistance(),
                profile.silverfishCount(), profile.radius());
    }

    public static BrewEffectPlan qiGather(BrewQuality quality) {
        QiGatherProfile profile = BrewEffectProfiles.qiGather(quality);
        return new BrewEffectPlan(BrewKind.QI_GATHER, quality, BrewUse.DRINK,
                EffectTargetScope.ORGAN, profile.durationSeconds(), profile.intakeMultiplier(),
                profile.exhaustionDurationSeconds(), 0.0);
    }

    public static BrewEffectPlan bitterYellow(BrewQuality quality, BrewUse use) {
        BitterYellowProfile profile = BrewEffectProfiles.bitterYellow(quality, use);
        return new BrewEffectPlan(BrewKind.BITTER_YELLOW, quality, use,
                EffectTargetScope.BODY_PART, profile.durationSeconds(), profile.healingPerSecond(),
                profile.tempoMultiplier(), profile.strengthMultiplier());
    }

    public static BrewEffectPlan charm(BrewQuality quality) {
        CharmProfile profile = BrewEffectProfiles.charm(quality);
        return new BrewEffectPlan(BrewKind.CHARM, quality, BrewUse.SPLASH,
                EffectTargetScope.BRAIN, profile.controlSeconds(), profile.suppression(),
                profile.staggerSeconds(), profile.immunitySeconds());
    }
}
