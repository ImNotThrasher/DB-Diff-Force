package studio.dbdiff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlywayReconstructionSourceTest {
    @TempDir Path directory;

    @Test
    void rejectsDuplicateMigrationVersionsBeforeDatabaseCreation() throws Exception {
        Path source = directory.resolve("src/main/resources/db/migration");
        Path compiled = directory.resolve("target/classes/db/migration");
        Files.createDirectories(source);
        Files.createDirectories(compiled);
        Files.writeString(source.resolve("V1__initial.sql"), "CREATE TABLE sample (id INT);");
        Files.writeString(compiled.resolve("V1__initial.sql"), "CREATE TABLE sample (id INT);");

        SQLException error = assertThrows(SQLException.class,
                () -> FlywayReconstructionSource.validateMigrationFolder(directory));

        assertTrue(error.getMessage().contains("más de una migración para la versión 1"));
        assertTrue(error.getMessage().contains("src" + java.io.File.separator + "main"));
        assertTrue(error.getMessage().contains("target" + java.io.File.separator + "classes"));
        assertTrue(error.getMessage().contains("No se creó ninguna base temporal"));
    }

    @Test
    void acceptsDistinctVersionsAcrossFlywayFiles() throws Exception {
        Files.writeString(directory.resolve("V1__initial.sql"), "CREATE TABLE sample (id INT);");
        Files.writeString(directory.resolve("V2__add_name.sql"), "ALTER TABLE sample ADD name VARCHAR(20);");

        FlywayReconstructionSource.validateMigrationFolder(directory);
    }
}
