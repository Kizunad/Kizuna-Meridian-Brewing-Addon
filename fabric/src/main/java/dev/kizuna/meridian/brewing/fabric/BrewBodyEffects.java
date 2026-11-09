package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.protocol.pb.BrewEffectMessages.BrewBodyState;
import dev.kizuna.meridian.brewing.server.BitterHealing;
import dev.kizuna.meridian.brewing.server.BrewBodyFacts;
import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BrewDoseOperations;
import dev.kizuna.meridian.brewing.server.BrewEffectProfiles;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewThresholds;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.CharmOutcome;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import dev.kizuna.meridian.core.CapabilityModifier;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** 从真实用药事件选择部位，再经可信宿主提交身体药效。 */
final class BrewBodyEffects {
    private static final double TICKS_PER_SECOND = 20;
    private static final double MILLIS_PER_SECOND = 1_000;
    private static final ResourceKey<DamageType> DEPLETION_DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE,
            Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "depletion"));
    private static BrewThresholds thresholds;

    private BrewBodyEffects() {
        // 运行时只持有配置，药效事实随权威身体保存。
    }

    static void initialize() {
        // 宿主与配置都是服务端硬依赖，初始化失败应停止装配。
        BrewBodyHost.initialize();
        thresholds = BrewThresholds.load(FabricLoader.getInstance().getConfigDir()
                .resolve("meridian-brewing.properties"));
        VanillaDamageFabric.registerPartArmorModifier(BrewArmorEffects::multiplier);
        BrewNausea.initialize();
        BrewEffectResume.initialize();
        BrewCharmSessions.initialize();
    }

    record Application(BrewKind kind, BrewQuality quality, BrewUse use, Vec3 impact, Entity source) { }

    static void apply(LivingEntity target, Application application) {
        // 客户端不结算；没有身体的实体只执行明确允许的物品蚀甲与一次腐蚀。
        if (!(target.level() instanceof ServerLevel level)) {
            return;
        }
        if (application.kind() == BrewKind.BITTER_YELLOW && target.hasEffect(BrewingEffects.BITTER_YELLOW)) {
            BrewEffectLifecycle.tick(target, application.kind(), application.quality());
        }
        var found = BrewBodyHost.inspect(target);
        if (found.isEmpty()) {
            applyUnbound(level, target, application);
            return;
        }
        BrewBodyHost.Binding binding = found.get();
        if (binding.body().getVitality().getDead()) {
            return;
        }
        if (application.kind() == BrewKind.CHARM) {
            charm(target, binding, application);
            return;
        }
        List<String> parts = parts(target, binding.body(), application);
        if (application.kind() == BrewKind.DEPLETION) {
            depletion(target, binding, application, parts);
        } else {
            install(target, binding, application, parts);
        }
    }

    private static List<String> parts(LivingEntity target, BodySnapshot body, Application application) {
        // 饮用苦黄膏作用于仍能修复的四肢，喷溅依品质和距离选点。
        if (application.kind() == BrewKind.QI_GATHER) {
            return List.of();
        }
        if (application.kind() == BrewKind.BITTER_YELLOW && application.use() == BrewUse.DRINK) {
            return body.getPartStatesList().stream().map(part -> part.getId())
                    .filter(BitterHealing::isLimb).filter(part -> BitterHealing.wound(body, part) > 0).toList();
        }
        int minimum;
        int maximum;
        BrewPartSelection.Scope scope = BrewPartSelection.Scope.ALL;
        if (application.kind() == BrewKind.ARMOR_ROT) {
            var profile = BrewEffectProfiles.armorRot(application.quality());
            minimum = profile.minimumParts();
            maximum = profile.maximumParts();
        } else {
            var profile = BrewEffectProfiles.depletion(application.quality());
            minimum = profile.minimumParts();
            maximum = profile.maximumParts();
        }
        if (application.kind() == BrewKind.BITTER_YELLOW) {
            scope = BrewPartSelection.Scope.WOUNDED_LIMBS;
        }
        var splash = new BrewPartSelection.Splash(target, application.impact(), minimum, maximum, scope);
        return BrewPartSelection.select(body, splash);
    }

    private static void depletion(LivingEntity target, BrewBodyHost.Binding binding,
                                   Application application, List<String> parts) {
        // 排毒力按中药那刻固定；无害档仍接受一次腐蚀，持续效果才免除。
        var profile = BrewEffectProfiles.depletion(application.quality());
        double duration = BrewBodyFacts.detox(binding.body(), thresholds).durationSeconds(profile.durationSeconds());
        DamageSource source = depletionSource((ServerLevel) target.level(), application.source());
        for (String part : parts) {
            VanillaDamageFabric.damageDirect(new VanillaDamageFabric.DirectDamage(
                    target, source, part, profile.partDamageHp()));
            if (!target.isAlive()) {
                return;
            }
        }
        if (duration == 0) {
            return;
        }
        // 每个部位单独合并，不能把另一部位已有的叠加层数算到本部位。
        for (String part : parts) {
            BrewBodyHost.Binding current = BrewBodyHost.inspect(target).orElseThrow();
            var request = new BrewDoseOperations.DoseRequest(application.kind(), application.quality(),
                    application.use(), List.of(part), duration);
            installDose(target, current, request);
        }
    }

    private static void install(LivingEntity target, BrewBodyHost.Binding binding,
                                 Application application, List<String> parts) {
        // 器官药不抽部位，部位药无有效目标时不挂空药封。
        if (parts.isEmpty() && application.kind() != BrewKind.QI_GATHER) {
            if (application.kind() == BrewKind.BITTER_YELLOW && application.use() == BrewUse.DRINK) {
                installDose(target, binding, request(application, parts));
            }
            return;
        }
        installDose(target, binding, request(application, parts));
    }

    private static BrewDoseOperations.DoseRequest request(Application application, List<String> parts) {
        // 状态倒计时涵盖聚气的虚脱段，使牛奶可以撤销整个器官曲线。
        double duration = switch (application.kind()) {
            case ARMOR_ROT -> BrewEffectProfiles.armorRot(application.quality()).durationSeconds();
            case BITTER_YELLOW -> BrewEffectProfiles.bitterYellow(
                    application.quality(), application.use()).durationSeconds();
            case QI_GATHER -> qiDuration(application.quality());
            default -> throw new IllegalArgumentException("药效不支持身体持续计划：" + application.kind());
        };
        return new BrewDoseOperations.DoseRequest(application.kind(), application.quality(),
                application.use(), parts, duration);
    }

    private static double qiDuration(BrewQuality quality) {
        // 两段共用同一状态生命周期，具体倍率在器官修正曲线里切换。
        var profile = BrewEffectProfiles.qiGather(quality);
        return profile.durationSeconds() + profile.exhaustionDurationSeconds();
    }

    private static void installDose(LivingEntity target, BrewBodyHost.Binding binding,
                                     BrewDoseOperations.DoseRequest request) {
        // 先提交权威修正和进度，成功后才更新可见状态，失败不得显示虚假的药效。
        BodyActivity plan = BrewDoseOperations.install(binding.body(), request);
        int retained = binding.body().getModifiersCount() - plan.getRemoveModifiersCount();
        if (retained + plan.getAddModifiersCount() > CapabilityModifier.MAX_PER_BODY) {
            if (target instanceof ServerPlayer player) {
                player.sendOverlayMessage(Component.translatable("message.meridian_brewing.modifier_limit"));
            }
            return;
        }
        BrewBodyHost.Binding updated = BrewBodyHost.activity(binding, plan);
        BrewBodyState state = BrewBodyProgress.read(updated.body());
        long now = updated.body().getSettledAt().getElapsedMillis();
        long end = state.getDosesList().stream().filter(dose -> dose.getKind().equals(request.kind().name()))
                .mapToLong(dose -> dose.getEndsMillis()).max().orElse(now);
        long statusEnd = end;
        if (request.kind() == BrewKind.BITTER_YELLOW) {
            statusEnd = Math.max(end, state.getNauseaUntilMillis());
        }
        int qualityValue = state.getDosesList().stream().filter(dose -> dose.getKind().equals(request.kind().name()))
                .mapToInt(dose -> dose.getQuality()).max().orElse(request.quality().componentValue());
        BrewQuality quality = BrewQuality.fromComponentValue(qualityValue);
        addStatus(target, BrewingEffects.status(request.kind()), (statusEnd - now) / MILLIS_PER_SECOND, quality);
        if (request.kind() == BrewKind.BITTER_YELLOW && request.use() == BrewUse.DRINK) {
            double nauseaSeconds = (state.getNauseaUntilMillis() - now) / MILLIS_PER_SECOND;
            addStatus(target, MobEffects.NAUSEA, nauseaSeconds, BrewQuality.POOR);
            target.stopUsingItem();
        }
    }

    private static void charm(LivingEntity target, BrewBodyHost.Binding binding, Application application) {
        // 判定和醒后免疫在本仓；受控目标的决策由控制宿主接续。
        BrewBodyState state = BrewBodyProgress.read(binding.body());
        long now = binding.body().getSettledAt().getElapsedMillis();
        if (state.getCharmImmunityUntilMillis() > now || BrewCharmSessions.controlled(target.getUUID())) {
            return;
        }
        BrewQuality quality = application.quality();
        CharmOutcome outcome = BrewBodyFacts.charm(binding.body(), quality, thresholds);
        if (outcome == CharmOutcome.IGNORE) {
            return;
        }
        var profile = BrewEffectProfiles.charm(quality);
        boolean playerStagger = target instanceof ServerPlayer
                && thresholds.playerCharm() == BrewThresholds.PlayerCharm.STAGGER;
        if (outcome == CharmOutcome.CONTROL && !playerStagger
                && application.source() instanceof LivingEntity caster) {
            BrewCharmSessions.start(target, caster, profile.controlSeconds(), quality);
            return;
        }
        double duration = profile.staggerSeconds();
        long immuneUntil = now + Math.round((duration + profile.immunitySeconds()) * MILLIS_PER_SECOND);
        var updated = state.toBuilder().setCharmImmunityUntilMillis(immuneUntil).build();
        var activity = BodyActivity.newBuilder();
        BrewBodyProgress.write(activity, updated);
        BrewBodyHost.activity(binding, activity.build());
        addStatus(target, BrewingEffects.CHARM_STAGGER, duration, quality);
        long millis = Math.round(duration * MILLIS_PER_SECOND);
        var stagger = new TechniqueService.Stagger(millis, 0, TechniqueService.StaggerKind.CHARM);
        TechniqueFabric.service().stagger(target.getUUID(), stagger);
    }

    private static void applyUnbound(ServerLevel level, LivingEntity target, Application application) {
        // 无身体物种没有组织目标；保留设计明确指定的一次原版伤害和物品蚀甲。
        if (application.kind() == BrewKind.DEPLETION) {
            var profile = BrewEffectProfiles.depletion(application.quality());
            target.hurtServer(level, depletionSource(level, application.source()), (float) profile.partDamageHp());
        } else if (application.kind() == BrewKind.ARMOR_ROT) {
            var profile = BrewEffectProfiles.armorRot(application.quality());
            BrewArmorEffects.selectUnbound(target, application.impact());
            addStatus(target, BrewingEffects.ARMOR_ROT, profile.durationSeconds(), application.quality());
        }
    }

    private static DamageSource depletionSource(ServerLevel level, Entity source) {
        // 施药者保留在原版来源中，Damage 负责观察者、僵直与击杀归属。
        var damageType = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DEPLETION_DAMAGE);
        return new DamageSource(damageType, source);
    }

    static void addStatus(LivingEntity target, Holder<MobEffect> status, double seconds, BrewQuality quality) {
        // 本仓已按药种合并剂量，覆盖图标避免原版 hiddenEffect 留下另一套刷新期限。
        int duration = Math.max(1, (int) Math.ceil(seconds * TICKS_PER_SECOND));
        var effect = new MobEffectInstance(status, duration, quality.componentValue(),
                false /* ambient */, true /* visible */, true /* showIcon */);
        if (BrewingEffects.kind(status).isPresent()) {
            target.forceAddEffect(effect, null);
        } else {
            target.addEffect(effect);
        }
    }
}
