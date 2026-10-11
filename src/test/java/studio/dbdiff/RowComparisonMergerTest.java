package studio.dbdiff;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowComparisonMergerTest {
    private static final SchemaObject.Key TABLE = new SchemaObject.Key(SchemaObject.Kind.TABLE, "", "items");
    private static final List<RowDiff.Column> COLUMNS = List.of(
            new RowDiff.Column("id", "int", "int", false),
            new RowDiff.Column("value", "varchar", "varchar(20)", true));
    private static final List<RowDiff.Column> PRIMARY_KEY = List.of(COLUMNS.get(0));

    @Test void mergesSourceOnlyTargetOnlyDifferentAndIdenticalRows() throws Exception {
        RowDiff.Result result = compare(List.of(row(1, "same"), row(2, "old"), row(4, "source")),
                List.of(row(1, "same"), row(2, "new"), row(3, "target")), 10, (read, total) -> { });

        assertEquals(1, result.sourceOnly());
        assertEquals(1, result.targetOnly());
        assertEquals(1, result.different());
        assertEquals(2, result.matchedRows());
        assertEquals(List.of(RowDiff.Status.IDENTICAL, RowDiff.Status.DIFFERENT,
                        RowDiff.Status.TARGET_ONLY, RowDiff.Status.SOURCE_ONLY),
                result.changes().stream().map(RowDiff.RowChange::status).toList());
        assertFalse(result.truncated());
    }

    @Test void capsRetainedChangesAndMarksResultTruncated() throws Exception {
        RowDiff.Result result = compare(List.of(row(1, "a"), row(2, "b")),
                List.of(row(1, "a"), row(2, "b")), 1, (read, total) -> { });

        assertEquals(1, result.changes().size());
        assertTrue(result.truncated());
        assertTrue(result.warning().contains("conservaron"));
    }

    @Test void reportsRowsConsumedAcrossBothCursors() throws Exception {
        List<String> reports = new ArrayList<>();
        BiConsumer<Long, Long> progress = (read, total) -> reports.add(read + "/" + total);
        compare(List.of(row(1, "a"), row(3, "c")), List.of(row(2, "b")), 10, progress);

        assertEquals("3/3", reports.get(reports.size() - 1));
    }

    private static RowDiff.Result compare(List<Map<String, Object>> source, List<Map<String, Object>> target,
                                          int maxChanges, BiConsumer<Long, Long> progress) throws Exception {
        return RowComparisonMerger.compare("target", TABLE, COLUMNS, PRIMARY_KEY, maxChanges,
                source.size() + target.size(), cursor(source), cursor(target),
                (left, right) -> Integer.compare((Integer) left.get("id"), (Integer) right.get("id")),
                Map::equals, progress, "");
    }

    private static RowComparisonMerger.RowCursor cursor(List<Map<String, Object>> rows) {
        Iterator<Map<String, Object>> iterator = rows.iterator();
        return () -> iterator.hasNext() ? iterator.next() : null;
    }

    private static Map<String, Object> row(int id, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("value", value);
        return row;
    }
}
