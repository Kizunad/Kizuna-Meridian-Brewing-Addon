package dev.kizuna.meridian.brewing.fabric;

import dev.kizuna.meridian.brewing.server.BrewActionResult;
import dev.kizuna.meridian.brewing.server.BrewCauldron;
import dev.kizuna.meridian.brewing.server.BrewCauldronState;
import dev.kizuna.meridian.brewing.server.BrewCollectionKind;
import dev.kizuna.meridian.brewing.server.BrewDirection;
import dev.kizuna.meridian.brewing.server.BrewForm;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewRecipe;
import dev.kizuna.meridian.brewing.server.BrewStructure;
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
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
    private static final long DAY_TICKS = 24_000L;
    private static final long COPPER_OXIDATION_INTERVAL_TICKS = 24_000L;

    private final VesselKind vessel;
    private final BrewCauldron cauldron;
    private BrewStructure structure;
    private long copperOxidationTicks;

    public BrewCauldronBlockEntity(BlockPos pos, BlockState state, VesselKind vessel) {
        super(BrewingBlocks.BREW_CAULDRON_ENTITY, pos, state);
        this.vessel = vessel;
        this.cauldron = new BrewCauldron(vessel, BrewForm.DRINK);
        this.structure = emptyStructure();
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
        if (level == null || level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = player.getItemInHand(hand);
        BrewActionResult result = tryUseItem(player, stack);
        if (!result.accepted()) {
            sendOverlay(player, result.reason());
            return InteractionResult.SUCCESS;
        }
        applyAcceptedUse(player, hand, stack);
        sendOverlay(player, result.reason());
        setChanged();
        return InteractionResult.SUCCESS;
    }

    public BrewActionResult startBrew(ServerPlayer caster) {
        if (level == null || level.isClientSide()) {
            return BrewActionResult.returned("巫釜只能在服务端起熬");
        }
        if (caster == null || !level.mayInteract(caster, worldPosition)) {
            return BrewActionResult.returned("没有权限操作巫釜");
        }
        structure = scanStructure();
        BrewActionResult result = cauldron.startBrew(structure);
        if (result.accepted()) {
            setChanged();
        }
        return result;
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
        if (currentLevel.isClientSide()) {
            return;
        }
        structure = scanStructure();
        cauldron.tick(SECONDS_PER_TICK, structure);
        if (vessel == VesselKind.COPPER_CAULDRON) {
            tickCopperOxidation();
        }
        setChanged();
    }

    private BrewActionResult tryUseItem(Player player, ItemStack stack) {
        if (stack.isEmpty()) {
            return describeState();
        }
        if (stack.is(Items.WATER_BUCKET)) {
            return useWaterBucket();
        }
        if (stack.is(Items.GLASS_BOTTLE)) {
            return collectBottle(player);
        }
        if (vessel == VesselKind.COPPER_CAULDRON && stack.is(Items.HONEYCOMB)) {
            return cauldron.waxCopper();
        }
        if (vessel == VesselKind.COPPER_CAULDRON && stack.is(ItemTags.AXES)) {
            return cauldron.scrapeCopper();
        }
        return addIngredient(stack);
    }

    private BrewActionResult useWaterBucket() {
        if (cauldron.state() == BrewCauldronState.SCORCHED) {
            return cauldron.scrubWithWater();
        }
        return cauldron.addWater(1);
    }

    private void applyAcceptedUse(Player player, InteractionHand hand, ItemStack stack) {
        boolean waterBucket = stack.is(Items.WATER_BUCKET);
        if (player.hasInfiniteMaterials() || stack.isEmpty()) {
            return;
        }
        if (stack.is(ItemTags.AXES)) {
            stack.hurtAndBreak(1, player, hand);
            return;
        }
        stack.shrink(1);
        if (waterBucket) {
            giveOrDrop(player, new ItemStack(Items.BUCKET));
        }
    }

    private BrewActionResult collectBottle(Player player) {
        long currentGameDay = currentGameDay();
        Optional<BrewRecipe> recipe = cauldron.recipe();
        BrewForm form = cauldron.form();
        var result = cauldron.collectBottle(currentGameDay);
        if (result.kind() == BrewCollectionKind.NOTHING) {
            return BrewActionResult.returned(result.reason());
        }
        ItemStack output = residueStack();
        if (result.kind() == BrewCollectionKind.POTION && result.score().isPresent()) {
            var score = result.score().orElseThrow();
            BrewKind kind = recipe.orElseThrow().kind();
            BrewUse use = form == BrewForm.SPLASH ? BrewUse.SPLASH : BrewUse.DRINK;
            output = BrewItemStacks.create(kind, score.quality(), use);
        }
        giveOrDrop(player, output);
        return BrewActionResult.accepted("已舀出一瓶，余量 " + result.remainingBottles());
    }

    private BrewActionResult describeState() {
        String recipeName = cauldron.recipe().map(value -> value.kind().displayName()).orElse("未定药方");
        String message = recipeName + " · " + cauldron.state()
                + " · 水 " + cauldron.waterUnits() + " 份 · 温度 "
                + Math.round(cauldron.temperature()) + "°";
        return BrewActionResult.accepted(message);
    }

    private BrewActionResult addIngredient(ItemStack stack) {
        String itemId = itemId(stack.getItem());
        Optional<IngredientSlot> slot = ingredientSlot(itemId);
        if (slot.isEmpty()) {
            return BrewActionResult.returned("这件物品不是当前药方的投料");
        }
        IngredientSlot selectedSlot = slot.orElseThrow();
        IngredientBatch batch = ingredientBatch(stack, selectedSlot, itemId);
        return cauldron.addIngredient(selectedSlot, batch);
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

    private long currentGameDay() {
        return Math.floorDiv(level.getOverworldClockTime(), DAY_TICKS);
    }

    private void giveOrDrop(Player player, ItemStack stack) {
        if (!player.addItem(stack)) {
            player.drop(stack, false /* throwRandomly */);
        }
    }

    private static void sendOverlay(Player player, String message) {
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection == null) {
            return;
        }
        player.sendOverlayMessage(Component.literal(message));
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
        cauldron.restore(readCauldronSnapshot(input));
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

    private static BrewCauldron.Snapshot readCauldronSnapshot(ValueInput input) {
        BrewCauldronState state = enumValue(input.getStringOr("state", "EMPTY"),
                BrewCauldronState.EMPTY);
        BrewForm form = enumValue(input.getStringOr("form", "DRINK"), BrewForm.DRINK);
        BrewKind recipeKind = enumValueOrNull(input.getStringOr("recipe_kind", ""), BrewKind.class);
        CopperCauldronState copperState = new CopperCauldronState(
                enumValue(input.getStringOr("copper_oxidation", "UNAFFECTED"),
                        CopperOxidation.UNAFFECTED),
                input.getBooleanOr("copper_waxed", false));
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
