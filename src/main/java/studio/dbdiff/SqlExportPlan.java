package studio.dbdiff;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Produces the human-readable execution order and review warnings for a schema export. */
record SqlExportPlan(List<DiffEngine.Result> ordered, List<String> warnings) {
    SqlExportPlan {
        ordered = List.copyOf(ordered);
        warnings = List.copyOf(warnings);
    }

    static SqlExportPlan create(List<DiffEngine.Result> selected, Set<DiffEngine.Status> operations,
                                Map<SchemaObject.Key, Set<SchemaObject.Key>> sourceDependencies,
                                Map<SchemaObject.Key, Set<SchemaObject.Key>> targetDependencies,
                                Set<SchemaObject.Key> sourceKeys, Set<SchemaObject.Key> targetKeys,
                                List<String> metadataLimitations, String generatedSql) {
        List<DiffEngine.Result> ordered = SqlExporter.orderByDependencies(selected, operations, sourceDependencies, targetDependencies);
        List<String> warnings = new ArrayList<>();
        Set<SchemaObject.Key> included = new HashSet<>();
        for (DiffEngine.Result result : ordered) {
            if (operations.contains(result.status()) && result.status() != DiffEngine.Status.IDENTICAL) included.add(result.key());
        }

        for (DiffEngine.Result result : ordered) {
            if (!included.contains(result.key()) || result.status() == DiffEngine.Status.TARGET_ONLY) continue;
            for (SchemaObject.Key prerequisite : sourceDependencies.getOrDefault(result.key(), Set.of())) {
                if (targetKeys.contains(prerequisite) || included.contains(prerequisite)) continue;
                if (sourceKeys.contains(prerequisite)) {
                    add(warnings, "Falta incluir el prerrequisito " + prerequisite.qualifiedName()
                            + " para " + result.key().qualifiedName() + ".");
                } else {
                    add(warnings, "No se encontró el prerrequisito " + prerequisite.qualifiedName()
                            + " requerido por " + result.key().qualifiedName() + ".");
                }
            }
        }

        Set<SchemaObject.Key> dropping = new HashSet<>();
        for (DiffEngine.Result result : ordered)
            if (included.contains(result.key()) && result.status() == DiffEngine.Status.TARGET_ONLY) dropping.add(result.key());
        for (Map.Entry<SchemaObject.Key, Set<SchemaObject.Key>> entry : targetDependencies.entrySet()) {
            if (!targetKeys.contains(entry.getKey()) || dropping.contains(entry.getKey())) continue;
            for (SchemaObject.Key prerequisite : entry.getValue()) {
                if (dropping.contains(prerequisite)) add(warnings, "Se elimina " + prerequisite.qualifiedName()
                        + " pero el objeto destino " + entry.getKey().qualifiedName() + " todavía depende de él.");
            }
        }

        Set<SchemaObject.Key> creating = new HashSet<>(), droppingObjects = new HashSet<>();
        for (DiffEngine.Result result : ordered) {
            if (!included.contains(result.key())) continue;
            if (result.status() == DiffEngine.Status.TARGET_ONLY) droppingObjects.add(result.key());
            else creating.add(result.key());
        }
        if (hasCycle(creating, sourceDependencies) || hasCycle(droppingObjects, targetDependencies))
            warnings.add("Hay dependencias cíclicas entre objetos seleccionados; el orden de esos objetos requiere revisión.");
        for (String limitation : metadataLimitations) add(warnings, limitation);
        for (String line : generatedSql.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("-- REVIEW ONLY:") || trimmed.startsWith("-- WARNING:")) add(warnings, trimmed.substring(3).strip());
        }
        return new SqlExportPlan(ordered.stream().filter(result -> included.contains(result.key())).toList(), warnings);
    }

    String render() {
        StringBuilder text = new StringBuilder("ORDEN PROPUESTO\n");
        if (ordered.isEmpty()) text.append("  No hay objetos seleccionados.\n");
        for (int index = 0; index < ordered.size(); index++) {
            DiffEngine.Result result = ordered.get(index);
            String operation = switch (result.status()) {
                case SOURCE_ONLY -> "CREAR";
                case CHANGED -> "ACTUALIZAR";
                case TARGET_ONLY -> "ELIMINAR";
                case IDENTICAL -> "SIN CAMBIOS";
            };
            text.append(String.format("%d. %s · %s · %s%n", index + 1, operation,
                    result.key().kind(), result.key().qualifiedName()));
        }
        text.append("\nADVERTENCIAS\n");
        if (warnings.isEmpty()) text.append("  No se detectaron advertencias con los metadatos disponibles.\n");
        else for (String warning : warnings) text.append("  • ").append(warning).append('\n');
        return text.toString();
    }

    private static void add(List<String> warnings, String value) {
        if (!warnings.contains(value)) warnings.add(value);
    }

    private static boolean hasCycle(Set<SchemaObject.Key> nodes,
                                    Map<SchemaObject.Key, Set<SchemaObject.Key>> dependencies) {
        Set<SchemaObject.Key> visited = new HashSet<>(), active = new HashSet<>();
        for (SchemaObject.Key node : new TreeSet<>(nodes))
            if (cycleFrom(node, nodes, dependencies, visited, active)) return true;
        return false;
    }

    private static boolean cycleFrom(SchemaObject.Key node, Set<SchemaObject.Key> nodes,
                                     Map<SchemaObject.Key, Set<SchemaObject.Key>> dependencies,
                                     Set<SchemaObject.Key> visited, Set<SchemaObject.Key> active) {
        if (active.contains(node)) return true;
        if (!visited.add(node)) return false;
        active.add(node);
        for (SchemaObject.Key prerequisite : dependencies.getOrDefault(node, Set.of()))
            if (nodes.contains(prerequisite) && cycleFrom(prerequisite, nodes, dependencies, visited, active)) return true;
        active.remove(node);
        return false;
    }
}
