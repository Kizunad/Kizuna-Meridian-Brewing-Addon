package dev.kizuna.meridian.brewing.server;

import java.util.EnumMap;
import java.util.Map;

/** 六种巫药的四档效果曲线；数值均来自旧稿，未定项在 C4 报告中列明。 */
public final class BrewEffectProfiles {
    private static final Map<BrewQuality, DepletionProfile> DEPLETION = depletionProfiles();
    private static final Map<BrewQuality, ArmorRotProfile> ARMOR_ROT = armorRotProfiles();
    private static final Map<BrewQuality, InsectLureProfile> INSECT_LURE = insectLureProfiles();
    private static final Map<BrewQuality, QiGatherProfile> QI_GATHER = qiGatherProfiles();
    private static final Map<BrewQuality, BitterYellowProfile> BITTER_SPLASH = bitterSplashProfiles();
    private static final Map<BrewQuality, BitterYellowProfile> BITTER_DRINK = bitterDrinkProfiles();
    private static final Map<BrewQuality, CharmProfile> CHARM = charmProfiles();

    private BrewEffectProfiles() {
        // 效果表不可变且按品质读取。
    }

    public static DepletionProfile depletion(BrewQuality quality) {
        // 枯竭药水包含一次腐蚀和可被肾脏免除的持续部分。
        return require(DEPLETION, quality);
    }

    public static ArmorRotProfile armorRot(BrewQuality quality) {
        // 蚀甲只影响盔甲耐久和局部护甲倍数，不读取肾脏。
        return require(ARMOR_ROT, quality);
    }

    public static InsectLureProfile insectLure(BrewQuality quality) {
        // 招虫气味属于皮肤表面效果，不读取肾脏。
        return require(INSECT_LURE, quality);
    }

    public static QiGatherProfile qiGather(BrewQuality quality) {
        // 聚气的倍率只作用于吐纳摄入和上限，炼化率仍由 Human 保持。
        return require(QI_GATHER, quality);
    }

    public static BitterYellowProfile bitterYellow(BrewQuality quality, BrewUse use) {
        // 苦黄膏饮用和喷溅的治疗、药封和反胃曲线不同。
        if (use == BrewUse.DRINK) {
            return require(BITTER_DRINK, quality);
        }
        if (use == BrewUse.SPLASH || use == BrewUse.ANOINT || use == BrewUse.THROW
                || use == BrewUse.FAN || use == BrewUse.MIST) {
            return require(BITTER_SPLASH, quality);
        }
        throw new IllegalArgumentException("苦黄膏不支持的用法：" + use);
    }

    public static CharmProfile charm(BrewQuality quality) {
        // 魅惑的免疫和控制时长由同一份品质表提供。
        return require(CHARM, quality);
    }

    private static Map<BrewQuality, DepletionProfile> depletionProfiles() {
        var profiles = new EnumMap<BrewQuality, DepletionProfile>(BrewQuality.class);
        profiles.put(BrewQuality.POOR, new DepletionProfile(3.0, 10.0, 1, 2, 0.95, 5.0, 0.90));
        profiles.put(BrewQuality.COMMON, new DepletionProfile(3.5, 13.0, 2, 2, 0.92, 5.0, 0.85));
        profiles.put(BrewQuality.FINE, new DepletionProfile(4.0, 16.0, 2, 3, 0.88, 6.0, 0.80));
        profiles.put(BrewQuality.SUPERIOR, new DepletionProfile(4.5, 20.0, 3, 3, 0.84, 7.0, 0.75));
        return Map.copyOf(profiles);
    }

    private static Map<BrewQuality, ArmorRotProfile> armorRotProfiles() {
        var profiles = new EnumMap<BrewQuality, ArmorRotProfile>(BrewQuality.class);
        profiles.put(BrewQuality.POOR, new ArmorRotProfile(3.0, 10.0, 1, 2, 0.005, 0.85));
        profiles.put(BrewQuality.COMMON, new ArmorRotProfile(3.0, 12.0, 2, 2, 0.0075, 0.80));
        profiles.put(BrewQuality.FINE, new ArmorRotProfile(3.5, 15.0, 2, 3, 0.01, 0.70));
        profiles.put(BrewQuality.SUPERIOR, new ArmorRotProfile(4.0, 20.0, 3, 3, 0.015, 0.60));
        return Map.copyOf(profiles);
    }

    private static Map<BrewQuality, InsectLureProfile> insectLureProfiles() {
        var profiles = new EnumMap<BrewQuality, InsectLureProfile>(BrewQuality.class);
        profiles.put(BrewQuality.POOR, new InsectLureProfile(3.0, 15.0, 12.0, 0));
        profiles.put(BrewQuality.COMMON, new InsectLureProfile(3.0, 20.0, 16.0, 1));
        profiles.put(BrewQuality.FINE, new InsectLureProfile(3.5, 25.0, 20.0, 2));
        profiles.put(BrewQuality.SUPERIOR, new InsectLureProfile(4.0, 30.0, 24.0, 3));
        return Map.copyOf(profiles);
    }

    private static Map<BrewQuality, QiGatherProfile> qiGatherProfiles() {
        var profiles = new EnumMap<BrewQuality, QiGatherProfile>(BrewQuality.class);
        profiles.put(BrewQuality.POOR, new QiGatherProfile(60.0, 1.3, 30.0));
        profiles.put(BrewQuality.COMMON, new QiGatherProfile(90.0, 1.5, 40.0));
        profiles.put(BrewQuality.FINE, new QiGatherProfile(120.0, 1.75, 50.0));
        profiles.put(BrewQuality.SUPERIOR, new QiGatherProfile(180.0, 2.0, 60.0));
        return Map.copyOf(profiles);
    }

    private static Map<BrewQuality, BitterYellowProfile> bitterSplashProfiles() {
        var profiles = new EnumMap<BrewQuality, BitterYellowProfile>(BrewQuality.class);
        profiles.put(BrewQuality.POOR, new BitterYellowProfile(10.0, 0.4, 4.0, 0.60, 0.70, 0));
        profiles.put(BrewQuality.COMMON, new BitterYellowProfile(12.0, 0.6, 7.2, 0.65, 0.75, 0));
        profiles.put(BrewQuality.FINE, new BitterYellowProfile(14.0, 0.8, 11.2, 0.70, 0.80, 0));
        profiles.put(BrewQuality.SUPERIOR, new BitterYellowProfile(16.0, 1.0, 16.0, 0.75, 0.85, 0));
        return Map.copyOf(profiles);
    }

    private static Map<BrewQuality, BitterYellowProfile> bitterDrinkProfiles() {
        var profiles = new EnumMap<BrewQuality, BitterYellowProfile>(BrewQuality.class);
        profiles.put(BrewQuality.POOR, new BitterYellowProfile(16.0, 0.8, 12.8, 0.80, 0.85, 10.0));
        profiles.put(BrewQuality.COMMON, new BitterYellowProfile(20.0, 1.2, 24.0, 0.85, 0.85, 8.0));
        profiles.put(BrewQuality.FINE, new BitterYellowProfile(24.0, 1.6, 38.4, 0.85, 0.90, 7.0));
        profiles.put(BrewQuality.SUPERIOR, new BitterYellowProfile(30.0, 2.0, 60.0, 0.90, 0.90, 6.0));
        return Map.copyOf(profiles);
    }

    private static Map<BrewQuality, CharmProfile> charmProfiles() {
        var profiles = new EnumMap<BrewQuality, CharmProfile>(BrewQuality.class);
        profiles.put(BrewQuality.POOR, new CharmProfile(2.5, 0, 0.8, 3.0, 10.0));
        profiles.put(BrewQuality.COMMON, new CharmProfile(3.0, 100, 1.0, 4.0, 10.0));
        profiles.put(BrewQuality.FINE, new CharmProfile(3.0, 200, 1.3, 5.0, 10.0));
        profiles.put(BrewQuality.SUPERIOR, new CharmProfile(3.5, 300, 1.6, 6.0, 10.0));
        return Map.copyOf(profiles);
    }

    private static <T> T require(Map<BrewQuality, T> profiles, BrewQuality quality) {
        // 缺少品质行属于表配置错误，不能用邻近档位代替。
        T profile = profiles.get(quality);
        if (profile == null) {
            throw new IllegalArgumentException("效果表缺少品质：" + quality);
        }
        return profile;
    }
}
