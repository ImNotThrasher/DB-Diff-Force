package studio.dbdiff;

import java.util.*;

public final class SqlExporter {
    private SqlExporter() { }

    public static String export(List<DiffEngine.Result> results, Set<DiffEngine.Status> operations, String targetDatabase) {
        return export(results, operations, targetDatabase, false, false);
    }

    public static String export(List<DiffEngine.Result> results, Set<DiffEngine.Status> operations,
                                String targetDatabase, boolean transaction, boolean dropIfExists) {
        StringBuilder out = new StringBuilder("-- " + AppInfo.NAME + " export\n-- Review and adapt before use. This file was not executed.\n-- Definitions may be incomplete when MariaDB metadata permissions are limited.\n");
        if (transaction) out.append("-- MariaDB may implicitly commit DDL; this transaction does not guarantee schema rollback.\n");
        out.append('\n');
        if (transaction) out.append("START TRANSACTION;\n\n");
        for (DiffEngine.Result result : results) {
            if (!operations.contains(result.status()) || result.status() == DiffEngine.Status.IDENTICAL) continue;
            SchemaObject object = result.source() != null ? result.source() : result.target();
            out.append("-- ").append(result.status()).append(" ").append(object.key().kind()).append(" ").append(object.key().name()).append('\n');
            out.append("USE ").append(quote(targetDatabase)).append(";\n");
            switch (result.status()) {
                case SOURCE_ONLY -> out.append("-- Proposed operation: CREATE from source definition\n").append(sourceSql(result.source())).append("\n\n");
                case TARGET_ONLY -> out.append("-- Proposed operation: DROP target object\n").append(dropSql(object.key(), dropIfExists)).append("\n\n");
                case CHANGED -> out.append(changedSql(result, dropIfExists)).append("\n\n");
                default -> { }
            }
        }
        if (transaction) out.append("COMMIT;\n");
        return out.toString();
    }
    private static String changedSql(DiffEngine.Result result, boolean dropIfExists) {
        SchemaObject object = result.source();
        if (object == null || unavailable(object)) return "-- REVIEW ONLY: complete source DDL is unavailable; no migration statement generated.\n" + (object == null ? "-- Source object could not be read." : object.definition());
        return switch (object.key().kind()) {
            case TABLE -> "-- REVIEW ONLY: table changes are not converted to ALTER TABLE statements. Compare the complete source CREATE TABLE definition below and author a migration.\n" + object.definition() + ";";
            case VIEW -> object.definition().replaceFirst("(?i)^\\s*CREATE\\s+", "CREATE OR REPLACE ") + ";";
            case PROCEDURE, FUNCTION -> routineReplace(object.key(), object.definition(), dropIfExists);
        };
    }
    private static String sourceSql(SchemaObject object) {
        if (object == null) return "-- Missing source definition";
        if (unavailable(object)) return "-- REVIEW ONLY: complete DDL unavailable; no executable statement generated.\n" + object.definition();
        return switch (object.key().kind()) {
            case VIEW -> object.definition().replaceFirst("(?i)^\\s*CREATE\\s+", "CREATE OR REPLACE ") + ";";
            case PROCEDURE, FUNCTION -> routineCreate(object.definition());
            case TABLE -> object.definition() + ";";
        };
    }
    private static String routineReplace(SchemaObject.Key key, String ddl, boolean dropIfExists) {
        return "DROP " + (key.kind() == SchemaObject.Kind.PROCEDURE ? "PROCEDURE" : "FUNCTION")
                + (dropIfExists ? " IF EXISTS " : " ") + quote(key.name()) + ";\n" + routineCreate(ddl);
    }
    private static String routineCreate(String ddl) { return "DELIMITER //\n" + ddl + " //\nDELIMITER ;"; }
    private static boolean unavailable(SchemaObject object) { return object.definition().startsWith("-- " + AppInfo.NAME + ": SHOW CREATE"); }
    private static String dropSql(SchemaObject.Key key, boolean dropIfExists) {
        String name = quote(key.name());
        String conditional = dropIfExists ? " IF EXISTS" : "";
        return switch (key.kind()) {
            case TABLE -> "DROP TABLE" + conditional + " " + name + ";";
            case VIEW -> "DROP VIEW" + conditional + " " + name + ";";
            case PROCEDURE -> "DROP PROCEDURE" + conditional + " " + name + ";";
            case FUNCTION -> "DROP FUNCTION" + conditional + " " + name + ";";
        };
    }
    private static String quote(String identifier) { return "`" + identifier.replace("`", "``") + "`"; }
}
