package studio.dbdiff;

import java.util.*;

public final class SqlExporter {
    public enum Strategy { ALTER, CREATE, CREATE_OR_REPLACE }

    private SqlExporter() { }

    public static String export(List<DiffEngine.Result> results, Set<DiffEngine.Status> operations, String targetDatabase) {
        return export(results, operations, targetDatabase, false, false, false, DatabaseEngine.MARIADB);
    }

    public static String export(List<DiffEngine.Result> results, Set<DiffEngine.Status> operations,
                                String targetDatabase, boolean transaction, boolean dropIfExists) {
        return export(results, operations, targetDatabase, transaction, dropIfExists, false, DatabaseEngine.MARIADB);
    }

    public static String export(List<DiffEngine.Result> results, Set<DiffEngine.Status> operations,
                                String targetDatabase, boolean transaction, boolean dropIfExists,
                                boolean replaceObjects, DatabaseEngine engine) {
        return export(results, operations, targetDatabase, transaction, dropIfExists,
                replaceObjects ? Strategy.CREATE_OR_REPLACE : Strategy.ALTER, engine);
    }

    public static String export(List<DiffEngine.Result> results, Set<DiffEngine.Status> operations,
                                String targetDatabase, boolean transaction, boolean dropIfExists,
                                Strategy strategy, DatabaseEngine engine) {
        StringBuilder out = new StringBuilder("-- " + AppInfo.NAME + " export\n-- Review and adapt before use. This file was not executed.\n-- Definitions may be incomplete when MariaDB metadata permissions are limited.\n");
        if (transaction) out.append("-- MariaDB may implicitly commit DDL; this transaction does not guarantee schema rollback.\n");
        if (strategy == Strategy.CREATE_OR_REPLACE) out.append("-- CREATE OR REPLACE overwrites existing compatible objects; for tables on MariaDB it drops the table and its data.\n");
        out.append('\n');
        if (transaction) out.append("START TRANSACTION;\n\n");
        for (DiffEngine.Result result : results) {
            if (!operations.contains(result.status()) || result.status() == DiffEngine.Status.IDENTICAL) continue;
            SchemaObject object = result.source() != null ? result.source() : result.target();
            out.append("-- ").append(result.status()).append(" ").append(object.key().kind()).append(" ").append(object.key().name()).append('\n');
            out.append("USE ").append(quote(targetDatabase)).append(";\n");
            switch (result.status()) {
                case SOURCE_ONLY -> out.append("-- Proposed operation: create object from source definition\n")
                        .append(sourceSql(result.source(), strategy == Strategy.CREATE_OR_REPLACE, engine)).append("\n\n");
                case TARGET_ONLY -> out.append("-- Proposed operation: DROP target object\n").append(dropSql(object.key(), dropIfExists)).append("\n\n");
                case CHANGED -> out.append(changedSql(result, targetDatabase, dropIfExists, strategy, engine)).append("\n\n");
                default -> { }
            }
        }
        if (transaction) out.append("COMMIT;\n");
        return out.toString();
    }
    private static String changedSql(DiffEngine.Result result, String targetDatabase, boolean dropIfExists, Strategy strategy, DatabaseEngine engine) {
        SchemaObject object = result.source();
        if (object == null || unavailable(object)) return "-- REVIEW ONLY: complete source DDL is unavailable; no migration statement generated.\n" + (object == null ? "-- Source object could not be read." : object.definition());
        return switch (object.key().kind()) {
            case TABLE -> changedTableSql(result, targetDatabase, strategy, engine);
            case VIEW -> statementWithReplace(object.definition(), true) + ";";
            case PROCEDURE, FUNCTION -> strategy == Strategy.CREATE_OR_REPLACE && engine == DatabaseEngine.MARIADB
                    ? routineCreate(statementWithReplace(object.definition(), true))
                    : routineReplace(object.key(), object.definition(), dropIfExists);
        };
    }

    private static String changedTableSql(DiffEngine.Result result, String database, Strategy strategy, DatabaseEngine engine) {
        SchemaObject source = result.source(), target = result.target();
        if (strategy == Strategy.CREATE_OR_REPLACE && engine == DatabaseEngine.MARIADB)
            return "-- WARNING: this drops and recreates the destination table, deleting its existing data.\n" + statementWithReplace(source.definition(), true) + ";";
        if (strategy == Strategy.CREATE)
            return "-- REVIEW ONLY: CREATE cannot update a table that already exists. Choose ALTER TABLE or a supported replacement strategy.\n" + source.definition() + ";";
        if (target == null || unavailable(target))
            return "-- REVIEW ONLY: the destination table definition is unavailable, so a safe ALTER TABLE cannot be derived.\n" + source.definition() + ";";
        return TableAlterGenerator.generate(source.definition(), target.definition(), database, source.key().name())
                .orElseGet(() -> "-- REVIEW ONLY: these table changes include clauses the automatic ALTER TABLE generator cannot safely translate.\n" + source.definition() + ";");
    }
    private static String sourceSql(SchemaObject object, boolean replaceObjects, DatabaseEngine engine) {
        if (object == null) return "-- Missing source definition";
        if (unavailable(object)) return "-- REVIEW ONLY: complete DDL unavailable; no executable statement generated.\n" + object.definition();
        boolean useReplace = replaceObjects && (engine == DatabaseEngine.MARIADB
                || object.key().kind() == SchemaObject.Kind.VIEW);
        return switch (object.key().kind()) {
            case VIEW -> statementWithReplace(object.definition(), useReplace) + ";";
            case PROCEDURE, FUNCTION -> routineCreate(statementWithReplace(object.definition(), useReplace));
            case TABLE -> engine == DatabaseEngine.MARIADB
                    ? statementWithReplace(object.definition(), useReplace) + ";"
                    : object.definition() + ";";
        };
    }
    private static String statementWithReplace(String ddl, boolean replace) {
        String normalized = ddl.replaceFirst("(?i)^\\s*CREATE\\s+OR\\s+REPLACE\\s+", "CREATE ");
        return replace ? normalized.replaceFirst("(?i)^\\s*CREATE\\s+", "CREATE OR REPLACE ") : normalized;
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
