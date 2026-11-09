package dev.kizuna.meridian.brewing.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.OrganCapabilities;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;

/** 从 inspect 完整身体读取肾、脑、注意组织，构型改造会自然影响药效判定。 */
public final class BrewBodyFacts {
    private BrewBodyFacts() {
        // 这些值只读，不保存器官等级或第二份脑力账户。
    }

    public static DetoxResult detox(BodySnapshot body, BrewThresholds thresholds) {
        // 两肾按有效吞吐和各自 HP 比例贡献，器官曲线也参与排毒力。
        var actual = BodyProtobuf.fromMessage(body);
        double power = 0;
        for (var organ : body.getStructure().getOrgansList()) {
            if (!organ.getKind().equals("human:left_kidney") && !organ.getKind().equals("human:right_kidney")) {
                continue;
            }
            var state = body.getOrganStatesList().stream()
                    .filter(item -> item.getId().equals(organ.getId())).findFirst().orElseThrow();
            double throughput = OrganCapabilities.throughputPerSecond(actual, new Anatomy.OrganId(organ.getId()));
            var metrics = new KidneyMetrics(throughput, state.getHp(), organ.getMaxHp());
            power += metrics.detoxPower();
        }
        return new DetoxResult(power, DetoxTier.fromPower(power, thresholds.kidneys()));
    }

    public static CharmOutcome charm(BodySnapshot body, BrewQuality quality, BrewThresholds thresholds) {
        // 脑疲劳以焦耳计，注意组织以自身疲劳上限计，不能直接相加原始刻度。
        double power = brainPower(body) + attentionPower(body) - BrewEffectProfiles.charm(quality).suppression();
        if (power >= thresholds.brain().ignore()) {
            return CharmOutcome.IGNORE;
        }
        if (power >= thresholds.brain().stagger()) {
            return CharmOutcome.STAGGER;
        }
        return CharmOutcome.CONTROL;
    }

    private static double brainPower(BodySnapshot body) {
        // 按实际器官定义读取，不使用凡人的固定耐力代替改造后的参数。
        double power = 0;
        for (var organ : body.getStructure().getOrgansList()) {
            if (!organ.getKind().equals("human:brain")) {
                continue;
            }
            var state = body.getOrganStatesList().stream()
                    .filter(item -> item.getId().equals(organ.getId())).findFirst().orElseThrow();
            double endurance = organ.getEndurance().getEnduranceJoules();
            var metrics = new BrainMetrics(endurance, state.getFatigueJoules() / endurance,
                    state.getHp(), organ.getMaxHp());
            power += metrics.availablePower();
        }
        return power;
    }

    private static double attentionPower(BodySnapshot body) {
        // 训练成长属于权威组织状态，脑力跟随成长后的耐力及当前疲劳。
        double power = 0;
        var actual = BodyProtobuf.fromMessage(body);
        for (var tissue : body.getStructure().getTissuesList()) {
            if (!tissue.getId().equals("attention_network")) {
                continue;
            }
            var state = body.getTissueStatesList().stream()
                    .filter(item -> item.getId().equals(tissue.getId())).findFirst().orElseThrow();
            double multiplier = TissueCapabilities.enduranceMultiplier(actual, new Anatomy.TissueId(tissue.getId()));
            double endurance = tissue.getCapacity().getEnduranceJoules()
                    * (1 + state.getTrainingGrowth()) * multiplier;
            var metrics = new BrainMetrics(endurance, state.getFatigue() / tissue.getFatigueCapacity(),
                    state.getHp(), tissue.getMaxHp());
            power += metrics.availablePower();
        }
        return power;
    }
}
