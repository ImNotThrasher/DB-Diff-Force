package studio.dbdiff;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Row-level comparison with status filters and per-field values in one table. */
final class RowComparisonPanel extends JPanel {
    record ExportOptions(boolean inserts, boolean updates, boolean deletes) { }

    private final JToggleButton disclosure = new JToggleButton("Comparación de filas");
    private final JLabel summary = new JLabel("Seleccioná una tabla comparada y elegí Comparar filas.");
    private final JPanel body = new JPanel(new BorderLayout(0, 7));
    private final JPanel resultContent = new JPanel(new BorderLayout(0, 5));
    private final JCheckBox inserts = new JCheckBox("INSERT: filas solo en origen", true);
    private final JCheckBox updates = new JCheckBox("UPDATE: filas diferentes", true);
    private final JCheckBox deletes = new JCheckBox("DELETE: filas solo en destino", false);
    private final JButton export = new JButton("Exportar SQL de filas…");
    private final BiConsumer<RowDiff.Result, ExportOptions> exportHandler;
    private final Consumer<Boolean> expandedHandler;
    private final List<JToggleButton> filterButtons = new ArrayList<>();
    private JTable changesTable;
    private JTextField searchField;
    private JLabel searchIcon;
    private JScrollPane changesScroll;
    private TableRowSorter<DefaultTableModel> sorter;
    private RowDiff.Result result;
    private RowDiff.Status activeFilter;
    private boolean dark;

    /** Cell presentation keeps source and destination values available to filters and tooltips. */
    private record FieldValue(String text, String tooltip, boolean different) {
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
        header.add(summary, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        options.setOpaque(false);
        options.add(inserts); options.add(updates); options.add(deletes);
        export.setIcon(UiIcons.themed(UiIcons.EXPORT, 14)); export.setIconTextGap(5);
        export.setToolTipText("Revisar y guardar SQL para las filas filtradas por las operaciones seleccionadas.");
        export.setEnabled(false);
        export.addActionListener(event -> {
            if (result == null) return;
            try {
                exportHandler.accept(result, new ExportOptions(inserts.isSelected(), updates.isSelected(), deletes.isSelected()));
            } catch (RuntimeException ex) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        "No se pudo preparar la exportación: " + AppUtils.errorMessage(ex), "Error al exportar filas",
                        javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        });
        JPanel actions = new JPanel(new BorderLayout(8, 0));
        actions.setOpaque(false); actions.add(options, BorderLayout.CENTER); actions.add(export, BorderLayout.EAST);
        body.add(resultContent, BorderLayout.CENTER); body.add(actions, BorderLayout.SOUTH);
        body.setPreferredSize(new Dimension(100, 285));
        body.setBorder(new EmptyBorder(2, 4, 2, 4));
        body.setVisible(false);
        add(body, BorderLayout.CENTER);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                new Color(223, 228, 235)), new EmptyBorder(5, 0, 0, 0)));
        updateExpandedState();
    }

    void showResult(RowDiff.Result result) {
        this.result = result;
        changesTable = null; searchField = null; searchIcon = null; changesScroll = null; sorter = null; filterButtons.clear(); activeFilter = null;
        long additions = result.changes().stream().filter(change -> change.status() == RowDiff.Status.SOURCE_ONLY).count();
        long removals = result.changes().stream().filter(change -> change.status() == RowDiff.Status.TARGET_ONLY).count();
        long modifications = result.changes().stream().filter(change -> change.status() == RowDiff.Status.DIFFERENT).count();
        String primaryKey = result.primaryKey().stream().map(RowDiff.Column::name).collect(Collectors.joining(", "));
        summary.setText(result.table().name() + " · " + result.matchedRows() + " claves coincidentes · "
                + result.sourceOnly() + " solo en origen · " + result.targetOnly() + " solo en destino · "
                + result.different() + " modificadas · PK: " + primaryKey);
        export.setEnabled(!result.changes().isEmpty());
        resultContent.removeAll();

        if (result.changes().isEmpty()) {
            String text = result.matchedRows() == 0 && result.sourceOnly() == 0 && result.targetOnly() == 0
                    ? "Las dos tablas están vacías."
                    : "No se encontraron diferencias en las filas emparejadas.";
            JLabel empty = new JLabel(text, JLabel.CENTER);
            empty.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            empty.setForeground(ThemePalette.forMode(dark).foreground());
            resultContent.add(empty, BorderLayout.CENTER);
        } else {
            JPanel toolbar = new JPanel(new BorderLayout(8, 0)); toolbar.setOpaque(false);
            JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0)); filters.setOpaque(false);
            ButtonGroup group = new ButtonGroup();
            addFilter(filters, group, "Todas", null, result.changes().size(), "Mostrar todos los registros con diferencias.");
            addFilter(filters, group, "Agregadas", RowDiff.Status.SOURCE_ONLY, additions, "Solo en origen; exportar como INSERT hacia destino.");
            addFilter(filters, group, "Quitadas", RowDiff.Status.TARGET_ONLY, removals, "Solo en destino; exportar como DELETE para igualar con origen.");
            addFilter(filters, group, "Modificadas", RowDiff.Status.DIFFERENT, modifications, "Presentes en ambos lados con valores distintos.");
            JPanel search = new JPanel(new BorderLayout(5, 0)); search.setOpaque(false);
            searchIcon = new JLabel(UiIcons.themed(UiIcons.SEARCH, 14));
            searchIcon.setForeground(ThemePalette.forMode(dark).foreground());
            search.add(searchIcon, BorderLayout.WEST);
            searchField = new JTextField(16); searchField.setToolTipText("Buscar en estado, clave y diferencias");
            searchField.setBackground(ThemePalette.forMode(dark).editor());
            searchField.setForeground(ThemePalette.forMode(dark).foreground());
            searchField.setCaretColor(ThemePalette.forMode(dark).foreground());
            searchField.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ThemePalette.forMode(dark).border()), new EmptyBorder(3, 6, 3, 6)));
            search.add(searchField, BorderLayout.CENTER);
            toolbar.add(filters, BorderLayout.WEST); toolbar.add(search, BorderLayout.EAST);
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
                    return "Estado de la fila";
                }
            };
            changesTable.setToolTipText("");
            changesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            changesTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            changesTable.setFillsViewportHeight(true);
            changesTable.setRowHeight(46);
            changesTable.getColumnModel().getColumn(0).setPreferredWidth(165);
            for (int column = 1; column < headers.length; column++)
                changesTable.getColumnModel().getColumn(column).setPreferredWidth(180);
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
            applyFilter(); applyTableTheme(changesTable);
        }

        if (result.truncated()) {
            JLabel warning = new JLabel("Resultados limitados a las primeras filas modificadas.");
            warning.setForeground(dark ? new Color(230, 174, 85) : new Color(155, 79, 21));
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
        result = null; changesTable = null; searchField = null; searchIcon = null; changesScroll = null; sorter = null; filterButtons.clear();
        summary.setText("Comparando " + tableName + "…"); export.setEnabled(false);
        resultContent.removeAll();
        JLabel loading = new JLabel("Comparando filas de " + tableName + "…", JLabel.CENTER);
        loading.setForeground(ThemePalette.forMode(dark).foreground());
        resultContent.add(loading, BorderLayout.CENTER);
        if (!isExpanded()) setExpanded(true);
        resultContent.revalidate(); resultContent.repaint();
    }

    void showMessage(String message) {
        result = null; changesTable = null; searchField = null; searchIcon = null; changesScroll = null; sorter = null; filterButtons.clear();
        summary.setText("Comparación de filas"); export.setEnabled(false);
        resultContent.removeAll();
        JLabel label = new JLabel(message, JLabel.CENTER);
        label.setForeground(ThemePalette.forMode(dark).foreground());
        resultContent.add(label, BorderLayout.CENTER);
        resultContent.revalidate(); resultContent.repaint();
    }

    private void clear(boolean collapse) {
        result = null; summary.setText("Seleccioná una tabla para comparar sus filas.");
        resultContent.removeAll(); changesTable = null; searchField = null; searchIcon = null; changesScroll = null; sorter = null; filterButtons.clear();
        export.setEnabled(false);
        if (collapse) disclosure.setSelected(false);
        updateExpandedState(); resultContent.revalidate(); resultContent.repaint();
    }

    void setThemeColors(boolean dark) {
        this.dark = dark;
        ThemePalette palette = ThemePalette.forMode(dark);
        setBackground(palette.panel()); body.setBackground(palette.panel()); resultContent.setBackground(palette.panel());
        summary.setForeground(palette.foreground());
        AppTheme.styleToggleButton(disclosure, dark);
        for (JToggleButton filter : filterButtons) AppTheme.styleToggleButton(filter, dark);
        for (JCheckBox option : new JCheckBox[]{inserts, updates, deletes}) {
            option.setForeground(palette.foreground()); option.setBackground(palette.panel());
        }
        if (searchField != null) {
            searchField.setBackground(palette.editor()); searchField.setForeground(palette.foreground());
            searchField.setCaretColor(palette.foreground());
            searchField.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(palette.border()), new EmptyBorder(3, 6, 3, 6)));
        }
        if (searchIcon != null) searchIcon.setForeground(palette.foreground());
        if (changesScroll != null) {
            changesScroll.setBorder(BorderFactory.createLineBorder(palette.border()));
            changesScroll.getViewport().setBackground(palette.editor());
            if (changesScroll.getColumnHeader() != null) changesScroll.getColumnHeader().setBackground(palette.panel());
        }
        export.setForeground(palette.foreground()); export.setBackground(palette.panel());
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                palette.border()), new EmptyBorder(5, 0, 0, 0)));
        if (changesTable != null) {
            changesTable.getColumnModel().getColumn(0).setCellRenderer(new StatusCellRenderer(dark));
            for (int column = 1; column < changesTable.getColumnModel().getColumnCount(); column++)
                changesTable.getColumnModel().getColumn(column).setCellRenderer(new FieldCellRenderer(dark));
            applyTableTheme(changesTable);
        }
    }

    private void addFilter(JPanel panel, ButtonGroup group, String name, RowDiff.Status status, long count, String tooltip) {
        JToggleButton button = new JToggleButton(name + " (" + count + ")");
        button.setToolTipText(tooltip);
        group.add(button); panel.add(button); filterButtons.add(button);
        if (status == null) button.setSelected(true);
        AppTheme.styleToggleButton(button, dark);
        button.addActionListener(event -> {
            activeFilter = status; applyFilter();
            for (JToggleButton filter : filterButtons) AppTheme.styleToggleButton(filter, dark);
        });
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

    private void applyTableTheme(JTable table) {
        ThemePalette palette = ThemePalette.forMode(dark);
        table.setBackground(palette.editor()); table.setForeground(palette.foreground());
        table.setGridColor(palette.border()); table.setSelectionBackground(palette.selection());
        table.setSelectionForeground(palette.foreground());
        table.getTableHeader().setBackground(palette.panel()); table.getTableHeader().setForeground(palette.foreground());
    }

    private void updateExpandedState() {
        body.setVisible(disclosure.isSelected());
        disclosure.setText(disclosure.isSelected() ? "▾ Comparación de filas" : "▸ Comparación de filas");
        AppTheme.styleToggleButton(disclosure, dark);
        revalidate(); repaint();
    }

    private static String statusLabel(RowDiff.Status status) {
        return switch (status) {
            case SOURCE_ONLY -> "Agregada · solo origen";
            case TARGET_ONLY -> "Quitada · solo destino";
            case DIFFERENT -> "Modificada";
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
                        ? new FieldValue("O: " + source + " → D: " + target, "Origen: " + source + "\nDestino: " + target, true)
                        : new FieldValue(source, "Igual en origen y destino: " + source, false);
            }
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

    private static final class FieldCellRenderer extends javax.swing.JTextArea implements javax.swing.table.TableCellRenderer {
        private final boolean dark;
        FieldCellRenderer(boolean dark) { this.dark = dark; setLineWrap(true); setWrapStyleWord(true); setOpaque(true); setBorder(new EmptyBorder(3, 6, 3, 6)); }
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                  boolean focus, int row, int column) {
            FieldValue field = value instanceof FieldValue cell ? cell : new FieldValue(String.valueOf(value), "", false);
            setText(field.text()); setFont(table.getFont());
            setForeground(selected ? table.getSelectionForeground() : table.getForeground());
            if (selected) setBackground(table.getSelectionBackground());
            else {
                int modelRow = table.convertRowIndexToModel(row);
                String status = String.valueOf(table.getModel().getValueAt(modelRow, 0));
                if (status.startsWith("Agregada")) setBackground(dark ? new Color(35, 84, 66) : new Color(222, 246, 229));
                else if (status.startsWith("Quitada")) setBackground(dark ? new Color(104, 48, 52) : new Color(255, 226, 226));
                else if (field.different()) setBackground(dark ? new Color(91, 69, 34) : new Color(255, 241, 204));
                else setBackground(table.getBackground());
            }
            return this;
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
                if (status.startsWith("Agregada")) cell.setBackground(dark ? new Color(35, 84, 66) : new Color(222, 246, 229));
                else if (status.startsWith("Quitada")) cell.setBackground(dark ? new Color(104, 48, 52) : new Color(255, 226, 226));
                else cell.setBackground(dark ? new Color(91, 69, 34) : new Color(255, 241, 204));
            }
            return cell;
        }
    }
}
