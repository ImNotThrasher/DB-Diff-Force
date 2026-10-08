package studio.dbdiff;

import java.sql.SQLException;
import java.util.List;

/** Database-engine boundary for read-only schema discovery. */
public interface DatabaseConnector {
    void connect(Endpoint endpoint) throws SQLException;
    List<SchemaObject> listObjects() throws SQLException;
    SchemaObject loadObject(SchemaObject.Key key) throws SQLException;
    RowDiff.Result compareTableRows(DatabaseConnector peer, SchemaObject.Key table, int maxChanges) throws SQLException;
    void close();
}
