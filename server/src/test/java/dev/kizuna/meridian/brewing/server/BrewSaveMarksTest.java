package dev.kizuna.meridian.brewing.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 方块实体标脏的节奏：离散内容一变就标，连续量最多每秒一次，什么都没变不标。 */
public final class BrewSaveMarksTest {
    private static final long START = 100;

    @Test
    public void unchangedContentAndProgressNeverMark() {
        BrewSaveMarks<String, Double> marks = new BrewSaveMarks<>();
        marks.marked("空釜", 0.0, START);
        assertFalse(marks.due("空釜", 0.0, START + 1));
        assertFalse(marks.due("空釜", 0.0, START + BrewSaveMarks.PROGRESS_TICKS * 10L));
    }

    @Test
    public void contentChangeMarksAtOnceEvenRightAfterAMark() {
        BrewSaveMarks<String, Double> marks = new BrewSaveMarks<>();
        marks.marked("生药", 0.0, START);
        assertTrue("离散内容变了要当 tick 标", marks.due("熬成", 0.0, START + 1));
    }

    @Test
    public void progressOnlyChangeWaitsOneSecondAfterTheLastMark() {
        BrewSaveMarks<String, Double> marks = new BrewSaveMarks<>();
        marks.marked("燃着", 60.0, START);
        assertFalse("连续量刚标过不到一秒，不标", marks.due("燃着", 59.0, START + BrewSaveMarks.PROGRESS_TICKS - 1));
        assertTrue("满一秒就标", marks.due("燃着", 59.0, START + BrewSaveMarks.PROGRESS_TICKS));
    }

    @Test
    public void markedResetsTheBaselineForBothParts() {
        BrewSaveMarks<String, Double> marks = new BrewSaveMarks<>();
        marks.marked("燃着", 60.0, START);
        long later = START + BrewSaveMarks.PROGRESS_TICKS;
        marks.marked("燃着", 59.0, later);
        assertFalse("刚记下的内容不用再标", marks.due("燃着", 59.0, later + BrewSaveMarks.PROGRESS_TICKS));
        assertFalse("刚记下后连续量又走了，也要再等一秒", marks.due("燃着", 58.0, later + 1));
    }

    @Test
    public void firstTickBeforeAnyMarkCountsAsChanged() {
        BrewSaveMarks<String, Double> marks = new BrewSaveMarks<>();
        assertTrue("新放下、还没记过的方块实体第一次比就算变了", marks.due("空釜", 0.0, START));
    }
}
