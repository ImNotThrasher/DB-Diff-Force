package studio.dbdiff;

import java.sql.SQLException;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Database-engine boundary for metadata, definitions, and row comparison.
 * The UI depends on this contract while each engine supplies its own SQL.
 */
public interface DatabaseConnector extends SchemaSource {
    void connect(Endpoint endpoint) throws SQLException;
    List<String> listDatabases() throws SQLException;
    /** Compare primary-key matched rows against a peer connector; retain at most maxChanges. */
    RowDiff.Result compareTableRows(DatabaseConnector peer, SchemaObject.Key table, int maxChanges) throws SQLException;
    /** Compare rows and report the number of source/target rows consumed so far. */
    default RowDiff.Result compareTableRows(DatabaseConnector peer, SchemaObject.Key table, int maxChanges,
                                            BiConsumer<Long, Long> progress) throws SQLException {
        return compareTableRows(peer, table, maxChanges);
    }
    void close();
}
