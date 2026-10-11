package studio.dbdiff;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;

/** Owns the object search, sorting, status legend, and catalog list presentation. */
final class ObjectCatalogPanel extends JPanel {
    private final DefaultListModel<Object> model = new DefaultListModel<>();
    private final List<Object> allItems = new ArrayList<>();
    private final JTextField search = new PlaceholderTextField("Buscar objetos...");
    private final JLabel count = new JLabel("0 objetos");
    private final List<JLabel> legendMarkers = new ArrayList<>();
    private final JList<Object> list = new JList<>(model) {
        @Override public String getToolTipText(MouseEvent event) {
            int index = locationToIndex(event.getPoint());
            if (index < 0 || !getCellBounds(index, index).contains(event.getPoint())) return null;
            return ResultRenderer.tooltip(getModel().getElementAt(index));
        }
    };
    private final Consumer<ListSelectionEvent> selectionListener;
    private final Runnable emptyFilterListener;
    private final List<JLabel> sortHeaders = new ArrayList<>();
    private boolean updatingItems;
    private int sortColumn = 1;
    private boolean sortAscending = true;

    ObjectCatalogPanel(Consumer<ListSelectionEvent> selectionListener,
                       Runnable emptyFilterListener, Consumer<Component> kindMenuAction,
                       Runnable diagnosticsAction) {
        super(new BorderLayout(0, 10));
        this.selectionListener = selectionListener;
        this.emptyFilterListener = emptyFilterListener;
        setOpaque(false);

        JPanel controls = new JPanel(new BorderLayout(6, 0));
        controls.setOpaque(false);
        JLabel heading = new JLabel("OBJETOS");
        heading.putClientProperty("FlatLaf.styleClass", "h3");
        JButton kinds = new JButton("Tipos ▾");
        kinds.addActionListener(event -> kindMenuAction.accept(kinds));
        JPanel kindAction = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        kindAction.setOpaque(false);
        kindAction.add(kinds);
        controls.add(heading, BorderLayout.NORTH);
        controls.add(kindAction, BorderLayout.SOUTH);

        list.setCellRenderer(new ResultRenderer());
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setFixedCellHeight(34);
        list.addListSelectionListener(event -> { if (!updatingItems) selectionListener.accept(event); });
        list.setToolTipText("");
        JScrollPane listScroll = new JScrollPane(list);

        search.setToolTipText("Buscar objetos por nombre o tipo");
        search.getAccessibleContext().setAccessibleName("Buscar objetos");
        search.putClientProperty("JTextField.leadingIcon", UiIcons.themed(UiIcons.SEARCH, 16));
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { applyFilter(); }
        });

        JPanel header = objectColumnsHeader();
        JPanel listTop = new JPanel();
        listTop.setOpaque(false);
        listTop.setLayout(new BoxLayout(listTop, BoxLayout.Y_AXIS));
        listTop.add(search);
        listTop.add(Box.createVerticalStrut(5));
        listTop.add(header);
        JPanel listArea = new JPanel(new BorderLayout(0, 4));
        listArea.setOpaque(false);
        listArea.add(listTop, BorderLayout.NORTH);
        listArea.add(listScroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, 6));
        JPanel footerTop = new JPanel(new BorderLayout(8, 0));
        footerTop.setOpaque(false);
        JButton diagnostics = new JButton("Diagnóstico del catálogo");
        diagnostics.setToolTipText("Comparar el listado de la aplicación con los conteos del catálogo del servidor.");
        diagnostics.addActionListener(event -> diagnosticsAction.run());
        footerTop.add(count, BorderLayout.CENTER);
        footerTop.add(diagnostics, BorderLayout.EAST);
        footer.add(footerTop, BorderLayout.NORTH);
        footer.add(objectStatusLegend(), BorderLayout.CENTER);

        add(controls, BorderLayout.NORTH);
        add(listArea, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    List<Object> selectedValues() { return list.getSelectedValuesList(); }
    Object selectedValue() { return list.getSelectedValue(); }
    List<Object> allItems() { return List.copyOf(allItems); }
    void clearSearch() { search.setText(""); }
    void selectValue(Object value, boolean scrollToValue) { list.setSelectedValue(value, scrollToValue); }
    void setItems(List<?> items) {
        List<Object> selected = selectedValues();
        allItems.clear();
        allItems.addAll(items);
        refreshItems(selected, false);
    }
    void refreshTheme(boolean dark) {
        int[] statuses = {0, 1, 2, 3};
        for (int i = 0; i < legendMarkers.size(); i++) legendMarkers.get(i).setForeground(ThemePalette.status(statuses[i], dark));
        list.repaint();
    }

    private JPanel objectColumnsHeader() {
        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(2, 6, 2, 6));
        header.add(sortHeader("ESTADO", 0, com.formdev.flatlaf.util.UIScale.scale(52)), BorderLayout.WEST);
        JPanel labels = new JPanel(new BorderLayout(4, 0));
        labels.setOpaque(false);
        labels.add(sortHeader("TIPO", 1, com.formdev.flatlaf.util.UIScale.scale(102)), BorderLayout.WEST);
        labels.add(sortHeader("NOMBRE", 2, 0), BorderLayout.CENTER);
        header.add(labels, BorderLayout.CENTER);
        return header;
    }

    private JLabel sortHeader(String title, int column, int width) {
        JLabel label = new JLabel(title);
        label.setFont(javax.swing.UIManager.getFont("Label.font").deriveFont(Font.BOLD));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setToolTipText("Ordenar por " + title.toLowerCase(Locale.ROOT));
        if (width > 0) label.setPreferredSize(new Dimension(width, 22));
        label.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { sortBy(column); }
        });
        sortHeaders.add(label);
        updateSortHeader(label, title, column);
        return label;
    }

    private void sortBy(int column) {
        if (sortColumn == column) sortAscending = !sortAscending;
        else { sortColumn = column; sortAscending = true; }
        sortHeaders.forEach(label -> {
            String title = label.getText().replaceAll(" [▲▼]$", "");
            int index = switch (title) { case "ESTADO" -> 0; case "TIPO" -> 1; default -> 2; };
            updateSortHeader(label, title, index);
        });
        applyFilter();
    }

    private void updateSortHeader(JLabel label, String title, int column) {
        label.setText(title + (sortColumn == column ? (sortAscending ? " ▲" : " ▼") : ""));
    }

    private JPanel objectStatusLegend() {
        JPanel legend = new JPanel(new GridLayout(2, 2, 8, 4));
        legend.setOpaque(false);
        addLegendItem(legend, "●", "Igual", ThemePalette.status(0, com.formdev.flatlaf.FlatLaf.isLafDark()));
        addLegendItem(legend, "●", "Modificado", ThemePalette.status(1, com.formdev.flatlaf.FlatLaf.isLafDark()));
        addLegendItem(legend, UiIcons.themed(UiIcons.SOURCE_ONLY, 16), "Solo origen", ThemePalette.status(2, com.formdev.flatlaf.FlatLaf.isLafDark()));
        addLegendItem(legend, UiIcons.themed(UiIcons.TARGET_ONLY, 16), "Solo destino", ThemePalette.status(3, com.formdev.flatlaf.FlatLaf.isLafDark()));
        return legend;
    }

    private void addLegendItem(JPanel legend, String symbol, String description, Color color) {
        JLabel marker = new JLabel(symbol);
        marker.setFont(javax.swing.UIManager.getFont("Label.font").deriveFont(Font.BOLD));
        marker.setForeground(color);
        legendMarkers.add(marker);
        JLabel text = new JLabel(description);
        text.setFont(javax.swing.UIManager.getFont("Label.font"));
        JPanel item = new JPanel(new BorderLayout(3, 0)); item.setOpaque(false);
        item.add(marker, BorderLayout.WEST); item.add(text, BorderLayout.CENTER); legend.add(item);
    }

    private void addLegendItem(JPanel legend, javax.swing.Icon icon, String description, Color color) {
        JLabel marker = new JLabel(icon);
        marker.setPreferredSize(new Dimension(18, 18)); marker.setForeground(color);
        legendMarkers.add(marker);
        JLabel text = new JLabel(description); text.setFont(javax.swing.UIManager.getFont("Label.font"));
        JPanel item = new JPanel(new BorderLayout(3, 0)); item.setOpaque(false);
        item.add(marker, BorderLayout.WEST); item.add(text, BorderLayout.CENTER); legend.add(item);
    }

    private void applyFilter() { refreshItems(selectedValues(), true); }

    private void refreshItems(List<Object> selected, boolean notifySelectionChange) {
        allItems.sort(comparator());
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        updatingItems = true;
        try {
            model.clear();
            for (Object item : allItems) if (matches(item, query)) model.addElement(item);
            for (Object item : selected) {
                int index = model.indexOf(item);
                if (index >= 0) list.addSelectionInterval(index, index);
            }
        } finally { updatingItems = false; }
        count.setText(model.size() + " / " + allItems.size() + " objetos");
        if (notifySelectionChange && !selected.equals(selectedValues()))
            selectionListener.accept(new ListSelectionEvent(list, 0, model.size(), false));
        if (notifySelectionChange && model.isEmpty()) emptyFilterListener.run();
    }

    private Comparator<Object> comparator() {
        Comparator<Object> comparator = switch (sortColumn) {
            case 0 -> Comparator.comparingInt(CatalogDisplay::statusRank);
            case 2 -> Comparator.comparing(item -> CatalogDisplay.objectKey(item).qualifiedName(), String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(item -> CatalogDisplay.kindName(CatalogDisplay.objectKey(item).kind()), String.CASE_INSENSITIVE_ORDER);
        };
        if (!sortAscending) comparator = comparator.reversed();
        return comparator.thenComparing(item -> CatalogDisplay.objectKey(item).qualifiedName(), String.CASE_INSENSITIVE_ORDER);
    }

    private boolean matches(Object item, String query) {
        SchemaObject.Key key = CatalogDisplay.objectKey(item);
        return query.isEmpty() || (key.qualifiedName() + " " + CatalogDisplay.kindName(key.kind()))
                .toLowerCase(Locale.ROOT).contains(query);
    }

    static final class ResultRenderer extends JPanel implements ListCellRenderer<Object> {
        private final JLabel marker = new JLabel(" ", JLabel.CENTER);
        private final JLabel type = new JLabel();
        private final JLabel name = new JLabel();

        ResultRenderer() {
            super(new BorderLayout(4, 0));
            marker.setPreferredSize(new Dimension(com.formdev.flatlaf.util.UIScale.scale(52), 20));
            type.setPreferredSize(new Dimension(com.formdev.flatlaf.util.UIScale.scale(102), 20));
            JPanel details = new JPanel(new BorderLayout(4, 0)); details.setOpaque(false);
            details.add(type, BorderLayout.WEST); details.add(name, BorderLayout.CENTER);
            add(marker, BorderLayout.WEST); add(details, BorderLayout.CENTER);
            setBorder(new EmptyBorder(5, 6, 5, 6));
            name.setFont(javax.swing.UIManager.getFont("Label.font"));
            type.setFont(javax.swing.UIManager.getFont("Label.font"));
        }

        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            SchemaObject.Key key = CatalogDisplay.objectKey(value);
            boolean enabled = list.isEnabled();
            name.setText(key.qualifiedName());
            type.setText(CatalogDisplay.kindName(key.kind()));
            Color foreground = !enabled ? javax.swing.UIManager.getColor("Label.disabledForeground")
                    : selected ? list.getSelectionForeground() : list.getForeground();
            setBackground(selected && enabled ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(foreground); type.setForeground(selected ? foreground : javax.swing.UIManager.getColor("Label.disabledForeground"));
            marker.setForeground(enabled ? markerColor(value, com.formdev.flatlaf.FlatLaf.isLafDark()) : foreground);
            javax.swing.Icon statusIcon = markerIcon(value);
            marker.setIcon(statusIcon);
            marker.setText(statusIcon == null ? markerText(value) : "");
            type.setIcon(switch (key.kind()) {
                case TABLE -> UiIcons.themed(UiIcons.TABLE, 14);
                case VIEW -> UiIcons.themed(UiIcons.VIEW, 14);
                case PROCEDURE -> UiIcons.themed(UiIcons.PROCEDURE, 14);
                case FUNCTION -> UiIcons.themed(UiIcons.FUNCTION, 14);
                case TRIGGER -> UiIcons.themed(UiIcons.TRIGGER, 14);
            });
            type.setIconTextGap(4);
            return this;
        }

        static String tooltip(Object value) {
            if (value instanceof CatalogItem catalog) {
                if (!catalog.sourcePresent()) return "Pendiente de comparar · falta en origen";
                if (!catalog.targetPresent()) return "Pendiente de comparar · falta en destino";
                return "Pendiente de comparar · presente en origen y destino";
            }
            DiffEngine.Result result = (DiffEngine.Result) value;
            return switch (result.status()) {
                case IDENTICAL -> "Definiciones idénticas en origen y destino";
                case CHANGED -> "Definiciones diferentes · compará origen → destino";
                case SOURCE_ONLY -> "Presente en origen · falta en destino";
                case TARGET_ONLY -> "Presente en destino · falta en origen";
            };
        }

        private static String markerText(Object value) {
            if (value instanceof CatalogItem catalog) {
                if (!catalog.sourcePresent()) return "◀";
                if (!catalog.targetPresent()) return "▶";
                return "○";
            }
            return switch (((DiffEngine.Result) value).status()) {
                case IDENTICAL, CHANGED -> "●";
                case SOURCE_ONLY -> "◀";
                case TARGET_ONLY -> "▶";
            };
        }

        private static javax.swing.Icon markerIcon(Object value) {
            int status = CatalogDisplay.statusRank(value);
            if (status == 2) return UiIcons.themed(UiIcons.SOURCE_ONLY, 20);
            if (status == 3) return UiIcons.themed(UiIcons.TARGET_ONLY, 20);
            return null;
        }

        private static Color markerColor(Object value, boolean dark) {
            if (value instanceof CatalogItem catalog) {
                if (!catalog.sourcePresent()) return ThemePalette.status(2, dark);
                if (!catalog.targetPresent()) return ThemePalette.status(3, dark);
                return javax.swing.UIManager.getColor("Label.disabledForeground");
            }
            return switch (((DiffEngine.Result) value).status()) {
                case IDENTICAL -> ThemePalette.status(0, dark);
                case CHANGED -> ThemePalette.status(1, dark);
                case SOURCE_ONLY -> ThemePalette.status(2, dark);
                case TARGET_ONLY -> ThemePalette.status(3, dark);
            };
        }
    }
}
