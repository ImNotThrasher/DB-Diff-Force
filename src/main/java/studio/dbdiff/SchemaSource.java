package studio.dbdiff;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only source of object names, definitions, and known dependencies. */
public interface SchemaSource extends AutoCloseable {
    List<SchemaObject> listObjects() throws SQLException;
    SchemaObject loadObject(SchemaObject.Key key) throws SQLException;
    default Map<SchemaObject.Key, Set<SchemaObject.Key>> dependencies() throws SQLException { return Map.of(); }
    default DependencyReport dependencyReport() throws SQLException {
        return new DependencyReport(dependencies(), List.of(), List.of("Este origen no informa el alcance de sus metadatos de dependencias."));
    }
    @Override default void close() { }
}
