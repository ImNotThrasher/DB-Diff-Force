package studio.dbdiff;

/** Supported MySQL-family database engines. */
public enum DatabaseEngine {
    MARIADB("MariaDB", 3306),
    MYSQL("MySQL", 3306);

    private final String displayName;
    private final int defaultPort;

    DatabaseEngine(String displayName, int defaultPort) {
        this.displayName = displayName;
        this.defaultPort = defaultPort;
    }

    public int defaultPort() { return defaultPort; }
    @Override public String toString() { return displayName; }
}
