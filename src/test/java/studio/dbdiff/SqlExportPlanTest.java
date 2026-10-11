package studio.dbdiff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlExportPlanTest {
    private static final Set<DiffEngine.Status> CHANGES = Set.of(
            DiffEngine.Status.SOURCE_ONLY, DiffEngine.Status.CHANGED, DiffEngine.Status.TARGET_ONLY);

    @Test
    void warnsWhenSelectedCreateOmitsSourcePrerequisite() {
        SchemaObject.Key table = key(SchemaObject.Kind.TABLE, "orders");
        SchemaObject.Key view = key(SchemaObject.Kind.VIEW, "open_orders");
        SqlExportPlan plan = plan(List.of(sourceOnly(view)), CHANGES,
                Map.of(view, Set.of(table)), Map.of(), Set.of(view, table), Set.of());

        assertTrue(plan.render().contains("Falta incluir el prerrequisito orders para open_orders."));
    }

    @Test
    void warnsWhenDroppingPrerequisiteLeavesExistingTargetDependent() {
        SchemaObject.Key table = key(SchemaObject.Kind.TABLE, "orders");
        SchemaObject.Key view = key(SchemaObject.Kind.VIEW, "open_orders");
        SqlExportPlan plan = plan(List.of(targetOnly(table)), CHANGES,
                Map.of(), Map.of(view, Set.of(table)), Set.of(), Set.of(table, view));

        assertTrue(plan.render().contains("Se elimina orders pero el objeto destino open_orders todavía depende de él."));
    }

    @Test
    void reportsCyclesMetadataLimitationsAndManualReviewStatements() {
        SchemaObject.Key first = key(SchemaObject.Kind.VIEW, "first_view");
        SchemaObject.Key second = key(SchemaObject.Kind.VIEW, "second_view");
        SqlExportPlan plan = SqlExportPlan.create(List.of(sourceOnly(first), sourceOnly(second)), CHANGES,
                Map.of(first, Set.of(second), second, Set.of(first)), Map.of(), Set.of(first, second), Set.of(),
                List.of("El catálogo no puede resolver algunas referencias."),
                "-- REVIEW ONLY: revisar definición antes de aplicar.\n");

        String preview = plan.render();
        assertTrue(preview.contains("dependencias cíclicas"));
        assertTrue(preview.contains("El catálogo no puede resolver algunas referencias."));
        assertTrue(preview.contains("REVIEW ONLY: revisar definición antes de aplicar."));
    }

    private static SqlExportPlan plan(List<DiffEngine.Result> selected, Set<DiffEngine.Status> operations,
                                      Map<SchemaObject.Key, Set<SchemaObject.Key>> sourceDependencies,
                                      Map<SchemaObject.Key, Set<SchemaObject.Key>> targetDependencies,
                                      Set<SchemaObject.Key> sourceKeys, Set<SchemaObject.Key> targetKeys) {
        return SqlExportPlan.create(selected, operations, sourceDependencies, targetDependencies,
                sourceKeys, targetKeys, List.of(), "");
    }

    private static SchemaObject.Key key(SchemaObject.Kind kind, String name) { return new SchemaObject.Key(kind, name); }
    private static DiffEngine.Result sourceOnly(SchemaObject.Key key) {
        return new DiffEngine.Result(key, DiffEngine.Status.SOURCE_ONLY,
                new SchemaObject(key, "CREATE " + key.kind() + " " + key.name(), ""), null, List.of());
    }
    private static DiffEngine.Result targetOnly(SchemaObject.Key key) {
        return new DiffEngine.Result(key, DiffEngine.Status.TARGET_ONLY, null,
                new SchemaObject(key, "CREATE " + key.kind() + " " + key.name(), ""), List.of());
    }
}
