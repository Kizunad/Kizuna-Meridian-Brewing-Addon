package dev.kizuna.meridian.brewing.server;

import java.util.Locale;

/**
 * 熬药结构外框的五个材料类。外框格只看世界里的方块和 NBT 那格是不是同一类，不比形状、朝向。
 *
 * <p>每个类对应 Brewing 的方块标签 {@code meridian_brewing:frame/<类名>}；
 * 用户 11-11 原话是“任一石头是石头，木头是木头即可”。</p>
 */
public enum FrameClass {
    STONE,
    WOOD,
    GLASS,
    BARS,
    CHAINS;

    /** 标签路径，例如 {@code frame/stone}。 */
    public String tagPath() {
        return "frame/" + name().toLowerCase(Locale.ROOT);
    }

    /** 不符时“应为”的那几个字，例如「任一石头方块」。 */
    public BrewText expected() {
        return BrewText.message("structure.frame." + name().toLowerCase(Locale.ROOT));
    }
}
