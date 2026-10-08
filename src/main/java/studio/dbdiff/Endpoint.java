package studio.dbdiff;

/** Connection details for one endpoint. Password is owned by the caller. */
public record Endpoint(String host, int port, String database, String user, char[] password, boolean useTls) {
    public Endpoint {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Host is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
        if (database == null || database.isBlank()) throw new IllegalArgumentException("Database is required");
        if (user == null || user.isBlank()) throw new IllegalArgumentException("User is required");
        password = password == null ? new char[0] : password.clone();
    }

    @Override public char[] password() { return password.clone(); }
    void clearPassword() { java.util.Arrays.fill(password, '\0'); }
}
