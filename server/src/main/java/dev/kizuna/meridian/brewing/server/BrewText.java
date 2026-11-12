package dev.kizuna.meridian.brewing.server;

import java.util.List;

/**
 * 给玩家看的一句话：语言键加参数。server 只记键，Fabric 一侧用语言文件拼成文字。
 *
 * <p>参数只能是数字、物品 ID（{@link ItemName}）、方块 ID（{@link BlockName}）、品质（显示时按档上色）或嵌套的一句话。
 * 这样同一句话既能交给客户端按玩家语言显示，也能在服务端按本仓的中文语言文件拼出来交给功法框架。</p>
 */
public record BrewText(String key, List<Object> args) {
    public static final String MESSAGE_PREFIX = "message.meridian_brewing.";

    public BrewText {
        if (key == null || key.isBlank() || args == null) {
            throw new IllegalArgumentException("提示文本不完整");
        }
        args = List.copyOf(args);
        for (Object arg : args) {
            if (!(arg instanceof Number) && !(arg instanceof ItemName) && !(arg instanceof BlockName)
                    && !(arg instanceof BrewQuality) && !(arg instanceof BrewText)) {
                throw new IllegalArgumentException("提示参数只能是数字、物品、方块、品质或嵌套提示：" + arg);
            }
        }
    }

    /** 本仓 {@code message.meridian_brewing.} 下的一句话。 */
    public static BrewText message(String name, Object... args) {
        return new BrewText(MESSAGE_PREFIX + name, List.of(args));
    }

    /** 任意语言键，例如药方名、品质名。 */
    public static BrewText key(String key, Object... args) {
        return new BrewText(key, List.of(args));
    }

    /** 物品 ID，显示时换成物品名。 */
    public record ItemName(String itemId) {
        public ItemName {
            if (itemId == null || itemId.isBlank()) {
                throw new IllegalArgumentException("物品 ID 不能为空");
            }
        }
    }

    /** 方块 ID，显示时换成方块名；结构不符时说“现在是什么”用。 */
    public record BlockName(String blockId) {
        public BlockName {
            if (blockId == null || blockId.isBlank()) {
                throw new IllegalArgumentException("方块 ID 不能为空");
            }
        }
    }
}
