package studio.dbdiff;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/** Stores the last comparison's non-secret connection settings. */
final class LastComparisonPreferences {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".db-diff-studio", "last-comparison.properties");

    record SavedEndpoint(DatabaseEngine engine, String host, int port, String database, String user, boolean useTls) {
        SavedEndpoint {
            if (engine == null || host == null || host.isBlank() || port < 1 || port > 65535
                    || database == null || database.isBlank() || user == null || user.isBlank())
                throw new IllegalArgumentException("Incomplete saved database endpoint");
        }

        static SavedEndpoint from(Endpoint endpoint) {
            return endpoint == null ? null : new SavedEndpoint(endpoint.engine(), endpoint.host(), endpoint.port(),
                    endpoint.database(), endpoint.user(), endpoint.useTls());
        }
    }

    record SavedComparison(ConnectionDialog.SourceMode mode, SavedEndpoint origin, SavedEndpoint destination, Path sourceFolder) {
        static SavedComparison from(ConnectionDialog.Selection selection) {
            return new SavedComparison(selection.mode(), SavedEndpoint.from(selection.origin()),
                    SavedEndpoint.from(selection.destination()), selection.sourceFolder());
        }
    }

    private LastComparisonPreferences() { }

    static Optional<SavedComparison> load() {
        if (!Files.isRegularFile(FILE)) return Optional.empty();
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(FILE)) {
            values.load(input);
            ConnectionDialog.SourceMode mode = ConnectionDialog.SourceMode.valueOf(values.getProperty("mode"));
            SavedEndpoint origin = mode == ConnectionDialog.SourceMode.DATABASE ? endpoint(values, "origin") : null;
            SavedEndpoint destination = endpoint(values, "destination");
            String folder = values.getProperty("sourceFolder", "");
            return Optional.of(new SavedComparison(mode, origin, destination, folder.isBlank() ? null : Path.of(folder)));
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    static void save(ConnectionDialog.Selection selection) throws IOException {
        Properties values = new Properties();
        values.setProperty("mode", selection.mode().name());
        putEndpoint(values, "origin", SavedEndpoint.from(selection.origin()));
        putEndpoint(values, "destination", SavedEndpoint.from(selection.destination()));
        if (selection.sourceFolder() != null) values.setProperty("sourceFolder", selection.sourceFolder().toString());
        Files.createDirectories(FILE.getParent());
        try (OutputStream output = Files.newOutputStream(FILE)) {
            values.store(output, AppInfo.NAME + " last comparison; passwords are never stored here.");
        }
    }

    static void clear() throws IOException {
        Files.deleteIfExists(FILE);
    }

    private static SavedEndpoint endpoint(Properties values, String prefix) {
        return new SavedEndpoint(DatabaseEngine.valueOf(values.getProperty(prefix + ".engine")),
                values.getProperty(prefix + ".host"), Integer.parseInt(values.getProperty(prefix + ".port")),
                values.getProperty(prefix + ".database"), values.getProperty(prefix + ".user"),
                Boolean.parseBoolean(values.getProperty(prefix + ".tls")));
    }

    private static void putEndpoint(Properties values, String prefix, SavedEndpoint endpoint) {
        if (endpoint == null) return;
        values.setProperty(prefix + ".engine", endpoint.engine().name());
        values.setProperty(prefix + ".host", endpoint.host());
        values.setProperty(prefix + ".port", Integer.toString(endpoint.port()));
        values.setProperty(prefix + ".database", endpoint.database());
        values.setProperty(prefix + ".user", endpoint.user());
        values.setProperty(prefix + ".tls", Boolean.toString(endpoint.useTls()));
    }
}
