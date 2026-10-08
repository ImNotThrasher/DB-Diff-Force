package studio.dbdiff;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.temporal.TemporalAccessor;
import java.util.*;

/** Produces reviewable row-migration SQL for the selected database dialect. */
public final class RowSqlExporter {
    private RowSqlExporter() { }

    public static String export(RowDiff.Result result, boolean inserts, boolean updates, boolean deletes) {
        return export(result, inserts, updates, deletes, false, DatabaseEngine.MARIADB);
    }

    public static String export(RowDiff.Result result, boolean inserts, boolean updates, boolean deletes, boolean transaction) {
        return export(result, inserts, updates, deletes, transaction, DatabaseEngine.MARIADB);
    }

    public static String export(RowDiff.Result result, boolean inserts, boolean updates, boolean deletes,
                                boolean transaction, DatabaseEngine engine) {
        StringBuilder sql = new StringBuilder("-- " + AppInfo.NAME + " row export\n-- Review before use. This file was not executed.\n");
        if (result.truncated()) sql.append("-- WARNING: comparison was truncated; this export contains only the retained first changes.\n");
        boolean sqlServer = engine == DatabaseEngine.SQLSERVER;
        String table = qualifiedTable(result, engine);
        boolean hasIdentity = sqlServer && result.columns().stream().anyMatch(RowSqlExporter::isIdentity);
        boolean hasInserts = inserts && result.changes().stream().anyMatch(change -> change.status() == RowDiff.Status.SOURCE_ONLY);
        StringBuilder statements = new StringBuilder();
        for (RowDiff.RowChange change : result.changes()) {
            switch (change.status()) {
                case SOURCE_ONLY -> {
                    if (inserts) {
                        List<RowDiff.Column> insertColumns = sqlServer
                                ? result.columns().stream().filter(column -> !isComputed(column)).toList() : result.columns();
                        statements.append("INSERT INTO ").append(table).append(" (")
                                .append(columnList(insertColumns, engine)).append(") VALUES (")
                                .append(values(insertColumns, change.source(), engine)).append(");\n");
                    }
                }
                case TARGET_ONLY -> {
                    if (deletes) statements.append(sqlServer ? "DELETE FROM " : "DELETE FROM ").append(table)
                            .append(" WHERE ").append(predicate(result.primaryKey(), change.key(), engine))
                            .append(sqlServer ? ";\n" : " LIMIT 1;\n");
                }
                case DIFFERENT -> {
                    if (!updates) continue;
                    List<RowDiff.Column> changed = result.columns().stream()
                            .filter(c -> !sqlServer || (!isComputed(c) && !isIdentity(c)))
                            .filter(c -> !Objects.deepEquals(canonical(change.source().get(c.name())), canonical(change.target().get(c.name())))).toList();
                    if (changed.isEmpty()) continue;
                    statements.append("UPDATE ").append(table).append(" SET ");
                    for (int i = 0; i < changed.size(); i++) {
                        if (i > 0) statements.append(", ");
                        RowDiff.Column column = changed.get(i);
                        statements.append(quote(column.name(), engine)).append(" = ")
                                .append(literal(change.source().get(column.name()), column.type(), engine));
                    }
                    statements.append(" WHERE ").append(predicate(result.primaryKey(), change.key(), engine))
                            .append(sqlServer ? ";\n" : " LIMIT 1;\n");
                }
                case IDENTICAL -> { /* No migration is needed for an unchanged row. */ }
            }
        }
        if (sqlServer) sql.append("USE ").append(quote(result.targetDatabase(), engine)).append(";\nGO\n\n");
        if (hasInserts && hasIdentity && !statements.isEmpty()) sql.append("SET IDENTITY_INSERT ").append(table).append(" ON;\nGO\n\n");
        if (transaction && !statements.isEmpty()) sql.append(sqlServer ? "BEGIN TRANSACTION;\n\n" : "START TRANSACTION;\n\n");
        sql.append(statements);
        if (transaction && !statements.isEmpty()) sql.append("\nCOMMIT;").append(sqlServer ? "\nGO\n" : "\n");
        if (hasInserts && hasIdentity && !statements.isEmpty()) sql.append("\nSET IDENTITY_INSERT ").append(table).append(" OFF;\nGO\n");
        return sql.toString();
    }

    private static String qualifiedTable(RowDiff.Result result, DatabaseEngine engine) {
        if (engine == DatabaseEngine.SQLSERVER)
            return quote(result.targetDatabase(), engine) + "." + quote(result.table().schema().isBlank() ? "dbo" : result.table().schema(), engine)
                    + "." + quote(result.table().name(), engine);
        return quote(result.targetDatabase(), engine) + "." + quote(result.table().name(), engine);
    }

    private static String predicate(List<RowDiff.Column> keys, Map<String, Object> row, DatabaseEngine engine) {
        List<String> predicates = new ArrayList<>();
        for (RowDiff.Column key : keys) {
            Object value = row.get(key.name());
            predicates.add(quote(key.name(), engine) + (value == null ? " IS NULL" : " = " + literal(value, key.type(), engine)));
        }
        return String.join(" AND ", predicates);
    }

    private static String columnList(List<RowDiff.Column> columns, DatabaseEngine engine) {
        return columns.stream().map(c -> quote(c.name(), engine)).collect(java.util.stream.Collectors.joining(", "));
    }

    private static String values(List<RowDiff.Column> columns, Map<String, Object> row, DatabaseEngine engine) {
        return columns.stream().map(c -> literal(row.get(c.name()), c.type(), engine)).collect(java.util.stream.Collectors.joining(", "));
    }

    private static boolean isIdentity(RowDiff.Column column) { return column.columnType().contains("|identity=true"); }
    private static boolean isComputed(RowDiff.Column column) { return column.columnType().contains("|computed=true"); }

    private static String literal(Object value, String type, DatabaseEngine engine) {
        if (value == null) return "NULL";
        String normalizedType = type.toLowerCase(Locale.ROOT);
        if (isNumeric(normalizedType)) {
            // MySQL Connector/J commonly returns BIT values as a byte array.
            // Interpret it as an unsigned integer so exported SQL remains a
            // valid numeric BIT value instead of parsing the Java array label.
            if (normalizedType.equals("bit") && value instanceof byte[] bytes)
                return new BigDecimal(new java.math.BigInteger(1, bytes)).toPlainString();
            if (value instanceof Boolean bool) return bool ? "1" : "0";
            try { return new BigDecimal(value.toString()).toPlainString(); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Unsafe numeric value for " + type, ex); }
        }
        if (value instanceof byte[] bytes) {
            String hex = HexFormat.of().formatHex(bytes);
            return engine == DatabaseEngine.SQLSERVER ? "0x" + hex : "X'" + hex + "'";
        }
        String escaped = value.toString().replace("'", "''");
        if (engine == DatabaseEngine.SQLSERVER) {
            boolean unicode = Set.of("nchar", "nvarchar", "ntext").contains(normalizedType);
            return (unicode ? "N" : "") + "'" + escaped + "'";
        }
        if (Set.of("char", "varchar", "tinytext", "text", "mediumtext", "longtext", "enum", "set").contains(normalizedType)) {
            String hex = HexFormat.of().formatHex(value.toString().getBytes(StandardCharsets.UTF_8));
            return "CONVERT(X'" + hex + "' USING utf8mb4)";
        }
        return "'" + escaped + "'";
    }

    private static boolean isNumeric(String type) {
        return Set.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint", "decimal", "numeric",
                "float", "double", "real", "year", "bit", "money", "smallmoney").contains(type);
    }

    private static String quote(String id, DatabaseEngine engine) {
        return engine == DatabaseEngine.SQLSERVER ? "[" + id.replace("]", "]]" ) + "]" : "`" + id.replace("`", "``") + "`";
    }

    private static Object canonical(Object value) {
        if (value instanceof BigDecimal d) return d.stripTrailingZeros();
        if (value instanceof byte[] bytes) return HexFormat.of().formatHex(bytes);
        return value == null ? null : value.toString();
    }
}
