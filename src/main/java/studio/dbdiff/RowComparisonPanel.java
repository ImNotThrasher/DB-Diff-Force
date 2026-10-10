package studio.dbdiff;

import javax.swing.BorderFactory;
import javax.swing.JTabbedPane;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.RowFilter;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Row-level comparison with status filters and per-field values in one table.
 * Green and red describe add/remove actions on the destination; object-list markers describe which side has an object.
 */
final class RowComparisonPanel extends JPanel {
    record ExportOptions(boolean inserts, boolean updates, boolean deletes) { }

    private final JToggleButton disclosure = new JToggleButton("Comparación de filas");
    private final JLabel summary = new JLabel("Desplegá este panel para comparar las filas de la tabla seleccionada.");
    private final JPanel body = new JPanel(new BorderLayout(0, 7));
    private final JPanel resultContent = new JPanel(new BorderLayout(0, 5));
    private final JButton export = new JButton("Exportar SQL de filas…");
    private final BiConsumer<RowDiff.Result, ExportOptions> exportHandler;
    private final Consumer<Boolean> expandedHandler;
    private JTable changesTable;
    private JTextField searchField;
    private JScrollPane changesScroll;
    private TableRowSorter<DefaultTableModel> sorter;
    private RowDiff.Result result;
    private RowDiff.Status activeFilter;
    private boolean dark;

    /** Preserve full values for filtering and tooltips when the fixed-height renderer abbreviates them. */
    private record FieldValue(String text, String tooltip, boolean different, String source, String target) {
        FieldValue(String text, String tooltip, boolean different) { this(text, tooltip, different, null, null); }
        @Override public String toString() { return text; }
    }

    RowComparisonPanel(BiConsumer<RowDiff.Result, ExportOptions> exportHandler, Consumer<Boolean> expandedHandler) {
        super(new BorderLayout(0, 4));
        this.exportHandler = exportHandler;
        this.expandedHandler = expandedHandler;
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        disclosure.setHorizontalAlignment(JToggleButton.LEFT);
        disclosure.setIcon(UiIcons.themed(UiIcons.COMPARE_ROWS, 15));
        disclosure.setIconTextGap(6);
        disclosure.addActionListener(event -> {
            updateExpandedState();
            expandedHandler.accept(disclosure.isSelected());
        });
        header.add(disclosure, BorderLayout.WEST);
        header.add(summary, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        export.setIcon(UiIcons.themed(UiIcons.EXPORT, 14)); export.setIconTextGap(5);
        export.setToolTipText("Generar SQL hacia destino según las operaciones marcadas. La búsqueda y las solapas no limitan la exportación. No ejecuta cambios.");
        export.setEnabled(false);
        export.addActionListener(event -> {
            if (result == null) return;
            try {
                exportHandler.accept(result, new ExportOptions(true, true, false));
            } catch (RuntimeException ex) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        "No se pudo preparar la exportación: " + AppUtils.errorMessage(ex), "Error al exportar filas",
                        javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        });
        JPanel actions = new JPanel(new BorderLayout(8, 0));
        actions.setOpaque(false); actions.add(export, BorderLayout.EAST);
        body.add(resultContent, BorderLayout.CENTER); body.add(actions, BorderLayout.SOUTH);
        body.setPreferredSize(new Dimension(100, 285));
        body.setBorder(new EmptyBorder(2, 4, 2, 4));
        body.setVisible(false);
        add(body, BorderLayout.CENTER);
        setBorder(new EmptyBorder(6, 0, 0, 0));
        updateExpandedState();
    }

    void showResult(RowDiff.Result result) {
        this.result = result;
        changesTable = null; searchField = null; changesScroll = null; sorter = null; activeFilter = null;
        long additions = result.changes().stream().filter(change -> change.status() == RowDiff.Status.SOURCE_ONLY).count();
        long removals = result.changes().stream().filter(change -> change.status() == RowDiff.Status.TARGET_ONLY).count();
        long modifications = result.changes().stream().filter(change -> change.status() == RowDiff.Status.DIFFERENT).count();
        long identical = result.changes().stream().filter(change -> change.status() == RowDiff.Status.IDENTICAL).count();
        String primaryKey = result.primaryKey().stream().map(RowDiff.Column::name).collect(Collectors.joining(", "));
        summary.setText(result.table().name() + " · " + result.changes().size() + " filas · " + (additions + removals + modifications) + " con diferencias");
        summary.setToolTipText("Clave primaria: " + primaryKey + " · " + result.matchedRows() + " claves coincidentes"
                + (result.warning().isBlank() ? "" : " · " + result.warning()));
        export.setEnabled(additions + removals + modifications > 0);
        resultContent.removeAll();

        if (result.changes().isEmpty()) {
            String text = result.matchedRows() == 0 && result.sourceOnly() == 0 && result.targetOnly() == 0
                    ? "Las dos tablas están vacías."
                    : "No se encontraron diferencias en las filas emparejadas.";
            JLabel empty = new JLabel(text, JLabel.CENTER);
            empty.setFont(javax.swing.UIManager.getFont("Label.font"));
            resultContent.add(empty, BorderLayout.CENTER);
        } else {
            JPanel toolbar = new JPanel(new BorderLayout(8, 6)); toolbar.setOpaque(false);
            JTabbedPane filters = new JTabbedPane();
            filters.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            addFilter(filters, "Todas", null, result.changes().size(), "Mostrar todas las filas comparadas, incluidas las idénticas.");
            addFilter(filters, "Iguales", RowDiff.Status.IDENTICAL, identical, "Filas idénticas en origen y destino.");
            addFilter(filters, "Solo origen", RowDiff.Status.SOURCE_ONLY, additions, "Solo en origen; exportar como INSERT hacia destino.");
            addFilter(filters, "Solo destino", RowDiff.Status.TARGET_ONLY, removals, "Solo en destino; exportar como DELETE para igualar con origen.");
            addFilter(filters, "Modificadas", RowDiff.Status.DIFFERENT, modifications, "Presentes en ambos lados con valores distintos.");
            filters.addChangeListener(event -> {
                activeFilter = (RowDiff.Status) ((javax.swing.JComponent) filters.getSelectedComponent()).getClientProperty("rowStatus");
                applyFilter();
            });
            JPanel search = new JPanel(new BorderLayout(5, 0)); search.setOpaque(false);
            searchField = new PlaceholderTextField("Buscar en todas las columnas…"); searchField.putClientProperty("JTextField.leadingIcon", UiIcons.themed(UiIcons.SEARCH, 16)); searchField.setToolTipText("Filtrar las filas visibles por cualquier valor");
            search.add(searchField, BorderLayout.CENTER);
            toolbar.add(filters, BorderLayout.NORTH); toolbar.add(search, BorderLayout.CENTER);
            if (!result.warning().isBlank()) {
                JLabel warning = new JLabel("⚠ Hay diferencias de metadata; revisá el destino antes de exportar cambios de filas.");
                warning.setToolTipText(result.warning());
                warning.setForeground(javax.swing.UIManager.getColor("Component.warning.focusedBorderColor"));
                toolbar.add(warning, BorderLayout.SOUTH);
            }
            resultContent.add(toolbar, BorderLayout.NORTH);

            String[] headers = new String[result.columns().size() + 1];
            headers[0] = "Estado";
            for (int column = 0; column < result.columns().size(); column++)
                headers[column + 1] = result.columns().get(column).name();
            Object[][] data = new Object[result.changes().size()][headers.length];
            for (int i = 0; i < result.changes().size(); i++) {
                RowDiff.RowChange change = result.changes().get(i);
                data[i][0] = statusLabel(change.status());
                for (int column = 0; column < result.columns().size(); column++)
                    data[i][column + 1] = fieldValue(result.columns().get(column), change);
            }
            DefaultTableModel model = new DefaultTableModel(data, headers) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
            changesTable = new JTable(model) {
                @Override public String getToolTipText(MouseEvent event) {
                    int row = rowAtPoint(event.getPoint());
                    int column = columnAtPoint(event.getPoint());
                    if (row < 0 || column < 0) return null;
                    Object value = getValueAt(row, column);
                    if (value instanceof FieldValue field) return field.tooltip();
                    return statusTooltip(String.valueOf(value));
                }
            };
            changesTable.setToolTipText("");
            changesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            changesTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            changesTable.setFillsViewportHeight(true);
            changesTable.setRowHeight(com.formdev.flatlaf.util.UIScale.scale(34));
            sizeColumnsToContent(changesTable);
            changesTable.getColumnModel().getColumn(0).setCellRenderer(new StatusCellRenderer(dark));
            for (int column = 1; column < headers.length; column++)
                changesTable.getColumnModel().getColumn(column).setCellRenderer(new FieldCellRenderer(dark));
            sorter = new TableRowSorter<>(model); changesTable.setRowSorter(sorter);
            searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { applyFilter(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { applyFilter(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { applyFilter(); }
            });
            changesScroll = new JScrollPane(changesTable);
            changesScroll.setColumnHeaderView(changesTable.getTableHeader());
            resultContent.add(changesScroll, BorderLayout.CENTER);
            applyFilter();
        }

        if (result.truncated()) {
            JLabel warning = new JLabel("Resultados limitados: no se muestran todas las filas comparadas.");
            warning.setForeground(javax.swing.UIManager.getColor("Component.warning.focusedBorderColor"));
            resultContent.add(warning, BorderLayout.SOUTH);
        }
        resultContent.revalidate(); resultContent.repaint(); revalidate(); repaint();
    }

    void clear() { clear(true); }
    void clearResult(boolean collapse) { clear(collapse); }

    void setExpanded(boolean expanded) {
        if (disclosure.isSelected() == expanded) return;
        disclosure.setSelected(expanded);
        updateExpandedState();
        expandedHandler.accept(expanded);
    }

    boolean isExpanded() { return disclosure.isSelected(); }

    void showLoading(String tableName) {
        result = null; changesTable = null; searchField = null; changesScroll = null; sorter = null;
        summary.setText("Comparando " + tableName + "…"); export.setEnabled(false);
        resultContent.removeAll();
        JLabel loading = new JLabel("Comparando filas de " + tableName + "…", JLabel.CENTER);
        resultContent.add(loading, BorderLayout.CENTER);
        if (!isExpanded()) setExpanded(true);
        resultContent.revalidate(); resultContent.repaint();
    }

    void showMessage(String message) {
        result = null; changesTable = null; searchField = null; changesScroll = null; sorter = null;
        summary.setText("Comparación de filas"); export.setEnabled(false);
        resultContent.removeAll();
        JLabel label = new JLabel(message, JLabel.CENTER);
        resultContent.add(label, BorderLayout.CENTER);
        resultContent.revalidate(); resultContent.repaint();
    }

    private void clear(boolean collapse) {
        result = null; summary.setText("Seleccioná una tabla para comparar sus filas.");
        resultContent.removeAll(); changesTable = null; searchField = null; changesScroll = null; sorter = null;
        export.setEnabled(false);
        if (collapse) disclosure.setSelected(false);
        updateExpandedState(); resultContent.revalidate(); resultContent.repaint();
    }

    void setThemeColors(boolean dark) {
        this.dark = dark;
        if (changesTable != null) {
            changesTable.getColumnModel().getColumn(0).setCellRenderer(new StatusCellRenderer(dark));
            for (int column = 1; column < changesTable.getColumnCount(); column++)
                changesTable.getColumnModel().getColumn(column).setCellRenderer(new FieldCellRenderer(dark));
            changesTable.repaint();
        }
    }

    private void addFilter(JTabbedPane tabs, String name, RowDiff.Status status, long count, String tooltip) {
        JPanel page = new JPanel(); page.setPreferredSize(new Dimension(0, 0));
        page.putClientProperty("rowStatus", status);
        tabs.addTab(name + " (" + count + ")", null, page, tooltip);
    }

    private void applyFilter() {
        if (sorter == null) return;
        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        sorter.setRowFilter(new RowFilter<>() {
            @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                if (activeFilter != null && !statusLabel(activeFilter).equals(entry.getStringValue(0))) return false;
                if (query.isEmpty()) return true;
                for (int column = 0; column < entry.getValueCount(); column++)
                    if (entry.getStringValue(column).toLowerCase(java.util.Locale.ROOT).contains(query)) return true;
                return false;
            }
        });
    }

    private void updateExpandedState() {
        body.setVisible(disclosure.isSelected());
        setMinimumSize(new Dimension(100, disclosure.isSelected()
                ? com.formdev.flatlaf.util.UIScale.scale(190) : getComponent(0).getPreferredSize().height + 8));
        disclosure.setText(disclosure.isSelected() ? "▾ Comparación de filas" : "▸ Comparación de filas");
        AppTheme.styleToggleButton(disclosure, dark);
        revalidate(); repaint();
    }

    private static String statusLabel(RowDiff.Status status) {
        return switch (status) {
            case SOURCE_ONLY -> "Solo origen";
            case TARGET_ONLY -> "Solo destino";
            case DIFFERENT -> "Modificada";
            case IDENTICAL -> "Igual";
        };
    }

    private static String statusTooltip(String status) {
        return switch (status) {
            case "Solo origen" -> "Solo en origen · agregar al destino (INSERT).";
            case "Solo destino" -> "Solo en destino · quitar del destino (DELETE).";
            case "Modificada" -> "La fila existe en ambos lados con valores distintos.";
            case "Igual" -> "La fila es idéntica en origen y destino.";
            default -> "Estado de la fila";
        };
    }

    private static FieldValue fieldValue(RowDiff.Column column, RowDiff.RowChange change) {
        String source = rowValue(change.source(), column.name());
        String target = rowValue(change.target(), column.name());
        return switch (change.status()) {
            case SOURCE_ONLY -> new FieldValue(source, "Presente en origen: " + source + "\nFalta en destino.", false);
            case TARGET_ONLY -> new FieldValue(target, "Falta en origen.\nPresente en destino: " + target, false);
            case DIFFERENT -> {
                boolean different = !Objects.deepEquals(change.source().get(column.name()), change.target().get(column.name()));
                yield different
                        ? new FieldValue("O: " + source + " → D: " + target, "Origen: " + source + "\nDestino: " + target, true, source, target)
                        : new FieldValue(source, "Igual en origen y destino: " + source, false);
            }
            case IDENTICAL -> new FieldValue(source, "Igual en origen y destino: " + source, false);
        };
    }

    private static String rowValue(Map<String, Object> row, String column) {
        return row.containsKey(column) ? displayValue(row.get(column)) : "(sin registro)";
    }

    private static String displayValue(Object value) {
        if (value == null) return "NULL";
        // MariaDB drivers may expose TINYINT(1) columns as Boolean values.
        if (value instanceof Boolean bool) return bool ? "1" : "0";
        if (value instanceof byte[] bytes) return "0x" + java.util.HexFormat.of().formatHex(bytes);
        String text = value.toString();
        return text.length() > 512 ? text.substring(0, 509) + "…" : text;
    }

    private static void sizeColumnsToContent(JTable table) {
        FontMetrics headerMetrics = table.getTableHeader().getFontMetrics(table.getTableHeader().getFont());
        FontMetrics cellMetrics = table.getFontMetrics(table.getFont());
        int minimum = com.formdev.flatlaf.util.UIScale.scale(92);
        int maximum = com.formdev.flatlaf.util.UIScale.scale(320);
        int padding = com.formdev.flatlaf.util.UIScale.scale(20);
        int statusWidth = Math.max(headerMetrics.stringWidth("Estado"),
                Math.max(cellMetrics.stringWidth("Solo origen"), Math.max(cellMetrics.stringWidth("Solo destino"),
                        Math.max(cellMetrics.stringWidth("Modificada"), cellMetrics.stringWidth("Igual"))))) + padding;
        int fittedStatusWidth = Math.min(maximum, Math.max(com.formdev.flatlaf.util.UIScale.scale(82), statusWidth));
        table.getColumnModel().getColumn(0).setPreferredWidth(fittedStatusWidth);
        table.getColumnModel().getColumn(0).setWidth(fittedStatusWidth);

        for (int column = 1; column < table.getColumnCount(); column++) {
            String heading = table.getColumnName(column);
            int preferred = Math.max(minimum, headerMetrics.stringWidth(heading) + padding);
            // Sample a bounded number of rows so measuring a large result stays inexpensive.
            int sampleRows = Math.min(table.getModel().getRowCount(), 200);
            for (int row = 0; row < sampleRows && preferred < maximum; row++) {
                Object value = table.getModel().getValueAt(row, column);
                String text = value == null ? "" : value.toString();
                int sampleEnd = text.offsetByCodePoints(0, Math.min(text.codePointCount(0, text.length()), 80));
                preferred = Math.max(preferred, Math.min(maximum, cellMetrics.stringWidth(text.substring(0, sampleEnd)) + padding));
            }
            int fittedWidth = Math.min(maximum, preferred);
            table.getColumnModel().getColumn(column).setPreferredWidth(fittedWidth);
            table.getColumnModel().getColumn(column).setWidth(fittedWidth);
        }
    }

    private static final class FieldCellRenderer extends DefaultTableCellRenderer {
        private final boolean dark;
        FieldCellRenderer(boolean dark) { this.dark = dark; setOpaque(true); setBorder(new EmptyBorder(3, 6, 3, 6)); }
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                  boolean focus, int row, int column) {
            FieldValue field = value instanceof FieldValue cell ? cell : new FieldValue(String.valueOf(value), "", false);
            super.getTableCellRendererComponent(table, field.text(), selected, focus, row, column);
            setFont(table.getFont());
            int availableWidth = Math.max(0, table.getCellRect(row, column, false).width
                    - com.formdev.flatlaf.util.UIScale.scale(16));
            FontMetrics metrics = getFontMetrics(getFont());
            setText(field.different() && field.source() != null
                    ? comparisonText(field.source(), field.target(), availableWidth, metrics)
                    : ellipsize(field.text(), availableWidth, metrics));
            setToolTipText(field.tooltip().isBlank() ? null : field.tooltip());
            setForeground(selected ? table.getSelectionForeground() : table.getForeground());
            if (selected) setBackground(table.getSelectionBackground());
            else {
                int modelRow = table.convertRowIndexToModel(row);
                String status = String.valueOf(table.getModel().getValueAt(modelRow, 0));
                if (status.startsWith("Solo origen")) setBackground(ThemePalette.addedRow(dark));
                else if (status.startsWith("Solo destino")) setBackground(ThemePalette.removedRow(dark));
                else if (status.startsWith("Igual")) setBackground(table.getBackground());
                else if (field.different()) setBackground(ThemePalette.modifiedBackground(dark));
                else setBackground(table.getBackground());
            }
            return this;
        }

        private static String comparisonText(String source, String target, int width, FontMetrics metrics) {
            String separator = " → ";
            int valueWidth = Math.max(0, width - metrics.stringWidth(separator));
            int sourceWidth = valueWidth / 2;
            int targetWidth = valueWidth - sourceWidth;
            return labeledValue("O: ", source, sourceWidth, metrics) + separator
                    + labeledValue("D: ", target, targetWidth, metrics);
        }

        private static String labeledValue(String label, String value, int width, FontMetrics metrics) {
            int labelWidth = metrics.stringWidth(label);
            if (width <= labelWidth) return ellipsize(label, width, metrics);
            return label + ellipsize(value, width - labelWidth, metrics);
        }

        private static String ellipsize(String text, int width, FontMetrics metrics) {
            String ellipsis = "…";
            if (width <= 0) return "";
            if (metrics.stringWidth(text) <= width) return text;
            if (metrics.stringWidth(ellipsis) > width) return "";
            int low = 0, high = text.codePointCount(0, text.length());
            while (low < high) {
                int middle = (low + high + 1) / 2;
                String candidate = text.substring(0, text.offsetByCodePoints(0, middle)) + ellipsis;
                if (metrics.stringWidth(candidate) <= width) low = middle;
                else high = middle - 1;
            }
            return text.substring(0, text.offsetByCodePoints(0, low)).stripTrailing() + ellipsis;
        }
    }

    private static final class StatusCellRenderer extends DefaultTableCellRenderer {
        private final boolean dark;
        private StatusCellRenderer(boolean dark) { this.dark = dark; }
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                  boolean focus, int row, int column) {
            Component cell = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (!selected) {
                String status = String.valueOf(value);
                if (status.startsWith("Solo origen")) cell.setBackground(ThemePalette.addedRow(dark));
                else if (status.startsWith("Solo destino")) cell.setBackground(ThemePalette.removedRow(dark));
                else if (status.startsWith("Igual")) cell.setBackground(table.getBackground());
                else cell.setBackground(ThemePalette.modifiedBackground(dark));
            }
            return cell;
        }
    }
}
