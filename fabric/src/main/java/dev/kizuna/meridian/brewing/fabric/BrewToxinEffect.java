package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import java.util.function.BiConsumer;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** 丹毒等级直接对应配置档位；动作、身体能力和原版属性各走自己的权威入口。 */
final class BrewToxinEffect extends MobEffect {
    private static final int TICKS_PER_SECOND = 20;
    private final BrewKind kind;
    private final Identifier modifierId;

    BrewToxinEffect(BrewKind kind) {
        super(MobEffectCategory.HARMFUL, kind.colorRgb());
        // 身份在等级变化和移除时保持稳定，避免重复叠属性。
        this.kind = kind;
        modifierId = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, kind.elementPath() + "_toxin");
    }

    @Override
    public void onEffectStarted(LivingEntity entity, int amplifier) {
        // 新喝、升级以及命令挂效果都立即同步副作用。
        if (kind == BrewKind.METAL_ELIXIR) {
            BrewMetalToxin.synchronize(entity);
        }
        if (kind == BrewKind.EARTH_ELIXIR && BrewElixirToxinHooks.blocksSprint(entity)) {
            entity.setSprinting(false /* sprinting */);
        }
    }

    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
        // 周期伤害与身体修正由原版效果剩余时间驱动。
        switch (kind) {
            case FIRE_ELIXIR -> BrewElixirs.burn(level, entity);
            case METAL_ELIXIR -> BrewMetalToxin.synchronize(entity);
            default -> { }
        }
        return true;
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        // 火毒第一次灼伤要等满间隔；金毒周期核对被宿主暂停或重载的身体。
        if (kind == BrewKind.FIRE_ELIXIR) {
            var burn = BrewElixirs.settings().toxins().burn();
            int interval = Math.max(1, (int) Math.round(burn.intervalSeconds() * TICKS_PER_SECOND));
            return amplifier + 1 >= burn.level() && (duration - 1) % interval == 0;
        }
        return kind == BrewKind.METAL_ELIXIR && duration % TICKS_PER_SECOND == 0;
    }

    @Override
    public void createModifiers(int amplifier, BiConsumer<Holder<Attribute>, AttributeModifier> consumer) {
        // 木、水的曲线可逐级配置，不使用原版按等级线性放大的固定常量。
        Holder<Attribute> attribute = switch (kind) {
            case WOOD_ELIXIR -> Attributes.ATTACK_SPEED;
            case WATER_ELIXIR -> Attributes.MOVEMENT_SPEED;
            default -> null;
        };
        if (attribute != null) {
            double multiplier = BrewElixirs.settings().toxins().multiplier(kind, amplifier + 1);
            consumer.accept(attribute, new AttributeModifier(modifierId, multiplier - 1,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    @Override
    public void addAttributeModifiers(AttributeMap attributes, int amplifier) {
        // 属性随原版玩家存档保存，读档不会重新挂效果；替换同名修正避免积累旧等级。
        createModifiers(amplifier, (attribute, modifier) -> {
            var instance = attributes.getInstance(attribute);
            if (instance != null) {
                instance.removeModifier(modifierId);
                instance.addPermanentModifier(modifier);
            }
        });
    }

    @Override
    public void removeAttributeModifiers(AttributeMap attributes) {
        // 移除只按身份，不需要读取已消失的效果等级。
        createModifiers(0, (attribute, modifier) -> {
            var instance = attributes.getInstance(attribute);
            if (instance != null) {
                instance.removeModifier(modifierId);
            }
        });
    }
}
