package studio.dbdiff;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Component;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Expandable row comparison results section below the schema diff. */
final class RowComparisonPanel extends JPanel {
    record ExportOptions(boolean inserts, boolean updates, boolean deletes) { }

    private final JToggleButton disclosure = new JToggleButton("Comparación de filas");
    private final JLabel summary = new JLabel("Seleccioná una tabla comparada y elegí Comparar filas.");
    private final JPanel body = new JPanel(new BorderLayout(0, 7));
    private final JPanel resultContent = new JPanel(new BorderLayout());
    private final JCheckBox inserts = new JCheckBox("INSERT: filas solo en origen", true);
    private final JCheckBox updates = new JCheckBox("UPDATE: filas diferentes", true);
    private final JCheckBox deletes = new JCheckBox("DELETE: filas solo en destino", false);
    private final JButton export = new JButton("Exportar SQL de filas…");
    private final BiConsumer<RowDiff.Result, ExportOptions> exportHandler;
    private final Consumer<Boolean> expandedHandler;
    private JTable changesTable;
    private JTable fieldsTable;
    private RowDiff.Result result;
    private boolean dark;

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
        export.setEnabled(false);
        export.addActionListener(event -> {
            if (result != null) exportHandler.accept(result,
                    new ExportOptions(inserts.isSelected(), updates.isSelected(), deletes.isSelected()));
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
        changesTable = null; fieldsTable = null;
        String primaryKey = result.primaryKey().stream().map(RowDiff.Column::name).collect(Collectors.joining(", "));
        summary.setText(result.table().name() + " · " + result.matchedRows() + " claves coincidentes · "
                + result.sourceOnly() + " solo en origen · "
                + result.targetOnly() + " solo en destino · " + result.different() + " diferentes · PK: " + primaryKey);
        export.setEnabled(!result.changes().isEmpty());
        if (result.changes().isEmpty()) {
            String text = result.matchedRows() == 0 && result.sourceOnly() == 0 && result.targetOnly() == 0
                    ? "Las dos tablas están vacías."
                    : "No se encontraron diferencias en las filas emparejadas.";
            JLabel empty = new JLabel(text, JLabel.CENTER);
            empty.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            empty.setForeground(ThemePalette.forMode(dark).foreground());
            resultContent.removeAll(); resultContent.add(empty, BorderLayout.CENTER);
        } else {
            String[] headers = {"Estado", "Clave primaria", "Detalle"};
            Object[][] data = new Object[result.changes().size()][headers.length];
            for (int i = 0; i < result.changes().size(); i++) {
                RowDiff.RowChange change = result.changes().get(i);
                data[i][0] = switch (change.status()) {
                    case SOURCE_ONLY -> "Solo en origen";
                    case TARGET_ONLY -> "Solo en destino";
                    case DIFFERENT -> "Diferente";
                };
                data[i][1] = keyText(change.key());
                data[i][2] = change.status() == RowDiff.Status.DIFFERENT
                        ? changedFieldCount(result, change) + " campos distintos" : "Registro completo";
            }
            JTable table = new JTable(new DefaultTableModel(data, headers) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            });
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
            table.setFillsViewportHeight(true);
            changesTable = table;
            JPanel tables = new JPanel(new BorderLayout(0, 5));
            fieldsTable = new JTable(new DefaultTableModel(new Object[][]{}, new String[]{"Campo", "Origen", "Destino"}) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            });
            fieldsTable.setFillsViewportHeight(true);
            fieldsTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
            JPanel changes = titledTable("REGISTROS CON DIFERENCIAS", table);
            JPanel fields = titledTable("DETALLE DEL REGISTRO", fieldsTable);
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, changes, fields);
            split.setResizeWeight(.55); split.setDividerSize(6); split.setBorder(null);
            tables.add(split, BorderLayout.CENTER);
            table.getSelectionModel().addListSelectionListener(event -> showSelectedChange(event, result));
            table.setRowSelectionInterval(0, 0);
            resultContent.removeAll(); resultContent.add(tables, BorderLayout.CENTER);
            applyTableTheme(table); applyTableTheme(fieldsTable);
        }
        if (result.truncated()) {
            JLabel warning = new JLabel("Resultados limitados a las primeras filas modificadas.");
            warning.setForeground(dark ? new Color(230, 174, 85) : new Color(155, 79, 21));
            resultContent.add(warning, BorderLayout.SOUTH);
        }
        resultContent.revalidate(); resultContent.repaint(); revalidate(); repaint();
    }

    void clear() {
        clear(true);
    }

    void clearResult(boolean collapse) {
        clear(collapse);
    }

    void setExpanded(boolean expanded) {
        if (disclosure.isSelected() == expanded) return;
        disclosure.setSelected(expanded);
        updateExpandedState();
        expandedHandler.accept(expanded);
    }

    boolean isExpanded() { return disclosure.isSelected(); }

    void showLoading(String tableName) {
        result = null;
        changesTable = null; fieldsTable = null;
        summary.setText("Comparando " + tableName + "…");
        export.setEnabled(false);
        resultContent.removeAll();
        JLabel loading = new JLabel("Comparando filas de " + tableName + "…", JLabel.CENTER);
        loading.setForeground(ThemePalette.forMode(dark).foreground());
        resultContent.add(loading, BorderLayout.CENTER);
        if (!isExpanded()) setExpanded(true);
        resultContent.revalidate(); resultContent.repaint();
    }

    void showMessage(String message) {
        result = null;
        changesTable = null; fieldsTable = null;
        summary.setText("Comparación de filas");
        export.setEnabled(false);
        resultContent.removeAll();
        JLabel label = new JLabel(message, JLabel.CENTER);
        label.setForeground(ThemePalette.forMode(dark).foreground());
        resultContent.add(label, BorderLayout.CENTER);
        resultContent.revalidate(); resultContent.repaint();
    }

    private void clear(boolean collapse) {
        result = null;
        summary.setText("Seleccioná una tabla para comparar sus filas.");
        resultContent.removeAll();
        changesTable = null; fieldsTable = null;
        export.setEnabled(false);
        if (collapse) disclosure.setSelected(false);
        updateExpandedState();
        resultContent.revalidate(); resultContent.repaint();
    }

    void setThemeColors(boolean dark) {
        this.dark = dark;
        ThemePalette palette = ThemePalette.forMode(dark);
        setBackground(palette.panel()); body.setBackground(palette.panel()); resultContent.setBackground(palette.panel());
        summary.setForeground(palette.foreground());
        disclosure.setForeground(palette.foreground()); disclosure.setBackground(palette.panel());
        for (JCheckBox option : new JCheckBox[]{inserts, updates, deletes}) {
            option.setForeground(palette.foreground()); option.setBackground(palette.panel());
        }
        export.setForeground(palette.foreground()); export.setBackground(palette.panel());
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                palette.border()), new EmptyBorder(5, 0, 0, 0)));
        if (resultContent.getComponentCount() > 0 && resultContent.getComponent(0) instanceof JScrollPane scroll
                && scroll.getViewport().getView() instanceof JTable table) applyTableTheme(table);
        if (changesTable != null) applyTableTheme(changesTable);
        if (fieldsTable != null) applyTableTheme(fieldsTable);
    }

    private void applyTableTheme(JTable table) {
        ThemePalette palette = ThemePalette.forMode(dark);
        table.setBackground(palette.editor()); table.setForeground(palette.foreground());
        table.setGridColor(palette.border()); table.setSelectionBackground(palette.selection());
        table.setSelectionForeground(palette.foreground());
        table.getTableHeader().setBackground(palette.panel()); table.getTableHeader().setForeground(palette.foreground());
        table.setRowHeight(24);
        if (table == changesTable && table.getColumnModel().getColumnCount() > 0)
            table.getColumnModel().getColumn(0).setCellRenderer(new StatusCellRenderer(dark));
    }

    private void updateExpandedState() {
        body.setVisible(disclosure.isSelected());
        disclosure.setText(disclosure.isSelected() ? "▾ Comparación de filas" : "▸ Comparación de filas");
        revalidate(); repaint();
    }

    private void showSelectedChange(ListSelectionEvent event, RowDiff.Result rows) {
        if (event.getValueIsAdjusting() || changesTable == null || fieldsTable == null) return;
        int selected = changesTable.getSelectedRow();
        if (selected < 0 || selected >= rows.changes().size()) return;
        RowDiff.RowChange change = rows.changes().get(selected);
        List<RowDiff.Column> columns = rows.columns().stream()
                .filter(column -> change.status() != RowDiff.Status.DIFFERENT
                        || !Objects.deepEquals(change.source().get(column.name()), change.target().get(column.name())))
                .toList();
        Object[][] data = new Object[columns.size()][3];
        for (int i = 0; i < columns.size(); i++) {
            RowDiff.Column column = columns.get(i);
            data[i][0] = column.name();
            data[i][1] = change.source().containsKey(column.name()) ? displayValue(change.source().get(column.name())) : "(sin registro)";
            data[i][2] = change.target().containsKey(column.name()) ? displayValue(change.target().get(column.name())) : "(sin registro)";
        }
        fieldsTable.setModel(new DefaultTableModel(data, new String[]{"Campo", "Origen", "Destino"}) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        });
        fieldsTable.setDefaultRenderer(Object.class, new DifferenceCellRenderer(change.status(), dark));
        applyTableTheme(fieldsTable);
    }

    private static JPanel titledTable(String title, JTable table) {
        JPanel panel = new JPanel(new BorderLayout(0, 3));
        JLabel heading = new JLabel(title);
        heading.setFont(new Font("Segoe UI", Font.BOLD, 10));
        heading.setBorder(new EmptyBorder(2, 3, 2, 3));
        panel.add(heading, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private static String keyText(Map<String, Object> key) {
        return key.entrySet().stream().map(entry -> entry.getKey() + " = " + displayValue(entry.getValue()))
                .collect(Collectors.joining(", "));
    }

    private static String displayValue(Object value) {
        if (value == null) return "NULL";
        if (value instanceof byte[] bytes) return "0x" + java.util.HexFormat.of().formatHex(bytes);
        String text = value.toString();
        return text.length() > 512 ? text.substring(0, 509) + "…" : text;
    }

    private static long changedFieldCount(RowDiff.Result rows, RowDiff.RowChange change) {
        return rows.columns().stream().filter(column -> !Objects.deepEquals(change.source().get(column.name()), change.target().get(column.name()))).count();
    }

    private static final class DifferenceCellRenderer extends DefaultTableCellRenderer {
        private final RowDiff.Status status;
        private final boolean dark;

        private DifferenceCellRenderer(RowDiff.Status status, boolean dark) { this.status = status; this.dark = dark; }

        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                  boolean focus, int row, int column) {
            Component cell = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (!selected) {
                Color source = dark ? new Color(104, 48, 52) : new Color(255, 226, 226);
                Color target = dark ? new Color(35, 84, 66) : new Color(222, 246, 229);
                if (status == RowDiff.Status.SOURCE_ONLY) cell.setBackground(column == 1 ? target : column == 2 ? source : table.getBackground());
                else if (status == RowDiff.Status.TARGET_ONLY) cell.setBackground(column == 1 ? source : column == 2 ? target : table.getBackground());
                else cell.setBackground(column == 1 ? source : column == 2 ? target : table.getBackground());
            }
            return cell;
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
                if (status.equals("Solo en origen")) cell.setBackground(dark ? new Color(35, 84, 66) : new Color(222, 246, 229));
                else if (status.equals("Solo en destino")) cell.setBackground(dark ? new Color(104, 48, 52) : new Color(255, 226, 226));
                else cell.setBackground(dark ? new Color(91, 69, 34) : new Color(255, 241, 204));
            }
            return cell;
        }
    }
}
