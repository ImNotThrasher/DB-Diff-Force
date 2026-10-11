package studio.dbdiff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FlywayMigrationGeneratorTest {
    @TempDir Path directory;

    @Test
    void createsObjectsThatExistOnlyInTheCurrentDatabase() {
        SchemaObject current = object(SchemaObject.Kind.TABLE, "new_table", "CREATE TABLE `new_table` (`id` int NOT NULL)");
        String sql = generate(result(current.key(), DiffEngine.Status.TARGET_ONLY, null, current), DatabaseEngine.MYSQL);
        assertTrue(sql.contains("CREATE TABLE `new_table`"));
        assertFalse(sql.contains("DROP TABLE"));
    }

    @Test
    void leavesObjectsRemovedFromTheCurrentDatabaseAsReviewComments() {
        SchemaObject old = object(SchemaObject.Kind.TABLE, "removed", "CREATE TABLE `removed` (`id` int)");
        String sql = generate(result(old.key(), DiffEngine.Status.SOURCE_ONLY, old, null), DatabaseEngine.MYSQL);
        assertTrue(sql.contains("REVIEW ONLY"));
        assertTrue(sql.contains("old Flyway schema but not in the current database"));
        assertFalse(sql.contains("DROP TABLE"));
    }

    @Test
    void generatesSafeAdditiveTableAlterWithoutHardCodingDatabaseName() {
        SchemaObject old = object(SchemaObject.Kind.TABLE, "items", "CREATE TABLE `items` (`id` int NOT NULL, PRIMARY KEY (`id`)) ENGINE=InnoDB");
        SchemaObject current = object(SchemaObject.Kind.TABLE, "items", "CREATE TABLE `items` (`id` int NOT NULL, `label` varchar(40) DEFAULT NULL, PRIMARY KEY (`id`)) ENGINE=InnoDB");
        String sql = generate(result(old.key(), DiffEngine.Status.CHANGED, old, current), DatabaseEngine.MARIADB);
        assertTrue(sql.contains("ADD COLUMN `label` varchar(40) DEFAULT NULL"));
        assertTrue(sql.contains("ALTER TABLE `items`"));
        assertFalse(sql.contains("ALTER TABLE `live_database`."));
    }

    @Test
    void doesNotEmitPotentiallyDestructiveTableChanges() {
        SchemaObject old = object(SchemaObject.Kind.TABLE, "items", "CREATE TABLE `items` (`id` int NOT NULL, `legacy` varchar(40), PRIMARY KEY (`id`)) ENGINE=InnoDB");
        SchemaObject current = object(SchemaObject.Kind.TABLE, "items", "CREATE TABLE `items` (`id` bigint NOT NULL, PRIMARY KEY (`id`)) ENGINE=InnoDB");
        String sql = generate(result(old.key(), DiffEngine.Status.CHANGED, old, current), DatabaseEngine.MYSQL);
        assertTrue(sql.contains("REVIEW ONLY"));
        assertFalse(sql.contains("ALTER TABLE"));
        assertFalse(sql.contains("DROP COLUMN"));
        assertFalse(sql.contains("MODIFY COLUMN"));
    }

    @Test
    void marksChangedSqlServerTablesForManualReview() {
        SchemaObject old = object(SchemaObject.Kind.TABLE, "items", "CREATE TABLE [dbo].[items] ([id] int NOT NULL)");
        SchemaObject current = object(SchemaObject.Kind.TABLE, "items", "CREATE TABLE [dbo].[items] ([id] bigint NOT NULL)");
        String sql = generate(result(old.key(), DiffEngine.Status.CHANGED, old, current), DatabaseEngine.SQLSERVER);
        assertTrue(sql.contains("REVIEW ONLY"));
        assertTrue(sql.contains("SQL Server table migration is not supported"));
    }

    @Test
    void suggestsNextVersionAndRejectsExistingVersionWithoutOverwriting() throws Exception {
        Files.writeString(directory.resolve("V2.4__old.sql"), "-- old");
        Files.writeString(directory.resolve("V2.5__next.sql"), "-- next");
        assertEquals("2.6", FlywayMigrationGenerator.nextVersion(directory));
        Path saved = FlywayMigrationGenerator.save(directory, "2.6", "sync_actual", "-- migration");
        assertEquals("V2.6__sync_actual.sql", saved.getFileName().toString());
        assertEquals("-- migration", Files.readString(saved));
        assertThrows(java.io.IOException.class, () -> FlywayMigrationGenerator.save(directory, "2.6", "again", "-- replace"));
        assertEquals("-- migration", Files.readString(saved));
    }

    @Test
    void rejectsInvalidFoldersVersionsAndDescriptions() {
        assertThrows(java.io.IOException.class, () -> FlywayMigrationGenerator.nextVersion(directory.resolve("missing")));
        assertThrows(java.io.IOException.class, () -> FlywayMigrationGenerator.save(directory, "../2", "bad", ""));
        assertThrows(java.io.IOException.class, () -> FlywayMigrationGenerator.save(directory, "1", "bad-name", ""));
    }

    private String generate(DiffEngine.Result result, DatabaseEngine engine) {
        return FlywayMigrationGenerator.generate(List.of(result), "live_database", engine, Map.of(), Map.of());
    }

    private static DiffEngine.Result result(SchemaObject.Key key, DiffEngine.Status status, SchemaObject old, SchemaObject current) {
        return new DiffEngine.Result(key, status, old, current, List.of());
    }

    private static SchemaObject object(SchemaObject.Kind kind, String name, String definition) {
        return new SchemaObject(new SchemaObject.Key(kind, name), definition, definition);
    }
}
