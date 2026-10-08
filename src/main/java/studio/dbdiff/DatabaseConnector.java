package studio.dbdiff;

import java.sql.SQLException;
import java.util.List;

/**
 * Database-engine boundary for metadata, definitions, and row comparison.
 * The UI depends on this contract while each engine supplies its own SQL.
 */
public interface DatabaseConnector {
    void connect(Endpoint endpoint) throws SQLException;
    List<String> listDatabases() throws SQLException;
    List<SchemaObject> listObjects() throws SQLException;
    SchemaObject loadObject(SchemaObject.Key key) throws SQLException;
    /** Compare primary-key matched rows against a peer connector; retain at most maxChanges. */
    RowDiff.Result compareTableRows(DatabaseConnector peer, SchemaObject.Key table, int maxChanges) throws SQLException;
    void close();
}
