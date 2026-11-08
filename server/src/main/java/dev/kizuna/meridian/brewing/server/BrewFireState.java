package dev.kizuna.meridian.brewing.server;

/** 铁火盆和铁炉座的服务端火档、火种与续炭状态。 */
public final class BrewFireState {
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 4;
    public static final double FUEL_SECONDS_PER_LEVEL = 60.0;
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
            return BrewActionResult.returned("火种不能为空");
        }
        if (fireKind == nextFireKind) {
            return BrewActionResult.returned("当前已经是该火种");
        }
        fireKind = nextFireKind;
        return BrewActionResult.accepted("火种已切换");
    }

    public int level() {
        return level;
    }

    public double fuelSeconds() {
        return fuelSeconds;
    }

    public BrewActionResult addFuel(String itemId) {
        if (!COAL_ID.equals(itemId) && !CHARCOAL_ID.equals(itemId)) {
            return BrewActionResult.returned("铁火盆只收煤炭或木炭");
        }
        if (source == BrewFireSource.CAMPFIRE) {
            return BrewActionResult.returned("营火不能调档");
        }
        if (level >= MAX_LEVEL) {
            return BrewActionResult.returned("火档已经最高");
        }
        level++;
        fuelSeconds += FUEL_SECONDS_PER_LEVEL;
        return BrewActionResult.accepted("火档升至 " + level);
    }

    public BrewActionResult lowerLevel() {
        if (source == BrewFireSource.CAMPFIRE) {
            return BrewActionResult.returned("营火不能调档");
        }
        if (level <= 0) {
            return BrewActionResult.returned("火已经熄灭");
        }
        level--;
        return BrewActionResult.accepted("火档降至 " + level);
    }

    public BrewActionResult extinguish() {
        if (source != BrewFireSource.CAMPFIRE) {
            level = 0;
            fuelSeconds = 0.0;
            return BrewActionResult.accepted("火已熄灭");
        }
        return BrewActionResult.returned("营火状态由方块本身控制");
    }

    public void tick(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0.0) {
            throw new IllegalArgumentException("火源推进时间非法");
        }
        if (source == BrewFireSource.CAMPFIRE || level == 0) {
            return;
        }
        fuelSeconds = Math.max(0.0, fuelSeconds - seconds);
        while (fuelSeconds <= 0.0 && level > 0) {
            level--;
            if (level > 0) {
                fuelSeconds += FUEL_SECONDS_PER_LEVEL;
            }
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
