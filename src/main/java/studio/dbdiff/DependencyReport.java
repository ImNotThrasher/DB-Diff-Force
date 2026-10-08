package studio.dbdiff;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Captures both discovered dependency edges and the metadata coverage limits. */
public record DependencyReport(Map<SchemaObject.Key, Set<SchemaObject.Key>> dependencies,
                               List<Coverage> coverage,
                               List<String> limitations) {
    public record Coverage(String source, int edges, String warning) { }

    public DependencyReport {
        if (dependencies == null || dependencies.isEmpty()) dependencies = Map.of();
        else {
            Map<SchemaObject.Key, Set<SchemaObject.Key>> copy = new HashMap<>();
            dependencies.forEach((key, values) -> copy.put(key, Set.copyOf(values)));
            dependencies = Map.copyOf(copy);
        }
        coverage = coverage == null ? List.of() : List.copyOf(coverage);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public int edgeCount() {
        return dependencies.values().stream().mapToInt(Set::size).sum();
    }

    public static DependencyReport unknown(Map<SchemaObject.Key, Set<SchemaObject.Key>> edges, String reason) {
        return new DependencyReport(edges, List.of(), List.of(reason));
    }
}
