package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewPotency;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewPartGroup;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewUseMethod;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.UseBrew;
import dev.kizuna.meridian.technique.fabric.world.TechniqueSounds;
import dev.kizuna.meridian.technique.server.EffectHandling;
import dev.kizuna.meridian.technique.server.TechniqueEffectEvent;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** 功法用药只在效果边界消耗真实药瓶，准备声音不改变库存。 */
final class BrewTechniqueUse {
    private static final String PROCESSOR = "meridian_brewing:use_brew";
    private static final double HALF_ANGLE = 2;
    private static final Set<EffectKey> handled = new HashSet<>();
    private static final Map<CastKey, BrewPartGroup> anointGroups = new HashMap<>();

    private BrewTechniqueUse() {
        // 状态只用于单次施放幂等和敷药动作分组。
    }

    static EffectHandling handle(MinecraftServer server, TechniqueEffectEvent event) {
        // 只接管声明了功法参数的用药；普通物品用法仍由既有入口执行。
        var intent = event.intent();
        UseBrew use = intent.getUseBrew();
        if (intent.hasLaunchProjectile()) {
            use = intent.getLaunchProjectile().getProjectile().getBrew();
        }
        if (!PROCESSOR.equals(use.getProcessorId()) || !use.hasTechnique()) {
            return EffectHandling.notApplicable();
        }
        LivingEntity actor = actor(server, event.entityUuid());
        if (actor == null) {
            return EffectHandling.rejected("施药者不在当前服务器");
        }
        var key = new EffectKey(new CastKey(event.entityUuid(), intent.getCastId()), intent.getEffectId());
        if (handled.contains(key)) {
            return EffectHandling.handled();
        }
        ItemStack stack = actor.getMainHandItem();
        String rejection = validate(stack, actor, use, event);
        if (!rejection.isEmpty()) {
            return EffectHandling.rejected(rejection);
        }
        EffectHandling result = execute(actor, stack, use, event, key.cast());
        if (result.accepted()) {
            handled.add(key);
        }
        return result;
    }

    private static String validate(ItemStack stack, LivingEntity actor, UseBrew use, TechniqueEffectEvent event) {
        // 效果时再次核对实际主手和同次 inspect，起手后换瓶也不能越过形态和药种限制。
        if (!BrewItemStacks.isBrew(stack)) {
            return "要拿一瓶巫药";
        }
        String kind = "meridian_brewing:" + BrewItemStacks.kind(stack).path();
        var held = event.heldItems().getMainHand();
        int quality = BrewItemStacks.quality(stack).componentValue();
        if (!kind.equals(held.getBrewKindId()) || held.getBrewQuality() != quality
                || !use.getBrewKindIdsList().contains(kind)) {
            return "手里的药已换了，这一式用不了";
        }
        BrewUseMethod physical = BrewUseMethod.DRINK;
        if (stack.is(Items.SPLASH_POTION)) {
            physical = BrewUseMethod.SPLASH;
        }
        if (!use.getUseMethodsList().contains(physical)) {
            if (use.getUseMethodsList().contains(BrewUseMethod.SPLASH)) {
                return "要喷溅型的巫药";
            }
            return "要拿一瓶饮用的巫药";
        }
        if (use.getTechnique().getMethod() == BrewUseMethod.ANOINT && !actor.getOffhandItem().isEmpty()) {
            return "敷药要空出副手";
        }
        BrewUseMethod method = use.getTechnique().getMethod();
        if ((method == BrewUseMethod.DRINK || method == BrewUseMethod.MIST) && BrewNausea.blocks(actor, stack)) {
            return "还在反胃，喝不下药";
        }
        return "";
    }

    private static EffectHandling execute(LivingEntity actor, ItemStack stack, UseBrew use,
                                           TechniqueEffectEvent event, CastKey cast) {
        // 敷药动作先固定伤势分组，后续声音与药封使用同一组。
        BrewUseMethod method = use.getTechnique().getMethod();
        if (method == BrewUseMethod.ANOINT) {
            anointGroups.computeIfAbsent(cast,
                    ignored -> BrewPartSelection.anointGroup(BrewBodyHost.inspect(actor).orElseThrow().body()));
        }
        if (use.hasPreparationSound()) {
            if (anointGroups.get(cast) == use.getSoundGroup()) {
                TechniqueSounds.play(actor, use.getPreparationSound());
            }
            return EffectHandling.handled();
        }
        BrewKind kind = BrewItemStacks.kind(stack);
        BrewQuality quality = BrewItemStacks.quality(stack);
        var potency = potency(quality, use);
        switch (method) {
            case DRINK, ANOINT -> self(actor, new BrewBodyEffects.Application(
                    kind, quality, BrewUse.DRINK, actor.position(), actor), potency, method, cast);
            case FAN -> fan(actor, kind, quality, use);
            case MIST -> BrewResidues.trigger(actor, kind, use.getTechnique());
            case THROW -> BrewProjectiles.launch(actor, stack, event.intent().getLaunchProjectile().getProjectile(),
                    event.intent().getTargetId());
            default -> { return EffectHandling.rejected("不支持的功法用药方式"); }
        }
        consume(actor, stack, method);
        return EffectHandling.handled();
    }

    private static void self(LivingEntity actor, BrewBodyEffects.Application dose, BrewPotency potency,
                             BrewUseMethod method, CastKey cast) {
        // 服药只作用自身；外敷限定此前选定组，不能因动作期间伤势变化而换到另一边。
        BrewUse deliveryUse = BrewUse.DRINK;
        List<String> parts = List.of();
        if (method == BrewUseMethod.ANOINT) {
            deliveryUse = BrewUse.ANOINT;
            BrewPartGroup group = anointGroups.get(cast);
            parts = BrewBodyHost.inspect(actor).orElseThrow().body().getPartStatesList().stream()
                    .filter(part -> part.getHp() > 0).map(part -> part.getId())
                    .filter(part -> BrewPartSelection.matches(part, group)).toList();
        }
        var application = new BrewBodyEffects.Application(dose.kind(), dose.quality(), deliveryUse,
                actor.position(), actor);
        var delivery = new BrewDelivery(potency, BrewPartGroup.BREW_PART_GROUP_UNSPECIFIED, parts);
        BrewBodyEffects.apply(actor, application, delivery);
    }

    private static void fan(LivingEntity actor, BrewKind kind, BrewQuality quality, UseBrew use) {
        // 扇形按实际距离排序截断，视线阻挡仍挡泼洒，噀催才允许隔墙。
        var parameters = use.getTechnique();
        double range = parameters.getRange().getValue();
        double cosine = Math.cos(Math.toRadians(parameters.getFanAngle().getValue() / HALF_ANGLE));
        Vec3 forward = actor.getLookAngle().multiply(1, 0, 1).normalize();
        var targets = actor.level().getEntitiesOfClass(LivingEntity.class, actor.getBoundingBox().inflate(range),
                        target -> target != actor && target.isAlive() && target.distanceToSqr(actor) <= range * range
                                && inFan(actor, target, forward, cosine) && actor.hasLineOfSight(target))
                .stream().sorted(Comparator.comparingDouble(actor::distanceToSqr))
                .limit(parameters.getMaximumTargets()).toList();
        for (LivingEntity target : targets) {
            var application = new BrewBodyEffects.Application(kind, quality, BrewUse.FAN, actor.position(), actor);
            applySeed(target, application, use, BrewPartGroup.FRONT_CHEST_ARMS);
            if (kind == BrewKind.INSECT_LURE) {
                BrewingRuntime.spawnSilverfish((ServerLevel) actor.level(), target.position(), quality, actor, 1);
            }
        }
        if (kind == BrewKind.DEPLETION) {
            WitheringService.enqueue((ServerLevel) actor.level(), actor.position().add(forward.scale(range)),
                    BrewingRuntime.DEFAULT_SPLASH_RADIUS, actor);
        }
    }

    private static boolean inFan(LivingEntity actor, LivingEntity target, Vec3 forward, double cosine) {
        // 扇形角度只看水平朝向，目标身高不改变前方判定。
        Vec3 offset = target.position().subtract(actor.position()).multiply(1, 0, 1).normalize();
        return offset.dot(forward) >= cosine;
    }

    static void applySeed(LivingEntity target, BrewBodyEffects.Application application, UseBrew use,
                          BrewPartGroup bias) {
        // 先由身体处理器返回真实沾药部位，再登记这些部位的残药。
        var delivery = new BrewDelivery(potency(application.quality(), use), bias, List.of());
        List<String> parts = BrewBodyEffects.apply(target, application, delivery);
        BrewResidues.seed(target, application, parts, use.getTechnique().getResidueDose());
    }

    private static BrewPotency potency(BrewQuality quality, UseBrew use) {
        // 升档只影响强度，原品质的持续和代价另算；已经顶档才使用目录的封顶倍率。
        var parameters = use.getTechnique();
        int maximum = BrewQuality.SUPERIOR.componentValue();
        int promoted = Math.min(maximum, quality.componentValue() + parameters.getStrengthQualitySteps());
        double strength = parameters.getStrengthMultiplier();
        if (quality == BrewQuality.SUPERIOR && parameters.getStrengthQualitySteps() > 0) {
            strength *= parameters.getMaximumQualityMultiplier();
        }
        return new BrewPotency(BrewQuality.fromComponentValue(promoted), strength,
                parameters.getDurationMultiplier(), parameters.getNauseaMultiplier());
    }

    private static void consume(LivingEntity actor, ItemStack stack, BrewUseMethod method) {
        // 生物和创造玩家不扣瓶；只有掷引把瓶扔出，其余用法在原手位留下空瓶。
        if (!(actor instanceof ServerPlayer player) || player.hasInfiniteMaterials()) {
            return;
        }
        stack.shrink(1);
        if (method == BrewUseMethod.THROW) {
            return;
        }
        ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
        if (stack.isEmpty()) {
            player.setItemInHand(InteractionHand.MAIN_HAND, bottle);
        } else {
            BrewingItemReturns.giveOrDrop(player, bottle);
        }
    }

    private static LivingEntity actor(MinecraftServer server, UUID id) {
        // 当前世界查找支持玩家和生物，不能缓存跨维度实体引用。
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    static void end(UUID actor, String castId) {
        // 结束只清施放幂等，已射出药瓶与已下残药继续按各自生命周期运行。
        var cast = new CastKey(actor, castId);
        handled.removeIf(key -> key.cast().equals(cast));
        anointGroups.remove(cast);
    }

    static void clear() {
        // 停服释放未结束动作缓存。
        handled.clear();
        anointGroups.clear();
    }

    private record CastKey(UUID actor, String cast) { }
    private record EffectKey(CastKey cast, String effect) { }
}
