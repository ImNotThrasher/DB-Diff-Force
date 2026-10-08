package studio.dbdiff;

import java.util.*;

public final class RowDiff {
    private RowDiff() { }
    public enum Status { SOURCE_ONLY, TARGET_ONLY, DIFFERENT }
    public record Column(String name, String type, String columnType, boolean nullable) {
        public Column(String name, String columnType, boolean nullable) { this(name, baseType(columnType), columnType, nullable); }
        private static String baseType(String columnType) {
            if (columnType == null) return "";
            String value = columnType.toLowerCase(Locale.ROOT).strip();
            int paren = value.indexOf('('); if (paren >= 0) value = value.substring(0, paren);
            return value.replaceAll("\\s+(unsigned|signed|zerofill)$", "").strip();
        }
    }
    public record RowChange(Status status, Map<String, Object> key, Map<String, Object> source, Map<String, Object> target) {
        public RowChange { key = immutable(key); source = immutable(source); target = immutable(target); }
        private static Map<String, Object> immutable(Map<String, Object> values) { return values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    }
    public record Result(String targetDatabase, SchemaObject.Key table, List<Column> columns, List<Column> primaryKey, List<RowChange> changes,
                         long sourceOnly, long targetOnly, long different, long matchedRows, boolean truncated, String warning) {
        public Result { columns = List.copyOf(columns); primaryKey = List.copyOf(primaryKey); changes = List.copyOf(changes); }
    }
}
