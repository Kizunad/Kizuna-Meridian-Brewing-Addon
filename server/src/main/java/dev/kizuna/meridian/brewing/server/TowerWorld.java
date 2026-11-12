package dev.kizuna.meridian.brewing.server;

import java.util.List;
import java.util.Optional;

/**
 * 药塔读写世界的全部入口：六只木桶当一个料库，一只铜箱子出药，火室四格燃料，四盏铜灯，水池所在区域的环境真元。
 * 物品 ID 按 {@link BrewRecipes#sameItemId} 比，和釜认料同一条规则。宿主用真实方块实现，单测用假实现。
 */
public interface TowerWorld {
    /** 药方里写的物品（可能只写了路径，例如 {@code dry_moss}）在注册表里的完整 ID，提示里显示物品名用。 */
    String itemId(String recipeItem);

    /** 木桶里一共有几个这种物品。 */
    int count(String itemId);

    /**
     * 按木桶顺序取 {@code count} 个，每个一份；主料带上它的炮制日。取出的东西有容器的（蜂蜜瓶、龙息、熔岩桶），
     * 容器当场放回木桶。不够时有几个取几个。
     */
    List<IngredientBatch> take(String itemId, int count);

    /** 没下锅的料退回木桶；木桶放不下就掉在铜箱子前的凹槽里。 */
    void putBack(IngredientBatch batch);

    /** 铜箱子还有几个空格。 */
    int chestFreeSlots();

    /** 往铜箱子放一瓶成药。 */
    void deliverPotion(BrewKind kind, BrewQuality quality, BrewForm form);

    /** 往铜箱子放一份药渣。 */
    void deliverResidue();

    /** 火室第 {@code cell} 格现在放着什么燃料；空着、流动熔岩、灵魂火都是空。 */
    Optional<TowerFuel> fuelAt(int cell);

    /** 这一格烧完了：燃料方块变成空气。 */
    void burnOut(int cell);

    /** 木桶里还有几件能放进火室的燃料（煤炭块、原木、熔岩桶）。 */
    int barrelFuel();

    /** 从木桶取一件燃料放进第 {@code cell} 格；木桶里没有就是空。 */
    Optional<TowerFuel> refuel(int cell);

    /** 水池这里的环境真元够不够这一批要的 {@code total}；够就是空，不够或没有环境真元区域时说明原因。 */
    Optional<BrewText> ambientProblem(double total);

    /** 向水池所在区域的环境取 {@code amount} 元，返回实际取到的；同一个 {@code drawKey} 只取一次。 */
    double drawAmbient(String drawKey, double amount);

    /** 现在是第几个游戏日，算主料新鲜度用。 */
    long gameDay();

    /** 四盏铜灯的亮灭，第 i 位是第 i 盏。 */
    void showLamps(int mask);
}
