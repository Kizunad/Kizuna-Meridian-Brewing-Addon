package dev.kizuna.meridian.brewing.server;

/** 窗口和铜灯显示的药塔状态；比 {@link TowerPhase} 多出“在等东西”“升温”“失败”“结构不对”。 */
public enum TowerStatus {
    IDLE,
    WAITING,
    SOAKING,
    HEATING,
    BREWING,
    BREWED,
    FAILED,
    BROKEN
}
