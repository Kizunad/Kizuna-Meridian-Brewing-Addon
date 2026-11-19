package dev.kizuna.meridian.brewing.server;

/** 铁火盆和铁炉座的服务端火档、火种与续炭状态。 */
public final class BrewFireState {
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 4;
    public static final double FUEL_SECONDS_PER_LEVEL = VesselKind.WITCH_CAULDRON.batch().fuelSeconds();
    public static final int COAL_BLOCK_UNITS = 9;
    private static final String COAL_ID = "minecraft:coal";
    private static final String CHARCOAL_ID = "minecraft:charcoal";

    private final BrewFireSource source;
    private FireKind fireKind;
    private int level;
    private double fuelSeconds;

    private BrewFireState(BrewFireSource source, FireKind fireKind, int level) {
        if (source == null || fireKind == null || level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("火源状态不完整");
        }
        this.source = source;
        this.fireKind = fireKind;
        this.level = level;
        this.fuelSeconds = level * FUEL_SECONDS_PER_LEVEL;
    }

    public static BrewFireState campfire(FireKind fireKind) {
        return new BrewFireState(BrewFireSource.CAMPFIRE, fireKind, MAX_LEVEL);
    }

    public static BrewFireState brazier(FireKind fireKind) {
        return new BrewFireState(BrewFireSource.BRAZIER, fireKind, 0);
    }

    public static BrewFireState stove(FireKind fireKind) {
        return new BrewFireState(BrewFireSource.STOVE, fireKind, 0);
    }

    public BrewFireSource source() {
        return source;
    }

    public FireKind fireKind() {
        return fireKind;
    }

    public BrewActionResult changeFireKind(FireKind nextFireKind) {
        if (nextFireKind == null) {
            throw new IllegalArgumentException("火种不能为空");
        }
        if (fireKind == nextFireKind) {
            return BrewActionResult.returned(BrewText.message("fire.kind.same", BrewStructure.fireName(fireKind)));
        }
        fireKind = nextFireKind;
        return BrewActionResult.accepted(BrewText.message("fire.kind.changed", BrewStructure.fireName(fireKind)));
    }

    public int level() {
        return level;
    }

    public double fuelSeconds() {
        return fuelSeconds;
    }

    public BrewActionResult addFuel(String itemId) {
        if (COAL_ID.equals(itemId) || CHARCOAL_ID.equals(itemId)) {
            return addFuel(1);
        }
        if ("minecraft:coal_block".equals(itemId)) {
            return addFuel(COAL_BLOCK_UNITS);
        }
        return BrewActionResult.returned(BrewText.message("fire.fuel.wrong"));
    }

    public BrewActionResult addFuel(int units) {
        if (units <= 0) {
            return BrewActionResult.returned(BrewText.message("fire.fuel.wrong"));
        }
        if (source == BrewFireSource.CAMPFIRE) {
            return BrewActionResult.returned(BrewText.message("fire.campfire_fixed"));
        }
        if (level >= MAX_LEVEL) {
            return BrewActionResult.returned(BrewText.message("fire.level.max", MAX_LEVEL));
        }
        level++;
        fuelSeconds += units * FUEL_SECONDS_PER_LEVEL;
        return BrewActionResult.accepted(BrewText.message("fire.level.up", level));
    }

    public BrewActionResult lowerLevel() {
        if (source == BrewFireSource.CAMPFIRE) {
            return BrewActionResult.returned(BrewText.message("fire.campfire_fixed"));
        }
        if (level <= 0) {
            return BrewActionResult.returned(BrewText.message("fire.level.out"));
        }
        level--;
        return BrewActionResult.accepted(BrewText.message("fire.level.down", level));
    }

    public BrewActionResult extinguish() {
        if (source != BrewFireSource.CAMPFIRE) {
            level = 0;
            return BrewActionResult.accepted(BrewText.message("fire.extinguished"));
        }
        return BrewActionResult.returned(BrewText.message("fire.campfire_block"));
    }

    public void tick(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0.0) {
            throw new IllegalArgumentException("火源推进时间非法");
        }
        if (source == BrewFireSource.CAMPFIRE || level == 0) {
            return;
        }
        fuelSeconds = Math.max(0.0, fuelSeconds - seconds * level);
        if (fuelSeconds == 0.0) {
            level = 0;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(source, fireKind, level, fuelSeconds);
    }

    public void restore(Snapshot snapshot) {
        if (snapshot == null || snapshot.source() != source || snapshot.fireKind() == null
                || snapshot.level() < 0 || snapshot.level() > MAX_LEVEL
                || !Double.isFinite(snapshot.fuelSeconds()) || snapshot.fuelSeconds() < 0.0) {
            throw new IllegalArgumentException("火源快照不完整");
        }
        fireKind = snapshot.fireKind();
        level = snapshot.level();
        fuelSeconds = snapshot.fuelSeconds();
    }

    public HeatSourceKind heatSource() {
        if (source == BrewFireSource.CAMPFIRE) {
            return fireKind == FireKind.SUN_FIRE
                    ? HeatSourceKind.SUN_CAMPFIRE : HeatSourceKind.SOUL_CAMPFIRE;
        }
        if (level == 0) {
            return HeatSourceKind.NONE;
        }
        if (source == BrewFireSource.BRAZIER) {
            return brazierHeatSource();
        }
        return stoveHeatSource();
    }

    private HeatSourceKind brazierHeatSource() {
        return switch (fireKind) {
            case SUN_FIRE -> switch (level) {
                case 1 -> HeatSourceKind.SUN_BRAZIER_1;
                case 2 -> HeatSourceKind.SUN_BRAZIER_2;
                case 3 -> HeatSourceKind.SUN_BRAZIER_3;
                default -> HeatSourceKind.SUN_BRAZIER_4;
            };
            case SOUL_FIRE -> switch (level) {
                case 1 -> HeatSourceKind.SOUL_BRAZIER_1;
                case 2 -> HeatSourceKind.SOUL_BRAZIER_2;
                case 3 -> HeatSourceKind.SOUL_BRAZIER_3;
                default -> HeatSourceKind.SOUL_BRAZIER_4;
            };
        };
    }

    private HeatSourceKind stoveHeatSource() {
        return switch (fireKind) {
            case SUN_FIRE -> switch (level) {
                case 1 -> HeatSourceKind.SUN_STOVE_1;
                case 2 -> HeatSourceKind.SUN_STOVE_2;
                case 3 -> HeatSourceKind.SUN_STOVE_3;
                default -> HeatSourceKind.SUN_STOVE_4;
            };
            case SOUL_FIRE -> switch (level) {
                case 1 -> HeatSourceKind.SOUL_STOVE_1;
                case 2 -> HeatSourceKind.SOUL_STOVE_2;
                case 3 -> HeatSourceKind.SOUL_STOVE_3;
                default -> HeatSourceKind.SOUL_STOVE_4;
            };
        };
    }

    public record Snapshot(BrewFireSource source, FireKind fireKind, int level, double fuelSeconds) {
    }
}
