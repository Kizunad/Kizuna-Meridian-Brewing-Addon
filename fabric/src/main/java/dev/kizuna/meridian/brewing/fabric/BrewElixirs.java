package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewElixirIntake;
import dev.kizuna.meridian.brewing.server.BrewElixirSettings;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.nio.file.Path;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * 五行丹露：喝下当场得一份本行真元，记进本行开着的经；同时留一段丹毒（原版状态效果，等级 1 到 3）。
 * 丹毒在身时再喝同一行：这一次得的量打折、丹毒升一级重新计时；到了最高级再喝一点不得。牛奶解不了丹毒。
 */
final class BrewElixirs {
    private static final int TICKS_PER_SECOND = 20;
    private static final String MESSAGE = "message.meridian_brewing.elixir.";
    private static BrewElixirSettings settings;

    private BrewElixirs() {
        // 只有静态入口，数从炼药配置读。
    }

    static void initialize(Path config) {
        // 所有丹露共用一次配置加载。
        settings = BrewElixirSettings.load(config);
    }

    static BrewElixirSettings settings() {
        // 接线顺序错误必须显式失败，不能用另一份默认状态。
        if (settings == null) {
            throw new IllegalStateException("丹露配置还没有加载");
        }
        return settings;
    }

    static void drink(LivingEntity target, BrewBodyHost.Binding binding, BrewKind kind, BrewQuality quality) {
        // 先入账，成了再挂丹毒；这一次给多少看喝之前身上这一行的丹毒是几级。
        BrewElixirSettings configuration = settings();
        Holder<MobEffect> toxin = BrewingEffects.status(kind);
        int before = level(target, toxin);
        double dose = configuration.dose(quality, before);
        double credited = 0;
        if (dose > 0) {
            var intake = BrewElixirIntake.plan(binding.body(), kind.elixirElement().orElseThrow(), dose);
            credited = intake.credited();
            if (credited > 0) {
                BrewBodyHost.activity(binding, intake.activity());
            }
        }
        int after = configuration.nextToxinLevel(before);
        int ticks = (int) Math.round(configuration.toxinSeconds() * TICKS_PER_SECOND);
        target.removeEffect(toxin);
        target.addEffect(new MobEffectInstance(toxin, ticks, after - 1, false /* ambient */, false /* showParticles */,
                true /* showIcon */));
        if (target instanceof ServerPlayer player) {
            player.sendOverlayMessage(Component.translatable(MESSAGE + outcome(before, dose, credited)));
        }
    }

    private static String outcome(int before, double dose, double credited) {
        // 动作栏说一句这一口怎么样了：没得（丹毒到顶）、没处装、打了折、足量。
        if (dose <= 0) {
            return "spent";
        }
        if (credited <= 0) {
            return "no_room";
        }
        return before > 0 ? "dulled" : "taken";
    }

    /** 身上这个丹毒是几级；没有是 0。 */
    static int level(LivingEntity entity, Holder<MobEffect> toxin) {
        // 原版效果是丹毒等级与时间的唯一来源。
        MobEffectInstance active = entity.getEffect(toxin);
        if (active == null) {
            return 0;
        }
        return active.getAmplifier() + 1;
    }

    /** 火行丹毒（内焦）让饥饿消耗变成几倍；没有丹毒是 1。 */
    static float exhaustionMultiplier(LivingEntity entity) {
        // 没有火毒时读表返回原消耗。
        int level = level(entity, BrewingEffects.status(BrewKind.FIRE_ELIXIR));
        return (float) settings().toxins().multiplier(BrewKind.FIRE_ELIXIR, level);
    }

    /** 火行丹毒到了灼伤的级数：隔一阵胸口受一点热伤。 */
    static void burn(ServerLevel level, LivingEntity entity) {
        // 热伤经伤害宿主落在胸口，不直接扣原版血条。
        var source = level.damageSources().onFire();
        VanillaDamageFabric.damageDirect(new VanillaDamageFabric.DirectDamage(entity, source, "chest",
                settings().toxins().burn().hp()));
    }
}
