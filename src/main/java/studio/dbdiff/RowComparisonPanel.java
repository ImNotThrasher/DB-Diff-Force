package studio.dbdiff;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.table.DefaultTableModel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.function.BiConsumer;
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
    private RowDiff.Result result;
    private boolean dark;

    RowComparisonPanel(BiConsumer<RowDiff.Result, ExportOptions> exportHandler) {
        super(new BorderLayout(0, 4));
        this.exportHandler = exportHandler;
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        disclosure.setHorizontalAlignment(JToggleButton.LEFT);
        disclosure.setIcon(UiIcons.themed(UiIcons.COMPARE_ROWS, 15));
        disclosure.setIconTextGap(6);
        disclosure.addActionListener(event -> updateExpandedState());
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
        body.setPreferredSize(new Dimension(100, 225));
        body.setBorder(new EmptyBorder(2, 4, 2, 4));
        body.setVisible(false);
        add(body, BorderLayout.CENTER);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                new Color(223, 228, 235)), new EmptyBorder(5, 0, 0, 0)));
        updateExpandedState();
    }

    void showResult(RowDiff.Result result) {
        this.result = result;
        String primaryKey = result.primaryKey().stream().map(RowDiff.Column::name).collect(Collectors.joining(", "));
        summary.setText(result.table().name() + " · " + result.matchedRows() + " claves coincidentes · "
                + result.sourceOnly() + " solo en origen · "
                + result.targetOnly() + " solo en destino · " + result.different() + " diferentes · PK: " + primaryKey);
        export.setEnabled(!result.changes().isEmpty());
        if (result.changes().isEmpty()) {
            JLabel empty = new JLabel("No se encontraron diferencias en las filas emparejadas.", JLabel.CENTER);
            empty.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            empty.setForeground(ThemePalette.forMode(dark).foreground());
            resultContent.removeAll(); resultContent.add(empty, BorderLayout.CENTER);
        } else {
            String[] headers = {"Estado", "Clave primaria", "Fila de origen", "Fila de destino"};
            Object[][] data = new Object[result.changes().size()][headers.length];
            for (int i = 0; i < result.changes().size(); i++) {
                RowDiff.RowChange change = result.changes().get(i);
                data[i][0] = switch (change.status()) {
                    case SOURCE_ONLY -> "Solo en origen";
                    case TARGET_ONLY -> "Solo en destino";
                    case DIFFERENT -> "Diferente";
                };
                data[i][1] = change.key(); data[i][2] = change.source(); data[i][3] = change.target();
            }
            JTable table = new JTable(new DefaultTableModel(data, headers) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            });
            table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
            table.setFillsViewportHeight(true);
            resultContent.removeAll(); resultContent.add(new JScrollPane(table), BorderLayout.CENTER);
            applyTableTheme(table);
        }
        if (result.truncated()) {
            JLabel warning = new JLabel("Resultados limitados a las primeras filas modificadas.");
            warning.setForeground(dark ? new Color(230, 174, 85) : new Color(155, 79, 21));
            resultContent.add(warning, BorderLayout.SOUTH);
        }
        disclosure.setSelected(true);
        updateExpandedState();
        resultContent.revalidate(); resultContent.repaint(); revalidate(); repaint();
    }

    void clear() {
        result = null;
        summary.setText("Seleccioná una tabla comparada y elegí Comparar filas.");
        resultContent.removeAll();
        export.setEnabled(false);
        disclosure.setSelected(false);
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
    }

    private void applyTableTheme(JTable table) {
        ThemePalette palette = ThemePalette.forMode(dark);
        table.setBackground(palette.editor()); table.setForeground(palette.foreground());
        table.setGridColor(palette.border()); table.setSelectionBackground(palette.selection());
        table.setSelectionForeground(palette.foreground());
        table.getTableHeader().setBackground(palette.panel()); table.getTableHeader().setForeground(palette.foreground());
        table.setRowHeight(24);
    }

    private void updateExpandedState() {
        body.setVisible(disclosure.isSelected());
        disclosure.setText(disclosure.isSelected() ? "▾ Comparación de filas" : "▸ Comparación de filas");
        revalidate(); repaint();
    }
}
