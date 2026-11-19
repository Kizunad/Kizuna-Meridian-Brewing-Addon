package dev.kizuna.meridian.brewing.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** 维度由 SavedData 的归属决定，同一底板中心只保留一条有效记录。 */
final class FurnaceRegister extends SavedData {
    record Site(Identifier definition, int version, int tier, BlockPos anchor, Mirror mirror, Rotation rotation) {
        static final Codec<Site> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("definition").forGetter(Site::definition),
                Codec.INT.fieldOf("version").forGetter(Site::version),
                Codec.INT.fieldOf("tier").forGetter(Site::tier),
                BlockPos.CODEC.fieldOf("anchor").forGetter(Site::anchor),
                Mirror.CODEC.fieldOf("mirror").forGetter(Site::mirror),
                Rotation.CODEC.fieldOf("rotation").forGetter(Site::rotation)
        ).apply(instance, Site::new));

        static Site of(FurnaceLayout layout) {
            var pattern = layout.pattern();
            return new Site(pattern.id(), pattern.version(), pattern.tier(), layout.anchor(),
                    layout.mirror(), layout.rotation());
        }
    }

    private record StockKey(BlockPos anchor, Identifier definition, int version) {
        static StockKey of(Site site) {
            return new StockKey(site.anchor(), site.definition(), site.version());
        }
    }

    private record Stock(Site site, FurnaceBatch batch) {
        static final Codec<Stock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Site.CODEC.fieldOf("site").forGetter(Stock::site),
                CompoundTag.CODEC.fieldOf("batch").forGetter(stock -> stock.batch().save())
        ).apply(instance, Stock::read));

        private static Stock read(Site site, CompoundTag tag) {
            var batch = new FurnaceBatch(site.tier());
            batch.load(tag);
            return new Stock(site, batch);
        }
    }

    static final Codec<FurnaceRegister> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Site.CODEC.listOf().fieldOf("furnaces").forGetter(FurnaceRegister::sites),
            Stock.CODEC.listOf().optionalFieldOf("stock", List.of()).forGetter(FurnaceRegister::stored)
    ).apply(instance, FurnaceRegister::new));
    static final SavedDataType<FurnaceRegister> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(BrewingFabric.MOD_ID, "formed_furnaces"),
            FurnaceRegister::new, CODEC, null);
    private final Map<BlockPos, Site> byAnchor = new LinkedHashMap<>();

    private final Map<StockKey, Stock> stock = new LinkedHashMap<>();

    private FurnaceRegister() {}

    private FurnaceRegister(List<Site> saved, List<Stock> supplies) {
        for (Stock contents : supplies) {
            stock.put(StockKey.of(contents.site()), contents);
        }
        for (Site site : saved) {
            byAnchor.put(site.anchor(), site);
        }
    }

    static FurnaceRegister of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    List<Site> sites() {
        return List.copyOf(byAnchor.values());
    }

    private List<Stock> stored() {
        return stock.values().stream().filter(entry -> !entry.batch().empty()).toList();
    }

    FurnaceBatch batch(FurnaceLayout layout) {
        Site site = Site.of(layout);
        return stock.computeIfAbsent(StockKey.of(site), key -> new Stock(site, new FurnaceBatch(site.tier()))).batch();
    }

    void remember(FurnaceLayout layout) {
        Site site = Site.of(layout);
        if (!site.equals(byAnchor.put(site.anchor(), site))) {
            setDirty();
        }
    }

    void forget(BlockPos anchor) {
        Site removed = byAnchor.remove(anchor);
        if (removed != null) {
            var key = StockKey.of(removed);
            Stock contents = stock.get(key);
            if (contents != null && contents.batch().empty()) {
                stock.remove(key);
            }
            setDirty();
        }
    }
}
