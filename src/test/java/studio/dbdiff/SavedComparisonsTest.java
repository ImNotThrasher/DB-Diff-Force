package studio.dbdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavedComparisonsTest {
    @TempDir Path temporaryDirectory;

    @Test
    void savesAndRestoresDatabaseAndFileBasedComparisonsWithoutPasswords() throws Exception {
        Path file = temporaryDirectory.resolve("comparison-presets.properties");
        LastComparisonPreferences.SavedComparison database = new LastComparisonPreferences.SavedComparison(
                ConnectionDialog.SourceMode.DATABASE, endpoint("dev", "app_dev"), endpoint("qa", "app_qa"), null);
        Path flywayFolder = temporaryDirectory.resolve("migrations");
        LastComparisonPreferences.SavedComparison flyway = new LastComparisonPreferences.SavedComparison(
                ConnectionDialog.SourceMode.FLYWAY, null, endpoint("qa", "app_qa"), flywayFolder);

        SavedComparisons.save(file, "Dev → QA", database);
        SavedComparisons.save(file, "Flyway → QA", flyway);

        List<SavedComparisons.Preset> presets = SavedComparisons.load(file);
        assertEquals(2, presets.size());
        assertEquals(database, presets.stream().filter(preset -> preset.name().equals("Dev → QA")).findFirst().orElseThrow().comparison());
        assertEquals(flyway, presets.stream().filter(preset -> preset.name().equals("Flyway → QA")).findFirst().orElseThrow().comparison());
        String persisted = Files.readString(file);
        assertFalse(persisted.contains("password="));
    }

    @Test
    void replacesNamesWithoutCaseSensitivityAndCanRemovePresets() throws Exception {
        Path file = temporaryDirectory.resolve("comparison-presets.properties");
        SavedComparisons.save(file, "Dev → QA", comparison("app_qa"));
        SavedComparisons.save(file, "dev → qa", comparison("app_qa_2"));

        List<SavedComparisons.Preset> replaced = SavedComparisons.load(file);
        assertEquals(1, replaced.size());
        assertEquals("dev → qa", replaced.get(0).name());
        assertEquals("app_qa_2", replaced.get(0).comparison().destination().database());

        SavedComparisons.remove(file, "DEV → QA");
        assertTrue(SavedComparisons.load(file).isEmpty());
    }

    private static LastComparisonPreferences.SavedComparison comparison(String destinationDatabase) {
        return new LastComparisonPreferences.SavedComparison(ConnectionDialog.SourceMode.DATABASE,
                endpoint("dev", "app_dev"), endpoint("qa", destinationDatabase), null);
    }

    private static LastComparisonPreferences.SavedEndpoint endpoint(String host, String database) {
        return new LastComparisonPreferences.SavedEndpoint(DatabaseEngine.MARIADB, host, 3306, database,
                "dbuser", true);
    }
}
