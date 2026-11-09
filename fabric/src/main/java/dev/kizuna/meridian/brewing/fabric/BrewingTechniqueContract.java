package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockWork;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;

/** 启动时核对本仓功法时序与巫釜规则，避免目录与处理器推进步长漂移。 */
public final class BrewingTechniqueContract {
    public static final String TECHNIQUE_ID = "meridian_brewing:brew";
    public static final long STEP_MILLIS = 1_000;
    public static final long ENTRY_MILLIS = STEP_MILLIS;
    private static final long RECOVERY_MILLIS = STEP_MILLIS;
    private static final String PROCESSOR_ID = "meridian_brewing:block_work";

    private BrewingTechniqueContract() {
        // 契约检查只读取最终装载的声明。
    }

    static void validate(TechniqueRegistry registry) {
        // 检查运行时实际装载的版本，数据包覆盖同样必须满足推进规则。
        TechniqueDefinition definition = registry.findLatest(TECHNIQUE_ID)
                .orElseThrow(() -> new IllegalArgumentException("缺少熬药式目录"));
        validate(definition);
    }

    public static void validate(TechniqueDefinition definition) {
        // 一秒进式、三十秒可打断搅药和一秒收势共同约束周期事件的起止。
        var effects = definition.getEffectsList().stream().filter(TechniqueEffect::hasBlockWork).toList();
        long brewMillis = Math.round(BrewCauldron.BREW_SECONDS * STEP_MILLIS);
        if (effects.size() != 1) {
            throw new IllegalArgumentException("熬药式必须恰有一个 block_work 声明");
        }
        TechniqueEffect effect = effects.getFirst();
        BlockWork work = effect.getBlockWork();
        var phases = definition.getPhases();
        if (!PROCESSOR_ID.equals(work.getProcessorId()) || effect.getAtMillis() != ENTRY_MILLIS
                || work.getIntervalMillis() != STEP_MILLIS || work.getDurationMillis() != brewMillis
                || phases.getWarmupMillis() != ENTRY_MILLIS + brewMillis || phases.getActiveMillis() != 0
                || phases.getRecoveryMillis() != RECOVERY_MILLIS || !phases.getInterruptibleByDamage()) {
            throw new IllegalArgumentException("熬药式时序与巫釜 BREW_SECONDS 或打断规则不一致");
        }
    }
}
