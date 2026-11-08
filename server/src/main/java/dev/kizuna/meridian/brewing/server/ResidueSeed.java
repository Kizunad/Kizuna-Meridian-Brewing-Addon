package dev.kizuna.meridian.brewing.server;

import java.util.UUID;

/** 功法掷引或泼引产生的一份残药。 */
public record ResidueSeed(
        UUID source,
        UUID target,
        BrewKind kind,
        BrewQuality quality,
        String partId,
        double dose) {
    public ResidueSeed {
        // 残药只能由功法方式创建，初始剂量必须为正。
        if (source == null || target == null || kind == null || quality == null
                || partId == null || !Double.isFinite(dose) || dose <= 0) {
            throw new IllegalArgumentException("残药种子不完整");
        }
    }

    public ResidueKey key() {
        // 同键覆盖旧残药，避免多个来源相同的内部账本叠加。
        return new ResidueKey(source, target, kind, partId);
    }
}
