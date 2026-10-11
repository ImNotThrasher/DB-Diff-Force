package studio.dbdiff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlSnapshotSourceTest {
    @TempDir Path directory;

    @Test
    void readsMysqlSnapshotObjectsAndKeepsOriginalDdl() throws Exception {
        Files.writeString(directory.resolve("01-table.sql"), "-- table snapshot\nCREATE TABLE `app_db`.`accounts` (`id` bigint NOT NULL, PRIMARY KEY (`id`)) ENGINE=InnoDB;");
        Files.writeString(directory.resolve("02-view.sql"), "CREATE ALGORITHM=UNDEFINED DEFINER=`reader`@`%` SQL SECURITY DEFINER VIEW `active_accounts` AS SELECT `id` FROM `accounts`;");

        SqlSnapshotSource source = new SqlSnapshotSource(directory, DatabaseEngine.MYSQL);
        List<SchemaObject> objects = source.listObjects();

        assertEquals(2, objects.size());
        assertEquals("", objects.getFirst().key().schema());
        SchemaObject.Key viewKey = new SchemaObject.Key(SchemaObject.Kind.VIEW, "active_accounts");
        assertTrue(source.loadObject(viewKey).definition().startsWith("CREATE ALGORITHM=UNDEFINED"));
    }

    @Test
    void defaultsUnqualifiedSqlServerObjectsToDboAndRecognizesTriggers() throws Exception {
        Files.writeString(directory.resolve("trigger.sql"), "SET ANSI_NULLS ON\nGO\nCREATE OR ALTER TRIGGER [audit_insert] ON [dbo].[accounts] AFTER INSERT AS BEGIN SET NOCOUNT ON; END\nGO\n");
        SqlSnapshotSource source = new SqlSnapshotSource(directory, DatabaseEngine.SQLSERVER);

        SchemaObject object = source.listObjects().getFirst();
        assertEquals(new SchemaObject.Key(SchemaObject.Kind.TRIGGER, "audit_insert", "dbo"), object.key());
        assertTrue(object.definition().startsWith("CREATE OR ALTER TRIGGER"));
        assertTrue(!object.definition().contains("GO"));
    }

    @Test
    void rejectsAmbiguousDuplicateDefinitions() throws Exception {
        Files.writeString(directory.resolve("a.sql"), "CREATE TABLE `accounts` (`id` int);");
        Files.writeString(directory.resolve("b.sql"), "CREATE TABLE `accounts` (`id` bigint);");

        java.sql.SQLException error = assertThrows(java.sql.SQLException.class,
                () -> new SqlSnapshotSource(directory, DatabaseEngine.MARIADB).listObjects());
        assertTrue(error.getMessage().contains("más de un archivo"));
    }
}
