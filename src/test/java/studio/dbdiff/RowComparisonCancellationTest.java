package studio.dbdiff;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowComparisonCancellationTest {
    @Test void cancelsEveryRegisteredStatement() throws Exception {
        RowComparisonCancellation cancellation = new RowComparisonCancellation();
        AtomicInteger cancelled = new AtomicInteger();
        CountDownLatch signal = new CountDownLatch(1);
        Statement statement = statement(cancelled, signal);

        cancellation.register(statement);
        cancellation.cancel();

        assertTrue(signal.await(2, TimeUnit.SECONDS));
        assertEquals(1, cancelled.get());
        assertThrows(SQLException.class, cancellation::checkCancelled);
    }

    @Test void immediatelyCancelsStatementsRegisteredAfterCancellation() throws Exception {
        RowComparisonCancellation cancellation = new RowComparisonCancellation();
        AtomicInteger cancelled = new AtomicInteger();
        CountDownLatch signal = new CountDownLatch(1);
        cancellation.cancel();

        assertThrows(SQLException.class, () -> cancellation.register(statement(cancelled, signal)));
        assertTrue(signal.await(2, TimeUnit.SECONDS));
        assertEquals(1, cancelled.get());
    }

    private static Statement statement(AtomicInteger cancelled, CountDownLatch signal) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("cancel")) { cancelled.incrementAndGet(); signal.countDown(); return null; }
                    if (method.getName().equals("isClosed")) return false;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
    }
}
