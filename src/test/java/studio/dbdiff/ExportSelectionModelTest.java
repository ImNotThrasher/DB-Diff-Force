package studio.dbdiff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportSelectionModelTest {
    private static final DiffEngine.Result USERS = result("users", SchemaObject.Kind.TABLE, DiffEngine.Status.CHANGED);
    private static final DiffEngine.Result V_ACTIVE_USERS = result("active_users", SchemaObject.Kind.VIEW, DiffEngine.Status.SOURCE_ONLY);
    private static final DiffEngine.Result OLD_VIEW = result("old_view", SchemaObject.Kind.VIEW, DiffEngine.Status.TARGET_ONLY);

    @Test void defaultsToAllChangesWhenMainSelectionIsEmpty() {
        ExportSelectionModel selection = new ExportSelectionModel(List.of(USERS, V_ACTIVE_USERS, OLD_VIEW), List.of());

        assertEquals(List.of(USERS, V_ACTIVE_USERS, OLD_VIEW), selection.selectedItems());
    }

    @Test void selectingVisibleWithoutAFilterSelectsEveryChange() {
        ExportSelectionModel selection = new ExportSelectionModel(List.of(USERS, V_ACTIVE_USERS, OLD_VIEW), List.of());
        selection.clear();
        selection.selectVisible();

        assertEquals(List.of(USERS, V_ACTIVE_USERS, OLD_VIEW), selection.selectedItems());
    }

    @Test void filterPreservesHiddenSelectionAndSelectVisibleAddsToIt() {
        ExportSelectionModel selection = new ExportSelectionModel(List.of(USERS, V_ACTIVE_USERS, OLD_VIEW), List.of(USERS));
        selection.setQuery("vista");

        assertEquals(List.of(V_ACTIVE_USERS, OLD_VIEW), selection.visibleItems());
        assertEquals(List.of(USERS), selection.selectedItems());
        selection.setQuery("USERS");
        assertEquals(List.of(USERS, V_ACTIVE_USERS), selection.visibleItems());
        selection.setQuery("vista");
        selection.selectVisible();
        assertEquals(List.of(USERS, V_ACTIVE_USERS, OLD_VIEW), selection.selectedItems());
    }

    @Test void changingVisibleSelectionDoesNotDropFilteredOutItems() {
        ExportSelectionModel selection = new ExportSelectionModel(List.of(USERS, V_ACTIVE_USERS, OLD_VIEW), List.of(USERS, OLD_VIEW));
        selection.setQuery("vista");
        selection.replaceVisibleSelection(List.of(V_ACTIVE_USERS));

        assertEquals(List.of(USERS, V_ACTIVE_USERS), selection.selectedItems());
    }

    @Test void kindFilterComposesWithSearchAndPreservesHiddenSelection() {
        ExportSelectionModel selection = new ExportSelectionModel(List.of(USERS, V_ACTIVE_USERS, OLD_VIEW), List.of(USERS));
        selection.setKinds(Set.of(SchemaObject.Kind.VIEW));

        assertEquals(List.of(V_ACTIVE_USERS, OLD_VIEW), selection.visibleItems());
        assertEquals(List.of(), selection.selectedItems());
        selection.setQuery("old");
        assertEquals(List.of(OLD_VIEW), selection.visibleItems());
        assertEquals(List.of(), selection.selectedItems());
        assertFalse(selection.canExport(Set.of(DiffEngine.Status.CHANGED)));
        selection.setKinds(Set.of());
        assertEquals(List.of(USERS), selection.selectedItems());
        selection.clear();
        assertTrue(selection.selectedItems().isEmpty());
    }

    @Test void clearRemovesVisibleAndHiddenItemsAndExportRequiresApplicableOperation() {
        ExportSelectionModel selection = new ExportSelectionModel(List.of(USERS, V_ACTIVE_USERS, OLD_VIEW), List.of(USERS));

        assertTrue(selection.canExport(Set.of(DiffEngine.Status.CHANGED)));
        assertFalse(selection.canExport(Set.of(DiffEngine.Status.TARGET_ONLY)));
        selection.clear();
        assertTrue(selection.selectedItems().isEmpty());
        assertFalse(selection.canExport(Set.of(DiffEngine.Status.CHANGED, DiffEngine.Status.SOURCE_ONLY, DiffEngine.Status.TARGET_ONLY)));
    }

    private static DiffEngine.Result result(String name, SchemaObject.Kind kind, DiffEngine.Status status) {
        SchemaObject.Key key = new SchemaObject.Key(kind, name);
        SchemaObject source = status == DiffEngine.Status.TARGET_ONLY ? null : new SchemaObject(key, "CREATE " + name, "source");
        SchemaObject target = status == DiffEngine.Status.SOURCE_ONLY ? null : new SchemaObject(key, "CREATE " + name, "target");
        return new DiffEngine.Result(key, status, source, target, List.of());
    }
}
