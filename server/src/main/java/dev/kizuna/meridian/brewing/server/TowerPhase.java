package dev.kizuna.meridian.brewing.server;

/** 药塔这一批走到哪：没在熬、泡料、熬制、熬好待收。 */
public enum TowerPhase {
    IDLE,
    SOAKING,
    BREWING,
    BREWED
}
