package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.ArmorRotProfile;
import dev.kizuna.meridian.brewing.server.BitterYellowProfile;
import dev.kizuna.meridian.brewing.server.BrewActionResult;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.BrewEffectProfiles;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.CharmProfile;
import dev.kizuna.meridian.brewing.server.DepletionProfile;
import dev.kizuna.meridian.brewing.server.InsectLureProfile;
import dev.kizuna.meridian.brewing.server.QiGatherProfile;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockWork;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewUseMethod;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.UseBrew;
import dev.kizuna.meridian.technique.server.EffectHandling;
import dev.kizuna.meridian.technique.server.TechniqueEffectEvent;
import dev.kizuna.meridian.technique.server.TechniqueCastEnded;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 将物品组件映射到原版状态效果，并维护需要世界参与的持续行为。 */
public final class BrewingRuntime {
    private static final int TICKS_PER_SECOND = 20;
    private static final int MIN_EFFECT_TICKS = 1;
    static final double DEFAULT_SPLASH_RADIUS = 3.0;
    private static final int SILVERFISH_SEARCH_RADIUS = 4;
    private static final String BLOCK_WORK_PROCESSOR = "meridian_brewing:block_work";
    private static final String USE_BREW_PROCESSOR = "meridian_brewing:use_brew";
    private static final String BREW_KIND_PREFIX = "meridian_brewing:";

    private static MinecraftServer activeServer;
    private static final Map<BrewCastKey, ActiveBrew> activeBrews = new HashMap<>();
    private static final Map<BrewCastKey, Set<String>> handledEffects = new HashMap<>();

    private BrewingRuntime() {
        // 药效服务只提供静态世界接线。
    }

    static void register() {
        // 世界生命周期、功法处理器和持物事实在初始化时各注册一次。
        WitheringService.register();
        BrewResidues.initialize();
        BrewProjectiles.initialize();
        ServerTickEvents.END_SERVER_TICK.register(WitheringService::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> activeServer = server);
        TechniqueFabric.SERVICE_STARTED.register(service -> BrewingTechniqueContract.validate(service.registry()));
        TechniqueFabric.SERVICE_STARTED.register(service -> service.addCastCheck(
                (entity, request) -> BrewCastChecks.rejection(activeServer, entity, request)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        TechniqueFabric.EFFECT_HANDLER.register(BrewingRuntime::handleTechniqueEffect);
        TechniqueFabric.CAST_ENDED.register(BrewingRuntime::endCast);
        ServerTickEvents.END_SERVER_TICK.register(server -> tickSounds());
        TechniqueWorldItems.ITEM_FACTS.register(BrewingRuntime::applyHeldBrewFacts);
    }

    private static void applyHeldBrewFacts(ItemStack stack, HeldItem.Builder item) {
        // 内容附属只补充已识别巫药的事实，普通物品保留基础宿主观察。
        if (!BrewItemStacks.isBrew(stack)) {
            return;
        }
        BrewKind kind = BrewItemStacks.kind(stack);
        BrewQuality quality = BrewItemStacks.quality(stack);
        BrewUse use = physicalUse(stack);
        if (use == null) {
            return;
        }
        item.setBrewKindId(BREW_KIND_PREFIX + kind.path());
        item.setBrewQuality(quality.componentValue());
        item.setBrewUse(toProtocolUse(use));
    }

    private static void stop() {
        // 服务器销毁世界前恢复仍在熬制的釜，避免把旧世界对象留在静态映射中。
        for (ActiveBrew active : activeBrews.values()) {
            active.cauldron().interruptTechniqueBrew();
        }
        activeBrews.clear();
        handledEffects.clear();
        BrewTechniqueUse.clear();
        activeServer = null;
    }

    private static void endCast(TechniqueCastEnded event) {
        // 所有施放结束路径共用清理；按 cast_id 整组移除幂等记录，不影响玩家后续施放。
        BrewCastKey key = new BrewCastKey(event.entityUuid(), event.castId());
        ActiveBrew active = activeBrews.remove(key);
        if (active != null) {
            active.cauldron().interruptTechniqueBrew();
        }
        handledEffects.remove(key);
        BrewTechniqueUse.end(event.entityUuid(), event.castId());
    }

    private static void tickSounds() {
        // 只检查当前熬药施放的少量声音，结束或打断后不留下独立播放任务。
        for (ActiveBrew active : activeBrews.values()) {
            active.sounds().tick(active.level(), active.pos());
        }
    }

    static Optional<BrewActionResult> tryTechniqueFire(ServerPlayer player,
                                                        InteractionHand hand, BlockPos firePos) {
        // 熬药期间只接受副手操作该玩家本次目标釜下的火源。
        ActiveBrew active = activeBrew(player.getUUID());
        if (active == null) {
            return Optional.empty();
        }
        BlockPos targetFirePos = active.pos().below();
        if (active.level() != player.level() || !targetFirePos.equals(firePos)) {
            return Optional.of(BrewActionResult.returned(BrewText.message("technique.fire_not_target")));
        }
        if (!(active.level().getBlockEntity(firePos) instanceof BrewFireBlockEntity fire)) {
            return Optional.of(BrewActionResult.returned(BrewText.message("technique.no_fire_below")));
        }
        return Optional.of(fire.useTechnique(player, hand));
    }

    private static ActiveBrew activeBrew(UUID playerUuid) {
        // 一个身体只允许一个活动施放，按玩家查找其已接管的巫釜。
        for (Map.Entry<BrewCastKey, ActiveBrew> entry : activeBrews.entrySet()) {
            if (entry.getKey().entityUuid().equals(playerUuid)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static EffectHandling handleTechniqueEffect(TechniqueEffectEvent event) {
        // 只接管本仓声明的两个处理器，其余处理器交给其他附属模组。
        EffectIntent intent = event.intent();
        EffectHandling techniqueBrew = BrewTechniqueUse.handle(activeServer, event);
        if (techniqueBrew.status() != EffectHandling.Status.NOT_APPLICABLE) {
            return techniqueBrew;
        }
        if (intent.hasBlockWork()) {
            return handleBlockWork(event);
        }
        if (intent.hasUseBrew()) {
            return handleUseBrew(event);
        }
        return EffectHandling.notApplicable();
    }

    private static EffectHandling handleBlockWork(TechniqueEffectEvent event) {
        // 处理器先固定施放者和视线目标，再按首次/后续事件推进同一口釜。
        BlockWork work = event.intent().getBlockWork();
        if (!BLOCK_WORK_PROCESSOR.equals(work.getProcessorId())) {
            return EffectHandling.notApplicable();
        }
        ServerPlayer player = player(event.entityUuid());
        if (player == null) {
            return EffectHandling.rejected("熬药式施放者不在当前服务器");
        }
        BrewEffectKey effectKey = effectKey(event.intent(), event.entityUuid());
        if (alreadyHandled(effectKey)) {
            return EffectHandling.handled();
        }
        if (event.intent().getSelectedHand() != Hand.MAIN_HAND
                || !player.getMainHandItem().is(BrewingTags.STIRRERS)) {
            return EffectHandling.rejected("熬药式需要主手搅药工具");
        }
        BlockTarget target = target(player, work);
        if (target == null) {
            return interrupt(event.entityUuid(), event.intent().getCastId(), "视线没有命中声明的巫釜");
        }
        BrewCastKey castKey = new BrewCastKey(event.entityUuid(), event.intent().getCastId());
        ActiveBrew active = activeBrews.get(castKey);
        if (active == null) {
            return startBrew(castKey, target, player, new BrewStep(effectKey, work.getIntervalMillis()));
        }
        return advanceBrew(castKey, target, player, new BrewStep(effectKey, work.getIntervalMillis()), active);
    }

    private static EffectHandling advanceBrew(BrewCastKey castKey, BlockTarget target,
                                               ServerPlayer player, BrewStep step,
                                               ActiveBrew active) {
        // 后续周期必须继续使用同一目标，并把推进结果写回方块实体。
        if (!active.sameTarget(target)) {
            return interrupt(castKey, "熬药式目标巫釜已经改变");
        }
        if (!(target.level().getBlockEntity(target.pos()) instanceof BrewCauldronBlockEntity cauldron)) {
            return interrupt(castKey, "目标方块没有巫釜方块实体");
        }
        BrewActionResult result = cauldron.advanceTechniqueBrew(player);
        if (!result.accepted()) {
            cauldron.interruptTechniqueBrew();
            return EffectHandling.rejected(BrewTexts.chinese(result.message()));
        }
        markHandled(step.key());
        if (cauldron.cauldron().state() == BrewCauldronState.BREWING) {
            active.sounds().schedule(active.level(), cauldron.cauldron().brewElapsedSeconds(), step.intervalMillis());
        }
        return EffectHandling.handled();
    }

    private static EffectHandling startBrew(BrewCastKey castKey, BlockTarget target,
                                            ServerPlayer player, BrewStep step) {
        // 起手由方块实体校验结构、火种、药材和权限，拒绝原因直接回到功法服务。
        if (!(target.level().getBlockEntity(target.pos()) instanceof BrewCauldronBlockEntity cauldron)) {
            return EffectHandling.rejected("目标方块没有巫釜方块实体");
        }
        BrewActionResult result = cauldron.startBrew(player);
        if (!result.accepted()) {
            return EffectHandling.rejected(BrewTexts.chinese(result.message()));
        }
        cauldron.beginTechniqueControl(player.getUUID());
        BrewSoundPlayback sounds = new BrewSoundPlayback();
        sounds.schedule(target.level(), 0, step.intervalMillis());
        activeBrews.put(castKey, new ActiveBrew(target.level(), target.pos(), cauldron, sounds));
        markHandled(step.key());
        return EffectHandling.handled();
    }

    private static EffectHandling interrupt(UUID entityUuid, String castId, String reason) {
        // 世界校验失败沿用这次施放身份，避免中断同一玩家的新施放。
        return interrupt(new BrewCastKey(entityUuid, castId), reason);
    }

    private static EffectHandling interrupt(BrewCastKey castKey, String reason) {
        // 结构、距离或权限失效时清理接管标记；生药和浸泡记录由方块实体保留。
        ActiveBrew active = activeBrews.remove(castKey);
        if (active != null) {
            active.cauldron().interruptTechniqueBrew();
        }
        return EffectHandling.rejected(reason);
    }

    private static EffectHandling handleUseBrew(TechniqueEffectEvent event) {
        // 处理器只接受当前双手真实物品，药水种类、品质和物理用法都在服务端重算。
        UseBrew useBrew = event.intent().getUseBrew();
        if (!USE_BREW_PROCESSOR.equals(useBrew.getProcessorId())) {
            return EffectHandling.notApplicable();
        }
        ServerPlayer player = player(event.entityUuid());
        if (player == null) {
            return EffectHandling.rejected("用药式施放者不在当前服务器");
        }
        BrewEffectKey effectKey = effectKey(event.intent(), event.entityUuid());
        if (alreadyHandled(effectKey)) {
            return EffectHandling.handled();
        }
        InteractionHand hand = interactionHand(event.intent().getSelectedHand());
        if (hand == null) {
            return EffectHandling.rejected("用药式没有声明施放手");
        }
        ItemStack stack = player.getItemInHand(hand);
        BrewAction brew = resolveBrewAction(stack, event.heldItems(), hand);
        if (brew == null) {
            return EffectHandling.rejected("持物不是可识别的巫药");
        }
        String kindId = BREW_KIND_PREFIX + brew.kind().path();
        if (!useBrew.getBrewKindIdsList().contains(kindId)) {
            return EffectHandling.rejected("功法没有声明该巫药种类");
        }
        if (!useBrew.getUseMethodsList().contains(toProtocolUse(brew.use()))) {
            return EffectHandling.rejected("功法没有声明该巫药用法");
        }
        if (!brew.kind().supports(brew.use())) {
            return EffectHandling.rejected("巫药种类不支持该物理用法");
        }
        return consumeBrewAction(player, stack, brew, effectKey);
    }

    private static EffectHandling consumeBrewAction(ServerPlayer player, ItemStack stack,
                                                     BrewAction brew, BrewEffectKey effectKey) {
        // 先按物理形态应用效果，再按生存规则消费已校验的堆叠。
        if (brew.use() == BrewUse.DRINK) {
            if (BrewNausea.blocks(player, stack)) {
                return EffectHandling.rejected("苦得咽不下去");
            }
            if (!consumeBrew(player, stack)) {
                return EffectHandling.rejected("巫药饮用入口拒绝了已验证物品");
            }
        } else if (brew.use() == BrewUse.SPLASH) {
            splash((ServerLevel) player.level(), stack, player.position(), player);
            consumeSplash(player, stack);
        } else {
            return EffectHandling.rejected("敷、掷、扇、噀尚未接入该处理器");
        }
        markHandled(effectKey);
        return EffectHandling.handled();
    }

    private static BrewAction resolveBrewAction(ItemStack stack, HeldItems heldItems,
                                                InteractionHand hand) {
        // 先读取原版物品类型，再可选比对宿主同一次 inspect 的巫药事实。
        if (!BrewItemStacks.isBrew(stack)) {
            return null;
        }
        BrewKind kind;
        BrewQuality quality;
        try {
            kind = BrewItemStacks.kind(stack);
            quality = BrewItemStacks.quality(stack);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        BrewUse use = physicalUse(stack);
        if (use == null) {
            return null;
        }
        BrewAction brew = new BrewAction(kind, quality, use);
        HeldItem held;
        if (hand == InteractionHand.MAIN_HAND) {
            held = heldItems.getMainHand();
        } else {
            held = heldItems.getOffHand();
        }
        if (!heldItemMatches(stack, brew, held)) {
            return null;
        }
        return brew;
    }

    private static boolean heldItemMatches(ItemStack stack, BrewAction brew, HeldItem held) {
        // 物品槽事实缺失或与当前堆叠不一致时拒绝，避免旧 inspect 重放消耗新物品。
        String actualItemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!actualItemId.equals(held.getItemId())) {
            return false;
        }
        if (held.getBrewKindId().isEmpty() && !held.hasBrewQuality() && !held.hasBrewUse()) {
            return true;
        }
        return (BREW_KIND_PREFIX + brew.kind().path()).equals(held.getBrewKindId())
                && held.hasBrewQuality() && held.getBrewQuality() == brew.quality().componentValue()
                && held.hasBrewUse() && toProtocolUse(brew.use()) == held.getBrewUse();
    }

    private static BrewUse physicalUse(ItemStack stack) {
        // 物理物品决定服用或喷溅；其余形态留给后续处理器，不能猜测成喷溅。
        if (stack.is(Items.POTION)) {
            return BrewUse.DRINK;
        }
        if (stack.is(Items.SPLASH_POTION)) {
            return BrewUse.SPLASH;
        }
        return null;
    }

    private static BrewUseMethod toProtocolUse(BrewUse use) {
        // 共享协议的枚举值与本仓规则枚举保持显式映射，避免依赖枚举序号。
        return switch (use) {
            case DRINK -> BrewUseMethod.DRINK;
            case ANOINT -> BrewUseMethod.ANOINT;
            case THROW -> BrewUseMethod.THROW;
            case SPLASH -> BrewUseMethod.SPLASH;
            case FAN -> BrewUseMethod.FAN;
            case MIST -> BrewUseMethod.MIST;
        };
    }

    private static InteractionHand interactionHand(Hand hand) {
        // 未指定手位没有可验证的物品来源，显式拒绝而不默认主手。
        if (hand == Hand.MAIN_HAND) {
            return InteractionHand.MAIN_HAND;
        }
        if (hand == Hand.OFF_HAND) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    private static boolean consumeBrew(ServerPlayer player, ItemStack stack) {
        // 先应用药效，再按原版生存规则扣药水并返还空瓶；创造模式保留物品。
        if (!consume(player.level(), player, stack)) {
            return false;
        }
        if (player.hasInfiniteMaterials()) {
            return true;
        }
        stack.shrink(1);
        BrewingItemReturns.giveOrDrop(player, new ItemStack(Items.GLASS_BOTTLE));
        return true;
    }

    private static void consumeSplash(ServerPlayer player, ItemStack stack) {
        // 喷溅已经在服务端位置应用，生存模式只消耗药水本身，不生成玻璃瓶。
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
    }

    private static ServerPlayer player(UUID entityUuid) {
        // 处理器只认当前服务器玩家，避免实体 UUID 在跨世界或停服后复用。
        if (activeServer == null) {
            return null;
        }
        return activeServer.getPlayerList().getPlayer(entityUuid);
    }

    private static BlockTarget target(ServerPlayer player, BlockWork work) {
        // 服务端视线最多取目录声明距离，并要求命中方块标签而非邻近方块实体。
        double distance = work.getMaximumDistance().getValue();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(distance));
        BlockHitResult hit = player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = player.level().getBlockState(pos);
        if (!matchesBlockTag(state, work)) {
            return null;
        }
        return new BlockTarget((ServerLevel) player.level(), pos);
    }

    private static boolean matchesBlockTag(BlockState state, BlockWork work) {
        // block_tags 使用任一标签命中；解析失败属于目录启动校验错误，不静默放宽。
        for (String tagId : work.getBlockTagsList()) {
            TagKey<Block> tag = TagKey.create(
                    Registries.BLOCK, Identifier.parse(tagId));
            if (state.is(tag)) {
                return true;
            }
        }
        return false;
    }

    private static BrewEffectKey effectKey(EffectIntent intent, UUID entityUuid) {
        // 一次施放的幂等身份分组保存，结束时整体释放。
        return new BrewEffectKey(new BrewCastKey(entityUuid, intent.getCastId()), intent.getEffectId());
    }

    private static boolean alreadyHandled(BrewEffectKey key) {
        // 重试不会再次扣物品或推进巫釜。
        return handledEffects.getOrDefault(key.cast(), Set.of()).contains(key.effectId());
    }

    private static void markHandled(BrewEffectKey key) {
        // 只记录已经完成的世界效果，失败仍由施放结束通知统一清理。
        handledEffects.computeIfAbsent(key.cast(), ignored -> new HashSet<>()).add(key.effectId());
    }

    private record BrewAction(BrewKind kind, BrewQuality quality, BrewUse use) { }

    private record BrewCastKey(UUID entityUuid, String castId) { }

    private record BrewEffectKey(BrewCastKey cast, String effectId) { }

    private record BrewStep(BrewEffectKey key, long intervalMillis) { }

    private record ActiveBrew(ServerLevel level, BlockPos pos, BrewCauldronBlockEntity cauldron,
                              BrewSoundPlayback sounds) {
        private boolean sameTarget(BlockTarget target) {
            // 世界和位置都一致才允许继续推进，跨维度不能复用旧釜。
            return level == target.level() && pos.equals(target.pos());
        }
    }

    private record BlockTarget(ServerLevel level, BlockPos pos) { }

    public static boolean consume(Level level, LivingEntity entity, ItemStack stack) {
        // 物品身份在服务端解析，消费由原版处理，身体效果由可信宿主提交。
        if (!BrewItemStacks.isBrew(stack)) {
            return false;
        }
        BrewKind kind = BrewItemStacks.kind(stack);
        BrewQuality quality = BrewItemStacks.quality(stack);
        if (!level.isClientSide()) {
            var application = new BrewBodyEffects.Application(kind, quality, BrewUse.DRINK, entity.position(), entity);
            BrewBodyEffects.apply(entity, application);
        }
        return true;
    }

    public static void splash(ServerLevel level, ItemStack stack, Vec3 position, Entity source) {
        // 每个目标独立抽部位，来源实体一路保留到直接伤害的击杀归属。
        if (!BrewItemStacks.isBrew(stack)) {
            return;
        }
        BrewKind kind = BrewItemStacks.kind(stack);
        BrewQuality quality = BrewItemStacks.quality(stack);
        ResolvedEffect effect = resolve(kind, quality);
        double radius = effect.radius();
        AABB bounds = new AABB(position, position).inflate(radius);
        double radiusSquared = radius * radius;
        for (Entity entity : level.getEntities((Entity) null, bounds,
                value -> value instanceof LivingEntity living
                        && living.distanceToSqr(position) <= radiusSquared)) {
            if (entity instanceof LivingEntity target) {
                if (kind == BrewKind.INSECT_LURE) {
                    addEffect(target, effect.data().status(), effect.data().duration(BrewUse.SPLASH), quality);
                } else {
                    var application = new BrewBodyEffects.Application(kind, quality, BrewUse.SPLASH, position, source);
                    BrewBodyEffects.apply(target, application);
                }
            }
        }
        if (kind == BrewKind.DEPLETION) {
            WitheringService.enqueue(level, position, radius, source);
        }
        if (kind == BrewKind.INSECT_LURE) {
            spawnSilverfish(level, position, effect, source);
        }
    }

    private static void addEffect(LivingEntity target, Holder<MobEffect> effect,
                                  double durationSeconds, BrewQuality quality) {
        int durationTicks = Math.max(MIN_EFFECT_TICKS,
                (int) Math.round(durationSeconds * TICKS_PER_SECOND));
        target.addEffect(new MobEffectInstance(effect, durationTicks, quality.componentValue(),
                false /* ambient */, true /* visible */, true /* showIcon */));
    }

    private static void spawnSilverfish(ServerLevel level, Vec3 position,
                                        ResolvedEffect effect, Entity source) {
        // 普通喷溅保留整瓶的孵化数量。
        InsectData data = (InsectData) effect.data();
        spawnSilverfishCount(level, position, data.profile().silverfishCount(), source);
    }

    static void spawnSilverfish(ServerLevel level, Vec3 position, BrewQuality quality, Entity source,
                                 double strength) {
        // 催发以剩余剂量缩减数量，向下取整，仍复用出生点保护和石头检查。
        int count = (int) Math.floor(BrewEffectProfiles.insectLure(quality).silverfishCount() * strength);
        spawnSilverfishCount(level, position, count, source);
    }

    private static void spawnSilverfishCount(ServerLevel level, Vec3 position, int count, Entity source) {
        // 每只虫独立寻找可出生的表面，任何一次无候选即结束本轮孵化。
        for (int index = 0; index < count; index++) {
            BlockPos spawnPos = findStoneSpawn(level, BlockPos.containing(position), source);
            if (spawnPos == null) {
                return;
            }
            EntityTypes.SILVERFISH.spawn(level, spawnPos, EntitySpawnReason.MOB_SUMMONED);
        }
    }

    private static BlockPos findStoneSpawn(ServerLevel level, BlockPos origin, Entity source) {
        int searchAttempts = SILVERFISH_SEARCH_RADIUS * SILVERFISH_SEARCH_RADIUS;
        int coordinateRange = SILVERFISH_SEARCH_RADIUS * 2 + 1;
        for (int attempt = 0; attempt < searchAttempts; attempt++) {
            int offsetX = level.getRandom().nextInt(coordinateRange) - SILVERFISH_SEARCH_RADIUS;
            int offsetZ = level.getRandom().nextInt(coordinateRange) - SILVERFISH_SEARCH_RADIUS;
            BlockPos candidate = origin.offset(offsetX, 0, offsetZ);
            if (!level.getBlockState(candidate).is(BlockTags.BASE_STONE_OVERWORLD)
                    || !level.getBlockState(candidate.above()).isAir()) {
                continue;
            }
            if (source instanceof Player player && !level.mayInteract(player, candidate)) {
                continue;
            }
            return candidate.above();
        }
        return null;
    }

    private static ResolvedEffect resolve(BrewKind kind, BrewQuality quality) {
        return switch (kind) {
            case DEPLETION -> new ResolvedEffect(kind, quality,
                    new DepletionData(BrewEffectProfiles.depletion(quality)));
            case ARMOR_ROT -> new ResolvedEffect(kind, quality,
                    new ArmorData(BrewEffectProfiles.armorRot(quality)));
            case INSECT_LURE -> new ResolvedEffect(kind, quality,
                    new InsectData(BrewEffectProfiles.insectLure(quality)));
            case QI_GATHER -> new ResolvedEffect(kind, quality,
                    new QiData(BrewEffectProfiles.qiGather(quality)));
            case BITTER_YELLOW -> new ResolvedEffect(kind, quality,
                    new BitterData(BrewEffectProfiles.bitterYellow(quality, BrewUse.DRINK),
                            BrewEffectProfiles.bitterYellow(quality, BrewUse.SPLASH)));
            case CHARM -> charmEffect(kind, quality);
        };
    }

    private static ResolvedEffect charmEffect(BrewKind kind, BrewQuality quality) {
        CharmProfile profile = BrewEffectProfiles.charm(quality);
        Holder<MobEffect> status = BrewingEffects.CHARM_STAGGER;
        if (quality == BrewQuality.SUPERIOR) {
            status = BrewingEffects.CHARM_CONTROL;
        }
        return new ResolvedEffect(kind, quality, new CharmData(profile, status));
    }

    private record ResolvedEffect(BrewKind kind, BrewQuality quality, EffectData data) {
        private double radius() {
            return data.radius();
        }
    }

    private interface EffectData {
        double radius();

        double duration(BrewUse use);

        Holder<MobEffect> status();
    }

    private record DepletionData(DepletionProfile profile) implements EffectData {
        @Override
        public double radius() {
            return profile.radius();
        }

        @Override
        public double duration(BrewUse use) {
            return profile.durationSeconds();
        }

        @Override
        public Holder<MobEffect> status() {
            return BrewingEffects.DEPLETION;
        }
    }

    private record ArmorData(ArmorRotProfile profile) implements EffectData {
        @Override
        public double radius() {
            return profile.radius();
        }

        @Override
        public double duration(BrewUse use) {
            return profile.durationSeconds();
        }

        @Override
        public Holder<MobEffect> status() {
            return BrewingEffects.ARMOR_ROT;
        }
    }

    private record InsectData(InsectLureProfile profile) implements EffectData {
        @Override
        public double radius() {
            return profile.radius();
        }

        @Override
        public double duration(BrewUse use) {
            return profile.durationSeconds();
        }

        @Override
        public Holder<MobEffect> status() {
            return BrewingEffects.INSECT_LURE;
        }
    }

    private record QiData(QiGatherProfile profile) implements EffectData {
        @Override
        public double radius() {
            return DEFAULT_SPLASH_RADIUS;
        }

        @Override
        public double duration(BrewUse use) {
            return profile.durationSeconds();
        }

        @Override
        public Holder<MobEffect> status() {
            return BrewingEffects.QI_GATHER;
        }
    }

    private record BitterData(BitterYellowProfile drink, BitterYellowProfile splash)
            implements EffectData {
        @Override
        public double radius() {
            return DEFAULT_SPLASH_RADIUS;
        }

        @Override
        public double duration(BrewUse use) {
            if (use == BrewUse.DRINK) {
                return drink.durationSeconds();
            }
            return splash.durationSeconds();
        }

        @Override
        public Holder<MobEffect> status() {
            return BrewingEffects.BITTER_YELLOW;
        }
    }

    private record CharmData(CharmProfile profile, Holder<MobEffect> status) implements EffectData {
        @Override
        public double radius() {
            return profile.radius();
        }

        @Override
        public double duration(BrewUse use) {
            if (status == BrewingEffects.CHARM_CONTROL) {
                return profile.controlSeconds();
            }
            return profile.staggerSeconds();
        }
    }
}
