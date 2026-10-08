package studio.dbdiff;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class RowSqlExporter {
    private RowSqlExporter() { }
    public static String export(RowDiff.Result result, boolean inserts, boolean updates, boolean deletes) {
        StringBuilder sql = new StringBuilder("-- " + AppInfo.NAME + " row export\n-- Review before use. This file was not executed.\n");
        if (result.truncated()) sql.append("-- WARNING: comparison was truncated; this export contains only the retained first changes.\n");
        sql.append("\n");
        String table = quote(result.targetDatabase()) + "." + quote(result.table().name());
        for (RowDiff.RowChange change : result.changes()) {
            switch (change.status()) {
                case SOURCE_ONLY -> { if (inserts) sql.append("INSERT INTO ").append(table).append(" (").append(columnList(result.columns())).append(") VALUES (").append(values(result.columns(), change.source())).append(");\n"); }
                case TARGET_ONLY -> { if (deletes) sql.append("DELETE FROM ").append(table).append(" WHERE ").append(predicate(result.primaryKey(), change.key())).append(" LIMIT 1;\n"); }
                case DIFFERENT -> {
                    if (!updates) continue;
                    List<RowDiff.Column> changed = result.columns().stream().filter(c -> !Objects.deepEquals(canonical(change.source().get(c.name())), canonical(change.target().get(c.name())))).toList();
                    if (changed.isEmpty()) continue;
                    sql.append("UPDATE ").append(table).append(" SET ");
                    for (int i = 0; i < changed.size(); i++) { if (i > 0) sql.append(", "); RowDiff.Column c = changed.get(i); sql.append(quote(c.name())).append(" = ").append(literal(change.source().get(c.name()), c.type())); }
                    sql.append(" WHERE ").append(predicate(result.primaryKey(), change.key())).append(" LIMIT 1;\n");
                }
            }
        }
        return sql.toString();
    }
    private static String predicate(List<RowDiff.Column> keys, Map<String, Object> row) {
        List<String> predicates = new ArrayList<>(); for (RowDiff.Column key : keys) predicates.add(quote(key.name()) + " <=> " + literal(row.get(key.name()), key.type())); return String.join(" AND ", predicates);
    }
    private static String columnList(List<RowDiff.Column> columns) { return columns.stream().map(c -> quote(c.name())).collect(java.util.stream.Collectors.joining(", ")); }
    private static String values(List<RowDiff.Column> columns, Map<String, Object> row) { return columns.stream().map(c -> literal(row.get(c.name()), c.type())).collect(java.util.stream.Collectors.joining(", ")); }
    private static String literal(Object value, String type) {
        if (value == null) return "NULL";
        if (Set.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint", "decimal", "numeric", "float", "double", "real", "year").contains(type)) {
            try { return new BigDecimal(value.toString()).toPlainString(); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Unsafe numeric value for " + type, ex); }
        }
        if (Set.of("char", "varchar", "tinytext", "text", "mediumtext", "longtext", "enum", "set").contains(type)) {
            String hex = HexFormat.of().formatHex(value.toString().getBytes(StandardCharsets.UTF_8));
            return "CONVERT(X'" + hex + "' USING utf8mb4)";
        }
        String escaped = value.toString().replace("'", "''");
        return "'" + escaped + "'";
    }
    private static String quote(String id) { return "`" + id.replace("`", "``") + "`"; }
    private static Object canonical(Object value) { if (value instanceof BigDecimal d) return d.stripTrailingZeros(); return value == null ? null : value.toString(); }
}
