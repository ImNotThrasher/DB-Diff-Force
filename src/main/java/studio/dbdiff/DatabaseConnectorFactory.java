package studio.dbdiff;

/** Creates the connector that matches an endpoint's database engine. */
public final class DatabaseConnectorFactory {
    private DatabaseConnectorFactory() { }

    public static DatabaseConnector create(Endpoint endpoint) {
        return switch (endpoint.engine()) {
            case MARIADB -> new MariaDbConnector();
            case MYSQL -> new MySqlConnector();
        };
    }
}
