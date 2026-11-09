package dev.kizuna.meridian.brewing.server;

import dev.kizuna.meridian.brewing.protocol.pb.BrewEffectMessages.BrewBodyState;
import dev.kizuna.meridian.brewing.protocol.pb.BrewEffectMessages.BrewDose;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CapabilityModifier;

import java.util.List;
import java.util.ArrayList;

/** 生成同来源修正与药效记录的原子替换计划。 */
public final class BrewDoseOperations {
    public static final double MAX_ENDURANCE_LOSS = 0.5;
    private static final double MILLIS_PER_SECOND = 1_000;

    private BrewDoseOperations() {
        // 计划完成前不会改变身体或状态效果。
    }

    public record DoseRequest(BrewKind kind, BrewQuality quality, BrewUse use,
                               List<String> partIds, DoseTiming timing) {
        public DoseRequest(BrewKind kind, BrewQuality quality, BrewUse use,
                           List<String> partIds, double durationSeconds) {
            // 原版物品入口沿用无加成的剂量。
            this(kind, quality, use, partIds, new DoseTiming(durationSeconds, BrewPotency.ordinary(quality)));
        }

        public double durationSeconds() {
            // 剂量时长独立于强度品质，便于兼容原始物品入口。
            return timing.durationSeconds();
        }

        public BrewPotency potency() {
            // 同一次用药共用完整的强度与代价参数。
            return timing.potency();
        }
    }

    public record DoseTiming(double durationSeconds, BrewPotency potency) { }

    public static BodyActivity install(BodySnapshot body, DoseRequest request) {
        // 保留不重叠部位的旧剂量，替换部分同时撤销旧修正并登记新修正。
        BrewBodyState previous = BrewBodyProgress.read(body);
        var state = previous.toBuilder().clearDoses();
        var activity = BodyActivity.newBuilder();
        BrewDose.Builder incoming = dose(body, request);
        for (BrewDose old : previous.getDosesList()) {
            if (old.getEndsMillis() <= body.getSettledAt().getElapsedMillis()) {
                BrewDoseOperations.removeModifiers(body, old, activity);
                continue;
            }
            if (!overlaps(old, request)) {
                state.addDoses(old);
                continue;
            }
            if (request.kind() == BrewKind.QI_GATHER && old.getEndsMillis() >= incoming.getEndsMillis()) {
                BrewBodyProgress.write(activity, previous);
                return activity.build();
            }
            List<String> replaced = replacements(body, incoming, old, request);
            if (request.kind() == BrewKind.DEPLETION) {
                merge(incoming, old);
            }
            retainOtherParts(body, state, activity, old, replaced);
        }
        finish(body, request, incoming, state, activity);
        return activity.build();
    }

    private static void finish(BodySnapshot body, DoseRequest request, BrewDose.Builder incoming,
                               BrewBodyState.Builder state, BodyActivity.Builder activity) {
        // 胜出的剂量和对应修正一起保存，反胃独立于四肢是否已有伤势。
        List<CapabilityModifier> modifiers = BrewModifiers.create(body, incoming.build());
        activity.addAllAddModifiers(modifiers);
        incoming.addAllModifierIds(modifiers.stream().map(CapabilityModifier::getId).toList());
        if (incoming.getPartIdsCount() > 0 || request.kind() == BrewKind.QI_GATHER) {
            state.addDoses(incoming.build());
        }
        if (request.kind() == BrewKind.BITTER_YELLOW && request.use() == BrewUse.DRINK) {
            var profile = BrewEffectProfiles.bitterYellow(request.quality(), request.use());
            double nauseaSeconds = profile.nauseaSeconds() * request.potency().nausea();
            long nauseaEnd = incoming.getStartedMillis() + Math.round(nauseaSeconds * MILLIS_PER_SECOND);
            state.setNauseaUntilMillis(Math.max(state.getNauseaUntilMillis(), nauseaEnd));
        }
        BrewBodyProgress.write(activity, state.build());
    }

    private static List<String> replacements(BodySnapshot body, BrewDose.Builder incoming,
                                              BrewDose old, DoseRequest request) {
        // 苦黄膏逐部位比较剩余治疗量；胜出的旧剂量保留原时间、用法和药封强度。
        if (request.kind() != BrewKind.BITTER_YELLOW) {
            return request.partIds();
        }
        var remaining = new ArrayList<>(incoming.getPartIdsList());
        var replaced = new ArrayList<String>();
        var oldHealing = BitterHealing.doses(body, old, old.getEndsMillis());
        var newHealing = BitterHealing.doses(body, incoming.build(), incoming.getEndsMillis());
        for (String part : old.getPartIdsList()) {
            if (!remaining.contains(part)) {
                continue;
            }
            if (oldHealing.getOrDefault(part, 0.0) >= newHealing.getOrDefault(part, 0.0)) {
                remaining.remove(part);
            } else {
                replaced.add(part);
            }
        }
        incoming.clearPartIds()
                .addAllPartIds(remaining);
        old.getFadingPartIdsList().stream().filter(remaining::contains).forEach(replaced::add);
        return List.copyOf(replaced);
    }

    private static BrewDose.Builder dose(BodySnapshot body, DoseRequest request) {
        // 剂量起点采用身体时钟，后续 tick 只治疗尚未结算的区间。
        long now = body.getSettledAt().getElapsedMillis();
        double loss = 0;
        if (request.kind() == BrewKind.DEPLETION) {
            double baseLoss = 1 - BrewEffectProfiles.depletion(request.potency().quality()).muscleEnduranceMultiplier();
            loss = Math.min(MAX_ENDURANCE_LOSS, baseLoss * request.potency().strength());
        }
        return BrewDose.newBuilder()
                .setKind(request.kind().name())
                .setQuality(request.quality().componentValue())
                .setStrengthQuality(request.potency().quality().componentValue())
                .setStrengthMultiplier(request.potency().strength())
                .setDurationMultiplier(request.potency().duration())
                .setUse(request.use().name())
                .addAllPartIds(request.partIds())
                .setStartedMillis(now)
                .setLastHealedMillis(now)
                .setEndsMillis(now + Math.round(request.durationSeconds() * MILLIS_PER_SECOND))
                .setEnduranceLoss(loss);
    }

    private static boolean overlaps(BrewDose old, DoseRequest request) {
        // 聚气作用于两肺整体；部位药只合并本次实际沾上的部位。
        return old.getKind().equals(request.kind().name())
                && (request.kind() == BrewKind.QI_GATHER
                || old.getPartIdsList().stream().anyMatch(request.partIds()::contains)
                || old.getFadingPartIdsList().stream().anyMatch(request.partIds()::contains));
    }

    private static void merge(BrewDose.Builder incoming, BrewDose old) {
        // 枯竭耐力扣减相加封顶，持续取较长者，迟滞取较强品质。
        BrewDose fresh = incoming.build();
        double freshTempoLoss = (1 - BrewEffectProfiles.depletion(BrewPotency.quality(fresh)).tempoMultiplier())
                * BrewPotency.strength(fresh);
        double oldTempoLoss = (1 - BrewEffectProfiles.depletion(BrewPotency.quality(old)).tempoMultiplier())
                * BrewPotency.strength(old);
        if (oldTempoLoss > freshTempoLoss) {
            incoming.setStrengthQuality(BrewPotency.quality(old).componentValue());
            incoming.setStrengthMultiplier(BrewPotency.strength(old));
        }
        incoming.setEndsMillis(Math.max(incoming.getEndsMillis(), old.getEndsMillis()));
        incoming.setQuality(Math.max(incoming.getQuality(), old.getQuality()));
        incoming.setEnduranceLoss(Math.min(MAX_ENDURANCE_LOSS,
                incoming.getEnduranceLoss() + old.getEnduranceLoss()));
    }

    private static void retainOtherParts(BodySnapshot body, BrewBodyState.Builder state,
                                         BodyActivity.Builder activity, BrewDose old, List<String> replaced) {
        // 一瓶曾沾到多个部位时，刷新其中一个不能撤掉其他部位的曲线。
        List<String> retained = old.getPartIdsList().stream().filter(part -> !replaced.contains(part)).toList();
        List<String> fading = old.getFadingPartIdsList().stream().filter(part -> !replaced.contains(part)).toList();
        var remaining = old.toBuilder()
                .clearPartIds()
                .addAllPartIds(retained)
                .clearFadingPartIds()
                .addAllFadingPartIds(fading)
                .clearFadingModifiers()
                .addAllFadingModifiers(old.getFadingModifiersList().stream()
                        .filter(modifier -> fading.contains(partOf(body, modifier.getTissueId()))).toList())
                .clearModifierIds();
        for (String id : old.getModifierIdsList()) {
            var modifier = body.getModifiersList().stream().filter(item -> item.getId().equals(id)).findFirst();
            if (modifier.isEmpty()) {
                continue;
            }
            String part = "";
            if (modifier.get().hasTissueId()) {
                part = partOf(body, modifier.get().getTissueId());
            }
            if (retained.contains(part) || fading.contains(part)) {
                remaining.addModifierIds(id);
            } else {
                activity.addRemoveModifiers(id);
            }
        }
        if (!retained.isEmpty() || !fading.isEmpty()) {
            state.addDoses(remaining.build());
        }
    }

    public static String partOf(BodySnapshot body, String tissueId) {
        // 以实际构型查所属部位，不根据组织名称猜测连接。
        return body.getStructure().getTissuesList().stream().filter(tissue -> tissue.getId().equals(tissueId))
                .findFirst().orElseThrow().getPartId();
    }

    public static void removeModifiers(BodySnapshot body, BrewDose dose, BodyActivity.Builder activity) {
        // 到期修正可能已被核心移除，只撤销仍实际存在的来源 ID。
        for (String id : dose.getModifierIdsList()) {
            if (body.getModifiersList().stream().anyMatch(modifier -> modifier.getId().equals(id))) {
                activity.addRemoveModifiers(id);
            }
        }
    }
}
