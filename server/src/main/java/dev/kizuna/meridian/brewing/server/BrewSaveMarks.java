package dev.kizuna.meridian.brewing.server;

import java.util.Objects;

/**
 * 方块实体什么时候该标脏：存档里的离散内容（状态、料、水、档位、物品）一变就标；只有连续量
 * （燃料秒、浸泡秒、晒制秒）在走时，离上次标脏满 {@link #PROGRESS_TICKS} 个 tick 才再标。
 *
 * <p>原版熔炉、酿造台计时走动时不标脏，区块卸载会丢掉上次标脏以后的进度；这里连续量每秒标一次，
 * 卸载、停服时最多丢一秒。调用方把两部分各做成能比相等的值（记录），每次标脏后用 {@link #marked}
 * 记下当时的两份值，之后只和它比。</p>
 *
 * @param <C> 离散内容
 * @param <P> 连续量
 */
public final class BrewSaveMarks<C, P> {
    public static final int PROGRESS_TICKS = 20;
    private C content;
    private P progress;
    private long markedTick;

    /** 这一刻该不该标脏：离散内容和上次不同就该；只有连续量不同，要离上次满一秒。 */
    public boolean due(C contentNow, P progressNow, long tick) {
        // 比较器读的状态在离散内容里，所以它变的那个 tick 一定会标，邻居比较器当场更新。
        if (!Objects.equals(content, contentNow)) {
            return true;
        }
        return !Objects.equals(progress, progressNow) && tick - markedTick >= PROGRESS_TICKS;
    }

    /** 记下这次标脏时的内容；读档时也记一次，刚读进来、什么都没变就不用再存。 */
    public void marked(C contentNow, P progressNow, long tick) {
        // 不管是哪个入口标的脏（右键、漏斗、tick 末），之后都只和这一份比。
        content = contentNow;
        progress = progressNow;
        markedTick = tick;
    }
}
