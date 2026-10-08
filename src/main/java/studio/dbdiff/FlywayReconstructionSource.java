package studio.dbdiff;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * Replays Flyway migrations in a uniquely named scratch database, snapshots its
 * schema into memory, and drops the scratch database before returning.
 */
final class FlywayReconstructionSource implements SchemaSource {
    private final Map<SchemaObject.Key, SchemaObject> objects;
    private final DependencyReport dependencies;
    private final int migrationsExecuted;

    private FlywayReconstructionSource(Map<SchemaObject.Key, SchemaObject> objects,
                                       DependencyReport dependencies, int migrationsExecuted) {
        this.objects = Map.copyOf(objects);
        this.dependencies = dependencies;
        this.migrationsExecuted = migrationsExecuted;
    }

    static FlywayReconstructionSource build(Path migrationFolder, Endpoint origin) throws SQLException {
        Path folder = migrationFolder.toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) throw new SQLException("La ruta seleccionada no es una carpeta válida.");
        if (sqlFileCount(folder) == 0) throw new SQLException("La carpeta no contiene archivos .sql para Flyway.");

        String temporaryDatabase = "dbdf_tmp_" + java.util.UUID.randomUUID().toString().replace("-", "");
        boolean created = false;
        DatabaseConnector reader = null;
        Throwable failure = null;
        Map<SchemaObject.Key, SchemaObject> captured = new TreeMap<>();
        DependencyReport report = new DependencyReport(Map.of(), List.of(), List.of());
        int applied = 0;
        try {
            createDatabase(origin, temporaryDatabase);
            created = true;

            String url = jdbcUrl(origin, temporaryDatabase);
            char[] secret = origin.password();
            MigrateResult migrationResult;
            try {
                migrationResult = Flyway.configure()
                        .dataSource(url, origin.user(), new String(secret))
                        .locations("filesystem:" + folder.toString().replace('\\', '/'))
                        .schemas(engineSchema(origin.engine(), temporaryDatabase))
                        .defaultSchema(engineSchema(origin.engine(), temporaryDatabase))
                        .cleanDisabled(true)
                        .validateOnMigrate(true)
                        .load()
                        .migrate();
            } finally {
                Arrays.fill(secret, '\0');
            }
            applied = migrationResult.migrationsExecuted;

            char[] temporarySecret = origin.password();
            Endpoint temporaryEndpoint;
            try {
                temporaryEndpoint = new Endpoint(origin.engine(), origin.host(), origin.port(), temporaryDatabase,
                        origin.user(), temporarySecret, origin.useTls());
            } finally { Arrays.fill(temporarySecret, '\0'); }
            try {
                reader = DatabaseConnectorFactory.create(temporaryEndpoint);
                reader.connect(temporaryEndpoint);
                for (SchemaObject object : reader.listObjects()) captured.put(object.key(), reader.loadObject(object.key()));
                report = reader.dependencyReport();
            } finally {
                temporaryEndpoint.clearPassword();
            }
            if (captured.isEmpty()) throw new SQLException("Flyway terminó, pero no dejó objetos de esquema para comparar.");
        } catch (Throwable ex) {
            failure = ex;
        } finally {
            if (reader != null) reader.close();
            if (created) {
                try { dropDatabase(origin, temporaryDatabase); }
                catch (SQLException cleanupError) {
                    SQLException residue = new SQLException("No se pudo borrar la base temporal '" + temporaryDatabase
                            + "'. Revisá el servidor y eliminála manualmente cuando sea seguro.", cleanupError);
                    if (failure == null) failure = residue;
                    else failure.addSuppressed(residue);
                }
            }
        }
        if (failure != null) {
            if (failure.getSuppressed().length > 0) throw new SQLException(
                    "Falló la reconstrucción Flyway y no se pudo borrar la base temporal '" + temporaryDatabase
                            + "'. Revisá el servidor y eliminála manualmente cuando sea seguro. Detalle: "
                            + describeFailure(failure), failure);
            if (failure instanceof SQLException sql) {
                throw sql;
            }
            throw new SQLException("No se pudo reconstruir el esquema desde Flyway. Detalle: "
                    + describeFailure(failure), failure);
        }
        return new FlywayReconstructionSource(captured, report, applied);
    }

    int migrationsExecuted() { return migrationsExecuted; }

    @Override public List<SchemaObject> listObjects() { return objects.values().stream().sorted(java.util.Comparator.comparing(SchemaObject::key)).toList(); }

    @Override public SchemaObject loadObject(SchemaObject.Key key) throws SQLException {
        SchemaObject object = objects.get(key);
        if (object == null) throw new SQLException("La reconstrucción Flyway no contiene " + key.qualifiedName() + ".");
        return object;
    }

    @Override public Map<SchemaObject.Key, Set<SchemaObject.Key>> dependencies() { return dependencies.dependencies(); }
    @Override public DependencyReport dependencyReport() { return dependencies; }

    private static long sqlFileCount(Path folder) throws SQLException {
        try (var paths = Files.walk(folder)) {
            return paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".sql"))
                    .count();
        } catch (java.io.IOException ex) {
            throw new SQLException("No se pudo revisar la carpeta de migraciones.", ex);
        }
    }

    /** Flyway wraps the actionable JDBC/script error; include its cause chain in the UI message. */
    private static String describeFailure(Throwable failure) {
        java.util.LinkedHashSet<String> messages = new java.util.LinkedHashSet<>();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) messages.add(message.replaceAll("[\\r\\n\\t]+", " ").trim());
            else if (depth == 0) messages.add(current.getClass().getSimpleName());
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            String message = suppressed.getMessage();
            if (message != null && !message.isBlank()) messages.add(message.replaceAll("[\\r\\n\\t]+", " ").trim());
        }
        String result = String.join(" → ", messages);
        return result.length() <= 1200 ? result : result.substring(0, 1197) + "...";
    }

    private static String engineSchema(DatabaseEngine engine, String database) {
        return engine == DatabaseEngine.SQLSERVER ? "dbo" : database;
    }

    private static String jdbcUrl(Endpoint endpoint, String database) {
        return switch (endpoint.engine()) {
            case MARIADB -> "jdbc:mariadb://" + endpoint.host() + ":" + endpoint.port() + "/" + database
                    + "?connectTimeout=8000&socketTimeout=120000" + (endpoint.useTls() ? "&sslMode=verify-full" : "");
            case MYSQL -> "jdbc:mysql://" + endpoint.host() + ":" + endpoint.port() + "/" + database
                    + "?connectTimeout=8000&socketTimeout=120000&sslMode=" + (endpoint.useTls() ? "VERIFY_IDENTITY" : "DISABLED");
            case SQLSERVER -> "jdbc:sqlserver://" + endpoint.host() + ":" + endpoint.port()
                    + ";databaseName=" + database + ";encrypt=" + endpoint.useTls() + ";trustServerCertificate=false;loginTimeout=8;socketTimeout=120000";
        };
    }

    private static void createDatabase(Endpoint endpoint, String database) throws SQLException {
        String sql = "CREATE DATABASE " + quote(endpoint.engine(), database);
        if (endpoint.engine() == DatabaseEngine.SQLSERVER) {
            String collation = readSqlServerCollation(endpoint);
            if (!collation.isBlank()) sql += " COLLATE " + safeCatalogIdentifier(collation);
        } else {
            String[] charsetAndCollation = readMySqlCharsetAndCollation(endpoint);
            if (charsetAndCollation != null) {
                sql += " CHARACTER SET " + safeCatalogIdentifier(charsetAndCollation[0])
                        + " COLLATE " + safeCatalogIdentifier(charsetAndCollation[1]);
            }
        }
        executeAdmin(endpoint, sql);
    }

    /** Match the source database defaults so reconstructed string columns use the same semantics. */
    private static String[] readMySqlCharsetAndCollation(Endpoint endpoint) throws SQLException {
        String sql = "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?";
        try (Connection connection = openAdmin(endpoint); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, endpoint.database());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("No se pudo leer el charset y la collation de la base de origen.");
                return new String[]{safeCatalogIdentifier(rows.getString(1)), safeCatalogIdentifier(rows.getString(2))};
            }
        }
    }

    private static String readSqlServerCollation(Endpoint endpoint) throws SQLException {
        String sql = "SELECT collation_name FROM sys.databases WHERE name=?";
        try (Connection connection = openAdmin(endpoint); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, endpoint.database());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null)
                    throw new SQLException("No se pudo leer la collation de la base de origen.");
                return safeCatalogIdentifier(rows.getString(1));
            }
        }
    }

    private static String safeCatalogIdentifier(String value) throws SQLException {
        if (value == null || !value.matches("[A-Za-z0-9_]+"))
            throw new SQLException("El servidor devolvió un valor de charset o collation inesperado.");
        return value;
    }

    private static void dropDatabase(Endpoint endpoint, String database) throws SQLException {
        executeAdmin(endpoint, "DROP DATABASE " + quote(endpoint.engine(), database));
    }

    private static void executeAdmin(Endpoint endpoint, String sql) throws SQLException {
        try (Connection connection = openAdmin(endpoint); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection openAdmin(Endpoint endpoint) throws SQLException {
        String adminUrl = switch (endpoint.engine()) {
            case MARIADB -> "jdbc:mariadb://" + endpoint.host() + ":" + endpoint.port() + "/?connectTimeout=8000&socketTimeout=30000"
                    + (endpoint.useTls() ? "&sslMode=verify-full" : "");
            case MYSQL -> "jdbc:mysql://" + endpoint.host() + ":" + endpoint.port() + "/?connectTimeout=8000&socketTimeout=30000&sslMode="
                    + (endpoint.useTls() ? "VERIFY_IDENTITY" : "DISABLED");
            case SQLSERVER -> "jdbc:sqlserver://" + endpoint.host() + ":" + endpoint.port()
                    + ";databaseName=master;encrypt=" + endpoint.useTls() + ";trustServerCertificate=false;loginTimeout=8";
        };
        Properties properties = new Properties();
        properties.setProperty("user", endpoint.user());
        char[] secret = endpoint.password();
        try {
            properties.setProperty("password", new String(secret));
            Connection connection = DriverManager.getConnection(adminUrl, properties);
            properties.remove("password");
            return connection;
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private static String quote(DatabaseEngine engine, String identifier) {
        return engine == DatabaseEngine.SQLSERVER
                ? "[" + identifier.replace("]", "]]" ) + "]"
                : "`" + identifier.replace("`", "``") + "`";
    }
}
