package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockWork;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhases;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.server.TechniqueCatalogManifest;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

/** 数据包时序覆盖不能与巫釜固定推进步长脱节。 */
public class BrewingTechniqueContractTest {
    private static final String RESOURCE = "/data/meridian_brewing/technique_catalog.txtpb";

    @Test
    public void productionCatalogMatchesRulesButChangedDurationOrIntervalIsRejected() throws Exception {
        // 加载实际资源而非复制其时长，证明启动检查覆盖目录与规则之间的重复声明。
        TechniqueDefinition definition = definition();
        BrewingTechniqueContract.validate(definition);
        TechniqueEffect effect = definition.getEffects(0);
        BlockWork work = effect.getBlockWork();
        BlockWork wrongDuration = work.toBuilder().setDurationMillis(work.getDurationMillis() - 1).build();
        BlockWork wrongInterval = work.toBuilder().setIntervalMillis(work.getIntervalMillis() + 1).build();
        assertThrows(IllegalArgumentException.class,
                () -> BrewingTechniqueContract.validate(withWork(definition, wrongDuration)));
        assertThrows(IllegalArgumentException.class,
                () -> BrewingTechniqueContract.validate(withWork(definition, wrongInterval)));
        CastPhases wrongPhases = definition.getPhases().toBuilder().setActiveMillis(1).build();
        TechniqueDefinition wrongWindow = definition.toBuilder().setPhases(wrongPhases).build();
        assertThrows(IllegalArgumentException.class, () -> BrewingTechniqueContract.validate(wrongWindow));
    }

    private static TechniqueDefinition withWork(TechniqueDefinition definition, BlockWork work) {
        // 保留其余生产声明，只改变一项周期契约。
        TechniqueEffect effect = definition.getEffects(0).toBuilder().setBlockWork(work).build();
        return definition.toBuilder().setEffects(0, effect).build();
    }

    private static TechniqueDefinition definition() throws Exception {
        // 资源必须存在并经过正式有界解析器，测试失败不能回退到手工样本。
        try (var stream = BrewingTechniqueContractTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(stream);
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return TechniqueCatalogManifest.parseText("meridian_brewing:" + RESOURCE.substring(1), text)
                    .getDefinitions(0);
        }
    }
}
