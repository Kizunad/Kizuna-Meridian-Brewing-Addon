package dev.kizuna.meridian.brewing.server;

import java.util.List;
import java.util.UUID;

/**
 * 一座药塔存档里的全部状态。{@code selected}、{@code chooser}、{@code qi} 可以为空：还没选药方、
 * 没人选过、这一批还没开熬。{@code reserve} 是开工时多扣、还没泡完的第二套料，泡料阶段坏了要一起退回。
 */
public record BrewTowerRecord(
        BrewKind selected,
        UUID chooser,
        BrewForm form,
        boolean continuous,
        boolean armed,
        TowerPhase phase,
        BrewCauldron.Snapshot cauldron,
        FireKind fire,
        List<FuelCell> fuel,
        List<IngredientBatch> reserve,
        BrewQiTally qi,
        long serial,
        int flashSeconds) {
    public BrewTowerRecord {
        // 四格燃料一格不少；列表复制成不可变的，存档读出来以后不再被改。
        if (form == null || phase == null || cauldron == null || fire == null || fuel == null || reserve == null) {
            throw new IllegalArgumentException("药塔记录字段不完整");
        }
        if (fuel.size() != BrewTowerController.FUEL_CELLS) {
            throw new IllegalArgumentException("药塔燃料格数不对：" + fuel.size());
        }
        if (serial < 0 || flashSeconds < 0) {
            throw new IllegalArgumentException("药塔记录计数不能为负");
        }
        fuel = List.copyOf(fuel);
        reserve = List.copyOf(reserve);
    }
}
