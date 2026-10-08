package studio.dbdiff;

import java.util.*;

/** Builds reviewable DDL exports in the selected engine's dialect. */
public final class SqlExporter {
    public enum Strategy { ALTER, CREATE, CREATE_OR_REPLACE, CREATE_OR_ALTER }
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
        return engine == DatabaseEngine.SQLSERVER
                ? exportSqlServer(results, operations, targetDatabase, transaction, dropIfExists, strategy)
                : exportMySqlFamily(results, operations, targetDatabase, transaction, dropIfExists, strategy, engine);
    }

    private static String exportSqlServer(List<DiffEngine.Result> results, Set<DiffEngine.Status> operations,
                                          String database, boolean transaction, boolean dropIfExists, Strategy strategy) {
        StringBuilder out = new StringBuilder("-- " + AppInfo.NAME + " export\n-- Review before use. This file was not executed.\n"
                + "-- The CREATE OR ALTER strategy requires SQL Server 2016 SP1 or later.\n"
                + "-- Table changes are review-only until a safe T-SQL ALTER can be derived.\n\n");
        out.append("USE ").append(tsqlQuote(database)).append(";\nGO\n\n");
        if (transaction) out.append("BEGIN TRANSACTION;\nGO\n\n");
        for (DiffEngine.Result result : results) {
            if (!operations.contains(result.status()) || result.status() == DiffEngine.Status.IDENTICAL) continue;
            SchemaObject object = result.source() != null ? result.source() : result.target();
            out.append("-- ").append(result.status()).append(' ').append(object.key().kind()).append(' ')
                    .append(object.key().qualifiedName()).append("\n");
            switch (result.status()) {
                case SOURCE_ONLY -> out.append("-- Proposed operation: create object from source definition\n")
                        .append(sqlServerSource(result.source(), strategy)).append("\nGO\n\n");
                case TARGET_ONLY -> out.append("-- Proposed operation: DROP target object\n")
                        .append(sqlServerDrop(object.key(), dropIfExists)).append("\nGO\n\n");
                case CHANGED -> out.append(sqlServerChanged(result, strategy, dropIfExists)).append("\nGO\n\n");
                default -> { }
            }
        }
        if (transaction) out.append("COMMIT TRANSACTION;\nGO\n");
        return out.toString();
    }

    private static String sqlServerChanged(DiffEngine.Result result, Strategy strategy, boolean dropIfExists) {
        SchemaObject source = result.source();
        if (source == null || unavailable(source)) return unavailableMessage(source);
        if (source.key().kind() == SchemaObject.Kind.TABLE)
            return "-- REVIEW ONLY: the table differs. Compare the complete definitions and prepare a data-safe ALTER TABLE migration.\n" + source.definition();
        if (strategy == Strategy.CREATE)
            return "-- REVIEW ONLY: CREATE fails when the destination object already exists. Choose CREATE OR ALTER.\n" + source.definition();
        if (strategy == Strategy.CREATE_OR_REPLACE || strategy == Strategy.CREATE_OR_ALTER)
            return sqlServerCreateOrAlter(source);
        return sqlServerAlter(source);
    }

    private static String sqlServerAlter(SchemaObject object) {
        String ddl = object.definition().replaceFirst("(?is)^\\s*CREATE\\s+(OR\\s+ALTER\\s+|OR\\s+REPLACE\\s+)?", "ALTER ");
        return ensureTerminator(ddl);
    }

    private static String sqlServerSource(SchemaObject object, Strategy strategy) {
        if (object == null) return "-- Missing source definition";
        if (unavailable(object)) return unavailableMessage(object);
        if (object.key().kind() == SchemaObject.Kind.TABLE) return ensureTerminator(object.definition());
        if (strategy == Strategy.CREATE_OR_ALTER || strategy == Strategy.CREATE_OR_REPLACE) return sqlServerCreateOrAlter(object);
        return ensureTerminator(object.definition());
    }

    private static String sqlServerCreateOrAlter(SchemaObject object) {
        if (object.key().kind() == SchemaObject.Kind.TABLE) return ensureTerminator(object.definition());
        String ddl = object.definition().replaceFirst("(?is)^\\s*CREATE\\s+(OR\\s+ALTER\\s+|OR\\s+REPLACE\\s+)?", "CREATE OR ALTER ");
        return ensureTerminator(ddl);
    }

    private static String sqlServerDrop(SchemaObject.Key key, boolean ifExists) {
        String type = switch (key.kind()) {
            case TABLE -> "TABLE";
            case VIEW -> "VIEW";
            case PROCEDURE -> "PROCEDURE";
            case FUNCTION -> "FUNCTION";
        };
        return "DROP " + type + (ifExists ? " IF EXISTS " : " ") + tsqlQuote(key.schema().isBlank() ? "dbo" : key.schema())
                + "." + tsqlQuote(key.name()) + ";";
    }

    private static String exportMySqlFamily(List<DiffEngine.Result> results, Set<DiffEngine.Status> operations,
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
            out.append("-- ").append(result.status()).append(" ").append(object.key().kind()).append(" ").append(object.key().qualifiedName()).append('\n');
            out.append("USE ").append(mysqlQuote(targetDatabase)).append(";\n");
            switch (result.status()) {
                case SOURCE_ONLY -> out.append("-- Proposed operation: create object from source definition\n")
                        .append(sourceSql(result.source(), strategy == Strategy.CREATE_OR_REPLACE, engine)).append("\n\n");
                case TARGET_ONLY -> out.append("-- Proposed operation: DROP target object\n").append(mysqlDrop(object.key(), dropIfExists)).append("\n\n");
                case CHANGED -> out.append(changedMySql(result, targetDatabase, dropIfExists, strategy, engine)).append("\n\n");
                default -> { }
            }
        }
        if (transaction) out.append("COMMIT;\n");
        return out.toString();
    }

    private static String changedMySql(DiffEngine.Result result, String targetDatabase, boolean dropIfExists, Strategy strategy, DatabaseEngine engine) {
        SchemaObject object = result.source();
        if (object == null || unavailable(object)) return unavailableMessage(object);
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
        if (unavailable(object)) return unavailableMessage(object);
        boolean useReplace = replaceObjects && (engine == DatabaseEngine.MARIADB || object.key().kind() == SchemaObject.Kind.VIEW);
        return switch (object.key().kind()) {
            case VIEW -> statementWithReplace(object.definition(), useReplace) + ";";
            case PROCEDURE, FUNCTION -> routineCreate(statementWithReplace(object.definition(), useReplace));
            case TABLE -> engine == DatabaseEngine.MARIADB ? statementWithReplace(object.definition(), useReplace) + ";" : object.definition() + ";";
        };
    }
    private static String statementWithReplace(String ddl, boolean replace) {
        String normalized = ddl.replaceFirst("(?i)^\\s*CREATE\\s+OR\\s+REPLACE\\s+", "CREATE ");
        return replace ? normalized.replaceFirst("(?i)^\\s*CREATE\\s+", "CREATE OR REPLACE ") : normalized;
    }
    private static String routineReplace(SchemaObject.Key key, String ddl, boolean dropIfExists) {
        return "DROP " + (key.kind() == SchemaObject.Kind.PROCEDURE ? "PROCEDURE" : "FUNCTION")
                + (dropIfExists ? " IF EXISTS " : " ") + mysqlQuote(key.qualifiedName()) + ";\n" + routineCreate(ddl);
    }
    private static String routineCreate(String ddl) { return "DELIMITER //\n" + ddl + " //\nDELIMITER ;"; }
    private static String unavailableMessage(SchemaObject object) {
        return "-- REVIEW ONLY: complete source DDL is unavailable; no migration statement generated.\n"
                + (object == null ? "-- Source object could not be read." : object.definition());
    }
    private static boolean unavailable(SchemaObject object) {
        return object.definition().startsWith("-- " + AppInfo.NAME + ": SHOW CREATE")
                || object.definition().startsWith("-- " + AppInfo.NAME + ": SQL Server");
    }
    private static String mysqlDrop(SchemaObject.Key key, boolean dropIfExists) {
        String name = mysqlQuote(key.qualifiedName()), conditional = dropIfExists ? " IF EXISTS" : "";
        return switch (key.kind()) {
            case TABLE -> "DROP TABLE" + conditional + " " + name + ";";
            case VIEW -> "DROP VIEW" + conditional + " " + name + ";";
            case PROCEDURE -> "DROP PROCEDURE" + conditional + " " + name + ";";
            case FUNCTION -> "DROP FUNCTION" + conditional + " " + name + ";";
        };
    }
    private static String ensureTerminator(String ddl) { return ddl.stripTrailing().endsWith(";") ? ddl.stripTrailing() : ddl.stripTrailing() + ";"; }
    private static String tsqlQuote(String id) { return "[" + id.replace("]", "]]" ) + "]"; }
    private static String mysqlQuote(String id) { return "`" + id.replace("`", "``") + "`"; }
}
