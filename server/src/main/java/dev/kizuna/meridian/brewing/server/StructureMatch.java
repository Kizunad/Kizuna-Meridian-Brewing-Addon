package dev.kizuna.meridian.brewing.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 一次结构比对的结果：有效、不符（带第一处不符）、区块暂未加载，或这种釜没有配结构。
 *
 * <p>不符的位置按世界方位记在锚点（釜或药池）的哪一边，动作栏照这个说“铜釜北 1、下 1：应为营火（现在：空气）”。</p>
 */
public record StructureMatch(Status status, Optional<Mismatch> mismatch) {
    public enum Status { VALID, MISMATCH, UNLOADED, UNDEFINED }

    private static final StructureMatch VALID = new StructureMatch(Status.VALID, Optional.empty());
    private static final StructureMatch UNLOADED = new StructureMatch(Status.UNLOADED, Optional.empty());
    private static final StructureMatch UNDEFINED = new StructureMatch(Status.UNDEFINED, Optional.empty());

    public StructureMatch {
        // 只有“不符”带位置，其余三种不带，免得调用方拿到一半的结果。
        if (status == null || mismatch == null || mismatch.isPresent() != (status == Status.MISMATCH)) {
            throw new IllegalArgumentException("结构比对结果不完整");
        }
    }

    public static StructureMatch valid() {
        return VALID;
    }

    public static StructureMatch unloaded() {
        return UNLOADED;
    }

    public static StructureMatch undefined() {
        return UNDEFINED;
    }

    public static StructureMatch mismatch(Mismatch first) {
        return new StructureMatch(Status.MISMATCH, Optional.of(first));
    }

    public boolean isValid() {
        return status == Status.VALID;
    }

    /** 给玩家看的一句话；有效时为空。 */
    public Optional<BrewText> problem() {
        return switch (status) {
            case VALID -> Optional.empty();
            case UNLOADED -> Optional.of(BrewText.message("structure.unloaded"));
            case UNDEFINED -> Optional.of(BrewText.message("structure.undefined"));
            case MISMATCH -> Optional.of(mismatch.orElseThrow().text());
        };
    }

    /**
     * 第一处不符：相对锚点往东、往上、往南各几格（负数是西、下、北），应为什么，现在是什么方块。
     *
     * @param anchor   锚点名，例如「铜釜」「药池」
     * @param expected 应为：方块名、材料类名（任一石头方块）或「空着」
     * @param actual   世界里现在那一格的方块 ID
     */
    public record Mismatch(BrewText anchor, int east, int up, int south, BrewText expected, String actual) {
        public Mismatch {
            if (anchor == null || expected == null || actual == null || actual.isBlank()) {
                throw new IllegalArgumentException("结构不符的位置不完整");
            }
        }

        public BrewText text() {
            // 先说南北、东西，再说上下，只说不为零的方向。
            List<BrewText> parts = new ArrayList<>();
            addPart(parts, south, "north", "south");
            addPart(parts, east, "west", "east");
            addPart(parts, up, "down", "up");
            BrewText offset = switch (parts.size()) {
                case 0 -> BrewText.message("structure.offset.here");
                case 1 -> parts.getFirst();
                case 2 -> BrewText.message("structure.offset.2", parts.get(0), parts.get(1));
                default -> BrewText.message("structure.offset.3", parts.get(0), parts.get(1), parts.get(2));
            };
            return BrewText.message("structure.mismatch", anchor, offset, expected,
                    new BrewText.BlockName(actual));
        }

        private static void addPart(List<BrewText> parts, int distance, String negative, String positive) {
            // 方向词带格数，例如「北 1」。
            if (distance < 0) {
                parts.add(BrewText.message("structure.dir." + negative, -distance));
            } else if (distance > 0) {
                parts.add(BrewText.message("structure.dir." + positive, distance));
            }
        }
    }
}
