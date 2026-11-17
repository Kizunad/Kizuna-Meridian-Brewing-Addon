package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewJumpHeight;
import dev.kizuna.meridian.brewing.server.BrewKind;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** 丹毒给 mixin 用的公开入口；规则都在 {@link BrewElixirs}。 */
public final class BrewElixirToxinHooks {
    private BrewElixirToxinHooks() {
        // 只有静态转发。
    }

    public static float exhaustionMultiplier(Player player) {
        // 客户端不放大：饥饿只在服务端结算。
        if (player.level().isClientSide()) {
            return 1;
        }
        return BrewElixirs.exhaustionMultiplier(player);
    }

    public static boolean blocksSprint(LivingEntity entity) {
        // 两端都拦起跑，避免客户端自行维持被土毒禁止的疾跑。
        int level = BrewElixirs.level(entity, BrewingEffects.status(BrewKind.EARTH_ELIXIR));
        return level >= BrewElixirs.settings().toxins().blockedSprintLevel();
    }

    public static float jumpPower(LivingEntity entity, float original, double gravity) {
        // 用实际重力和原始起跳速度计算高度，保留其他来源已经施加的运动修正。
        int level = BrewElixirs.level(entity, BrewingEffects.status(BrewKind.EARTH_ELIXIR));
        double multiplier = BrewElixirs.settings().toxins().multiplier(BrewKind.EARTH_ELIXIR, level);
        return BrewJumpHeight.power(original, gravity, multiplier);
    }
}
