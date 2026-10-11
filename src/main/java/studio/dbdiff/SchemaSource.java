package studio.dbdiff;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.EnumMap;
import java.util.stream.Collectors;

/** Read-only source of object names, definitions, and known dependencies. */
public interface SchemaSource extends AutoCloseable {
    List<SchemaObject> listObjects() throws SQLException;
    SchemaObject loadObject(SchemaObject.Key key) throws SQLException;
    /** Summarizes the catalog seen by the application; connectors may append engine-level counts. */
    default String catalogDiagnostics(List<SchemaObject> catalog) throws SQLException {
        Map<SchemaObject.Kind, Long> counts = catalog.stream().collect(Collectors.groupingBy(
                object -> object.key().kind(), () -> new EnumMap<>(SchemaObject.Kind.class), Collectors.counting()));
        StringBuilder report = new StringBuilder("Objetos visibles para la aplicación: ").append(catalog.size());
        for (SchemaObject.Kind kind : SchemaObject.Kind.values())
            report.append("\n").append(kind).append(": ").append(counts.getOrDefault(kind, 0L));
        return report.toString();
    }
    default Map<SchemaObject.Key, Set<SchemaObject.Key>> dependencies() throws SQLException { return Map.of(); }
    default DependencyReport dependencyReport() throws SQLException {
        return new DependencyReport(dependencies(), List.of(), List.of("Este origen no informa el alcance de sus metadatos de dependencias."));
    }
    @Override default void close() { }
}
