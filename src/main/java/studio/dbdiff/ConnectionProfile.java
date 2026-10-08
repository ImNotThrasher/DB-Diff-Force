package studio.dbdiff;

/** A reusable endpoint configuration. Passwords are deliberately not persisted. */
public record ConnectionProfile(String name, DatabaseEngine engine, String host, int port,
                                String database, String user, boolean useTls) {
    public ConnectionProfile {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Profile name is required");
        if (engine == null) throw new IllegalArgumentException("Database engine is required");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Host is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
        if (database == null || database.isBlank()) throw new IllegalArgumentException("Database is required");
        if (user == null || user.isBlank()) throw new IllegalArgumentException("User is required");
    }
}
