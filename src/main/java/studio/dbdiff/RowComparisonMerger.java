package studio.dbdiff;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Shared streaming merge for connector result sets that are sorted by primary key. */
final class RowComparisonMerger {
    private RowComparisonMerger() { }

    static RowDiff.Result compare(String targetDatabase, SchemaObject.Key table,
                                  List<RowDiff.Column> columns, List<RowDiff.Column> primaryKey,
                                  int maxChanges, long totalRows, RowCursor source, RowCursor target,
                                  KeyComparator keyComparator, RowEquality rowEquality,
                                  BiConsumer<Long, Long> progress, String warning) throws SQLException {
        List<RowDiff.RowChange> changes = new ArrayList<>();
        long sourceOnly = 0, targetOnly = 0, different = 0, matchedRows = 0;
        long rowsRead = 0, lastReported = 0;
        Map<String, Object> sourceRow = source.next();
        Map<String, Object> targetRow = target.next();
        if (sourceRow != null) rowsRead++;
        if (targetRow != null) rowsRead++;

        while (sourceRow != null || targetRow != null) {
            int comparison = sourceRow == null ? 1 : targetRow == null ? -1 : keyComparator.compare(sourceRow, targetRow);
            if (comparison < 0) {
                sourceOnly++;
                retain(changes, maxChanges, new RowDiff.RowChange(RowDiff.Status.SOURCE_ONLY,
                        keyOf(sourceRow, primaryKey), sourceRow, null));
                sourceRow = source.next();
                if (sourceRow != null) rowsRead++;
            } else if (comparison > 0) {
                targetOnly++;
                retain(changes, maxChanges, new RowDiff.RowChange(RowDiff.Status.TARGET_ONLY,
                        keyOf(targetRow, primaryKey), null, targetRow));
                targetRow = target.next();
                if (targetRow != null) rowsRead++;
            } else {
                matchedRows++;
                RowDiff.Status status = rowEquality.equal(sourceRow, targetRow)
                        ? RowDiff.Status.IDENTICAL : RowDiff.Status.DIFFERENT;
                if (status == RowDiff.Status.DIFFERENT) different++;
                retain(changes, maxChanges, new RowDiff.RowChange(status,
                        keyOf(sourceRow, primaryKey), sourceRow, targetRow));
                sourceRow = source.next();
                targetRow = target.next();
                if (sourceRow != null) rowsRead++;
                if (targetRow != null) rowsRead++;
            }
            if (rowsRead - lastReported >= 500) {
                progress.accept(rowsRead, totalRows);
                lastReported = rowsRead;
            }
        }
        progress.accept(rowsRead, totalRows);

        // Preserve the existing result contract: identical rows count toward the retained sample.
        boolean truncated = sourceOnly + targetOnly + matchedRows > changes.size();
        String finalWarning = warning == null ? "" : warning;
        if (truncated) {
            String truncationWarning = RowComparisonMessages.truncated(maxChanges);
            finalWarning = finalWarning.isBlank() ? truncationWarning : finalWarning + " " + truncationWarning;
        }
        return new RowDiff.Result(targetDatabase, table, columns, primaryKey, changes,
                sourceOnly, targetOnly, different, matchedRows, truncated, finalWarning);
    }

    private static void retain(List<RowDiff.RowChange> changes, int limit, RowDiff.RowChange change) {
        if (changes.size() < limit) changes.add(change);
    }

    private static Map<String, Object> keyOf(Map<String, Object> row, List<RowDiff.Column> keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (RowDiff.Column key : keys) result.put(key.name(), row.get(key.name()));
        return result;
    }

    @FunctionalInterface interface RowCursor { Map<String, Object> next() throws SQLException; }
    @FunctionalInterface interface KeyComparator { int compare(Map<String, Object> source, Map<String, Object> target) throws SQLException; }
    @FunctionalInterface interface RowEquality { boolean equal(Map<String, Object> source, Map<String, Object> target) throws SQLException; }
}
