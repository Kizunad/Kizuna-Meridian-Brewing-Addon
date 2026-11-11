package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewActionResult;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewCollectionKind;
import dev.kizuna.meridian.brewing.server.BrewDirection;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewProgress;
import dev.kizuna.meridian.brewing.server.BrewQiTally;
import dev.kizuna.meridian.brewing.server.BrewRecipe;
import dev.kizuna.meridian.brewing.server.BrewStructure;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.CopperExposure;
import dev.kizuna.meridian.brewing.server.CopperCauldronState;
import dev.kizuna.meridian.brewing.server.CopperOxidation;
import dev.kizuna.meridian.brewing.server.FireKind;
import dev.kizuna.meridian.brewing.server.HeatSourceKind;
import dev.kizuna.meridian.brewing.server.HerbState;
import dev.kizuna.meridian.brewing.server.IngredientBatch;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import dev.kizuna.meridian.brewing.server.PreparationKind;
import dev.kizuna.meridian.brewing.server.SmokeVentState;
import dev.kizuna.meridian.brewing.server.VesselKind;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** 巫釜方块实体：扫描四向结构、推进规则状态并处理服务端右键。 */
public final class BrewCauldronBlockEntity extends BlockEntity {
    private static final double SECONDS_PER_TICK = 0.05;
    private static final double TECHNIQUE_MAX_DISTANCE = 3.0;
    private static final double TECHNIQUE_MAX_DISTANCE_SQUARED =
            TECHNIQUE_MAX_DISTANCE * TECHNIQUE_MAX_DISTANCE;
    private static final double TECHNIQUE_SECONDS_PER_STEP = 1.0;
    private static final double BLOCK_CENTER = 0.5;
    private static final long DAY_TICKS = 24_000L;
    private static final long COPPER_OXIDATION_INTERVAL_TICKS = 24_000L;
    private static final int WATER_BUCKET_UNITS = 3;
    private static final int WATER_BOTTLE_UNITS = 1;

    private final VesselKind vessel;
    private final BrewCauldron cauldron;
    private BrewStructure structure;
    private long copperOxidationTicks;
    private UUID techniqueCaster;
    private BrewQiTally qiTally;

    public BrewCauldronBlockEntity(BlockPos pos, BlockState state, VesselKind vessel) {
        super(BrewingBlocks.BREW_CAULDRON_ENTITY, pos, state);
        this.vessel = vessel;
        this.cauldron = new BrewCauldron(vessel, BrewForm.DRINK);
        this.structure = emptyStructure();
        if (vessel == VesselKind.COPPER_CAULDRON) {
            // 用 /setblock 或结构放下的铜釜带着外观氧化级，规则状态从它起算。
            cauldron.restoreCopperOxidation(CopperCauldronBlock.oxidation(state));
        }
    }

    static <T extends BlockEntity> BlockEntityTicker<T> ticker(BlockEntityType<T> type) {
        if (type != BrewingBlocks.BREW_CAULDRON_ENTITY) {
            return null;
        }
        return (level, pos, state, entity) -> {
            if (entity instanceof BrewCauldronBlockEntity cauldron) {
                cauldron.tick(level);
            }
        };
    }

    public BrewCauldron cauldron() {
        return cauldron;
    }

    public BrewStructure structure() {
        // 暴露结构快照供服务端状态观察和 GameTest 验证，不允许调用方修改方块实体。
        return structure;
    }

    public InteractionResult use(Player player, InteractionHand hand) {
        // Human 已统一执行施放中的手位和标签门禁，这里只处理釜的投料规则。
        if (level == null || level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        boolean controlledByCaster = techniqueControlledBy(player);
        ItemStack stack = player.getItemInHand(hand);
        BrewActionResult result;
        if (controlledByCaster) {
            result = tryUseTechniqueOffHand(stack);
        } else {
            result = tryUseItem(player, stack);
        }
        if (!result.accepted()) {
            BrewTexts.sendOverlay(player, result.message());
            return InteractionResult.SUCCESS;
        }
        applyAcceptedUse(player, hand, stack);
        BrewTexts.sendOverlay(player, result.message());
        setChanged();
        return InteractionResult.SUCCESS;
    }

    public BrewActionResult startBrew(ServerPlayer caster) {
        if (level == null || level.isClientSide()) {
            return BrewActionResult.returned(BrewText.message("cauldron.server_only"));
        }
        if (caster == null || !level.mayInteract(caster, worldPosition)) {
            return BrewActionResult.returned(BrewText.message("no_permission"));
        }
        structure = scanStructure();
        Optional<BrewText> problem = cauldron.startProblem(structure);
        if (problem.isEmpty()) {
            problem = casterProblem(caster, cauldron.recipe().orElseThrow().kind());
        }
        if (problem.isEmpty()) {
            problem = BrewQiPayment.startProblem(caster, level, worldPosition, vessel);
        }
        if (problem.isPresent()) {
            return BrewActionResult.returned(problem.orElseThrow());
        }
        BrewActionResult result = cauldron.startBrew(structure);
        if (result.accepted()) {
            setChanged();
        }
        return result;
    }

    private static Optional<BrewText> casterProblem(ServerPlayer caster, BrewKind kind) {
        // 釜这边都齐了才查施法者：创造模式也要读过药方。
        BrewRecipeBook.Lookup recipes = BrewRecipeBook.lookup(caster);
        if (recipes.presence() == BrewBodyHost.Presence.PAUSED) {
            return Optional.of(BrewText.message("recipe.body_paused"));
        }
        if (!recipes.knows(kind)) {
            return Optional.of(BrewText.message("recipe.not_learned", BrewCauldron.recipeName(kind)));
        }
        return Optional.empty();
    }

    public void beginTechniqueControl(UUID casterUuid) {
        // 技法接管后暂停普通 ticker，避免一次熬药被推进两遍；同时开一本这锅的耗元账。
        if (casterUuid == null) {
            throw new IllegalArgumentException("熬药式施放者不能为空");
        }
        techniqueCaster = casterUuid;
        qiTally = BrewQiPayment.tally(vessel);
    }

    /** 当前或刚熬成的这一锅耗了多少真元；中断后清空，不存档。 */
    public Optional<BrewQiTally> qiTally() {
        return Optional.ofNullable(qiTally);
    }

    public BrewActionResult advanceTechniqueBrew(ServerPlayer caster, String drawId) {
        // 每个周期只推进一次规则时间，结构或权限失效时先恢复普通 ticker；推进前先付这一秒的真元，
        // 付不起就中断，不降分。
        if (!techniqueControlled()) {
            return BrewActionResult.returned(BrewText.message("technique.not_controlled"));
        }
        Optional<BrewText> accessProblem = techniqueAccessProblem(caster);
        if (accessProblem.isPresent()) {
            return interruptTechnique(accessProblem.orElseThrow());
        }
        if (cauldron.state() == BrewCauldronState.BREWED) {
            techniqueCaster = null;
            return BrewActionResult.accepted(BrewText.message("cauldron.brewed"));
        }
        structure = scanStructure();
        BrewRecipe recipe = cauldron.recipe().orElseThrow(() -> new IllegalStateException("熬制中的釜没有药方"));
        Optional<BrewText> structureProblem = structure.problemFor(recipe.kind(), recipe.fireKind());
        if (structureProblem.isPresent()) {
            return interruptTechnique(BrewText.message("technique.interrupted", structureProblem.orElseThrow()));
        }
        Optional<BrewQiTally> paid = BrewQiPayment.paySecond(caster, level, worldPosition, drawId, qiTally);
        if (paid.isEmpty()) {
            BrewTexts.sendOverlay(caster, BrewText.message("technique.qi_short"));
            return interruptTechnique(BrewText.message("technique.qi_short"));
        }
        qiTally = paid.orElseThrow();
        tickCauldron(TECHNIQUE_SECONDS_PER_STEP);
        setChanged();
        if (cauldron.state() == BrewCauldronState.SCORCHED) {
            techniqueCaster = null;
            return BrewActionResult.returned(BrewText.message("technique.scorched"));
        }
        if (cauldron.state() == BrewCauldronState.BREWED) {
            techniqueCaster = null;
            BrewTexts.sendOverlay(caster, BrewTexts.brewed(qiTally));
            return BrewActionResult.accepted(BrewText.message("cauldron.brewed"));
        }
        BrewTexts.sendProgress(caster, BrewProgress.of(cauldron), qiTally);
        return BrewActionResult.accepted(BrewText.message("technique.step"));
    }

    public BrewActionResult interruptTechniqueBrew() {
        // 已完成的釜保留成药；仍被接管的生药释放控制并清除本次熬制累计。
        if (!techniqueControlled()) {
            return BrewActionResult.returned(BrewText.message("technique.not_controlled"));
        }
        return interruptTechnique(BrewText.message("technique.stopped"));
    }

    public void dropStoredIngredients() {
        if (level == null || level.isClientSide()) {
            return;
        }
        NonNullList<ItemStack> drops = NonNullList.create();
        for (IngredientBatch batch : cauldron.ingredients().values()) {
            drops.add(itemStack(batch));
        }
        Containers.dropContents(level, worldPosition, drops);
    }

    private void tick(Level currentLevel) {
        // 熬药式接管期间只刷新结构与铜釜状态，规则推进交给周期效果，避免重复计时。
        if (currentLevel.isClientSide()) {
            return;
        }
        structure = scanStructure();
        if (!techniqueControlled()) {
            tickCauldron(SECONDS_PER_TICK);
        }
        if (vessel == VesselKind.COPPER_CAULDRON) {
            tickCopperOxidation();
            showCopperOxidation();
        }
        setChanged();
    }

    /** 推进一段时间；熬成和糊锅那一刻在釜上出声。 */
    private void tickCauldron(double seconds) {
        BrewCauldronState before = cauldron.state();
        cauldron.tick(seconds, structure);
        BrewCauldronState after = cauldron.state();
        if (before == after) {
            return;
        }
        if (after == BrewCauldronState.BREWED) {
            BrewSounds.atBlock(level, worldPosition, BrewSounds.CAULDRON_DONE);
        } else if (after == BrewCauldronState.SCORCHED) {
            BrewSounds.atBlock(level, worldPosition, BrewSounds.CAULDRON_SCORCH);
            BrewSounds.atBlock(level, worldPosition, BrewSounds.CAULDRON_SCORCH_FIRE);
        }
    }

    private Optional<BrewText> techniqueAccessProblem(ServerPlayer caster) {
        // 施放期间继续验证原版权限和距离，不能沿用起手时的授权。
        if (caster == null || !level.mayInteract(caster, worldPosition)) {
            return Optional.of(BrewText.message("no_permission"));
        }
        if (caster.distanceToSqr(worldPosition.getX() + BLOCK_CENTER,
                worldPosition.getY() + BLOCK_CENTER, worldPosition.getZ() + BLOCK_CENTER)
                > TECHNIQUE_MAX_DISTANCE_SQUARED) {
            return Optional.of(BrewText.message("technique.too_far"));
        }
        return Optional.empty();
    }

    private BrewActionResult interruptTechnique(BrewText reason) {
        // 无论规则中断是否接受都释放接管，避免方块 ticker 永久暂停。
        BrewActionResult interruption = cauldron.interruptBrew();
        techniqueCaster = null;
        qiTally = null;
        setChanged();
        if (interruption.accepted()) {
            return BrewActionResult.returned(reason);
        }
        return BrewActionResult.returned(BrewText.message("joined", reason, interruption.message()));
    }

    private BrewActionResult tryUseItem(Player player, ItemStack stack) {
        if (stack.isEmpty()) {
            return describeState();
        }
        if (stack.is(Items.WATER_BUCKET)) {
            return useWaterBucket();
        }
        if (isWaterBottle(stack)) {
            return cauldron.addWater(WATER_BOTTLE_UNITS);
        }
        if (stack.is(Items.GLASS_BOTTLE)) {
            return collectBottle(player);
        }
        // 蜜脾是招虫水的引药：铜釜正熬着时按下料处理，不熬的时候才是上蜡。
        if (vessel == VesselKind.COPPER_CAULDRON && stack.is(Items.HONEYCOMB)
                && cauldron.state() != BrewCauldronState.BREWING) {
            return cauldron.waxCopper();
        }
        if (vessel == VesselKind.COPPER_CAULDRON && stack.is(ItemTags.AXES)) {
            return cauldron.scrapeCopper();
        }
        return addIngredient(stack);
    }

    private BrewActionResult tryUseTechniqueOffHand(ItemStack stack) {
        // 熬药式期间副手只允许添水或按药方下料，舀药、上蜡和刮锈不属于协作入口。
        if (stack.is(Items.WATER_BUCKET)) {
            return useWaterBucket();
        }
        if (isWaterBottle(stack)) {
            return cauldron.addWater(WATER_BOTTLE_UNITS);
        }
        return addIngredient(stack);
    }

    private boolean techniqueControlled() {
        // 接管状态只表示当前世界内的活动施放，不写入巫釜存档。
        return techniqueCaster != null;
    }

    private boolean techniqueControlledBy(Player player) {
        // 其他玩家的交互不借用当前施放者的副手许可。
        return techniqueCaster != null && techniqueCaster.equals(player.getUUID());
    }

    static boolean isWaterBottle(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return stack.is(Items.POTION) && contents != null && contents.is(Potions.WATER);
    }

    private BrewActionResult useWaterBucket() {
        if (cauldron.state() != BrewCauldronState.SCORCHED) {
            return cauldron.addWater(WATER_BUCKET_UNITS);
        }
        BrewActionResult scrubbed = cauldron.scrubWithWater();
        if (scrubbed.accepted()) {
            BrewSounds.atBlock(level, worldPosition, BrewSounds.CAULDRON_SCRUB);
        }
        return scrubbed;
    }

    private void applyAcceptedUse(Player player, InteractionHand hand, ItemStack stack) {
        // 添水和灭火使用同一种容器返还规则，最后一桶保持在原手位。
        if (player.hasInfiniteMaterials() || stack.isEmpty()) {
            return;
        }
        if (stack.is(ItemTags.AXES)) {
            stack.hurtAndBreak(1, player, hand);
            return;
        }
        if (stack.is(Items.WATER_BUCKET)) {
            BrewingItemReturns.consumeWaterBucket(player, hand, stack);
            return;
        }
        // 水瓶、蜂蜜瓶、龙息用完还一个玻璃瓶；水瓶在原版没有合成剩余物，单独还。
        ItemStack remainder = ItemStack.EMPTY;
        ItemStackTemplate craftingRemainder = stack.getItem().getCraftingRemainder();
        if (isWaterBottle(stack)) {
            remainder = new ItemStack(Items.GLASS_BOTTLE);
        } else if (craftingRemainder != null) {
            remainder = craftingRemainder.create();
        }
        stack.shrink(1);
        if (!remainder.isEmpty()) {
            BrewingItemReturns.giveOrDrop(player, remainder);
        }
    }

    /**
     * 发射器对着釜用这一件：水桶、水瓶注水，玻璃瓶舀药。返回发射器里换回的东西（空桶、玻璃瓶、成药或药渣），
     * 釜不收时为空，发射器里的东西不动。
     */
    Optional<ItemStack> dispense(ItemStack stack) {
        if (level == null || level.isClientSide()) {
            return Optional.empty();
        }
        BrewActionResult result;
        ItemStack remainder;
        if (stack.is(Items.WATER_BUCKET)) {
            result = useWaterBucket();
            remainder = new ItemStack(Items.BUCKET);
        } else if (isWaterBottle(stack)) {
            result = cauldron.addWater(WATER_BOTTLE_UNITS);
            remainder = new ItemStack(Items.GLASS_BOTTLE);
        } else if (stack.is(Items.GLASS_BOTTLE)) {
            Collected collected = collect();
            result = collected.result();
            remainder = collected.output();
        } else {
            return Optional.empty();
        }
        if (!result.accepted()) {
            return Optional.empty();
        }
        setChanged();
        return Optional.of(remainder);
    }

    private BrewActionResult collectBottle(Player player) {
        Collected collected = collect();
        if (collected.result().accepted()) {
            BrewingItemReturns.giveOrDrop(player, collected.output());
        }
        return collected.result();
    }

    /** 舀一瓶。收药可能清空釜内配方，先留下药方和剂型再舀；没舀到时产物为空。 */
    private Collected collect() {
        long currentGameDay = currentGameDay();
        Optional<BrewRecipe> recipe = cauldron.recipe();
        BrewForm form = cauldron.form();
        var result = cauldron.collectBottle(currentGameDay);
        if (result.kind() == BrewCollectionKind.NOTHING) {
            return new Collected(BrewActionResult.returned(result.message()), ItemStack.EMPTY);
        }
        var score = result.score().orElseThrow();
        ItemStack output = residueStack();
        if (result.kind() == BrewCollectionKind.POTION) {
            BrewKind kind = recipe.orElseThrow().kind();
            BrewUse use = form == BrewForm.SPLASH ? BrewUse.SPLASH : BrewUse.DRINK;
            output = BrewItemStacks.create(kind, score.quality().orElseThrow(), use);
            BrewSounds.quality(level, worldPosition, score.quality().orElseThrow());
        } else {
            BrewSounds.atBlock(level, worldPosition, BrewSounds.CAULDRON_DUMP);
        }
        return new Collected(BrewActionResult.accepted(result.message()), output);
    }

    private record Collected(BrewActionResult result, ItemStack output) {
    }

    private BrewActionResult describeState() {
        BrewText recipeName = BrewText.message("cauldron.no_recipe");
        if (cauldron.recipe().isPresent()) {
            recipeName = BrewCauldron.recipeName(cauldron.recipe().orElseThrow().kind());
        }
        BrewText stateName = BrewText.message("state." + cauldron.state().name().toLowerCase(Locale.ROOT));
        int temperature = (int) Math.round(cauldron.temperature());
        return BrewActionResult.accepted(BrewText.message("cauldron.describe", recipeName, stateName,
                cauldron.waterUnits(), vessel.waterCapacity(), temperature));
    }

    private BrewActionResult addIngredient(ItemStack stack) {
        String itemId = itemId(stack.getItem());
        Optional<IngredientSlot> slot = ingredientSlot(itemId);
        if (slot.isEmpty()) {
            return BrewActionResult.returned(notAnIngredient(itemId));
        }
        IngredientSlot selectedSlot = slot.orElseThrow();
        IngredientBatch batch = ingredientBatch(stack, selectedSlot, itemId);
        BrewActionResult added = cauldron.addIngredient(selectedSlot, batch);
        if (added.accepted()) {
            BrewSounds.atBlock(level, worldPosition, BrewSounds.CAULDRON_ADD);
        }
        return added;
    }

    private BrewText notAnIngredient(String itemId) {
        BrewText.ItemName item = new BrewText.ItemName(itemId);
        if (cauldron.recipe().isEmpty()) {
            return BrewText.message("cauldron.primary.unknown", item);
        }
        return BrewText.message("cauldron.ingredient.wrong", item,
                BrewCauldron.recipeName(cauldron.recipe().orElseThrow().kind()));
    }

    private IngredientBatch ingredientBatch(ItemStack stack, IngredientSlot slot, String itemId) {
        if (slot != IngredientSlot.PRIMARY) {
            return IngredientBatch.raw(itemId);
        }
        Long recordedDay = stack.get(BrewingComponents.PREPARED_DAY);
        long preparedDay;
        if (recordedDay == null) {
            preparedDay = currentGameDay();
        } else {
            preparedDay = recordedDay;
        }
        return IngredientBatch.prepared(itemId, preparedDay);
    }

    private Optional<IngredientSlot> ingredientSlot(String itemId) {
        return cauldron.ingredientSlotFor(itemId);
    }

    private BrewStructure scanStructure() {
        HeatSourceKind heatSource = scanHeatSource();
        EnumSet<BrewDirection> hearthDirections = scanHearthDirections();
        SmokeVentState smokeVent = scanSmokeVent();
        HerbState herb = scanHerbs();
        return BrewStructure.fromHearthDirections(heatSource, hearthDirections, smokeVent, herb);
    }

    private HeatSourceKind scanHeatSource() {
        BlockPos firePos = worldPosition.below();
        BlockState fireBlock = level.getBlockState(firePos);
        if (fireBlock.is(Blocks.CAMPFIRE) && CampfireBlock.isLitCampfire(fireBlock)) {
            return HeatSourceKind.SUN_CAMPFIRE;
        }
        if (fireBlock.is(Blocks.SOUL_CAMPFIRE) && CampfireBlock.isLitCampfire(fireBlock)) {
            return HeatSourceKind.SOUL_CAMPFIRE;
        }
        BlockEntity fireEntity = level.getBlockEntity(firePos);
        if (fireEntity instanceof BrewFireBlockEntity fire) {
            return fire.heatSource();
        }
        return HeatSourceKind.NONE;
    }

    private EnumSet<BrewDirection> scanHearthDirections() {
        EnumSet<BrewDirection> directions = EnumSet.noneOf(BrewDirection.class);
        addHearthDirection(directions, BrewDirection.NORTH, worldPosition.north().below());
        addHearthDirection(directions, BrewDirection.EAST, worldPosition.east().below());
        addHearthDirection(directions, BrewDirection.SOUTH, worldPosition.south().below());
        addHearthDirection(directions, BrewDirection.WEST, worldPosition.west().below());
        return directions;
    }

    private void addHearthDirection(EnumSet<BrewDirection> directions, BrewDirection direction,
                                    BlockPos pos) {
        if (level.getBlockState(pos).is(BrewingTags.HEARTH_STONES)) {
            directions.add(direction);
        }
    }

    private SmokeVentState scanSmokeVent() {
        boolean open = level.getBlockState(worldPosition.above()).isAir()
                && level.getBlockState(worldPosition.above(2)).isAir();
        return open ? SmokeVentState.OPEN : SmokeVentState.BLOCKED;
    }

    private HerbState scanHerbs() {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                BlockState state = level.getBlockState(worldPosition.offset(x, 0, z));
                if (state.is(BrewingTags.POTTED_HERBS) || state.is(BrewingTags.POTTED_FUNGI)) {
                    return HerbState.PRESENT;
                }
            }
        }
        return HerbState.MISSING;
    }

    private void tickCopperOxidation() {
        copperOxidationTicks++;
        if (copperOxidationTicks < COPPER_OXIDATION_INTERVAL_TICKS) {
            return;
        }
        copperOxidationTicks = 0;
        boolean exposedToSky = level.canSeeSkyFromBelowWater(worldPosition.above());
        CopperExposure exposure = exposedToSky ? CopperExposure.EXPOSED : CopperExposure.SHELTERED;
        cauldron.ageCopper(exposure);
    }

    private void showCopperOxidation() {
        // 上蜡、刮锈和老化只改规则状态，外观在下一次 tick 同步到方块状态；同一方块换状态不会重建方块实体。
        BlockState shown = getBlockState();
        CopperOxidation oxidation = cauldron.copperState().oxidation();
        if (CopperCauldronBlock.oxidation(shown) != oxidation) {
            level.setBlock(worldPosition, CopperCauldronBlock.showOxidation(shown, oxidation), Block.UPDATE_CLIENTS);
        }
    }

    private long currentGameDay() {
        return Math.floorDiv(level.getOverworldClockTime(), DAY_TICKS);
    }

    private ItemStack itemStack(IngredientBatch batch) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(batch.itemId()));
        ItemStack stack = new ItemStack(item, batch.count());
        if (batch.preparation() == PreparationKind.PREPARED) {
            stack.set(BrewingComponents.PREPARED_DAY, batch.preparedGameDay());
        }
        return stack;
    }

    private ItemStack residueStack() {
        Identifier id = Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "brew_residue");
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return new ItemStack(item);
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        cauldron.restore(readCauldronSnapshot(input, cauldron.copperState()));
        copperOxidationTicks = input.getLongOr("copper_oxidation_ticks", 0L);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        BrewCauldron.Snapshot snapshot = cauldron.snapshot();
        output.putString("state", snapshot.state().name());
        output.putString("form", snapshot.form().name());
        output.putString("recipe_kind", enumName(snapshot.recipeKind()));
        output.putInt("water_units", snapshot.waterUnits());
        output.putDouble("temperature", snapshot.temperature());
        output.putDouble("soak_seconds", snapshot.soakSeconds());
        output.putDouble("brew_elapsed_seconds", snapshot.brewElapsedSeconds());
        output.putDouble("in_band_seconds", snapshot.inBandSeconds());
        output.putDouble("overheat_seconds", snapshot.overheatSeconds());
        output.putDouble("evaporation_seconds", snapshot.evaporationSeconds());
        output.putDouble("heat_phase_seconds", snapshot.heatPhaseSeconds());
        output.putInt("scorch_count", snapshot.scorchCount());
        output.putInt("remaining_bottles", snapshot.remainingBottles());
        output.putString("copper_oxidation", snapshot.copperState().oxidation().name());
        output.putBoolean("copper_waxed", snapshot.copperState().waxed());
        writeIngredients(output, snapshot.ingredients());
        writeIngredientTimes(output, snapshot.ingredientTimes());
        output.putLong("copper_oxidation_ticks", copperOxidationTicks);
    }

    private static BrewCauldron.Snapshot readCauldronSnapshot(ValueInput input, CopperCauldronState placed) {
        BrewCauldronState state = enumValue(input.getStringOr("state", "EMPTY"),
                BrewCauldronState.EMPTY);
        BrewForm form = enumValue(input.getStringOr("form", "DRINK"), BrewForm.DRINK);
        BrewKind recipeKind = enumValueOrNull(input.getStringOr("recipe_kind", ""), BrewKind.class);
        // 没写铜釜字段的数据（如手写 NBT）沿用放置时按方块外观得到的氧化级。
        CopperCauldronState copperState = new CopperCauldronState(
                enumValue(input.getStringOr("copper_oxidation", ""), placed.oxidation()),
                input.getBooleanOr("copper_waxed", placed.waxed()));
        Map<IngredientSlot, IngredientBatch> ingredients = readIngredients(input);
        Map<IngredientSlot, Double> ingredientTimes = readIngredientTimes(input);
        return new BrewCauldron.Snapshot(state, form, recipeKind, ingredients, ingredientTimes,
                input.getIntOr("water_units", 0), input.getDoubleOr("temperature", 0.0),
                input.getDoubleOr("soak_seconds", 0.0),
                input.getDoubleOr("brew_elapsed_seconds", 0.0),
                input.getDoubleOr("in_band_seconds", 0.0),
                input.getDoubleOr("overheat_seconds", 0.0),
                input.getDoubleOr("evaporation_seconds", 0.0),
                input.getDoubleOr("heat_phase_seconds", 0.0),
                input.getIntOr("scorch_count", 0), input.getIntOr("remaining_bottles", 0),
                copperState);
    }

    private static Map<IngredientSlot, IngredientBatch> readIngredients(ValueInput input) {
        Map<IngredientSlot, IngredientBatch> ingredients = new EnumMap<>(IngredientSlot.class);
        for (ValueInput child : input.childrenListOrEmpty("ingredients")) {
            IngredientSlot slot = enumValueOrNull(child.getStringOr("slot", ""), IngredientSlot.class);
            if (slot != null) {
                ingredients.put(slot, readBatch(child));
            }
        }
        return ingredients;
    }

    private static Map<IngredientSlot, Double> readIngredientTimes(ValueInput input) {
        Map<IngredientSlot, Double> times = new EnumMap<>(IngredientSlot.class);
        for (ValueInput child : input.childrenListOrEmpty("ingredient_times")) {
            IngredientSlot slot = enumValueOrNull(child.getStringOr("slot", ""), IngredientSlot.class);
            if (slot != null) {
                times.put(slot, child.getDoubleOr("seconds", 0.0));
            }
        }
        return times;
    }

    private static IngredientBatch readBatch(ValueInput input) {
        String itemId = input.getStringOr("item_id", "");
        PreparationKind preparation = enumValue(input.getStringOr("preparation", "RAW"),
                PreparationKind.RAW);
        long preparedDay = input.getLongOr("prepared_game_day", -1L);
        int count = input.getIntOr("count", 1);
        return new IngredientBatch(itemId, preparation, preparedDay, count);
    }

    private static void writeIngredients(ValueOutput output,
                                         Map<IngredientSlot, IngredientBatch> ingredients) {
        ValueOutput.ValueOutputList list = output.childrenList("ingredients");
        for (IngredientSlot slot : IngredientSlot.values()) {
            IngredientBatch batch = ingredients.get(slot);
            if (batch == null) {
                continue;
            }
            ValueOutput child = list.addChild();
            child.putString("slot", slot.name());
            writeBatch(child, batch);
        }
    }

    private static void writeIngredientTimes(ValueOutput output,
                                             Map<IngredientSlot, Double> ingredientTimes) {
        ValueOutput.ValueOutputList list = output.childrenList("ingredient_times");
        for (IngredientSlot slot : IngredientSlot.values()) {
            Double seconds = ingredientTimes.get(slot);
            if (seconds == null) {
                continue;
            }
            ValueOutput child = list.addChild();
            child.putString("slot", slot.name());
            child.putDouble("seconds", seconds);
        }
    }

    private static void writeBatch(ValueOutput output, IngredientBatch batch) {
        output.putString("item_id", batch.itemId());
        output.putString("preparation", batch.preparation().name());
        output.putLong("prepared_game_day", batch.preparedGameDay());
        output.putInt("count", batch.count());
    }

    private static String enumName(Enum<?> value) {
        if (value == null) {
            return "";
        }
        return value.name();
    }

    private static <T extends Enum<T>> T enumValue(String name, T fallback) {
        T value = enumValueOrNull(name, fallback.getDeclaringClass());
        return value == null ? fallback : value;
    }

    private static <T extends Enum<T>> T enumValueOrNull(String name, Class<T> type) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static BrewStructure emptyStructure() {
        return BrewStructure.fromHearthDirections(HeatSourceKind.NONE,
                EnumSet.noneOf(BrewDirection.class), SmokeVentState.OPEN, HerbState.MISSING);
    }
}
