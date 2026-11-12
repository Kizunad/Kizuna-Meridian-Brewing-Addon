package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewActionResult;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewCollectionKind;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewProgress;
import dev.kizuna.meridian.brewing.server.BrewQiTally;
import dev.kizuna.meridian.brewing.server.BrewRecipe;
import dev.kizuna.meridian.brewing.server.BrewSaveMarks;
import dev.kizuna.meridian.brewing.server.BrewStructure;
import dev.kizuna.meridian.brewing.server.BrewText;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.brewing.server.CopperCauldronState;
import dev.kizuna.meridian.brewing.server.CopperExposure;
import dev.kizuna.meridian.brewing.server.CopperOxidation;
import dev.kizuna.meridian.brewing.server.FireKind;
import dev.kizuna.meridian.brewing.server.HeatSourceKind;
import dev.kizuna.meridian.brewing.server.IngredientBatch;
import dev.kizuna.meridian.brewing.server.IngredientSlot;
import dev.kizuna.meridian.brewing.server.PreparationKind;
import dev.kizuna.meridian.brewing.server.VesselKind;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.WorldlyContainer;
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

/**
 * 釜的方块实体：查釜所在的熬药结构和釜下的火、推进规则状态并处理服务端右键。
 *
 * <p>对漏斗、投掷器是一个只放 1 件的暂存格：开熬前的主料、底料、火药能放，下一 tick 下锅；
 * 别的东西放不进来，留在漏斗、投掷器里。什么都抽不出来。</p>
 *
 * <p>存档：料、水、状态、暂存格这类离散内容一变当 tick 标脏；浸泡、熬制计时每秒最多标一次；温度和火苗相位
 * 不单独标，跟着别的变化一起存，区块卸载后回来是上次存档那一刻的温度；铜釜老化计数按分钟标
 * （{@link BrewSaveMarks}）。</p>
 */
public final class BrewCauldronBlockEntity extends BlockEntity implements WorldlyContainer {
    private static final double SECONDS_PER_TICK = 0.05;
    private static final double TECHNIQUE_MAX_DISTANCE = 3.0;
    private static final double TECHNIQUE_MAX_DISTANCE_SQUARED =
            TECHNIQUE_MAX_DISTANCE * TECHNIQUE_MAX_DISTANCE;
    private static final double TECHNIQUE_SECONDS_PER_STEP = 1.0;
    private static final double BLOCK_CENTER = 0.5;
    private static final long DAY_TICKS = 24_000L;
    private static final long COPPER_OXIDATION_INTERVAL_TICKS = 24_000L;
    private static final long COPPER_SAVE_TICKS = 1_200L;
    private static final long COPPER_NOT_AGING = -1L;
    private static final int WATER_BUCKET_UNITS = 3;
    private static final int WATER_BOTTLE_UNITS = 1;
    private static final int STRUCTURE_CHECK_TICKS = 20;
    private static final int[] STAGING_SLOT = {0};
    private static final Set<IngredientSlot> AUTOMATED_SLOTS =
            EnumSet.of(IngredientSlot.PRIMARY, IngredientSlot.BASE, IngredientSlot.PROPELLANT);
    private static final double ABOVE_RIM = 1.0;

    private final VesselKind vessel;
    private final BrewCauldron cauldron;
    private BrewStructure structure;
    private BrewStructureMatcher.Result layout;
    private int structureCheckCountdown;
    private long copperOxidationTicks;
    private UUID techniqueCaster;
    private BrewQiTally qiTally;
    private final NonNullList<ItemStack> staged = NonNullList.withSize(1, ItemStack.EMPTY);
    private final BrewSaveMarks<SaveContent, SaveProgress> saveMarks = new BrewSaveMarks<>();
    private long ticks;

    public BrewCauldronBlockEntity(BlockPos pos, BlockState state, VesselKind vessel) {
        super(BrewingBlocks.BREW_CAULDRON_ENTITY, pos, state);
        this.vessel = vessel;
        this.cauldron = new BrewCauldron(vessel, BrewForm.DRINK);
        this.layout = BrewStructureMatcher.unchecked();
        this.structure = new BrewStructure(HeatSourceKind.NONE, layout.match());
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
        // 煤、铲子、沙子是给火的：二阶的铁炉座四面埋在砖里够不着，对着釜用就转给釜下的火源（P1 甲）。
        if (BrewFireBlockEntity.tendsFire(stack)
                && level.getBlockEntity(worldPosition.below()) instanceof BrewFireBlockEntity fire) {
            return fire.use(player, hand);
        }
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
        rematchStructure();
        Optional<BrewText> problem = cauldron.startProblem(structure);
        if (problem.isPresent() && problem.equals(layout.match().problem())) {
            // 卡在结构上才在不符那格发标记；缺料、缺火不发。
            BrewStructureMarkers.show(caster, layout);
        }
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
        // 付不满记欠付，欠付超过宽限才中断，不降分。
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
        rematchStructure();
        BrewRecipe recipe = cauldron.recipe().orElseThrow(() -> new IllegalStateException("熬制中的釜没有药方"));
        Optional<BrewText> structureProblem = structure.problemFor(recipe.kind(), recipe.fireKind());
        if (structureProblem.isPresent()) {
            BrewStructureMarkers.show(caster, layout);
            return interruptTechnique(BrewText.message("technique.interrupted", structureProblem.orElseThrow()));
        }
        BrewQiTally paid = BrewQiPayment.paySecond(caster, level, worldPosition, drawId, qiTally);
        if (paid.beyondGrace()) {
            BrewTexts.sendOverlay(caster, BrewText.message("technique.qi_short"));
            return interruptTechnique(BrewText.message("technique.qi_short"));
        }
        qiTally = paid;
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
        // 火每 tick 读一格；整座结构每 20 tick 查一次就够，晚一秒发现结构坏了无所谓。
        if (currentLevel.isClientSide()) {
            return;
        }
        ticks++;
        structureCheckCountdown--;
        if (structureCheckCountdown <= 0) {
            structureCheckCountdown = STRUCTURE_CHECK_TICKS;
            rematchStructure();
        } else {
            refreshHeatSource();
        }
        addStagedIngredient();
        if (!techniqueControlled()) {
            tickCauldron(SECONDS_PER_TICK);
        }
        if (vessel == VesselKind.COPPER_CAULDRON) {
            tickCopperOxidation();
            showCopperOxidation();
        }
        markIfChanged();
    }

    @Override
    public void setChanged() {
        // 右键、漏斗、熬药式哪个入口标的脏都记下这一刻的存档内容，tick 末只和它比，不为同一处变化再标一次。
        super.setChanged();
        rememberSaved();
    }

    private void rememberSaved() {
        // 记下此刻会写进存档的内容；读档后也记一次，刚读进来、什么都没变就不用再存。
        BrewCauldron.Snapshot snapshot = cauldron.snapshot();
        saveMarks.marked(saveContent(snapshot), saveProgress(snapshot), ticks);
    }

    private void markIfChanged() {
        // 离散内容一变当 tick 标，比较器跟着更新；计时在走时最多每秒标一次；釜里没动静就不碰区块。
        BrewCauldron.Snapshot snapshot = cauldron.snapshot();
        if (saveMarks.due(saveContent(snapshot), saveProgress(snapshot), ticks)) {
            setChanged();
        }
    }

    private SaveContent saveContent(BrewCauldron.Snapshot snapshot) {
        // 状态、料、水、剩几瓶、铜釜状态，加上暂存格里那一件。
        ItemStack stagedStack = staged.getFirst();
        String stagedItem = "";
        if (!stagedStack.isEmpty()) {
            stagedItem = itemId(stagedStack.getItem());
        }
        return new SaveContent(snapshot.withoutProgress(), stagedItem);
    }

    private SaveProgress saveProgress(BrewCauldron.Snapshot snapshot) {
        // 浸泡、熬制这几段计时，加上铜釜老化计数走到第几分钟。
        return new SaveProgress(snapshot.progress(), copperSaveStep());
    }

    private long copperSaveStep() {
        // 只有还会老化的铜釜才存计数：上了蜡、已经锈到头、或者不是铜釜，计数走不走都不影响什么。
        if (vessel != VesselKind.COPPER_CAULDRON) {
            return COPPER_NOT_AGING;
        }
        CopperCauldronState copper = cauldron.copperState();
        CopperOxidation oxidation = copper.oxidation();
        if (copper.waxed() || oxidation.next() == oxidation) {
            return COPPER_NOT_AGING;
        }
        return copperOxidationTicks / COPPER_SAVE_TICKS;
    }

    /** 存档里一变就要存的那部分。 */
    private record SaveContent(BrewCauldron.Snapshot settled, String stagedItem) {
    }

    /** 存档里一直在走、每秒最多存一次的那部分。 */
    private record SaveProgress(BrewCauldron.Progress brewing, long copperStep) {
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
            return describeState(player);
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

    private BrewActionResult describeState(Player player) {
        // 空手右键看釜：现查一次结构，不符时在不符那格发标记，并在状态后面接上哪一格不对。
        rematchStructure();
        BrewText recipeName = BrewText.message("cauldron.no_recipe");
        if (cauldron.recipe().isPresent()) {
            recipeName = BrewCauldron.recipeName(cauldron.recipe().orElseThrow().kind());
        }
        BrewText stateName = BrewText.message("state." + cauldron.state().name().toLowerCase(Locale.ROOT));
        int temperature = (int) Math.round(cauldron.temperature());
        BrewText described = BrewText.message("cauldron.describe", recipeName, stateName,
                cauldron.waterUnits(), vessel.waterCapacity(), temperature);
        Optional<BrewText> structureProblem = structure.match().problem();
        if (structureProblem.isEmpty()) {
            return BrewActionResult.accepted(described);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            BrewStructureMarkers.show(serverPlayer, layout);
        }
        return BrewActionResult.accepted(BrewText.message("joined", described, structureProblem.orElseThrow()));
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

    private void addStagedIngredient() {
        // 漏斗、投掷器上一 tick 放进来的那一件这时下锅；同一 tick 里釜变了下不进去，就从釜口弹出来，不吞。
        ItemStack stagedStack = staged.getFirst();
        if (stagedStack.isEmpty()) {
            return;
        }
        staged.set(0, ItemStack.EMPTY);
        if (!addIngredient(stagedStack).accepted()) {
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY() + ABOVE_RIM,
                    worldPosition.getZ(), stagedStack);
        }
    }

    private boolean automatable(ItemStack stack) {
        // 和手动下料同一套判断，只是辅料、引药不收：它们在熬药式的时窗里下，算「时」的分。
        String itemId = itemId(stack.getItem());
        Optional<IngredientSlot> slot = ingredientSlot(itemId);
        if (slot.isEmpty() || !AUTOMATED_SLOTS.contains(slot.orElseThrow())) {
            return false;
        }
        IngredientSlot selectedSlot = slot.orElseThrow();
        return cauldron.ingredientProblem(selectedSlot, ingredientBatch(stack, selectedSlot, itemId)).isEmpty();
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return STAGING_SLOT;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return staged.getFirst().isEmpty() && automatable(stack);
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public int getContainerSize() {
        return staged.size();
    }

    @Override
    public boolean isEmpty() {
        return staged.getFirst().isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return staged.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack taken = ContainerHelper.removeItem(staged, slot, count);
        setChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(staged, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        staged.set(slot, stack);
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        staged.clear();
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

    private void rematchStructure() {
        // 现比对一次整座结构，结果留着给显示和标记用，再顺手读火。
        layout = BrewStructureMatcher.matchCauldron((ServerLevel) level, worldPosition, getBlockState().getBlock());
        refreshHeatSource();
    }

    private void refreshHeatSource() {
        // 火源每次都现读，结构沿用上次比对的结果。
        structure = new BrewStructure(scanHeatSource(), layout.match());
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
        cauldron.restore(BrewCauldronSnapshots.read(input, cauldron.copperState()));
        copperOxidationTicks = input.getLongOr("copper_oxidation_ticks", 0L);
        staged.clear();
        ContainerHelper.loadAllItems(input, staged);
        rememberSaved();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        BrewCauldronSnapshots.write(output, cauldron.snapshot());
        output.putLong("copper_oxidation_ticks", copperOxidationTicks);
        ContainerHelper.saveAllItems(output, staged);
    }
}
