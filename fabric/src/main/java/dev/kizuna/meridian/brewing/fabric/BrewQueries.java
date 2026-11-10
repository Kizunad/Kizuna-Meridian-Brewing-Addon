package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewPartGroup;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.world.entity.LivingEntity;

/** 跨附属只读查询；药效进度仍以 Brewing 和唯一身体为准。 */
public final class BrewQueries {
    private BrewQueries() {
        // 不缓存调用方的目标或身体快照。
    }

    public static long charmImmunityMillis(LivingEntity target) {
        // 无身体或身体被宿主暂停时没有免疫计时；控制期间另由魅惑会话拒绝重复施药。
        var found = BrewBodyHost.observe(target).found();
        if (found.isEmpty()) {
            return 0;
        }
        var body = found.orElseThrow().body();
        long until = BrewBodyProgress.read(body).getCharmImmunityUntilMillis();
        return Math.max(0, until - body.getSettledAt().getElapsedMillis());
    }

    public static boolean charmed(LivingEntity target) {
        // 读取当前受控身份，不能把尚未结束的控制误当作免疫期已过。
        return BrewCharmSessions.controlled(target.getUUID());
    }

    public static LimbRatios limbRatios(BodySnapshot body) {
        // 四肢比例与敷药组由同一查询计算，避免 AI 与药效各自定义伤势规则。
        Map<String, Double> hp = body.getPartStatesList().stream()
                .collect(Collectors.toUnmodifiableMap(state -> state.getId(), state -> state.getHp()));
        double armHp = 0;
        double armMaximum = 0;
        double legHp = 0;
        double legMaximum = 0;
        for (var part : body.getStructure().getPartsList()) {
            if (BrewPartSelection.matches(part.getId(), BrewPartGroup.ARMS)) {
                armHp += hp.getOrDefault(part.getId(), 0D);
                armMaximum += part.getMaxHp();
            }
            if (BrewPartSelection.matches(part.getId(), BrewPartGroup.LEGS)) {
                legHp += hp.getOrDefault(part.getId(), 0D);
                legMaximum += part.getMaxHp();
            }
        }
        double arms = ratio(armHp, armMaximum);
        double legs = ratio(legHp, legMaximum);
        double combined = ratio(armHp + legHp, armMaximum + legMaximum);
        BrewPartGroup anointGroup = arms <= legs ? BrewPartGroup.ARMS : BrewPartGroup.LEGS;
        return new LimbRatios(arms, legs, combined, anointGroup);
    }

    public static boolean matchesPart(String part, BrewPartGroup group) {
        // 跨附属只读部位身份，不暴露 Brewing 的内部选择器或可变状态。
        return BrewPartSelection.matches(part, group);
    }

    private static double ratio(double hp, double maximum) {
        // 缺少某组身体时按完整处理，避免无该组的定义被判成重伤。
        if (maximum == 0) {
            return 1;
        }
        return hp / maximum;
    }

    public record LimbRatios(double arms, double legs, double combined, BrewPartGroup anointGroup) { }
}
