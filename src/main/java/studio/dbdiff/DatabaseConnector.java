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
    RowComparisonMetadata rowComparisonMetadata(SchemaObject.Key table) throws SQLException;
    default RowComparisonMetadata rowComparisonMetadata(SchemaObject.Key table,
                                                         RowComparisonCancellation cancellation) throws SQLException {
        if (cancellation != null) cancellation.checkCancelled();
        return rowComparisonMetadata(table);
    }
    /** Compare rows matched by a stable key against a peer connector; retain at most maxChanges. */
    RowDiff.Result compareTableRows(DatabaseConnector peer, SchemaObject.Key table, int maxChanges) throws SQLException;
    /** Compare rows and report the number of source/target rows consumed so far. */
    default RowDiff.Result compareTableRows(DatabaseConnector peer, SchemaObject.Key table, int maxChanges,
                                            BiConsumer<Long, Long> progress) throws SQLException {
        return compareTableRows(peer, table, maxChanges);
    }
    /** Compare rows with an optional user-selected key for tables without a primary key. */
    default RowDiff.Result compareTableRows(DatabaseConnector peer, SchemaObject.Key table, int maxChanges,
                                            List<String> customKey, BiConsumer<Long, Long> progress) throws SQLException {
        if (customKey != null && !customKey.isEmpty()) throw new SQLException(RowComparisonMessages.customKeysUnsupported());
        return compareTableRows(peer, table, maxChanges, progress);
    }
    /** Compare rows with progress reporting and a signal that can cancel active JDBC reads. */
    default RowDiff.Result compareTableRows(DatabaseConnector peer, SchemaObject.Key table, int maxChanges,
                                            List<String> customKey, BiConsumer<Long, Long> progress,
                                            RowComparisonCancellation cancellation) throws SQLException {
        if (cancellation != null) cancellation.checkCancelled();
        return compareTableRows(peer, table, maxChanges, customKey, progress);
    }
    void close();
}
