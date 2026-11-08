package dev.kizuna.meridian.brewing.server;

import java.util.UUID;

/** 残药唯一键；同一来源、目标、药种、部位只保留一份。 */
public record ResidueKey(UUID source, UUID target, BrewKind kind, String partId) {
    public ResidueKey {
        // 魅惑药没有部位，其他药的部位由宿主提供稳定 ID。
        if (source == null || target == null || kind == null || partId == null) {
            throw new IllegalArgumentException("残药键不完整");
        }
    }
}
