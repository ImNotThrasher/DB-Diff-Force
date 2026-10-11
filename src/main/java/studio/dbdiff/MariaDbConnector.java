package studio.dbdiff;

/** Read-only MariaDB connector. */
public final class MariaDbConnector extends MySqlFamilyConnector {
    public MariaDbConnector() { super(DatabaseEngine.MARIADB); }
}
