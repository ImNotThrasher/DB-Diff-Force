package studio.dbdiff;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Stores named source-to-destination setups without saving database passwords. */
final class SavedComparisons {
    record Preset(String name, LastComparisonPreferences.SavedComparison comparison) {
        @Override public String toString() { return name; }
    }

    private static final Path FILE = Path.of(System.getProperty("user.home"), ".db-diff-studio", "comparison-presets.properties");

    private SavedComparisons() { }

    static List<Preset> load() throws IOException { return load(FILE); }

    static void save(String name, LastComparisonPreferences.SavedComparison comparison) throws IOException {
        save(FILE, name, comparison);
    }

    static void remove(String name) throws IOException { remove(FILE, name); }

    static List<Preset> load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(file)) { values.load(input); }
        int count;
        try { count = Integer.parseInt(values.getProperty("count", "0")); }
        catch (NumberFormatException ex) { return List.of(); }
        Map<String, Preset> presets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int index = 0; index < count; index++) {
            String base = "preset." + index + ".";
            try {
                String name = values.getProperty(base + "name");
                if (name == null || name.isBlank()) continue;
                ConnectionDialog.SourceMode mode = ConnectionDialog.SourceMode.valueOf(values.getProperty(base + "mode"));
                LastComparisonPreferences.SavedEndpoint origin = mode == ConnectionDialog.SourceMode.DATABASE
                        ? endpoint(values, base + "origin.") : null;
                LastComparisonPreferences.SavedEndpoint destination = endpoint(values, base + "destination.");
                String folder = values.getProperty(base + "sourceFolder", "");
                LastComparisonPreferences.SavedComparison comparison = new LastComparisonPreferences.SavedComparison(
                        mode, origin, destination, folder.isBlank() ? null : Path.of(folder));
                presets.put(name, new Preset(name, comparison));
            } catch (RuntimeException ignored) {
                // One malformed entry should not hide other usable comparison presets.
            }
        }
        return List.copyOf(presets.values());
    }

    static void save(Path file, String name, LastComparisonPreferences.SavedComparison comparison) throws IOException {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty()) throw new IllegalArgumentException("Comparison name is required");
        Map<String, Preset> presets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Preset preset : load(file)) presets.put(preset.name(), preset);
        presets.put(normalizedName, new Preset(normalizedName, comparison));
        write(file, presets.values());
    }

    static void remove(Path file, String name) throws IOException {
        Map<String, Preset> presets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Preset preset : load(file)) presets.put(preset.name(), preset);
        presets.remove(name);
        write(file, presets.values());
    }

    private static void write(Path file, Iterable<Preset> presets) throws IOException {
        Properties values = new Properties();
        int index = 0;
        for (Preset preset : presets) {
            String base = "preset." + index++ + ".";
            values.setProperty(base + "name", preset.name());
            LastComparisonPreferences.SavedComparison comparison = preset.comparison();
            values.setProperty(base + "mode", comparison.mode().name());
            putEndpoint(values, base + "origin.", comparison.origin());
            putEndpoint(values, base + "destination.", comparison.destination());
            if (comparison.sourceFolder() != null) values.setProperty(base + "sourceFolder", comparison.sourceFolder().toString());
        }
        values.setProperty("count", Integer.toString(index));
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream output = Files.newOutputStream(file)) {
            values.store(output, AppInfo.NAME + " named comparisons; passwords are stored in the OS credential store.");
        }
    }

    private static LastComparisonPreferences.SavedEndpoint endpoint(Properties values, String prefix) {
        return new LastComparisonPreferences.SavedEndpoint(DatabaseEngine.valueOf(values.getProperty(prefix + "engine")),
                values.getProperty(prefix + "host"), Integer.parseInt(values.getProperty(prefix + "port")),
                values.getProperty(prefix + "database"), values.getProperty(prefix + "user"),
                Boolean.parseBoolean(values.getProperty(prefix + "tls")));
    }

    private static void putEndpoint(Properties values, String prefix, LastComparisonPreferences.SavedEndpoint endpoint) {
        if (endpoint == null) return;
        values.setProperty(prefix + "engine", endpoint.engine().name());
        values.setProperty(prefix + "host", endpoint.host());
        values.setProperty(prefix + "port", Integer.toString(endpoint.port()));
        values.setProperty(prefix + "database", endpoint.database());
        values.setProperty(prefix + "user", endpoint.user());
        values.setProperty(prefix + "tls", Boolean.toString(endpoint.useTls()));
    }
}
