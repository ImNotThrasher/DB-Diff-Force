package studio.dbdiff;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation signal shared by the row-comparison UI and active JDBC statements. */
public final class RowComparisonCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<Statement> activeStatements = ConcurrentHashMap.newKeySet();

    public boolean isCancelled() { return cancelled.get(); }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        List<Statement> statements = List.copyOf(activeStatements);
        Thread canceller = new Thread(() -> statements.forEach(RowComparisonCancellation::cancelStatement),
                "row-comparison-cancel");
        canceller.setDaemon(true);
        canceller.start();
    }

    void checkCancelled() throws SQLException {
        if (isCancelled()) throw new SQLException("Comparación cancelada", "57014");
    }

    void register(Statement statement) throws SQLException {
        if (statement == null) return;
        activeStatements.add(statement);
        if (isCancelled()) {
            activeStatements.remove(statement);
            cancelStatement(statement);
            checkCancelled();
        }
    }

    void unregister(Statement statement) {
        if (statement != null) activeStatements.remove(statement);
    }

    private static void cancelStatement(Statement statement) {
        try { statement.cancel(); }
        catch (SQLException | RuntimeException ignored) { }
    }
}
