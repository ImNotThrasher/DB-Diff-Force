package studio.dbdiff;

/** Read-only MySQL connector using the official Connector/J driver. */
public final class MySqlConnector extends MySqlFamilyConnector {
    public MySqlConnector() { super(DatabaseEngine.MYSQL); }
}
