package studio.dbdiff;

import java.awt.Color;

/** A reusable endpoint configuration. Passwords live in the OS credential store. */
public record ConnectionProfile(String name, DatabaseEngine engine, String host, int port,
                                String database, String user, boolean useTls, boolean rememberPassword,
                                String environment, String colorHex) {
    public static final String COLOR_GREY = "#808080";
    public static final String COLOR_RED = "#E05252";
    public static final String COLOR_ORANGE = "#E69735";
    public static final String COLOR_YELLOW = "#D6B832";
    public static final String COLOR_GREEN = "#36A269";
    public static final String COLOR_BLUE = "#4389D1";
    public static final String COLOR_VIOLET = "#9A68C7";
    public static final String DEFAULT_COLOR = COLOR_GREY;

    public ConnectionProfile(String name, DatabaseEngine engine, String host, int port,
                             String database, String user, boolean useTls) {
        this(name, engine, host, port, database, user, useTls, false, "", DEFAULT_COLOR);
    }

    public ConnectionProfile(String name, DatabaseEngine engine, String host, int port,
                             String database, String user, boolean useTls, boolean rememberPassword) {
        this(name, engine, host, port, database, user, useTls, rememberPassword, "", DEFAULT_COLOR);
    }

    public ConnectionProfile {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Profile name is required");
        if (engine == null) throw new IllegalArgumentException("Database engine is required");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Host is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
        if (database == null || database.isBlank()) throw new IllegalArgumentException("Database is required");
        if (user == null || user.isBlank()) throw new IllegalArgumentException("User is required");
        environment = environment == null ? "" : environment.trim();
        colorHex = colorHex == null || colorHex.isBlank() ? DEFAULT_COLOR : colorHex.trim().toUpperCase(java.util.Locale.ROOT);
        if (!colorHex.matches("#[0-9A-F]{6}")) throw new IllegalArgumentException("Profile color must be #RRGGBB");
    }

    public Color markerColor() { return Color.decode(colorHex); }

    public boolean hasMarker() { return !environment.isBlank() || !DEFAULT_COLOR.equals(colorHex); }
}
