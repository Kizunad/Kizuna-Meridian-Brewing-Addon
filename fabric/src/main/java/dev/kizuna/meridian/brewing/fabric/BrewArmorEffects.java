package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.protocol.pb.BrewEffectMessages.BrewDose;
import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BrewEffectProfiles;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 蚀甲只影响覆盖命中部位的装备，局部护甲折扣和原版耐久各自使用同一剂量。 */
final class BrewArmorEffects {
    private static final double HALF_EFFECT = 0.5;
    private static final double COPPER_EFFECT = 1;
    private static final long RAMP_MILLIS = 500;
    private static final long FADE_MILLIS = 1_000;
    private static final double HEAD_HEIGHT = 0.8;
    private static final double CHEST_HEIGHT = 0.5;
    private static final double LEGS_HEIGHT = 0.2;

    private BrewArmorEffects() {
        // 装备耐久保存在 ItemStack，药效来源保存在身体进度。
    }

    static double multiplier(LivingEntity entity, String partId, EquipmentSlot slot, ItemStack stack) {
        // 只有仍有显示状态的部位药效参与，牛奶移除后立即恢复额外折扣。
        if (!entity.hasEffect(BrewingEffects.ARMOR_ROT)) {
            return 1;
        }
        var found = BrewBodyHost.inspect(entity);
        if (found.isEmpty()) {
            return 1;
        }
        BodySnapshot body = found.get().body();
        long now = body.getSettledAt().getElapsedMillis();
        double strongest = 1;
        for (BrewDose dose : BrewBodyProgress.read(body).getDosesList()) {
            if (dose.getKind().equals(BrewKind.ARMOR_ROT.name()) && dose.getPartIdsList().contains(partId)) {
                var profile = BrewEffectProfiles.armorRot(BrewQuality.fromComponentValue(dose.getQuality()));
                double reduction = (1 - profile.armorMultiplier()) * material(stack) * curve(dose, now);
                strongest = Math.min(strongest, 1 - reduction);
            }
        }
        return strongest;
    }

    static void tick(LivingEntity entity, BodySnapshot body, List<BrewDose> doses) {
        // 同一件盔甲被多个部位选中仍每秒只蚀一次，选择最强的一份剂量。
        var slots = new EnumMap<EquipmentSlot, Double>(EquipmentSlot.class);
        long now = body.getSettledAt().getElapsedMillis();
        for (BrewDose dose : doses) {
            if (!dose.getKind().equals(BrewKind.ARMOR_ROT.name())) {
                continue;
            }
            var profile = BrewEffectProfiles.armorRot(BrewQuality.fromComponentValue(dose.getQuality()));
            double percent = profile.durabilityPercentPerSecond() * curve(dose, now);
            for (String part : dose.getPartIdsList()) {
                VanillaDamageFabric.coveredEquipmentSlot(body, part)
                        .ifPresent(slot -> slots.merge(slot, percent, Math::max));
                shields(entity, part, percent, slots);
            }
        }
        slots.forEach((slot, percent) -> damage(entity, slot, percent));
    }

    private static void shields(LivingEntity entity, String part, double percent,
                                 Map<EquipmentSlot, Double> slots) {
        // 主副手对应的解剖侧随左利手变化，手臂沾药才蚀该手的盾。
        if (!part.endsWith("_upper_arm") && !part.endsWith("_forearm") && !part.endsWith("_hand")) {
            return;
        }
        HumanoidArm arm = HumanoidArm.RIGHT;
        if (part.startsWith("left_")) {
            arm = HumanoidArm.LEFT;
        }
        EquipmentSlot slot = EquipmentSlot.OFFHAND;
        if (entity.getMainArm() == arm) {
            slot = EquipmentSlot.MAINHAND;
        }
        if (entity.getItemBySlot(slot).is(Items.SHIELD)) {
            slots.merge(slot, percent, Math::max);
        }
    }

    static void damage(LivingEntity entity, EquipmentSlot slot, double percent) {
        // 走原版物品损耗，处理破损动画与装备槽更新；金甲完全免疫。
        ItemStack stack = entity.getItemBySlot(slot);
        double factor = material(stack);
        if (percent <= 0 || factor == 0 || !stack.isDamageableItem()) {
            return;
        }
        int amount = Math.max(1, (int) Math.ceil(stack.getMaxDamage() * percent * factor));
        stack.hurtAndBreak(amount, entity, slot);
    }

    static double material(ItemStack stack) {
        // 铜甲沿当前配置占位；材质标签可由数据包扩展。
        if (stack.is(BrewingTags.ARMOR_ROT_IMMUNE)) {
            return 0;
        }
        if (stack.is(BrewingTags.ARMOR_ROT_HALF)) {
            return HALF_EFFECT;
        }
        if (stack.is(BrewingTags.COPPER_ARMOR)) {
            return COPPER_EFFECT;
        }
        return 1;
    }

    static void selectUnbound(LivingEntity entity, Vec3 impact) {
        // 没有身体构型时依碰撞箱高度固定一件命中装备，随后换装仍沿该槽生效。
        double height = (impact.y - entity.getY()) / entity.getBbHeight();
        EquipmentSlot slot = EquipmentSlot.FEET;
        if (height >= HEAD_HEIGHT) {
            slot = EquipmentSlot.HEAD;
        } else if (height >= CHEST_HEIGHT) {
            slot = EquipmentSlot.CHEST;
        } else if (height >= LEGS_HEIGHT) {
            slot = EquipmentSlot.LEGS;
        }
        ((BrewArmorTarget) entity).meridianBrewing$armorSlot(slot.name());
    }

    static void tickUnbound(LivingEntity entity, BrewQuality quality) {
        // 状态周期只损耗先前命中的槽；直接用命令挂状态但未命中装备时不蚀甲。
        String selected = ((BrewArmorTarget) entity).meridianBrewing$armorSlot();
        if (!selected.isEmpty()) {
            var profile = BrewEffectProfiles.armorRot(quality);
            damage(entity, EquipmentSlot.valueOf(selected), profile.durabilityPercentPerSecond());
        }
    }

    private static double curve(BrewDose dose, long now) {
        // 额外护甲折扣遵循药效的上升、平台和衰减曲线。
        long elapsed = now - dose.getStartedMillis();
        long remaining = dose.getEndsMillis() - now;
        if (elapsed <= 0 || remaining <= 0) {
            return 0;
        }
        return Math.min(1, Math.min(elapsed / (double) RAMP_MILLIS, remaining / (double) FADE_MILLIS));
    }
}
