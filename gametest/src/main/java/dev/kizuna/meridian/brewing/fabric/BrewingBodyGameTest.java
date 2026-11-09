package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewBodyProgress;
import dev.kizuna.meridian.brewing.server.BitterHealing;
import dev.kizuna.meridian.brewing.server.BrewDoseOperations;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.test.BrewingTestPlayers;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.CapabilityModifier;
import dev.kizuna.meridian.core.OrganCapabilities;
import dev.kizuna.meridian.human.fabric.HumanGameTestAccess;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Map;

/** 真实 Human、Damage、状态周期与物品入口的药效回归。 */
public final class BrewingBodyGameTest {
    private static final int TIMEOUT = 200;
    private static final int SETTLED_TICKS = 25;
    private static final int HEAL_TICKS = 65;
    private static final int STACKS = 8;
    private static final int INVULNERABLE_TICKS = 100;
    private static final double DAMAGE_HP = 5;
    private static final double LETHAL_HP = 10_000;
    private static final double DURATION_SECONDS = 20;
    private static final double TOLERANCE = 1e-8;
    private static final long STAGGER_MILLIS = 1_000;
    private static final double NETHERITE_DAMAGE_MULTIPLIER = 0.5;
    private static final String ARM = "right_upper_arm";
    private static final String LEG = "right_thigh";
    private static final String LUNG = "left_lung";
    private static final int BREATH_TIMEOUT = 400;
    private static final double INTAKE_BOOST = 2;
    private static final double DURABILITY_PERCENT = 0.1;
    private static final int OFFLINE_TICKS = 60;

    @GameTest(maxTicks = TIMEOUT)
    public void directDepletionBypassesFramesAndKeepsKiller(GameTestHelper context) {
        // 指定部位、无敌帧、无击退、击杀归属都通过正式 Damage 与 Human 事务验证。
        ServerPlayer attacker = BrewingTestPlayers.create(context);
        ServerPlayer target = BrewingTestPlayers.create(context);
        BodySnapshot before = BrewingTestPlayers.body(target);
        var velocity = target.getDeltaMovement();
        target.invulnerableTime = INVULNERABLE_TICKS;
        hit(target, attacker, ARM, DAMAGE_HP);
        hit(target, attacker, ARM, DAMAGE_HP);
        BodySnapshot after = BrewingTestPlayers.body(target);
        context.assertTrue(hp(before, ARM) - hp(after, ARM) >= DAMAGE_HP,
                "直接伤害被无敌帧吞掉或没有扣指定部位");
        context.assertTrue(hp(before, LEG) == hp(after, LEG), "部位伤扩散到了未命中部位");
        context.assertTrue(target.getDeltaMovement().equals(velocity), "直接腐蚀产生了击退");
        context.assertTrue(target.getLastHurtByPlayerMemoryTime() > 0, "没有记录玩家攻击者");
        hit(target, attacker, "head", LETHAL_HP);
        context.assertTrue(!target.isAlive() && target.getKillCredit() == attacker, "致死腐蚀丢失击杀归属");
        BrewingTestPlayers.close(target);
        BrewingTestPlayers.close(attacker);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void depletionCapsAndSlowsVanillaAttributes(GameTestHelper context) {
        // 同部位重复药效合并到一条组织修正；主手和腿分别投影原版属性。
        ServerPlayer player = BrewingTestPlayers.create(context);
        double attack = player.getAttributeValue(Attributes.ATTACK_SPEED);
        double movement = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
        double digging = player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
        for (int index = 0; index < STACKS; index++) {
            install(player, BrewKind.DEPLETION, List.of(ARM), BrewUse.SPLASH);
        }
        install(player, BrewKind.DEPLETION, List.of(LEG), BrewUse.SPLASH);
        var doses = BrewBodyProgress.read(BrewingTestPlayers.body(player)).getDosesList();
        context.assertTrue(doses.stream().filter(dose -> dose.getPartIdsList().contains(ARM))
                .allMatch(dose -> dose.getEnduranceLoss() == BrewDoseOperations.MAX_ENDURANCE_LOSS),
                "枯竭没有按部位累加封顶");
        context.runAfterDelay(SETTLED_TICKS, () -> {
            context.assertTrue(player.getAttributeValue(Attributes.ATTACK_SPEED) < attack, "攻击冷却没有变慢");
            context.assertTrue(player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED) < digging, "挖掘没有变慢");
            context.assertTrue(player.getAttributeValue(Attributes.MOVEMENT_SPEED) < movement, "移动没有变慢");
            player.removeAllEffects();
            context.assertTrue(BrewingTestPlayers.body(player).getModifiersCount() == 0, "清药后仍保留能力修正");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void armorRotIsLocalDamagesEquipmentAndProtectsGold(GameTestHelper context) {
        // 胸甲和左手盾命中，靴子未命中；材质免疫与减半使用同一真实物品损耗入口。
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        install(player, BrewKind.ARMOR_ROT, List.of(ARM, "left_forearm"), BrewUse.SPLASH);
        context.runAfterDelay(SETTLED_TICKS, () -> {
            ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
            double affected = BrewArmorEffects.multiplier(player, ARM, EquipmentSlot.CHEST, chest);
            double untouched = BrewArmorEffects.multiplier(player, "left_upper_arm", EquipmentSlot.CHEST, chest);
            context.assertTrue(affected < untouched, "局部护甲没有按实际命中部位打折");
            context.assertTrue(chest.getDamageValue() > 0, "胸甲没有真实损耗");
            context.assertTrue(player.getOffhandItem().getDamageValue() > 0, "手臂命中没有蚀掉盾牌耐久");
            context.assertTrue(player.getItemBySlot(EquipmentSlot.FEET).getDamageValue() == 0, "未命中靴子被蚀甲");
            assertMaterials(context, player);
            player.removeAllEffects();
            context.assertTrue(BrewArmorEffects.multiplier(player, ARM, EquipmentSlot.CHEST, chest) == 1,
                    "清药后没有恢复额外护甲倍率");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    private static void assertMaterials(GameTestHelper context, ServerPlayer player) {
        // 相同百分比直接经过物品耐久方法，读取实际 DamageValue 而非只比较材质系数。
        double percent = DURABILITY_PERCENT;
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.GOLDEN_CHESTPLATE));
        BrewArmorEffects.damage(player, EquipmentSlot.CHEST, percent);
        context.assertTrue(player.getItemBySlot(EquipmentSlot.CHEST).getDamageValue() == 0, "金甲未免疫");
        ItemStack netherite = new ItemStack(Items.NETHERITE_CHESTPLATE);
        player.setItemSlot(EquipmentSlot.CHEST, netherite);
        BrewArmorEffects.damage(player, EquipmentSlot.CHEST, percent);
        int expected = (int) Math.ceil(netherite.getMaxDamage() * percent * NETHERITE_DAMAGE_MULTIPLIER);
        context.assertTrue(netherite.getDamageValue() == expected, "下界合金没有减半");
    }

    @GameTest(maxTicks = TIMEOUT)
    public void bitterHealsPartAndInnerTissuesAndBlocksEating(GameTestHelper context) {
        // 先制造可修复伤与归零肢体，药效必须经健康宿主同时修复部位与内部账户。
        ServerPlayer player = BrewingTestPlayers.create(context);
        hit(player, null, ARM, DAMAGE_HP);
        hit(player, null, "left_hand", LETHAL_HP);
        BodySnapshot hurt = BrewingTestPlayers.body(player);
        double innerBefore = tissueHp(hurt, ARM);
        BrewingRuntime.consume(context.getLevel(), player,
                BrewItemStacks.create(BrewKind.BITTER_YELLOW, BrewQuality.SUPERIOR, BrewUse.DRINK));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE));
        context.assertTrue(UseItemCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND)
                == InteractionResult.FAIL, "反胃未拦进食事件");
        player.startUsingItem(InteractionHand.MAIN_HAND);
        context.assertTrue(!player.isUsingItem() && player.getMainHandItem().getCount() == 1,
                "反胃时开始了进食或消耗了食物");
        context.runAfterDelay(HEAL_TICKS, () -> {
            BodySnapshot healed = BrewingTestPlayers.body(player);
            context.assertTrue(hp(healed, ARM) > hp(hurt, ARM), "部位 HP 没有恢复");
            context.assertTrue(tissueHp(healed, ARM) > innerBefore, "内部组织没有恢复");
            context.assertTrue(hp(healed, "left_hand") == 0, "苦黄膏接回了归零肢体");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void refreshedBitterReplacesFadingSealAndQiKeepsWinningQuality(GameTestHelper context) {
        // 治满后的退封也属于同部位旧剂量，新伤新药不能与旧退封相乘。
        ServerPlayer player = BrewingTestPlayers.create(context);
        hit(player, null, ARM, DAMAGE_HP);
        install(player, BrewKind.BITTER_YELLOW, List.of(ARM), BrewUse.SPLASH);
        context.runAfterDelay(SETTLED_TICKS, () -> {
            var binding = BrewBodyHost.inspect(player).orElseThrow();
            BrewBodyHost.heal(binding, BitterHealing.command(binding.body(), Map.of(ARM, LETHAL_HP)));
            BrewEffectLifecycle.tick(player, BrewKind.BITTER_YELLOW, BrewQuality.SUPERIOR);
            var faded = BrewBodyProgress.read(BrewingTestPlayers.body(player));
            boolean fading = faded.getDosesList().stream()
                    .anyMatch(dose -> dose.getFadingPartIdsList().contains(ARM));
            context.assertTrue(fading,
                    "治满没有生成退封计划");
            hit(player, null, ARM, DAMAGE_HP);
            install(player, BrewKind.BITTER_YELLOW, List.of(ARM), BrewUse.SPLASH);
            BodySnapshot body = BrewingTestPlayers.body(player);
            long uniqueTargets = body.getModifiersList().stream().map(modifier -> modifier.getTissueId())
                    .distinct().count();
            context.assertTrue(uniqueTargets == body.getModifiersCount(), "新药与同部位旧退封叠加");
            assertQiRefresh(context, player);
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    private static void assertQiRefresh(GameTestHelper context, ServerPlayer player) {
        // 较短低品质新瓶不能改写胜出的剂量品质或建立另一套图标期限。
        player.removeAllEffects();
        BrewingRuntime.consume(context.getLevel(), player,
                BrewItemStacks.create(BrewKind.QI_GATHER, BrewQuality.SUPERIOR, BrewUse.DRINK));
        var before = BrewBodyProgress.read(BrewingTestPlayers.body(player));
        BrewingRuntime.consume(context.getLevel(), player,
                BrewItemStacks.create(BrewKind.QI_GATHER, BrewQuality.POOR, BrewUse.DRINK));
        context.assertTrue(before.equals(BrewBodyProgress.read(BrewingTestPlayers.body(player))),
                "低品质短剂量替换了胜出聚气曲线");
        context.assertTrue(player.getEffect(BrewingEffects.QI_GATHER).getAmplifier()
                == BrewQuality.SUPERIOR.componentValue(), "图标品质与胜出剂量不符");
    }

    @GameTest(maxTicks = TIMEOUT)
    public void fullModifierBudgetRejectsBrewWithoutPartialProgress(GameTestHelper context) {
        // 常规游玩可达到核心总上限，拒绝新药时不能生成图标或空药效进度。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewingRuntime.consume(context.getLevel(), player,
                BrewItemStacks.create(BrewKind.QI_GATHER, BrewQuality.SUPERIOR, BrewUse.DRINK));
        var binding = BrewBodyHost.inspect(player).orElseThrow();
        var template = binding.body().getModifiers(0);
        var fill = BodyActivity.newBuilder();
        int remaining = CapabilityModifier.MAX_PER_BODY - binding.body().getModifiersCount();
        for (int index = 0; index < remaining; index++) {
            var modifier = template.toBuilder().setId("meridian_brewing:limit_" + index).build();
            fill.addAddModifiers(modifier);
        }
        BrewBodyHost.Binding full = BrewBodyHost.activity(binding, fill.build());
        hit(player, null, ARM, DAMAGE_HP);
        var before = BrewBodyProgress.read(BrewingTestPlayers.body(player));
        BrewingRuntime.consume(context.getLevel(), player,
                BrewItemStacks.create(BrewKind.BITTER_YELLOW, BrewQuality.SUPERIOR, BrewUse.DRINK));
        context.assertTrue(before.equals(BrewBodyProgress.read(BrewingTestPlayers.body(player))),
                "额度不足仍提交了部分药效进度");
        context.assertTrue(!player.hasEffect(BrewingEffects.BITTER_YELLOW), "额度不足仍显示药效");
        context.assertTrue(BrewingTestPlayers.body(player).getModifiersCount() == full.body().getModifiersCount(),
                "拒绝新药改变了现有修正");
        BrewingTestPlayers.close(player);
        context.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void deathAndRespawnDoNotCarryDepletionStacks(GameTestHelper context) {
        // 死亡由核心清修正，重生后清掉旧剂量，不能让下一瓶继承死前叠层。
        ServerPlayer player = BrewingTestPlayers.create(context);
        for (int index = 0; index < STACKS; index++) {
            install(player, BrewKind.DEPLETION, List.of(ARM), BrewUse.SPLASH);
        }
        hit(player, null, "head", LETHAL_HP);
        context.assertTrue(!player.isAlive() && BrewingTestPlayers.body(player).getModifiersCount() == 0,
                "死亡没有清除实际修正");
        player.connection.handleClientCommand(
                new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        ServerPlayer revived = player.level().getServer().getPlayerList().getPlayer(player.getUUID());
        context.runAfterDelay(1, () -> {
            var state = BrewBodyProgress.read(BrewingTestPlayers.body(revived));
            context.assertTrue(state.getDosesCount() == 0, "重生还保留死前药效剂量");
            install(revived, BrewKind.DEPLETION, List.of(ARM), BrewUse.SPLASH);
            var doses = BrewBodyProgress.read(BrewingTestPlayers.body(revived)).getDosesList();
            context.assertTrue(doses.getFirst().getEnduranceLoss() < BrewDoseOperations.MAX_ENDURANCE_LOSS,
                    "新瓶继承了死前封顶层数");
            BrewingTestPlayers.close(revived);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void reloginPausesHealingAndRestoresCurves(GameTestHelper context) {
        // 真实退出再读同 UUID 的玩家存档，离线期间不补药量也不丢能力修正。
        ServerPlayer player = BrewingTestPlayers.create(context);
        hit(player, null, ARM, DAMAGE_HP);
        BrewingRuntime.consume(context.getLevel(), player,
                BrewItemStacks.create(BrewKind.BITTER_YELLOW, BrewQuality.SUPERIOR, BrewUse.DRINK));
        context.runAfterDelay(SETTLED_TICKS, () -> {
            double before = hp(BrewingTestPlayers.body(player), ARM);
            int remaining = player.getEffect(BrewingEffects.BITTER_YELLOW).getDuration();
            BrewingTestPlayers.close(player);
            context.runAfterDelay(OFFLINE_TICKS, () -> {
                ServerPlayer restored = BrewingTestPlayers.rejoin(context, player);
                context.assertTrue(restored.getEffect(BrewingEffects.BITTER_YELLOW).getDuration() == remaining,
                        "重登重置了原版状态剩余时间");
                context.runAfterDelay(1, () -> assertResumedHealing(context, restored, before));
            });
        });
    }

    private static void assertResumedHealing(GameTestHelper context, ServerPlayer player, double before) {
        // 登录回调后的首个 tick 完成恢复，然后继续真实状态周期。
        BodySnapshot body = BrewingTestPlayers.body(player);
        context.assertTrue(Math.abs(hp(body, ARM) - before) < TOLERANCE, "离线区间被补算成治疗");
        context.assertTrue(body.getModifiersCount() > 0, "重登没有恢复药封曲线");
        context.runAfterDelay(SETTLED_TICKS, () -> {
            context.assertTrue(hp(BrewingTestPlayers.body(player), ARM) > before, "重登后治疗没有继续");
            player.removeAllEffects();
            context.assertTrue(BrewingTestPlayers.body(player).getModifiersCount() == 0,
                    "重登后清药遗漏了新曲线身份");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void qiGatherAndMilkChangeActualLungCapabilities(GameTestHelper context) {
        // 饮用产生真实器官修正，两肺摄入和上限一起变化，牛奶撤销同一来源。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewingRuntime.consume(context.getLevel(), player,
                BrewItemStacks.create(BrewKind.QI_GATHER, BrewQuality.SUPERIOR, BrewUse.DRINK));
        context.runAfterDelay(SETTLED_TICKS, () -> {
            var body = BodyProtobuf.fromMessage(BrewingTestPlayers.body(player));
            var lung = new Anatomy.OrganId(LUNG);
            context.assertTrue(OrganCapabilities.intakePerSecond(body, lung) > 1, "聚气没有提高肺摄入上限");
            new ItemStack(Items.MILK_BUCKET).finishUsingItem(context.getLevel(), player);
            var cleared = BodyProtobuf.fromMessage(BrewingTestPlayers.body(player));
            context.assertTrue(Math.abs(OrganCapabilities.intakeMultiplier(cleared, lung) - 1) < TOLERANCE,
                    "牛奶未撤销器官药效");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    @GameTest(maxTicks = BREATH_TIMEOUT)
    public void qiGatherMultipliesActualBreathingCapture(GameTestHelper context) {
        // 让 Human 真正吸入环境粒子，以权威接触回执逐笔核对加倍采样量。
        ServerPlayer player = BrewingTestPlayers.create(context);
        BrewingRuntime.consume(context.getLevel(), player,
                BrewItemStacks.create(BrewKind.QI_GATHER, BrewQuality.SUPERIOR, BrewUse.DRINK));
        context.runAfterDelay(SETTLED_TICKS, () -> {
            var view = HumanGameTestAccess.breathe(player);
            double inventory = view.getEnvironment().getQi().getEntriesList().stream()
                    .mapToDouble(qi -> qi.getAmount()).sum();
            double volume = inventory / view.getEnvironment().getConcentrationPerBlock();
            context.succeedWhen(() -> assertCapture(context, player, volume));
        });
    }

    private static void assertCapture(GameTestHelper context, ServerPlayer player, double volume) {
        // 至少有一次真实成功命中；原始摄入应大于未喝药时的相同体积采样。
        var captures = HumanGameTestAccess.captures(player);
        context.assertTrue(!captures.isEmpty(), "尚无真实吐纳接触回执");
        var receipt = captures.getFirst();
        var exchange = receipt.getQiCapture().getEnvironment().getExchange();
        double available = exchange.getOffer().getAvailableQi().getEntriesList().stream()
                .mapToDouble(qi -> qi.getAmount()).sum();
        double ordinary = available / volume * receipt.getCommand().getCaptureQi().getSampleVolumeBlocks();
        double absorbed = exchange.getAbsorbedQi().getEntriesList().stream()
                .mapToDouble(qi -> qi.getAmount()).sum();
        context.assertTrue(absorbed > ordinary, "聚气没有增加实际吐纳摄入");
        context.assertTrue(Math.abs(absorbed - ordinary * INTAKE_BOOST) < TOLERANCE,
                "真实采样量没有应用器官倍率");
        BrewingTestPlayers.close(player);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void charmStaggerLocksAttackUseAndDestruction(GameTestHelper context) {
        // 功法服务发出僵直事件后，真实玩家输入和已经启动的动作都被锁住。
        ServerPlayer player = BrewingTestPlayers.create(context);
        var stagger = new TechniqueService.Stagger(STAGGER_MILLIS, 0,
                TechniqueService.StaggerKind.CHARM);
        TechniqueFabric.service().stagger(player.getUUID(), stagger);
        context.assertTrue(player.getAttributeValue(Attributes.MOVEMENT_SPEED) == 0, "僵直没有锁住移动属性");
        context.assertTrue(AttackEntityCallback.EVENT.invoker().interact(player, player.level(),
                InteractionHand.MAIN_HAND, player, null) == InteractionResult.FAIL, "僵直没有拦攻击");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHIELD));
        player.startUsingItem(InteractionHand.MAIN_HAND);
        context.assertTrue(!player.isUsingItem(), "僵直没有拦住直接使用物品");
        BlockPos pos = player.blockPosition().below();
        context.getLevel().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        context.assertTrue(!player.gameMode.destroyBlock(pos), "僵直没有拦住挖掘完成");
        context.runAfterDelay(SETTLED_TICKS, () -> {
            context.assertTrue(player.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0, "僵直到期没有解锁");
            BrewingTestPlayers.close(player);
            context.succeed();
        });
    }

    public static void install(ServerPlayer player, BrewKind kind, List<String> parts, BrewUse use) {
        // 指定落点后的同一生产计划，仍经公开活动宿主提交到 Human 权威身体。
        BrewBodyHost.Binding binding = BrewBodyHost.inspect(player).orElseThrow();
        var request = new BrewDoseOperations.DoseRequest(kind, BrewQuality.SUPERIOR, use, parts, DURATION_SECONDS);
        BrewBodyHost.activity(binding, BrewDoseOperations.install(binding.body(), request));
        BrewBodyEffects.addStatus(player, BrewingEffects.status(kind), DURATION_SECONDS, BrewQuality.SUPERIOR);
    }

    private static void hit(ServerPlayer target, ServerPlayer attacker, String part, double hp) {
        // 使用与药水相同的来源标签，固定部位使断言不依赖随机抽取。
        var key = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("meridian_brewing:depletion"));
        var type = target.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
        var source = new DamageSource(type, attacker);
        VanillaDamageFabric.damageDirect(new VanillaDamageFabric.DirectDamage(target, source, part, hp)).orElseThrow();
    }

    private static double hp(BodySnapshot body, String part) {
        // 读取实际部位账户，原版总血量不代表指定肢体。
        return body.getPartStatesList().stream().filter(state -> state.getId().equals(part))
                .findFirst().orElseThrow().getHp();
    }

    private static double tissueHp(BodySnapshot body, String part) {
        // 只累加实际属于该部位的内部组织，证明治疗没有落到全身平均回血。
        var ids = body.getStructure().getTissuesList().stream().filter(tissue -> tissue.getPartId().equals(part))
                .map(tissue -> tissue.getId()).toList();
        return body.getTissueStatesList().stream().filter(state -> ids.contains(state.getId()))
                .mapToDouble(state -> state.getHp()).sum();
    }
}
