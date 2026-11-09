package dev.kizuna.meridian.brewing.test;

import dev.kizuna.meridian.brewing.fabric.BrewCauldronBlockEntity;
import dev.kizuna.meridian.brewing.fabric.BrewFireBlockEntity;
import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.fabric.BrewingRuntime;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockWork;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewUseMethod;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.UseBrew;
import dev.kizuna.meridian.technique.server.EffectHandling;
import dev.kizuna.meridian.technique.server.TechniqueEffectEvent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.InteractionResult;

/** 处理器的补充测试使用服务端玩家、视线、方块实体和药水物品。 */
public final class BrewingC4cGameTest {
    private static final int MAX_TICKS = 160;
    private static final int STRUCTURE_X = 2;
    private static final int STRUCTURE_Y = 2;
    private static final int STRUCTURE_Z = 2;
    private static final double TARGET_DISTANCE = 3.0;
    private static final String BLOCK_WORK_ID = "meridian_brewing:block_work";
    private static final String USE_BREW_ID = "meridian_brewing:use_brew";
    private static final double CENTER = 0.5;
    private static final double FAR_DISTANCE = 10;
    private static final double STANDOFF_DISTANCE = 2;
    private static final long INTERVAL_MILLIS = 1_000;
    private static final long DURATION_MILLIS = 30_000;

    @GameTest(maxTicks = MAX_TICKS)
    public void blockWorkStartsAdvancesAndInterruptsThroughWorldTarget(GameTestHelper context) {
        // 直接处理器测试补充视线与幂等边界，正式施放另由服务流程用例覆盖。
        ServerLevel level = context.getLevel();
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.setGameMode(GameType.SURVIVAL);
        BlockPos cauldronPos = context.absolutePos(
                new BlockPos(STRUCTURE_X, STRUCTURE_Y, STRUCTURE_Z));
        BrewCauldronBlockEntity cauldron = placeCauldron(level, cauldronPos, player);
        assertIncompleteStart(context, player, cauldronPos);
        prepareInputs(cauldron, player, level, cauldronPos);
        assertBrewProgress(context, player, cauldron, cauldronPos);
        assertDistanceInterrupt(context, player, cauldron, cauldronPos);
        context.succeed();
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void useBrewRecognizesFactsAndConsumesDrinkAndSplash(GameTestHelper context) {
        // 使用真实物品和持物事实验证用药消费，不依赖客户端输入。
        ServerLevel level = context.getLevel();
        ServerPlayer player = BrewingTestPlayers.create(context);
        player.setGameMode(GameType.SURVIVAL);

        ItemStack drink = BrewItemStacks.create(BrewKind.QI_GATHER,
                BrewQuality.FINE, BrewUse.DRINK);
        player.setItemInHand(InteractionHand.MAIN_HAND, drink);
        HeldItems drinkFacts = TechniqueWorldItems.inspect(player);
        assertDrink(context, player, drinkFacts);
        LivingEntity target = BrewingTestPlayers.create(context);
        player.setPos(target.position());
        assertSplash(context, player, target);
        assertMismatchedFacts(context, player);
        context.succeed();
    }

    private static void assertIncompleteStart(GameTestHelper context, ServerPlayer player,
                                              BlockPos cauldronPos) {
        // 缺少水、主料和底料时，起手通过真实视线返回可读拒绝原因。
        setLookingAt(player, cauldronPos);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        EffectHandling incomplete = invokeBlockWork(player, "invalid", "invalid_0");
        context.assertTrue(!incomplete.accepted(), "结构不全时 block_work 被接受");
        context.assertTrue(!incomplete.reason().isBlank(), "结构拒绝没有返回原因");
    }

    private static void assertBrewProgress(GameTestHelper context, ServerPlayer player,
                                            BrewCauldronBlockEntity cauldron, BlockPos cauldronPos) {
        // 合法起手进入熬制状态，并由第二个周期事件推进时长。
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        setLookingAt(player, cauldronPos);
        EffectHandling started = invokeBlockWork(player, "brew_cast", "brew_0");
        context.assertTrue(started.accepted(), "合法巫釜没有接受 block_work 起手");
        context.assertTrue(cauldron.cauldron().state() == BrewCauldronState.BREWING,
                "block_work 起手没有进入熬制状态");
        double before = cauldron.cauldron().brewElapsedSeconds();
        EffectHandling advanced = invokeBlockWork(player, "brew_cast", "brew_1");
        context.assertTrue(advanced.accepted(), "block_work 后续推进被拒绝");
        context.assertTrue(cauldron.cauldron().brewElapsedSeconds() > before,
                "block_work 没有推进熬制进度");
    }

    static void assertOffHandOperations(GameTestHelper context, ServerPlayer player,
                                                BrewCauldronBlockEntity cauldron, ServerLevel level,
                                                BlockPos cauldronPos) {
        // 熬制中主手被接管，副手只允许向釜投料或操作釜下火源。
        assertUndeclaredTargets(context, player, cauldronPos);
        ItemStack mainIngredient = new ItemStack(Items.PRISMARINE_SHARD);
        player.setItemInHand(InteractionHand.MAIN_HAND, mainIngredient);
        var cauldronHit = new BlockHitResult(Vec3.atCenterOf(cauldronPos), Direction.UP,
                cauldronPos, false /* inside */);
        context.assertTrue(UseBlockCallback.EVENT.invoker()
                .interact(player, level, InteractionHand.MAIN_HAND, cauldronHit) == InteractionResult.FAIL,
                "Human 没有拦截施放中主手");
        context.assertTrue(mainIngredient.getCount() == 1, "熬制中主手右键没有被拦截");
        ItemStack offIngredient = new ItemStack(Items.PRISMARINE_SHARD);
        player.setItemInHand(InteractionHand.OFF_HAND, offIngredient);
        context.assertTrue(UseBlockCallback.EVENT.invoker()
                .interact(player, level, InteractionHand.OFF_HAND, cauldronHit) != InteractionResult.FAIL,
                "Human 拒绝了标签允许的副手目标");
        cauldron.use(player, InteractionHand.OFF_HAND);
        context.assertTrue(offIngredient.isEmpty(), "熬制中副手投料没有生效");
        assertFireOperations(context, player, level, cauldronPos);
    }

    private static void assertUndeclaredTargets(GameTestHelper context, ServerPlayer player, BlockPos cauldronPos) {
        // 方块白名单不能成为空气、实体或无标签方块的副手豁免。
        BlockPos target = cauldronPos.above();
        var hit = new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false /* inside */);
        context.assertTrue(UseBlockCallback.EVENT.invoker()
                .interact(player, player.level(), InteractionHand.OFF_HAND, hit) == InteractionResult.FAIL,
                "副手放行了未声明方块");
        context.assertTrue(UseItemCallback.EVENT.invoker()
                .interact(player, player.level(), InteractionHand.OFF_HAND) == InteractionResult.FAIL,
                "副手空气操作借用了方块白名单");
        context.assertTrue(UseEntityCallback.EVENT.invoker()
                .interact(player, player.level(), InteractionHand.OFF_HAND, player, null) == InteractionResult.FAIL,
                "副手实体操作借用了方块白名单");
    }

    private static void assertFireOperations(GameTestHelper context, ServerPlayer player,
                                              ServerLevel level, BlockPos cauldronPos) {
        // 火源使用同一 Human 门禁，放行后才交给正式方块实体操作。
        BrewFireBlockEntity fire = (BrewFireBlockEntity) level.getBlockEntity(cauldronPos.below());
        int fuelLevel = fire.fire().level();
        ItemStack mainCoal = new ItemStack(Items.COAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, mainCoal);
        var fireHit = new BlockHitResult(Vec3.atCenterOf(cauldronPos.below()), Direction.UP,
                cauldronPos.below(), false /* inside */);
        context.assertTrue(UseBlockCallback.EVENT.invoker()
                .interact(player, level, InteractionHand.MAIN_HAND, fireHit) == InteractionResult.FAIL,
                "Human 没有拦截主手调火");
        context.assertTrue(mainCoal.getCount() == 1, "熬制中主手加炭没有被拦截");
        context.assertTrue(fire.fire().level() == fuelLevel, "熬制中主手操作改变了火档");
        ItemStack offCoal = new ItemStack(Items.COAL);
        player.setItemInHand(InteractionHand.OFF_HAND, offCoal);
        fire.use(player, InteractionHand.OFF_HAND);
        context.assertTrue(offCoal.isEmpty(), "熬制中副手加炭没有消耗煤炭");
        context.assertTrue(fire.fire().level() == fuelLevel + 1, "熬制中副手加炭没有升档");
        ItemStack offShovel = new ItemStack(Items.IRON_SHOVEL);
        int shovelDamage = offShovel.getDamageValue();
        player.setItemInHand(InteractionHand.OFF_HAND, offShovel);
        fire.use(player, InteractionHand.OFF_HAND);
        context.assertTrue(offShovel.getDamageValue() > shovelDamage, "熬制中副手拨灰没有损耗锹");
        context.assertTrue(fire.fire().level() == fuelLevel, "熬制中副手拨灰没有降档");
    }

    private static void assertDistanceInterrupt(GameTestHelper context, ServerPlayer player,
                                                BrewCauldronBlockEntity cauldron, BlockPos cauldronPos) {
        // 施放者离开三格操作距离后，周期事件清除接管并恢复生药状态。
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        player.setPos(cauldronPos.getX() + CENTER, cauldronPos.getY() + 1,
                cauldronPos.getZ() + FAR_DISTANCE);
        setLookingAt(player, cauldronPos);
        EffectHandling interrupted = invokeBlockWork(player, "brew_cast", "brew_2");
        context.assertTrue(!interrupted.accepted(), "离釜过远时 block_work 没有打断");
        context.assertTrue(cauldron.cauldron().state() == BrewCauldronState.RAW,
                "打断后巫釜没有回到生药状态");
    }

    private static void assertDrink(GameTestHelper context, ServerPlayer player,
                                    HeldItems drinkFacts) {
        // 持物事实与药水内容一致时，饮用入口消耗药水并返还玻璃瓶。
        context.assertTrue(drinkFacts.getMainHand().getBrewKindId()
                        .equals("meridian_brewing:qi_gather"),
                "持物观察没有识别聚气汤种类");
        context.assertTrue(drinkFacts.getMainHand().getBrewQuality()
                        == BrewQuality.FINE.componentValue(),
                "持物观察没有识别聚气汤品质");
        UseBrew drinkUse = useBrew(BrewKind.QI_GATHER, BrewUseMethod.DRINK);
        EffectHandling drinkResult = invokeUseBrew(player, "drink_cast", drinkUse, drinkFacts);
        context.assertTrue(drinkResult.accepted(), "use_brew 饮用聚气汤被拒绝："
                + drinkResult.reason());
        context.assertTrue(!BrewItemStacks.isBrew(player.getItemInHand(InteractionHand.MAIN_HAND)),
                "饮用 use_brew 后仍持有药水");
        context.assertTrue(countItem(player, Items.GLASS_BOTTLE) > 0,
                "饮用 use_brew 没有返还玻璃瓶");
    }

    private static void assertSplash(GameTestHelper context, ServerPlayer player,
                                     LivingEntity target) {
        // 喷溅入口按当前药水位置应用状态，并消耗喷溅药水。
        ItemStack splash = BrewItemStacks.create(BrewKind.CHARM,
                BrewQuality.SUPERIOR, BrewUse.SPLASH);
        player.setItemInHand(InteractionHand.MAIN_HAND, splash);
        HeldItems splashFacts = TechniqueWorldItems.inspect(player);
        UseBrew splashUse = useBrew(BrewKind.CHARM, BrewUseMethod.SPLASH);
        EffectHandling splashResult = invokeUseBrew(player, "splash_cast", splashUse, splashFacts);
        context.assertTrue(splashResult.accepted(), "use_brew 喷溅魅惑被拒绝："
                + splashResult.reason());
        context.assertTrue(splash.isEmpty(), "喷溅 use_brew 没有消耗药水");
        context.assertTrue(target.getActiveEffects().stream().findAny().isPresent(),
                "喷溅 use_brew 没有给目标添加状态");
    }

    private static void assertMismatchedFacts(GameTestHelper context, ServerPlayer player) {
        // 药种或品质事实与当前物品不一致时，入口拒绝且不扣除堆叠。
        ItemStack wrongKind = BrewItemStacks.create(BrewKind.INSECT_LURE,
                BrewQuality.FINE, BrewUse.SPLASH);
        player.setItemInHand(InteractionHand.MAIN_HAND, wrongKind);
        HeldItems wrongKindFacts = TechniqueWorldItems.inspect(player);
        UseBrew drinkUse = useBrew(BrewKind.QI_GATHER, BrewUseMethod.DRINK);
        EffectHandling wrongKindResult = invokeUseBrew(player, "wrong_kind", drinkUse, wrongKindFacts);
        context.assertTrue(!wrongKindResult.accepted(), "错误药种没有被 use_brew 拒绝");
        context.assertTrue(wrongKind.getCount() == 1, "错误药种被拒绝后仍消耗了物品");
        HeldItem wrongQualityMainHand = wrongKindFacts.getMainHand().toBuilder()
                .setBrewQuality(BrewQuality.POOR.componentValue())
                .build();
        HeldItems wrongQualityFacts = wrongKindFacts.toBuilder()
                .setMainHand(wrongQualityMainHand)
                .build();
        UseBrew insectUse = useBrew(BrewKind.INSECT_LURE, BrewUseMethod.SPLASH);
        EffectHandling wrongQualityResult = invokeUseBrew(player, "wrong_quality", insectUse, wrongQualityFacts);
        context.assertTrue(!wrongQualityResult.accepted(), "品质事实不匹配没有被 use_brew 拒绝");
        context.assertTrue(wrongKind.getCount() == 1, "品质事实不匹配后仍消耗了物品");
    }

    static BrewCauldronBlockEntity placeCauldron(ServerLevel level, BlockPos pos,
                                                         ServerPlayer player) {
        // 先放入巫釜、炉座和药苗，后续起手入口会自行扫描结构。
        level.setBlockAndUpdate(pos, block("witch_cauldron").defaultBlockState());
        level.setBlockAndUpdate(pos.below(), block("iron_stove").defaultBlockState());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            level.setBlockAndUpdate(pos.below().relative(direction), Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(pos.north(), Blocks.POTTED_RED_MUSHROOM.defaultBlockState());
        player.setPos(pos.getX() + CENTER, pos.getY() + 1, pos.getZ() - STANDOFF_DISTANCE);
        BlockEntity entity = level.getBlockEntity(pos);
        return (BrewCauldronBlockEntity) entity;
    }

    static void prepareInputs(BrewCauldronBlockEntity cauldron, ServerPlayer player,
                                      ServerLevel level, BlockPos cauldronPos) {
        // 通过方块实体交互补齐火种、水、主料和底料，避免绕过世界入口。
        BrewFireBlockEntity fire = (BrewFireBlockEntity) level.getBlockEntity(cauldronPos.below());
        for (int index = 0; index < 3; index++) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL));
            fire.use(player, InteractionHand.MAIN_HAND);
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET, 3));
        for (int index = 0; index < 3; index++) {
            cauldron.use(player, InteractionHand.MAIN_HAND);
        }
        player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(item("meridian_brewing:dried_pufferfish")));
        cauldron.use(player, InteractionHand.MAIN_HAND);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLOWSTONE_DUST));
        cauldron.use(player, InteractionHand.MAIN_HAND);
    }

    private static EffectHandling invokeBlockWork(ServerPlayer player, String castId, String effectId) {
        // 处理器入口接收与 TechniqueService 相同的稳定效果身份和选定手。
        BlockWork work = BlockWork.newBuilder()
                .setProcessorId(BLOCK_WORK_ID)
                .addBlockTags("meridian_brewing:brew_cauldrons")
                .setMaximumDistance(quantity(TARGET_DISTANCE, Unit.METER))
                .setIntervalMillis(INTERVAL_MILLIS)
                .setDurationMillis(DURATION_MILLIS)
                .build();
        EffectIntent intent = EffectIntent.newBuilder()
                .setCastId(castId)
                .setEffectId(effectId)
                .setSelectedHand(Hand.MAIN_HAND)
                .setBlockWork(work)
                .build();
        TechniqueEffectEvent event = new TechniqueEffectEvent(player.getUUID(), intent,
                HeldItems.getDefaultInstance());
        return TechniqueFabric.EFFECT_HANDLER.invoker().onEffect(event);
    }

    private static EffectHandling invokeUseBrew(ServerPlayer player, String castId,
                                                 UseBrew useBrew, HeldItems heldItems) {
        // 每种消费使用独立施放身份，主手事实与实际物品必须一致。
        EffectIntent intent = EffectIntent.newBuilder()
                .setCastId(castId)
                .setEffectId(castId + "_0")
                .setSelectedHand(Hand.MAIN_HAND)
                .setUseBrew(useBrew)
                .build();
        TechniqueEffectEvent event = new TechniqueEffectEvent(player.getUUID(), intent, heldItems);
        return TechniqueFabric.EFFECT_HANDLER.invoker().onEffect(event);
    }

    private static UseBrew useBrew(BrewKind kind, BrewUseMethod method) {
        // 只授权测试需要的单种巫药和单种物理用法。
        return UseBrew.newBuilder()
                .setProcessorId(USE_BREW_ID)
                .addBrewKindIds("meridian_brewing:" + kind.path())
                .addUseMethods(method)
                .build();
    }

    private static int countItem(Player player, Item item) {
        // 容器可能回到原手位或其他背包槽位，统一按真实库存计数。
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    static void setLookingAt(ServerPlayer player, BlockPos pos) {
        // 服务器端视线直接对准方块中心，测试不依赖客户端 packet 到达时序。
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
    }

    private static Block block(String path) {
        // 通过原版注册表取本模组实际注册的方块。
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(
                "meridian_brewing", path));
    }

    private static Item item(String id) {
        // 测试投料使用正式注册的物品，不构造替身材料。
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static Quantity quantity(double value, Unit unit) {
        // 测试协议字段显式携带单位，和目录校验使用同一边界。
        return Quantity.newBuilder().setValue(value).setUnit(unit).build();
    }
}
