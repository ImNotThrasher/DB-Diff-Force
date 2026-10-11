package studio.dbdiff;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Keeps export selection independent from the subset currently shown by the search filter. */
final class ExportSelectionModel {
    private final List<DiffEngine.Result> available;
    private final Set<SchemaObject.Key> selectedKeys = new LinkedHashSet<>();
    private String query = "";

    ExportSelectionModel(List<DiffEngine.Result> available, List<DiffEngine.Result> preselected) {
        this.available = List.copyOf(available);
        List<DiffEngine.Result> initialSelection = preselected.isEmpty() ? available : preselected;
        Set<SchemaObject.Key> availableKeys = available.stream().map(DiffEngine.Result::key)
                .collect(java.util.stream.Collectors.toSet());
        initialSelection.stream().map(DiffEngine.Result::key).filter(availableKeys::contains).forEach(selectedKeys::add);
    }

    void setQuery(String value) { query = value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }

    List<DiffEngine.Result> visibleItems() {
        if (query.isEmpty()) return available;
        List<DiffEngine.Result> visible = new ArrayList<>();
        for (DiffEngine.Result result : available) {
            String searchable = result.key().qualifiedName() + " " + CatalogDisplay.kindName(result.key().kind());
            if (searchable.toLowerCase(Locale.ROOT).contains(query)) visible.add(result);
        }
        return List.copyOf(visible);
    }

    List<DiffEngine.Result> selectedItems() {
        return available.stream().filter(result -> selectedKeys.contains(result.key())).toList();
    }

    boolean isSelected(SchemaObject.Key key) { return selectedKeys.contains(key); }
    int selectedCount() { return selectedKeys.size(); }
    int availableCount() { return available.size(); }
    boolean canExport(Set<DiffEngine.Status> operations) {
        return available.stream().anyMatch(result -> selectedKeys.contains(result.key()) && operations.contains(result.status()));
    }

    void selectVisible() { visibleItems().stream().map(DiffEngine.Result::key).forEach(selectedKeys::add); }
    void clear() { selectedKeys.clear(); }

    /** Replaces selection only for visible rows, preserving selections hidden by the current filter. */
    void replaceVisibleSelection(List<DiffEngine.Result> visibleSelection) {
        Set<SchemaObject.Key> visibleKeys = visibleItems().stream().map(DiffEngine.Result::key)
                .collect(java.util.stream.Collectors.toSet());
        selectedKeys.removeAll(visibleKeys);
        visibleSelection.stream().map(DiffEngine.Result::key).filter(visibleKeys::contains).forEach(selectedKeys::add);
    }
}
