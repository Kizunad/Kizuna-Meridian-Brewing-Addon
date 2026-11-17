package dev.kizuna.meridian.brewing.server;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** 六种首版巫药和五行丹露的稳定身份和可用方式。新种类只往后加：序号进了已学药方的位图和药塔的选择。 */
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
            EnumSet.of(BrewUse.SPLASH, BrewUse.THROW, BrewUse.FAN, BrewUse.MIST)),
    /** 火行丹露：喝下得一份火行真元，留一段丹毒。 */
    FIRE_ELIXIR("fire_elixir", "炽膏丹露", 0xD9541E, FireKind.SUN_FIRE, "meridian:fire"),
    METAL_ELIXIR("metal_elixir", "白矾丹露", 0xD8D8E0, FireKind.SUN_FIRE, "meridian:metal"),
    WOOD_ELIXIR("wood_elixir", "青藤丹露", 0x5FA84A, FireKind.SUN_FIRE, "meridian:wood"),
    WATER_ELIXIR("water_elixir", "井华丹露", 0x4A7FD9, FireKind.SOUL_FIRE, "meridian:water"),
    EARTH_ELIXIR("earth_elixir", "地脂丹露", 0xA07A48, FireKind.SUN_FIRE, "meridian:earth");

    private final String path;
    private final String displayName;
    private final int colorRgb;
    private final FireKind fireKind;
    private final Set<BrewUse> uses;
    private final Optional<String> elixirElement;

    BrewKind(String path, String displayName, int colorRgb, FireKind fireKind, Set<BrewUse> uses) {
        // 身份、颜色和用法集中在枚举，药效表不重复推导注册 ID。
        this.path = path;
        this.displayName = displayName;
        this.colorRgb = colorRgb;
        this.fireKind = fireKind;
        this.uses = Set.copyOf(uses);
        this.elixirElement = Optional.empty();
    }

    BrewKind(String path, String displayName, int colorRgb, FireKind fireKind, String elixirElement) {
        // 丹露只能喝：不泼、不抹、不给功法用，也就不留残药；给的是哪一行的真元写在这里。
        this.path = path;
        this.displayName = displayName;
        this.colorRgb = colorRgb;
        this.fireKind = fireKind;
        this.uses = Set.of(BrewUse.DRINK);
        this.elixirElement = Optional.of(elixirElement);
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

    /** 药塔里能熬的：六种巫药，丹露不进塔。顺序和序号一致，塔的按钮、已选药方都按这个下标记。 */
    public static BrewKind[] towerKinds() {
        // 丹露不占用药塔的方子按钮。
        return Arrays.stream(values()).filter(kind -> !kind.elixir()).toArray(BrewKind[]::new);
    }

    public static BrewKind[] elixirKinds() {
        // 注册、配置和验证共用同一份丹露目录。
        return Arrays.stream(values()).filter(BrewKind::elixir).toArray(BrewKind[]::new);
    }

    public String elementPath() {
        // 属性的路径也用于丹毒配置和效果身份。
        String element = elixirElement.orElseThrow();
        return element.substring(element.indexOf(':') + 1);
    }

    public Optional<String> elixirElement() {
        // 丹露给的那一行真元的属性 ID；六种巫药是空。
        return elixirElement;
    }

    public boolean elixir() {
        // 是不是五行丹露。
        return elixirElement.isPresent();
    }

    public boolean supports(BrewUse use) {
        // 方式门禁集中在内容层，功法库只提交枚举方式。
        return uses.contains(use);
    }
}
