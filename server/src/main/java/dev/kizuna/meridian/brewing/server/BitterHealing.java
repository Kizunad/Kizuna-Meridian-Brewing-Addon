package dev.kizuna.meridian.brewing.server;

import dev.kizuna.meridian.brewing.protocol.pb.BrewEffectMessages.BrewDose;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HealBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.Healing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 苦黄膏按部位分配表里两份治疗预算，内部按骨、肌肉、皮的顺序修复。 */
public final class BitterHealing {
    private static final double MILLIS_PER_SECOND = 1_000;
    static final long RAMP_MILLIS = 1_000;
    static final long FADE_MILLIS = 2_000;
    private static final List<String> LAYERS = List.of("human:bone", "human:muscle", "human:skin");
    private static final List<List<String>> LIMB_GROUPS = List.of(
            List.of("upper_arm", "thigh"), List.of("forearm", "lower_leg"), List.of("hand", "foot"));
    private static final double[] DRINK_SHARES = {0.5, 0.3, 0.2};
    private static final double MEAN_DIVISOR = 2;

    private BitterHealing() {
        // 只生成健康命令的账户剂量，提交由唯一宿主完成。
    }

    public static boolean isLimb(String partId) {
        // 只接受十二个四肢部位，骨盆和躯干不因邻近而获得治疗。
        return LIMB_GROUPS.stream().flatMap(List::stream)
                .anyMatch(suffix -> partId.equals("left_" + suffix) || partId.equals("right_" + suffix));
    }

    public static double wound(BodySnapshot body, String partId) {
        // 部位归零不可接回；部位与内部预算各自计算，取较大缺口作为分配权重。
        double hp = body.getPartStatesList().stream().filter(part -> part.getId().equals(partId))
                .findFirst().orElseThrow().getHp();
        if (hp == 0) {
            return 0;
        }
        double maximum = body.getStructure().getPartsList().stream().filter(part -> part.getId().equals(partId))
                .findFirst().orElseThrow().getMaxHp();
        double internal = 0;
        for (var tissue : body.getStructure().getTissuesList()) {
            if (tissue.getPartId().equals(partId) && LAYERS.contains(tissue.getKind())) {
                double current = tissueHp(body, tissue.getId());
                internal += tissue.getMaxHp() - current;
            }
        }
        return Math.max(maximum - hp, internal);
    }

    public static Map<String, Double> doses(BodySnapshot body, BrewDose dose, long nowMillis) {
        // 每秒按实际曲线积分，服务端跳过 tick 也不会重复治疗已结算区间。
        BrewUse use = BrewUse.valueOf(dose.getUse());
        var profile = BrewEffectProfiles.bitterYellow(BrewQuality.fromComponentValue(dose.getQuality()), use);
        double seconds = healingSeconds(dose, nowMillis);
        double budget = profile.healingPerSecond() * seconds;
        var result = new LinkedHashMap<String, Double>();
        if (use == BrewUse.DRINK) {
            allocateDrink(body, dose.getPartIdsList(), budget, result);
        } else {
            for (String partId : dose.getPartIdsList()) {
                result.put(partId, Math.min(budget, wound(body, partId)));
            }
        }
        return result;
    }

    private static void allocateDrink(BodySnapshot body, List<String> parts, double budget,
                                       Map<String, Double> result) {
        // 上层未用完的预算让给下一层，同层按伤势比例分配。
        double carry = 0;
        for (int index = 0; index < LIMB_GROUPS.size(); index++) {
            List<String> group = LIMB_GROUPS.get(index);
            List<String> selected = parts.stream()
                    .filter(part -> group.stream().anyMatch(suffix -> part.endsWith("_" + suffix)))
                    .toList();
            double totalWound = selected.stream().mapToDouble(part -> wound(body, part)).sum();
            double available = budget * DRINK_SHARES[index] + carry;
            double allocated = Math.min(available, totalWound);
            if (totalWound > 0) {
                for (String part : selected) {
                    result.put(part, allocated * wound(body, part) / totalWound);
                }
            }
            carry = available - allocated;
        }
    }

    public static HealBody command(BodySnapshot body, Map<String, Double> doses) {
        // 同一次治疗中的目标唯一，表里预算分别落到指定账户。
        var healing = HealBody.newBuilder().setSourceId(BrewBodyProgress.KEY);
        for (var entry : doses.entrySet()) {
            if (entry.getValue() <= 0 || wound(body, entry.getKey()) == 0) {
                continue;
            }
            var target = HealthTarget.newBuilder().setPartId(entry.getKey()).build();
            healing.addDoses(healing(target, entry.getValue()));
            addInternal(body, entry.getKey(), entry.getValue(), healing);
        }
        return healing.build();
    }

    private static void addInternal(BodySnapshot body, String partId, double budget, HealBody.Builder healing) {
        // 骨满后才补肌肉，肌肉满后才补皮，内部总量不能超过对应部位的那份预算。
        double remaining = budget;
        for (String kind : LAYERS) {
            for (var tissue : body.getStructure().getTissuesList()) {
                if (!tissue.getPartId().equals(partId) || !tissue.getKind().equals(kind)) {
                    continue;
                }
                double missing = tissue.getMaxHp() - tissueHp(body, tissue.getId());
                double amount = Math.min(remaining, missing);
                if (amount > 0) {
                    var target = HealthTarget.newBuilder().setTissueId(tissue.getId()).build();
                    healing.addDoses(healing(target, amount));
                    remaining -= amount;
                }
            }
        }
    }

    private static Healing healing(HealthTarget target, double amount) {
        // 内层消息先独立构造，便于核对每个账户的单位和剂量。
        return Healing.newBuilder()
                .setTarget(target)
                .setHp(amount)
                .build();
    }

    private static double tissueHp(BodySnapshot body, String tissueId) {
        // 实际构型和状态已由 Framework 完整校验。
        return body.getTissueStatesList().stream().filter(state -> state.getId().equals(tissueId))
                .findFirst().orElseThrow().getHp();
    }

    private static double healingSeconds(BrewDose dose, long nowMillis) {
        // 曲线的上升、平台和下降分段求梯形面积，末端只结算到药效结束。
        long start = dose.getStartedMillis();
        long end = dose.getEndsMillis();
        long from = Math.max(start, dose.getLastHealedMillis());
        long to = Math.min(end, nowMillis);
        var window = new Interval(from, to);
        double rising = area(window, new Interval(start, start + RAMP_MILLIS), Slope.RISING);
        double plateau = Math.max(0, Math.min(to, end - FADE_MILLIS) - Math.max(from, start + RAMP_MILLIS));
        double falling = area(window, new Interval(end - FADE_MILLIS, end), Slope.FALLING);
        return (rising + plateau + falling) / MILLIS_PER_SECOND;
    }

    private static double area(Interval window, Interval segment, Slope slope) {
        // 单段面积按平均高度计算，区间外不产生治疗。
        long left = Math.max(window.start(), segment.start());
        long right = Math.min(window.end(), segment.end());
        if (right <= left) {
            return 0;
        }
        double risingMean = ((left - segment.start()) + (right - segment.start()))
                / (MEAN_DIVISOR * (segment.end() - segment.start()));
        double mean = risingMean;
        if (slope == Slope.FALLING) {
            mean = 1 - risingMean;
        }
        return (right - left) * mean;
    }

    private record Interval(long start, long end) { }

    private enum Slope { RISING, FALLING }
}
