package studio.dbdiff;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;

public final class Main {
    private final JFrame frame = new JFrame("DB-Diff Studio");
    private final DefaultListModel<Object> resultModel = new DefaultListModel<>();
    private final JList<Object> resultList = new JList<>(resultModel);
    private final JTextArea detail = area(), diff = area();
    private final JLabel connectionStatus = new JLabel("Sin conexión");
    private final JComboBox<SchemaObject.Kind> kindFilter = new JComboBox<>(SchemaObject.Kind.values());
    private final EnumSet<SchemaObject.Kind> selectedKinds = EnumSet.allOf(SchemaObject.Kind.class);
    private DatabaseConnector source, target;
    private String targetDatabase = "";
    private List<SchemaObject> sourceObjects = List.of(), targetObjects = List.of();
    private boolean compared;
    private final Set<SchemaObject.Key> comparedKeys = new TreeSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "schema-reader"); t.setDaemon(true); return t; });

    private Main() { }
    public static void main(String[] args) { SwingUtilities.invokeLater(() -> new Main().show()); }

    private void show() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { close(); } });
        frame.setMinimumSize(new Dimension(1020, 680)); frame.setSize(1260, 820); frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout(0, 0)); frame.add(header(), BorderLayout.NORTH); frame.add(content(), BorderLayout.CENTER); frame.add(statusBar(), BorderLayout.SOUTH);
        frame.setVisible(true);
    }

    private JComponent header() {
        JPanel panel = new JPanel(new BorderLayout(16, 0)); panel.setBackground(new Color(23, 36, 54)); panel.setBorder(new EmptyBorder(16, 22, 16, 20));
        JPanel brand = new JPanel(); brand.setOpaque(false); brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("DB-Diff Studio"); title.setForeground(Color.WHITE); title.setFont(new Font("Segoe UI", Font.BOLD, 21));
        JLabel sub = new JLabel("Compará esquemas. Revisá los cambios. Exportá con seguridad."); sub.setForeground(new Color(184, 198, 216)); sub.setFont(new Font("Segoe UI", Font.PLAIN, 12)); brand.add(title); brand.add(Box.createVerticalStrut(3)); brand.add(sub);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4)); actions.setOpaque(false);
        JButton connect = button("Conectar bases", true); connect.addActionListener(e -> connect());
        JButton compare = button("Comparar", false); compare.addActionListener(e -> compare());
        JButton rows = button("Comparar filas", false); rows.addActionListener(e -> compareRows());
        JButton export = button("Exportar SQL", false); export.addActionListener(e -> export());
        actions.add(connect); actions.add(compare); actions.add(rows); actions.add(export); panel.add(brand, BorderLayout.WEST); panel.add(actions, BorderLayout.EAST); return panel;
    }

    private JComponent content() {
        JPanel split = new JPanel(new BorderLayout(0, 0));
        JPanel left = new JPanel(new BorderLayout(0, 10)); left.setBorder(new EmptyBorder(16, 16, 16, 12)); left.setPreferredSize(new Dimension(370, 100)); left.setBackground(new Color(246, 248, 251));
        JPanel controls = new JPanel(new BorderLayout(6, 0)); controls.setOpaque(false);
        JLabel objects = new JLabel("OBJETOS"); objects.setFont(new Font("Segoe UI", Font.BOLD, 11)); objects.setForeground(new Color(84, 99, 119));
        JButton kinds = new JButton("Tipos ▾"); kinds.addActionListener(e -> kindMenu(kinds)); controls.add(objects, BorderLayout.WEST); controls.add(kinds, BorderLayout.EAST);
        resultList.setCellRenderer(new ResultRenderer()); resultList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); resultList.addListSelectionListener(this::selectResult);
        JScrollPane listScroll = new JScrollPane(resultList); listScroll.setBorder(BorderFactory.createLineBorder(new Color(223, 228, 235))); listScroll.getViewport().setBackground(Color.WHITE);
        JLabel legend = new JLabel("  Verde  idéntico     Ámbar  modificado     Azul / gris  faltante"); legend.setFont(new Font("Segoe UI", Font.PLAIN, 10)); legend.setForeground(new Color(105, 118, 136));
        left.add(controls, BorderLayout.NORTH); left.add(listScroll, BorderLayout.CENTER); left.add(legend, BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout(0, 0)); right.setBorder(new EmptyBorder(15, 12, 15, 16)); right.setBackground(Color.WHITE);
        JSplitPane textSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, titled("Definición del objeto / esquema", detail), titled("Diferencias por línea  ·  origen → destino", diff)); textSplit.setResizeWeight(.47); textSplit.setBorder(null); textSplit.setDividerSize(8);
        right.add(textSplit, BorderLayout.CENTER);
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right); mainSplit.setResizeWeight(.29); mainSplit.setDividerLocation(360); mainSplit.setBorder(null); split.add(mainSplit, BorderLayout.CENTER);
        return split;
    }

    private JComponent statusBar() { JPanel p = new JPanel(new BorderLayout()); p.setBorder(new EmptyBorder(7, 16, 7, 16)); p.setBackground(new Color(247, 249, 252)); p.add(connectionStatus); connectionStatus.setFont(new Font("Segoe UI", Font.PLAIN, 11)); connectionStatus.setForeground(new Color(88, 101, 120)); return p; }
    private static JTextArea area() { JTextArea a = new JTextArea(); a.setEditable(false); a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); a.setBackground(new Color(251, 252, 254)); a.setBorder(new EmptyBorder(12, 12, 12, 12)); a.setLineWrap(false); return a; }
    private static JComponent titled(String title, JTextArea text) { JPanel p = new JPanel(new BorderLayout()); JLabel label = new JLabel(title); label.setFont(new Font("Segoe UI", Font.BOLD, 12)); label.setBorder(new EmptyBorder(0, 0, 9, 0)); p.add(label, BorderLayout.NORTH); JScrollPane scroll = new JScrollPane(text); scroll.setBorder(BorderFactory.createLineBorder(new Color(226, 231, 238))); p.add(scroll, BorderLayout.CENTER); return p; }
    private static JButton button(String name, boolean primary) { JButton b = new JButton(name); b.setFont(new Font("Segoe UI", Font.BOLD, 12)); if (primary) { b.setBackground(new Color(61, 117, 207)); b.setForeground(Color.WHITE); b.setFocusPainted(false); } return b; }

    private void kindMenu(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        for (SchemaObject.Kind kind : SchemaObject.Kind.values()) { JCheckBoxMenuItem item = new JCheckBoxMenuItem(kind.toString(), selectedKinds.contains(kind)); item.addActionListener(e -> { if (item.isSelected()) selectedKinds.add(kind); else selectedKinds.remove(kind); refreshResults(); }); menu.add(item); }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void connect() {
        EndpointDialog dialog = new EndpointDialog(frame); dialog.setVisible(true); if (dialog.endpoints == null) return;
        Endpoint[] endpoints = dialog.endpoints;
        setBusy(true, "Conectando y leyendo las listas de objetos…");
        worker.submit(() -> {
            DatabaseConnector a = DatabaseConnectorFactory.create(endpoints[0]), b = DatabaseConnectorFactory.create(endpoints[1]);
            try {
                a.connect(endpoints[0]); b.connect(endpoints[1]);
                List<SchemaObject> ao = a.listObjects(), bo = b.listObjects();
                SwingUtilities.invokeLater(() -> { closeConnectors(); source = a; target = b; targetDatabase = endpoints[1].database(); sourceObjects = ao; targetObjects = bo; compared = false; comparedKeys.clear(); showCatalog(); setBusy(false, "Conectado · Origen: " + endpoints[0].database() + " · Destino: " + endpoints[1].database() + " · " + ao.size() + " / " + bo.size() + " objetos encontrados · sin comparar"); });
            } catch (SQLException | RuntimeException ex) { a.close(); b.close(); SwingUtilities.invokeLater(() -> { setBusy(false, "Error de conexión"); error("No se pudo conectar", ex); }); }
            finally { endpoints[0].clearPassword(); endpoints[1].clearPassword(); }
        });
    }

    private void compare() {
        if (source == null || target == null) { JOptionPane.showMessageDialog(frame, "Conectá ambas bases antes de comparar.", "Bases sin conexión", JOptionPane.INFORMATION_MESSAGE); return; }
        Set<SchemaObject.Key> selectedKeys = selectedObjectKeys();
        setBusy(true, "Comparando los tipos de objetos seleccionados…");
        worker.submit(() -> {
            try {
                List<SchemaObject> a = loadSelected(source, sourceObjects, selectedKinds, selectedKeys), b = loadSelected(target, targetObjects, selectedKinds, selectedKeys);
                List<DiffEngine.Result> results = new DiffEngine().compare(a, b, selectedKinds, selectedKeys);
                SwingUtilities.invokeLater(() -> { sourceObjects = mergeLoaded(sourceObjects, a); targetObjects = mergeLoaded(targetObjects, b); compared = true; results.forEach(r -> comparedKeys.add(r.key())); resultModel.clear(); results.forEach(resultModel::addElement); detail.setText(""); diff.setText(""); setBusy(false, "Comparación lista · " + results.size() + " objetos · " + countChanged(results) + " con cambios"); });
            } catch (SQLException ex) { SwingUtilities.invokeLater(() -> { setBusy(false, "Error al comparar"); error("No se pudieron leer las definiciones del esquema", ex); }); }
        });
    }

    private static List<SchemaObject> loadSelected(DatabaseConnector connector, List<SchemaObject> catalog, Set<SchemaObject.Kind> kinds, Set<SchemaObject.Key> selected) throws SQLException {
        List<SchemaObject> loaded = new ArrayList<>();
        for (SchemaObject object : catalog) if (kinds.contains(object.key().kind()) && (selected.isEmpty() || selected.contains(object.key()))) loaded.add(connector.loadObject(object.key()));
        return loaded;
    }

    private void refreshResults() {
        if (!compared) { showCatalog(); return; }
        List<SchemaObject> left = sourceObjects.stream().filter(o -> comparedKeys.contains(o.key())).toList();
        List<SchemaObject> right = targetObjects.stream().filter(o -> comparedKeys.contains(o.key())).toList();
        List<DiffEngine.Result> values = new DiffEngine().compare(left, right, selectedKinds, comparedKeys); resultModel.clear(); values.forEach(resultModel::addElement);
    }

    private void showCatalog() {
        resultModel.clear();
        TreeMap<SchemaObject.Key, SchemaObject.Key> keys = new TreeMap<>();
        sourceObjects.forEach(o -> keys.put(o.key(), o.key())); targetObjects.forEach(o -> keys.put(o.key(), o.key()));
        for (SchemaObject.Key key : keys.keySet()) if (selectedKinds.contains(key.kind())) resultModel.addElement(new CatalogItem(key, sourceObjects.stream().anyMatch(o -> o.key().equals(key)), targetObjects.stream().anyMatch(o -> o.key().equals(key))));
        detail.setText("Objetos encontrados. Seleccioná uno o más y elegí Comparar.\nTodavía no se compararon las definiciones."); diff.setText("");
    }

    private Set<SchemaObject.Key> selectedObjectKeys() {
        Set<SchemaObject.Key> keys = new TreeSet<>();
        for (Object item : resultList.getSelectedValuesList()) {
            if (item instanceof CatalogItem catalog) keys.add(catalog.key());
            else if (item instanceof DiffEngine.Result result) keys.add(result.key());
        }
        return keys;
    }

    private static List<SchemaObject> mergeLoaded(List<SchemaObject> catalog, List<SchemaObject> loaded) {
        Map<SchemaObject.Key, SchemaObject> merged = new TreeMap<>(); catalog.forEach(o -> merged.put(o.key(), o)); loaded.forEach(o -> merged.put(o.key(), o)); return List.copyOf(merged.values());
    }

    private void selectResult(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) return;
        if (resultList.getSelectedValuesList().size() > 1) { detail.setText("Seleccionaste varios objetos. Elegí Comparar para analizarlos o seleccioná un resultado para ver sus detalles."); diff.setText(""); return; }
        Object item = resultList.getSelectedValue();
        if (item instanceof CatalogItem catalog) { detail.setText(catalog.key() + "\nEstado: SIN COMPARAR\nOrigen: " + (catalog.sourcePresent() ? "presente" : "faltante") + "\nDestino: " + (catalog.targetPresent() ? "presente" : "faltante") + "\n\nElegí Comparar para revisar las definiciones."); diff.setText(""); return; }
        if (!(item instanceof DiffEngine.Result selected)) return;
        StringBuilder info = new StringBuilder(selected.key() + "\nEstado: " + statusName(selected.status()) + "\n\n");
        if (selected.source() != null) info.append("ORIGEN\n").append(selected.source().definition()).append("\n\n");
        if (selected.target() != null) info.append("DESTINO\n").append(selected.target().definition());
        detail.setText(info.toString()); diff.setText(String.join("\n", selected.diffLines()));
    }

    private void compareRows() {
        List<Object> selectedItems = resultList.getSelectedValuesList();
        if (source == null || target == null) { JOptionPane.showMessageDialog(frame, "Conectá ambas bases antes de comparar filas.", "Bases sin conexión", JOptionPane.INFORMATION_MESSAGE); return; }
        if (selectedItems.size() != 1 || !(selectedItems.get(0) instanceof DiffEngine.Result selected) || selected.key().kind() != SchemaObject.Kind.TABLE || selected.source() == null || selected.target() == null) {
            JOptionPane.showMessageDialog(frame, "Seleccioná una tabla que exista en ambas bases.", "Elegí una tabla comparable", JOptionPane.INFORMATION_MESSAGE); return;
        }
        setBusy(true, "Leyendo las filas de " + selected.key().name() + "…");
        worker.submit(() -> {
            try {
                RowDiff.Result rows = source.compareTableRows(target, selected.key(), 10000);
                SwingUtilities.invokeLater(() -> { setBusy(false, "Comparación de filas lista · " + rows.sourceOnly() + " solo en origen · " + rows.targetOnly() + " solo en destino · " + rows.different() + " diferentes" + (rows.truncated() ? " · resultados limitados" : "")); showRowResults(rows); });
            } catch (SQLException | RuntimeException ex) { SwingUtilities.invokeLater(() -> { setBusy(false, "No se compararon las filas"); error("No se pudieron comparar las filas", ex); }); }
        });
    }

    private void showRowResults(RowDiff.Result rows) {
        String[] headers = {"Estado", "Clave primaria", "Fila de origen", "Fila de destino"};
        Object[][] data = new Object[rows.changes().size()][headers.length];
        for (int i = 0; i < rows.changes().size(); i++) {
            RowDiff.RowChange change = rows.changes().get(i);
            data[i][0] = change.status(); data[i][1] = change.key(); data[i][2] = change.source(); data[i][3] = change.target();
        }
        JTable table = new JTable(data, headers); table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN); table.setFillsViewportHeight(true);
        JLabel summary = new JLabel("  " + rows.table().name() + "  ·  " + rows.sourceOnly() + " solo en origen  ·  " + rows.targetOnly() + " solo en destino  ·  " + rows.different() + " diferentes  ·  clave primaria: " + rows.primaryKey().stream().map(RowDiff.Column::name).collect(java.util.stream.Collectors.joining(", ")));
        JPanel panel = new JPanel(new BorderLayout(0, 8)); panel.add(summary, BorderLayout.NORTH); panel.add(new JScrollPane(table), BorderLayout.CENTER);
        if (rows.truncated()) { JLabel warning = new JLabel("Se conservan las primeras 10.000 filas modificadas. La exportación incluirá solo esas filas."); warning.setForeground(new Color(155, 79, 21)); panel.add(warning, BorderLayout.SOUTH); }
        panel.setPreferredSize(new Dimension(980, 560));
        JCheckBox inserts = new JCheckBox("INSERT: filas solo en origen", true), updates = new JCheckBox("UPDATE: filas diferentes", true), deletes = new JCheckBox("DELETE: filas solo en destino", false);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT)); options.add(inserts); options.add(updates); options.add(deletes);
        JButton save = button("Exportar SQL de filas…", true); save.addActionListener(e -> saveRowSql(rows, inserts.isSelected(), updates.isSelected(), deletes.isSelected()));
        JPanel controls = new JPanel(new BorderLayout()); controls.add(options, BorderLayout.CENTER); controls.add(save, BorderLayout.EAST);
        JPanel content = new JPanel(new BorderLayout(0, 8)); content.add(panel, BorderLayout.CENTER); content.add(controls, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(frame, content, "Comparación de filas", JOptionPane.PLAIN_MESSAGE);
    }

    private void saveRowSql(RowDiff.Result rows, boolean inserts, boolean updates, boolean deletes) {
        JTextArea preview = area(); preview.setText(RowSqlExporter.export(rows, inserts, updates, deletes)); preview.setCaretPosition(0);
        JCheckBox insertChoice = new JCheckBox("INSERT: filas solo en origen", inserts), updateChoice = new JCheckBox("UPDATE: filas diferentes", updates), deleteChoice = new JCheckBox("DELETE: filas solo en destino", deletes);
        deleteChoice.setForeground(new Color(155, 79, 21));
        Runnable refresh = () -> preview.setText(RowSqlExporter.export(rows, insertChoice.isSelected(), updateChoice.isSelected(), deleteChoice.isSelected()));
        insertChoice.addActionListener(e -> refresh.run()); updateChoice.addActionListener(e -> refresh.run()); deleteChoice.addActionListener(e -> refresh.run());
        JPanel choices = new JPanel(new FlowLayout(FlowLayout.LEFT)); choices.add(insertChoice); choices.add(updateChoice); choices.add(deleteChoice);
        JPanel wrapper = new JPanel(new BorderLayout(8, 8)); wrapper.add(choices, BorderLayout.NORTH); wrapper.add(new JScrollPane(preview), BorderLayout.CENTER);
        JLabel warning = new JLabel("Solo se exporta; nunca se ejecuta. DELETE requiere habilitarse. Revisá cada instrucción."); warning.setForeground(new Color(155, 79, 21)); wrapper.add(warning, BorderLayout.SOUTH); wrapper.setPreferredSize(new Dimension(820, 520));
        if (JOptionPane.showConfirmDialog(frame, wrapper, "Revisar SQL de filas", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("db-diff-exportacion-filas.sql"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        try { Files.writeString(chooser.getSelectedFile().toPath(), preview.getText(), StandardCharsets.UTF_8); JOptionPane.showMessageDialog(frame, "Se guardó el SQL de filas. No se ejecutó.", "Exportación lista", JOptionPane.INFORMATION_MESSAGE); }
        catch (IOException ex) { error("No se pudo guardar la exportación de filas", ex); }
    }

    private void export() {
        List<DiffEngine.Result> chosen = resultList.getSelectedValuesList().stream().filter(DiffEngine.Result.class::isInstance).map(DiffEngine.Result.class::cast).toList();
        if (chosen.isEmpty()) { JOptionPane.showMessageDialog(frame, "Seleccioná uno o más resultados comparados para exportar. Los objetos no seleccionados no se exportan.", "Elegí resultados para exportar", JOptionPane.INFORMATION_MESSAGE); return; }
        final List<DiffEngine.Result> exportResults = List.copyOf(chosen);
        EnumSet<DiffEngine.Status> ops = EnumSet.of(DiffEngine.Status.CHANGED, DiffEngine.Status.SOURCE_ONLY, DiffEngine.Status.TARGET_ONLY);
        String sql = SqlExporter.export(chosen, ops, targetDatabase);
        JTextArea preview = area(); preview.setText(sql); preview.setCaretPosition(0);
        JCheckBox[] checks = new JCheckBox[3]; JPanel choices = new JPanel(new GridLayout(0, 1));
        DiffEngine.Status[] statuses = {DiffEngine.Status.SOURCE_ONLY, DiffEngine.Status.CHANGED, DiffEngine.Status.TARGET_ONLY};
        String[] labels = {"CREATE: objetos solo en origen", "ALTER / reemplazar: objetos modificados", "DROP: objetos solo en destino"};
        for (int i = 0; i < checks.length; i++) {
            checks[i] = new JCheckBox(labels[i], i != 2);
            final DiffEngine.Status status = statuses[i];
            final JCheckBox check = checks[i];
            checks[i].addActionListener(e -> {
                if (check.isSelected()) ops.add(status); else ops.remove(status);
                preview.setText(SqlExporter.export(exportResults, ops, targetDatabase));
            });
            choices.add(checks[i]);
        }
        JPanel wrapper = new JPanel(new BorderLayout(8, 8)); wrapper.add(choices, BorderLayout.NORTH); wrapper.add(new JScrollPane(preview), BorderLayout.CENTER); wrapper.setPreferredSize(new Dimension(720, 500));
        JLabel warning = new JLabel("Solo se exporta. DB-Diff Studio nunca ejecuta este SQL. Revisá cada instrucción antes de usarla."); warning.setForeground(new Color(155, 79, 21)); wrapper.add(warning, BorderLayout.SOUTH);
        int answer = JOptionPane.showConfirmDialog(frame, wrapper, "Revisar exportación SQL", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("db-diff-exportacion.sql")); if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        try { Files.writeString(chooser.getSelectedFile().toPath(), preview.getText(), StandardCharsets.UTF_8); JOptionPane.showMessageDialog(frame, "Se guardó el SQL. No se ejecutó.", "Exportación lista", JOptionPane.INFORMATION_MESSAGE); }
        catch (IOException ex) { error("No se pudo guardar la exportación", ex); }
    }
    private void setBusy(boolean busy, String text) { connectionStatus.setText(text); frame.setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor()); }
    private static long countChanged(List<DiffEngine.Result> values) { return values.stream().filter(r -> r.status() != DiffEngine.Status.IDENTICAL).count(); }
    private void error(String title, Exception ex) { JOptionPane.showMessageDialog(frame, ex.getMessage() == null ? ex.toString() : ex.getMessage(), title, JOptionPane.ERROR_MESSAGE); }
    private void closeConnectors() { if (source != null) source.close(); if (target != null) target.close(); source = target = null; }
    private void close() { closeConnectors(); worker.shutdownNow(); frame.dispose(); }

    private static final class ResultRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof CatalogItem catalog) {
                label.setText("  ○  " + catalog.key().name() + "   ·   " + kindName(catalog.key().kind()) + "   ·   sin comparar");
                if (!selected) label.setForeground(new Color(105, 118, 136));
            } else if (value instanceof DiffEngine.Result result) {
                String marker = switch (result.status()) { case IDENTICAL -> "●"; case CHANGED -> "●"; case SOURCE_ONLY -> "◀"; case TARGET_ONLY -> "▶"; };
                label.setText("  " + marker + "  " + result.key().name() + "   ·   " + kindName(result.key().kind()));
                if (!selected) label.setForeground(switch (result.status()) { case IDENTICAL -> new Color(49, 130, 97); case CHANGED -> new Color(178, 112, 24); case SOURCE_ONLY -> new Color(56, 112, 184); case TARGET_ONLY -> new Color(111, 122, 140); });
            }
            label.setBorder(new EmptyBorder(10, 8, 10, 8)); return label;
        }
    }

    private record CatalogItem(SchemaObject.Key key, boolean sourcePresent, boolean targetPresent) {
        @Override public String toString() { return key.name() + " · " + kindName(key.kind()) + " · sin comparar"; }
    }

    private static String kindName(SchemaObject.Kind kind) {
        return switch (kind) { case TABLE -> "tabla"; case VIEW -> "vista"; case PROCEDURE -> "procedimiento"; case FUNCTION -> "función"; };
    }

    private static String statusName(DiffEngine.Status status) {
        return switch (status) { case IDENTICAL -> "idéntico"; case CHANGED -> "modificado"; case SOURCE_ONLY -> "solo en origen"; case TARGET_ONLY -> "solo en destino"; };
    }

    private static final class EndpointDialog extends JDialog {
        private Endpoint[] endpoints;
        private final JTextField[][] fields = new JTextField[2][3];
        private final JPasswordField[] passwords = {new JPasswordField(15), new JPasswordField(15)};
        private final JCheckBox[] tls = {new JCheckBox("Use TLS and verify server certificate"), new JCheckBox("Use TLS and verify server certificate")};
        private final JComboBox<DatabaseEngine> engine = new JComboBox<>(DatabaseEngine.values());
        private final List<JComboBox<String>> databases = List.of(new JComboBox<>(), new JComboBox<>());
        private final List<JComboBox<String>> savedChoices = List.of(new JComboBox<>(), new JComboBox<>());
        private final JButton[] testButtons = new JButton[2];
        private final JLabel[] testStatus = new JLabel[2];
        private final Map<String, ConnectionProfile> savedProfiles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final boolean[] changingSavedChoice = new boolean[2];
        private final boolean[] updatingDatabaseChoice = new boolean[2];
        private final boolean[] testedSuccessfully = new boolean[2];
        private final int[] testGenerations = new int[2];
        private final String[] preferredDatabase = new String[2];
        private boolean autoConnectRequested;

        EndpointDialog(JFrame owner) {
            super(owner, "Conectar bases", true); setLayout(new BorderLayout(10, 10)); JPanel form = new JPanel(new GridBagLayout()); form.setBorder(new EmptyBorder(16, 20, 10, 20));
            engine.addActionListener(e -> {
                String port = Integer.toString(((DatabaseEngine) engine.getSelectedItem()).defaultPort());
                for (int i = 0; i < 2; i++) { fields[i][1].setText(port); invalidateTest(i); }
            });
            GridBagConstraints engineConstraints = new GridBagConstraints(); engineConstraints.insets = new Insets(5, 6, 5, 6); engineConstraints.anchor = GridBagConstraints.WEST; engineConstraints.gridx = 0; engineConstraints.gridy = 0;
            form.add(new JLabel("Motor de base de datos"), engineConstraints); engineConstraints.gridx = 1; engineConstraints.gridwidth = 3; form.add(engine, engineConstraints);
            try { for (ConnectionProfile profile : ConnectionProfiles.load()) savedProfiles.put(profile.name(), profile); }
            catch (IOException ex) { JOptionPane.showMessageDialog(owner, "No se pudieron cargar los perfiles guardados: " + ex.getMessage(), "Perfiles no disponibles", JOptionPane.WARNING_MESSAGE); }
            String[] labels = {"Servidor", "Puerto", "Usuario"}; String[] defaults = {"127.0.0.1", "3306", ""};
            for (int side = 0; side < 2; side++) {
                GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(5, 6, 5, 6); c.anchor = GridBagConstraints.WEST;
                c.gridx = side * 2; c.gridy = 1; c.gridwidth = 2; JLabel header = new JLabel(side == 0 ? "BASE DE ORIGEN" : "BASE DE DESTINO"); header.setFont(new Font("Segoe UI", Font.BOLD, 11)); form.add(header, c);
                c.gridy = 2; c.gridwidth = 1; c.weightx = 0; form.add(new JLabel("Perfil"), c);
                c.gridx = side * 2 + 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
                JComboBox<String> savedChoice = savedChoices.get(side); savedChoice.setPrototypeDisplayValue("Elegí un perfil guardado"); refreshSavedChoices(side, null);
                JButton saveProfile = new JButton("Guardar"); JButton deleteProfile = new JButton("Eliminar");
                JPanel profileControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0)); profileControls.add(saveProfile); profileControls.add(deleteProfile);
                JPanel profileRow = new JPanel(new BorderLayout(4, 0)); profileRow.add(savedChoice, BorderLayout.CENTER); profileRow.add(profileControls, BorderLayout.EAST);
                c.gridx = side * 2 + 1; c.gridwidth = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(profileRow, c);
                for (int row = 0; row < labels.length; row++) {
                    c.gridy = row + 3; c.gridwidth = 1; c.gridx = side * 2; c.weightx = 0; c.fill = GridBagConstraints.NONE; form.add(new JLabel(labels[row]), c);
                    c.gridx = side * 2 + 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; JTextField f = new JTextField(defaults[row], 15); fields[side][row] = f; form.add(f, c);
                }
                c.gridy = 6; c.gridx = side * 2; c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE; form.add(new JLabel("Base de datos"), c);
                c.gridx = side * 2 + 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
                JComboBox<String> database = databases.get(side); database.setEnabled(false); database.setPrototypeDisplayValue("Elegí una base de datos"); form.add(database, c);
                c.gridy = 7; c.gridx = side * 2; c.weightx = 0; c.fill = GridBagConstraints.NONE; form.add(new JLabel("Contraseña"), c);
                c.gridx = side * 2 + 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; passwords[side].setEchoChar('•'); form.add(passwords[side], c);
                c.gridy = 8; c.gridx = side * 2; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(tls[side], c);
                testButtons[side] = new JButton("Probar conexión"); testStatus[side] = new JLabel("Sin probar"); testStatus[side].setForeground(new Color(105, 118, 136));
                JPanel testRow = new JPanel(new BorderLayout(8, 0)); testRow.add(testButtons[side], BorderLayout.WEST); testRow.add(testStatus[side], BorderLayout.CENTER);
                c.gridy = 9; c.gridx = side * 2; c.gridwidth = 2; form.add(testRow, c);
                final int endpointIndex = side; testButtons[side].addActionListener(e -> testConnection(endpointIndex));
                savedChoice.addActionListener(e -> {
                    String name = (String) savedChoice.getSelectedItem();
                    if (!changingSavedChoice[endpointIndex] && name != null && !name.equals("Elegí un perfil guardado")) applyProfile(endpointIndex, savedProfiles.get(name));
                });
                databases.get(side).addActionListener(e -> { if (!updatingDatabaseChoice[endpointIndex]) finishAutomaticConnect(); });
                saveProfile.addActionListener(e -> saveProfile(endpointIndex));
                deleteProfile.addActionListener(e -> deleteProfile(endpointIndex));
                observeChanges(side);
            }
            JLabel secure = new JLabel("Las contraseñas nunca se guardan; los perfiles conservan servidor, base, usuario y TLS. Se recomienda TLS para conexiones remotas."); secure.setBorder(new EmptyBorder(0, 20, 0, 20));
            JButton cancel = new JButton("Cancelar"), connect = button("Conectar", true); JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT)); buttons.add(cancel); buttons.add(connect);
            cancel.addActionListener(e -> { autoConnectRequested = false; dispose(); }); connect.addActionListener(e -> requestConnect()); add(form, BorderLayout.CENTER); add(secure, BorderLayout.NORTH); add(buttons, BorderLayout.SOUTH); pack(); setLocationRelativeTo(owner);
        }

        private void refreshSavedChoices(int side, String selectedName) {
            JComboBox<String> choice = savedChoices.get(side); changingSavedChoice[side] = true;
            choice.removeAllItems(); choice.addItem("Elegí un perfil guardado");
            savedProfiles.keySet().forEach(choice::addItem);
            choice.setSelectedItem(selectedName == null ? "Elegí un perfil guardado" : selectedName);
            choice.setEnabled(!savedProfiles.isEmpty()); changingSavedChoice[side] = false;
        }

        private void applyProfile(int side, ConnectionProfile profile) {
            if (profile == null) return;
            engine.setSelectedItem(profile.engine());
            fields[side][0].setText(profile.host()); fields[side][1].setText(Integer.toString(profile.port())); fields[side][2].setText(profile.user());
            passwords[side].setText(""); tls[side].setSelected(profile.useTls());
            updatingDatabaseChoice[side] = true; databases.get(side).removeAllItems(); databases.get(side).setEnabled(false); updatingDatabaseChoice[side] = false;
            preferredDatabase[side] = profile.database(); invalidateTest(side);
        }

        private void saveProfile(int side) {
            if (!testedSuccessfully[side] || databases.get(side).getSelectedItem() == null) {
                JOptionPane.showMessageDialog(this, "Probá la conexión y elegí una base de datos antes de guardar el perfil.", "Conexión incompleta", JOptionPane.INFORMATION_MESSAGE); return;
            }
            String selectedName = (String) savedChoices.get(side).getSelectedItem();
            if (selectedName == null || selectedName.equals("Elegí un perfil guardado")) selectedName = "";
            String initialName = selectedName.isBlank() ? fields[side][0].getText().trim() + " / " + databases.get(side).getSelectedItem() : selectedName;
            String name = JOptionPane.showInputDialog(this, "Nombre del perfil de conexión:", initialName);
            if (name == null || name.isBlank()) return;
            try {
                ConnectionProfile profile = new ConnectionProfile(name.trim(), (DatabaseEngine) engine.getSelectedItem(),
                        fields[side][0].getText().trim(), Integer.parseInt(fields[side][1].getText().trim()),
                        (String) databases.get(side).getSelectedItem(), fields[side][2].getText().trim(), tls[side].isSelected());
                ConnectionProfiles.save(profile); savedProfiles.put(profile.name(), profile); refreshSavedChoices(side, profile.name());
            } catch (IOException | RuntimeException ex) {
                JOptionPane.showMessageDialog(this, "No se pudo guardar el perfil: " + ex.getMessage(), "Error al guardar", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void deleteProfile(int side) {
            String name = (String) savedChoices.get(side).getSelectedItem();
            if (name == null || name.equals("Elegí un perfil guardado") || !savedProfiles.containsKey(name)) {
                JOptionPane.showMessageDialog(this, "Elegí un perfil guardado para eliminar.", "No hay un perfil seleccionado", JOptionPane.INFORMATION_MESSAGE); return;
            }
            if (JOptionPane.showConfirmDialog(this, "¿Eliminar el perfil de conexión '" + name + "'?", "Eliminar perfil", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            try { ConnectionProfiles.remove(name); savedProfiles.remove(name); refreshSavedChoices(side, null); }
            catch (IOException ex) { JOptionPane.showMessageDialog(this, "No se pudo eliminar el perfil: " + ex.getMessage(), "Error al eliminar", JOptionPane.ERROR_MESSAGE); }
        }

        private void observeChanges(int side) {
            javax.swing.event.DocumentListener listener = new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { invalidateTest(side); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { invalidateTest(side); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { invalidateTest(side); }
            };
            for (JTextField field : fields[side]) field.getDocument().addDocumentListener(listener);
            passwords[side].getDocument().addDocumentListener(listener);
            tls[side].addActionListener(e -> invalidateTest(side));
        }

        private void invalidateTest(int side) {
            JComboBox<String> database = databases.get(side);
            Object selectedDatabase = database.getSelectedItem(); if (selectedDatabase != null) preferredDatabase[side] = selectedDatabase.toString();
            updatingDatabaseChoice[side] = true; database.removeAllItems(); database.setEnabled(false); updatingDatabaseChoice[side] = false;
            testedSuccessfully[side] = false; testGenerations[side]++;
            if (testStatus[side] != null) { testStatus[side].setText("Probar nuevamente"); testStatus[side].setForeground(new Color(105, 118, 136)); }
        }

        private void requestConnect() {
            autoConnectRequested = true;
            for (int side = 0; side < 2; side++) if (!testedSuccessfully[side] && testButtons[side].isEnabled()) testConnection(side);
            finishAutomaticConnect();
        }

        private void finishAutomaticConnect() {
            if (!autoConnectRequested || !isDisplayable()) return;
            for (JButton testButton : testButtons) if (!testButton.isEnabled()) return;
            if (!testedSuccessfully[0] || !testedSuccessfully[1]) { autoConnectRequested = false; return; }
            for (int side = 0; side < 2; side++) {
                if (databases.get(side).getItemCount() == 0) { autoConnectRequested = false; return; }
                if (databases.get(side).getSelectedItem() == null) {
            testStatus[side].setText("Elegí una base para continuar"); testStatus[side].setForeground(new Color(178, 112, 24)); return;
                }
            }
            autoConnectRequested = false; accept();
        }

        private void testConnection(int side) {
            if (!testButtons[side].isEnabled()) return;
            try {
                DatabaseEngine selectedEngine = (DatabaseEngine) engine.getSelectedItem();
                String host = fields[side][0].getText().trim();
                int port = Integer.parseInt(fields[side][1].getText().trim());
                String user = fields[side][2].getText().trim();
                char[] typedPassword = passwords[side].getPassword();
                Endpoint endpoint;
                try { endpoint = new Endpoint(selectedEngine, host, port, "", user, typedPassword, tls[side].isSelected()); }
                finally { Arrays.fill(typedPassword, '\0'); }

                testButtons[side].setEnabled(false); invalidateTest(side); testStatus[side].setText("Probando conexión…");
                int testGeneration = testGenerations[side];
                new SwingWorker<List<String>, Void>() {
                    @Override protected List<String> doInBackground() throws Exception {
                        DatabaseConnector connector = null;
                        try { connector = DatabaseConnectorFactory.create(endpoint); connector.connect(endpoint); return connector.listDatabases(); }
                        finally { if (connector != null) connector.close(); endpoint.clearPassword(); }
                    }
                    @Override protected void done() {
                        testButtons[side].setEnabled(true);
                        if (testGeneration != testGenerations[side]) { finishAutomaticConnect(); return; }
                        try {
                            List<String> available = get(); JComboBox<String> choice = databases.get(side);
                            updatingDatabaseChoice[side] = true;
                            available.forEach(choice::addItem); choice.setSelectedIndex(-1);
                            if (preferredDatabase[side] != null && available.contains(preferredDatabase[side])) choice.setSelectedItem(preferredDatabase[side]);
                            else if (available.size() == 1) choice.setSelectedIndex(0);
                            choice.setEnabled(!available.isEmpty()); testedSuccessfully[side] = true;
                            updatingDatabaseChoice[side] = false;
                            testStatus[side].setText(available.isEmpty() ? "Conectado; no hay bases visibles" : choice.getSelectedItem() == null ? "Conectado · " + available.size() + " bases · elegí una" : "Conectado · base seleccionada");
                            testStatus[side].setForeground(available.isEmpty() ? new Color(178, 112, 24) : new Color(49, 130, 97));
                            finishAutomaticConnect();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt(); testedSuccessfully[side] = false; autoConnectRequested = false; testStatus[side].setText("Prueba interrumpida");
                        } catch (ExecutionException ex) {
                            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                            testedSuccessfully[side] = false; autoConnectRequested = false; testStatus[side].setText("Error de conexión"); testStatus[side].setForeground(new Color(178, 65, 65));
                            JOptionPane.showMessageDialog(EndpointDialog.this, cause.getMessage() == null ? cause.toString() : cause.getMessage(), "Falló la prueba de conexión", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            } catch (RuntimeException ex) {
                autoConnectRequested = false;
                JOptionPane.showMessageDialog(this, "Revisá los datos de conexión: " + ex.getMessage(), "Datos de conexión no válidos", JOptionPane.WARNING_MESSAGE);
            }
        }

        private void accept() {
            Endpoint[] selectedEndpoints = new Endpoint[2];
            try {
                DatabaseEngine selectedEngine = (DatabaseEngine) engine.getSelectedItem();
                for (int i = 0; i < 2; i++) {
                    String database = (String) databases.get(i).getSelectedItem();
                    if (database == null) throw new IllegalArgumentException("Probá la conexión y elegí una base de datos para ambos extremos");
                    char[] typedPassword = passwords[i].getPassword();
                    try { selectedEndpoints[i] = new Endpoint(selectedEngine, fields[i][0].getText().trim(), Integer.parseInt(fields[i][1].getText().trim()), database, fields[i][2].getText().trim(), typedPassword, tls[i].isSelected()); }
                    finally { Arrays.fill(typedPassword, '\0'); }
                }
                endpoints = selectedEndpoints;
                dispose();
            } catch (RuntimeException ex) {
                for (Endpoint endpoint : selectedEndpoints) if (endpoint != null) endpoint.clearPassword();
                JOptionPane.showMessageDialog(this, "Revisá los datos de conexión: " + ex.getMessage(), "Datos de conexión no válidos", JOptionPane.WARNING_MESSAGE);
            }
        }
    }
}
