package studio.dbdiff;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Loads and saves endpoint profiles without storing passwords. */
public final class ConnectionProfiles {
    private static final String PREFIX = "connection.";
    private ConnectionProfiles() { }

    public static List<ConnectionProfile> load() throws IOException {
        Properties properties = read();
        List<ConnectionProfile> profiles = new ArrayList<>();
        for (String nameKey : properties.stringPropertyNames()) {
            if (!nameKey.startsWith(PREFIX) || !nameKey.endsWith(".name")) continue;
            String base = nameKey.substring(0, nameKey.length() - ".name".length());
            try {
                profiles.add(new ConnectionProfile(properties.getProperty(nameKey),
                        DatabaseEngine.valueOf(properties.getProperty(base + ".engine")),
                        properties.getProperty(base + ".host"),
                        Integer.parseInt(properties.getProperty(base + ".port")),
                        properties.getProperty(base + ".database"),
                        properties.getProperty(base + ".user"),
                        Boolean.parseBoolean(properties.getProperty(base + ".tls"))));
            } catch (RuntimeException ignored) {
                // Skip malformed entries and keep the remaining saved profiles usable.
            }
        }
        profiles.sort(java.util.Comparator.comparing(ConnectionProfile::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(profiles);
    }

    public static void save(ConnectionProfile profile) throws IOException {
        Map<String, ConnectionProfile> profiles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ConnectionProfile existing : load()) profiles.put(existing.name(), existing);
        profiles.put(profile.name().trim(), profile);
        write(profiles.values());
    }

    public static void remove(String name) throws IOException {
        Map<String, ConnectionProfile> profiles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ConnectionProfile existing : load()) profiles.put(existing.name(), existing);
        profiles.remove(name);
        write(profiles.values());
    }

    private static Properties read() throws IOException {
        Properties properties = new Properties(); Path file = file();
        if (Files.isRegularFile(file)) try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
        return properties;
    }

    private static void write(Iterable<ConnectionProfile> profiles) throws IOException {
        Properties properties = new Properties();
        for (ConnectionProfile profile : profiles) {
            String key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(profile.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            properties.setProperty(key + ".name", profile.name());
            properties.setProperty(key + ".engine", profile.engine().name());
            properties.setProperty(key + ".host", profile.host());
            properties.setProperty(key + ".port", Integer.toString(profile.port()));
            properties.setProperty(key + ".database", profile.database());
            properties.setProperty(key + ".user", profile.user());
            properties.setProperty(key + ".tls", Boolean.toString(profile.useTls()));
        }
        Path file = file(); Files.createDirectories(file.getParent());
        try (OutputStream output = Files.newOutputStream(file)) { properties.store(output, AppInfo.NAME + " connection profiles. Passwords are not stored."); }
    }

    private static Path file() { return Path.of(System.getProperty("user.home"), ".db-diff-studio", "connections.properties"); }
}
