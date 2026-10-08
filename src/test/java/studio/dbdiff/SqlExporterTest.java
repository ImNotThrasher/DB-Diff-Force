package studio.dbdiff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlExporterTest {
    private static final Set<DiffEngine.Status> ALL_CHANGES = Set.of(
            DiffEngine.Status.SOURCE_ONLY, DiffEngine.Status.CHANGED, DiffEngine.Status.TARGET_ONLY);

    @Test
    void createsPrerequisitesBeforeViewsAndTriggers() {
        SchemaObject.Key tableKey = key(SchemaObject.Kind.TABLE, "parent");
        SchemaObject.Key viewKey = key(SchemaObject.Kind.VIEW, "parent_view");
        SchemaObject.Key triggerKey = key(SchemaObject.Kind.TRIGGER, "parent_audit");
        List<DiffEngine.Result> results = List.of(
                sourceOnly(triggerKey, "CREATE TRIGGER `parent_audit` BEFORE INSERT ON `parent` FOR EACH ROW SET NEW.id=NEW.id"),
                sourceOnly(viewKey, "CREATE VIEW `parent_view` AS SELECT id FROM `parent`"),
                sourceOnly(tableKey, "CREATE TABLE `parent` (`id` int NOT NULL)"));
        Map<SchemaObject.Key, Set<SchemaObject.Key>> dependencies = Map.of(
                viewKey, Set.of(tableKey), triggerKey, Set.of(tableKey));

        String sql = SqlExporter.export(results, Set.of(DiffEngine.Status.SOURCE_ONLY), "target_db",
                false, false, SqlExporter.Strategy.CREATE, DatabaseEngine.MARIADB, dependencies);

        assertTrue(sql.indexOf("CREATE TABLE `parent`") < sql.indexOf("CREATE VIEW `parent_view`"));
        assertTrue(sql.indexOf("CREATE TABLE `parent`") < sql.indexOf("CREATE TRIGGER `parent_audit`"));
    }

    @Test
    void dropsDependentsBeforeTheirPrerequisites() {
        SchemaObject.Key tableKey = key(SchemaObject.Kind.TABLE, "parent");
        SchemaObject.Key triggerKey = key(SchemaObject.Kind.TRIGGER, "parent_audit");
        List<DiffEngine.Result> results = List.of(
                targetOnly(tableKey, "CREATE TABLE `parent` (`id` int NOT NULL)"),
                targetOnly(triggerKey, "CREATE TRIGGER `parent_audit` BEFORE INSERT ON `parent` FOR EACH ROW SET NEW.id=NEW.id"));

        String sql = SqlExporter.export(results, Set.of(DiffEngine.Status.TARGET_ONLY), "target_db",
                false, true, SqlExporter.Strategy.ALTER, DatabaseEngine.MARIADB,
                Map.of(triggerKey, Set.of(tableKey)));

        assertTrue(sql.indexOf("DROP TRIGGER IF EXISTS") < sql.indexOf("DROP TABLE IF EXISTS"));
    }

    @Test
    void sqlServerTriggerUsesCreateOrAlterAndSchemaQualifiedDrop() {
        SchemaObject.Key key = new SchemaObject.Key(SchemaObject.Kind.TRIGGER, "audit_parent", "dbo");
        String ddl = "CREATE TRIGGER [dbo].[audit_parent] ON [dbo].[parent] AFTER INSERT AS BEGIN SET NOCOUNT ON; END";
        String create = SqlExporter.export(List.of(sourceOnly(key, ddl)), Set.of(DiffEngine.Status.SOURCE_ONLY),
                "target_db", false, false, SqlExporter.Strategy.CREATE_OR_ALTER, DatabaseEngine.SQLSERVER);
        String drop = SqlExporter.export(List.of(targetOnly(key, ddl)), Set.of(DiffEngine.Status.TARGET_ONLY),
                "target_db", false, true, SqlExporter.Strategy.ALTER, DatabaseEngine.SQLSERVER);

        assertTrue(create.contains("CREATE OR ALTER TRIGGER [dbo].[audit_parent]"));
        assertTrue(drop.contains("DROP TRIGGER IF EXISTS [dbo].[audit_parent]"));
    }

    private static SchemaObject.Key key(SchemaObject.Kind kind, String name) { return new SchemaObject.Key(kind, name); }
    private static DiffEngine.Result sourceOnly(SchemaObject.Key key, String ddl) {
        return new DiffEngine.Result(key, DiffEngine.Status.SOURCE_ONLY, object(key, ddl), null, List.of());
    }
    private static DiffEngine.Result targetOnly(SchemaObject.Key key, String ddl) {
        return new DiffEngine.Result(key, DiffEngine.Status.TARGET_ONLY, null, object(key, ddl), List.of());
    }
    private static SchemaObject object(SchemaObject.Key key, String ddl) { return new SchemaObject(key, ddl, ddl); }
}
