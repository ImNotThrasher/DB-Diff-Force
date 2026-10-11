package studio.dbdiff;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Window;
import java.util.List;
import java.util.Set;

/** Lets a user choose a composite row-matching key when a table has no primary key. */
final class RowComparisonKeyDialog {
    private RowComparisonKeyDialog() { }

    static List<String> show(Window owner, String table, List<RowDiff.Column> columns, DatabaseEngine engine,
                             List<String> currentKey) {
        Set<String> supported = engine == DatabaseEngine.SQLSERVER
                ? Set.of("tinyint", "smallint", "int", "bigint", "decimal", "numeric", "char", "varchar", "nchar", "nvarchar", "date", "datetime", "datetime2", "smalldatetime", "time", "bit")
                : Set.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint", "decimal", "numeric", "char", "varchar", "date", "datetime", "timestamp", "time", "year");
        List<RowDiff.Column> eligible = columns.stream()
                .filter(column -> supported.contains(column.type().toLowerCase(java.util.Locale.ROOT)))
                .filter(column -> !column.columnType().contains("length=-1"))
                .toList();
        if (eligible.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "La tabla no tiene columnas con tipos compatibles para usarlas como clave de comparación.",
                    "Sin columnas compatibles", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        JList<RowDiff.Column> choices = new JList<>(eligible.toArray(RowDiff.Column[]::new));
        choices.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        choices.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof RowDiff.Column column) {
                    setText(column.name() + "  ·  " + column.columnType() + (column.nullable() ? "  ·  admite NULL" : ""));
                }
                return this;
            }
        });
        if (currentKey != null && !currentKey.isEmpty()) {
            List<Integer> selectedIndices = new java.util.ArrayList<>();
            for (int index = 0; index < eligible.size(); index++) {
                String name = eligible.get(index).name();
                if (currentKey.stream().anyMatch(selected -> selected.equalsIgnoreCase(name))) selectedIndices.add(index);
            }
            choices.setSelectedIndices(selectedIndices.stream().mapToInt(Integer::intValue).toArray());
        }
        JScrollPane scroll = new JScrollPane(choices);
        scroll.setPreferredSize(new java.awt.Dimension(440, Math.min(320, Math.max(150, eligible.size() * 28))));
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(new javax.swing.JLabel("Elegí una o más columnas que identifiquen cada fila de " + table + "."), BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        content.add(new javax.swing.JLabel("Se comprobará que la combinación no tenga NULL ni duplicados en ambos extremos."), BorderLayout.SOUTH);
        while (true) {
            int answer = JOptionPane.showConfirmDialog(owner, content, "Clave para comparar filas",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return null;
            List<String> selected = choices.getSelectedValuesList().stream().map(RowDiff.Column::name).toList();
            if (!selected.isEmpty()) return selected;
            JOptionPane.showMessageDialog(owner, RowComparisonMessages.customKeyRequired(), "Clave requerida", JOptionPane.WARNING_MESSAGE);
        }
    }
}
