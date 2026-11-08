package dev.kizuna.meridian.brewing.server;

import java.util.EnumSet;
import java.util.Set;

/** 六种首版巫药的稳定身份和可用方式。 */
public enum BrewKind {
    DEPLETION("depletion", "枯竭药水", 0xA08A4A, FireKind.SOUL_FIRE,
            EnumSet.of(BrewUse.SPLASH, BrewUse.THROW, BrewUse.FAN, BrewUse.MIST)),
    BITTER_YELLOW("bitter_yellow", "苦黄膏", 0xC9A227, FireKind.SUN_FIRE,
            EnumSet.of(BrewUse.DRINK, BrewUse.SPLASH, BrewUse.ANOINT, BrewUse.THROW, BrewUse.FAN, BrewUse.MIST)),
    ARMOR_ROT("armor_rot", "蚀甲水", 0x5E9C7A, FireKind.SOUL_FIRE,
            EnumSet.of(BrewUse.SPLASH, BrewUse.THROW, BrewUse.FAN, BrewUse.MIST)),
    INSECT_LURE("insect_lure", "招虫水", 0xE0A030, FireKind.SUN_FIRE,
            EnumSet.of(BrewUse.SPLASH, BrewUse.THROW, BrewUse.FAN, BrewUse.MIST)),
    QI_GATHER("qi_gather", "聚气汤", 0x4FA3A0, FireKind.SUN_FIRE,
            EnumSet.of(BrewUse.DRINK)),
    CHARM("charm", "魅惑药水", 0xC77DBA, FireKind.SOUL_FIRE,
            EnumSet.of(BrewUse.SPLASH, BrewUse.THROW, BrewUse.FAN, BrewUse.MIST));

    private final String path;
    private final String displayName;
    private final int colorRgb;
    private final FireKind fireKind;
    private final Set<BrewUse> uses;

    BrewKind(String path, String displayName, int colorRgb, FireKind fireKind, Set<BrewUse> uses) {
        // 身份、颜色和用法集中在枚举，药效表不重复推导注册 ID。
        this.path = path;
        this.displayName = displayName;
        this.colorRgb = colorRgb;
        this.fireKind = fireKind;
        this.uses = Set.copyOf(uses);
    }

    public String path() {
        // 路径是稳定的注册表尾段，也用于品质组件和残药键。
        return path;
    }

    public String displayName() {
        // 服务端规则和客户端语言键共用设计稿中的中文名。
        return displayName;
    }

    public int colorRgb() {
        // 颜色只作为药水颜色的默认值，不承担品质信息。
        return colorRgb;
    }

    public FireKind fireKind() {
        // 药方的阴阳火要求由内容身份直接决定。
        return fireKind;
    }

    public Set<BrewUse> uses() {
        // 返回不可变集合，避免调用方改变药方能力。
        return uses;
    }

    public boolean supports(BrewUse use) {
        // 方式门禁集中在内容层，功法库只提交枚举方式。
        return uses.contains(use);
    }
}
