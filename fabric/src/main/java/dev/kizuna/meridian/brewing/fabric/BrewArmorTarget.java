package dev.kizuna.meridian.brewing.fabric;

/** 无身体实体的蚀甲命中槽；随原版实体与药效一起保存。 */
public interface BrewArmorTarget {
    String meridianBrewing$armorSlot();
    void meridianBrewing$armorSlot(String slot);
}
