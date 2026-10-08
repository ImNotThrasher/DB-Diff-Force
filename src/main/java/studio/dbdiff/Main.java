package studio.dbdiff;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public final class Main {
    private final JFrame frame = new JFrame("DB-Diff Studio");
    private final DefaultListModel<Object> resultModel = new DefaultListModel<>();
    private final JList<Object> resultList = new JList<>(resultModel);
    private final JTextArea detail = area(), diff = area();
    private final JLabel connectionStatus = new JLabel("Not connected");
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
        JLabel sub = new JLabel("Compare schemas. Review changes. Export safely."); sub.setForeground(new Color(184, 198, 216)); sub.setFont(new Font("Segoe UI", Font.PLAIN, 12)); brand.add(title); brand.add(Box.createVerticalStrut(3)); brand.add(sub);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4)); actions.setOpaque(false);
        JButton connect = button("Connect endpoints", true); connect.addActionListener(e -> connect());
        JButton compare = button("Compare", false); compare.addActionListener(e -> compare());
        JButton rows = button("Compare table rows", false); rows.addActionListener(e -> compareRows());
        JButton export = button("Export SQL", false); export.addActionListener(e -> export());
        actions.add(connect); actions.add(compare); actions.add(rows); actions.add(export); panel.add(brand, BorderLayout.WEST); panel.add(actions, BorderLayout.EAST); return panel;
    }

    private JComponent content() {
        JPanel split = new JPanel(new BorderLayout(0, 0));
        JPanel left = new JPanel(new BorderLayout(0, 10)); left.setBorder(new EmptyBorder(16, 16, 16, 12)); left.setPreferredSize(new Dimension(370, 100)); left.setBackground(new Color(246, 248, 251));
        JPanel controls = new JPanel(new BorderLayout(6, 0)); controls.setOpaque(false);
        JLabel objects = new JLabel("OBJECTS"); objects.setFont(new Font("Segoe UI", Font.BOLD, 11)); objects.setForeground(new Color(84, 99, 119));
        JButton kinds = new JButton("Kinds ▾"); kinds.addActionListener(e -> kindMenu(kinds)); controls.add(objects, BorderLayout.WEST); controls.add(kinds, BorderLayout.EAST);
        resultList.setCellRenderer(new ResultRenderer()); resultList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); resultList.addListSelectionListener(this::selectResult);
        JScrollPane listScroll = new JScrollPane(resultList); listScroll.setBorder(BorderFactory.createLineBorder(new Color(223, 228, 235))); listScroll.getViewport().setBackground(Color.WHITE);
        JLabel legend = new JLabel("  Green  identical     Amber  changed     Blue / gray  missing"); legend.setFont(new Font("Segoe UI", Font.PLAIN, 10)); legend.setForeground(new Color(105, 118, 136));
        left.add(controls, BorderLayout.NORTH); left.add(listScroll, BorderLayout.CENTER); left.add(legend, BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout(0, 0)); right.setBorder(new EmptyBorder(15, 12, 15, 16)); right.setBackground(Color.WHITE);
        JSplitPane textSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, titled("Object definition / schema", detail), titled("Line differences  ·  source → target", diff)); textSplit.setResizeWeight(.47); textSplit.setBorder(null); textSplit.setDividerSize(8);
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
        setBusy(true, "Connecting and reading object lists…");
        worker.submit(() -> {
            DatabaseConnector a = new MariaDbConnector(), b = new MariaDbConnector();
            try {
                a.connect(endpoints[0]); b.connect(endpoints[1]);
                List<SchemaObject> ao = a.listObjects(), bo = b.listObjects();
                SwingUtilities.invokeLater(() -> { closeConnectors(); source = a; target = b; targetDatabase = endpoints[1].database(); sourceObjects = ao; targetObjects = bo; compared = false; comparedKeys.clear(); showCatalog(); setBusy(false, "Connected · Source: " + endpoints[0].database() + " · Target: " + endpoints[1].database() + " · " + ao.size() + " / " + bo.size() + " objects discovered · not compared"); });
            } catch (SQLException | RuntimeException ex) { a.close(); b.close(); SwingUtilities.invokeLater(() -> { setBusy(false, "Connection failed"); error("Could not connect", ex); }); }
            finally { endpoints[0].clearPassword(); endpoints[1].clearPassword(); }
        });
    }

    private void compare() {
        if (source == null || target == null) { JOptionPane.showMessageDialog(frame, "Connect both endpoints before comparing.", "Endpoints not connected", JOptionPane.INFORMATION_MESSAGE); return; }
        Set<SchemaObject.Key> selectedKeys = selectedObjectKeys();
        setBusy(true, "Comparing selected object kinds…");
        worker.submit(() -> {
            try {
                List<SchemaObject> a = loadSelected(source, sourceObjects, selectedKinds, selectedKeys), b = loadSelected(target, targetObjects, selectedKinds, selectedKeys);
                List<DiffEngine.Result> results = new DiffEngine().compare(a, b, selectedKinds, selectedKeys);
                SwingUtilities.invokeLater(() -> { sourceObjects = mergeLoaded(sourceObjects, a); targetObjects = mergeLoaded(targetObjects, b); compared = true; results.forEach(r -> comparedKeys.add(r.key())); resultModel.clear(); results.forEach(resultModel::addElement); detail.setText(""); diff.setText(""); setBusy(false, "Compared " + results.size() + " objects · " + countChanged(results) + " changes"); });
            } catch (SQLException ex) { SwingUtilities.invokeLater(() -> { setBusy(false, "Comparison failed"); error("Could not read schema definitions", ex); }); }
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
        detail.setText("Objects discovered. Select one or more items and choose Compare.\nNo definitions have been compared yet."); diff.setText("");
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
        if (resultList.getSelectedValuesList().size() > 1) { detail.setText("Multiple objects selected. Choose Compare to analyze them, or select one result to inspect its details."); diff.setText(""); return; }
        Object item = resultList.getSelectedValue();
        if (item instanceof CatalogItem catalog) { detail.setText(catalog.key() + "\nStatus: NOT COMPARED\nSource: " + (catalog.sourcePresent() ? "present" : "missing") + "\nTarget: " + (catalog.targetPresent() ? "present" : "missing") + "\n\nChoose Compare to inspect definitions."); diff.setText(""); return; }
        if (!(item instanceof DiffEngine.Result selected)) return;
        StringBuilder info = new StringBuilder(selected.key() + "\nStatus: " + selected.status() + "\n\n");
        if (selected.source() != null) info.append("SOURCE\n").append(selected.source().definition()).append("\n\n");
        if (selected.target() != null) info.append("TARGET\n").append(selected.target().definition());
        detail.setText(info.toString()); diff.setText(String.join("\n", selected.diffLines()));
    }

    private void compareRows() {
        List<Object> selectedItems = resultList.getSelectedValuesList();
        if (source == null || target == null) { JOptionPane.showMessageDialog(frame, "Connect both endpoints before comparing rows.", "Endpoints not connected", JOptionPane.INFORMATION_MESSAGE); return; }
        if (selectedItems.size() != 1 || !(selectedItems.get(0) instanceof DiffEngine.Result selected) || selected.key().kind() != SchemaObject.Kind.TABLE || selected.source() == null || selected.target() == null) {
            JOptionPane.showMessageDialog(frame, "Select a table present on both endpoints.", "Select a comparable table", JOptionPane.INFORMATION_MESSAGE); return;
        }
        setBusy(true, "Streaming rows for " + selected.key().name() + "…");
        worker.submit(() -> {
            try {
                RowDiff.Result rows = source.compareTableRows(target, selected.key(), 10000);
                SwingUtilities.invokeLater(() -> { setBusy(false, "Row comparison complete · " + rows.sourceOnly() + " source-only · " + rows.targetOnly() + " target-only · " + rows.different() + " different" + (rows.truncated() ? " · results truncated" : "")); showRowResults(rows); });
            } catch (SQLException | RuntimeException ex) { SwingUtilities.invokeLater(() -> { setBusy(false, "Row comparison skipped"); error("Could not compare table rows", ex); }); }
        });
    }

    private void showRowResults(RowDiff.Result rows) {
        String[] headers = {"Status", "Primary key", "Source row", "Target row"};
        Object[][] data = new Object[rows.changes().size()][headers.length];
        for (int i = 0; i < rows.changes().size(); i++) {
            RowDiff.RowChange change = rows.changes().get(i);
            data[i][0] = change.status(); data[i][1] = change.key(); data[i][2] = change.source(); data[i][3] = change.target();
        }
        JTable table = new JTable(data, headers); table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN); table.setFillsViewportHeight(true);
        JLabel summary = new JLabel("  " + rows.table().name() + "  ·  " + rows.sourceOnly() + " source-only  ·  " + rows.targetOnly() + " target-only  ·  " + rows.different() + " different  ·  primary key: " + rows.primaryKey().stream().map(RowDiff.Column::name).collect(java.util.stream.Collectors.joining(", ")));
        JPanel panel = new JPanel(new BorderLayout(0, 8)); panel.add(summary, BorderLayout.NORTH); panel.add(new JScrollPane(table), BorderLayout.CENTER);
        if (rows.truncated()) { JLabel warning = new JLabel("First 10,000 changed rows are retained. Export includes only these rows."); warning.setForeground(new Color(155, 79, 21)); panel.add(warning, BorderLayout.SOUTH); }
        panel.setPreferredSize(new Dimension(980, 560));
        JCheckBox inserts = new JCheckBox("INSERT source-only rows", true), updates = new JCheckBox("UPDATE different rows", true), deletes = new JCheckBox("DELETE target-only rows", false);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT)); options.add(inserts); options.add(updates); options.add(deletes);
        JButton save = button("Export row SQL…", true); save.addActionListener(e -> saveRowSql(rows, inserts.isSelected(), updates.isSelected(), deletes.isSelected()));
        JPanel controls = new JPanel(new BorderLayout()); controls.add(options, BorderLayout.CENTER); controls.add(save, BorderLayout.EAST);
        JPanel content = new JPanel(new BorderLayout(0, 8)); content.add(panel, BorderLayout.CENTER); content.add(controls, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(frame, content, "Table row comparison", JOptionPane.PLAIN_MESSAGE);
    }

    private void saveRowSql(RowDiff.Result rows, boolean inserts, boolean updates, boolean deletes) {
        JTextArea preview = area(); preview.setText(RowSqlExporter.export(rows, inserts, updates, deletes)); preview.setCaretPosition(0);
        JCheckBox insertChoice = new JCheckBox("INSERT source-only rows", inserts), updateChoice = new JCheckBox("UPDATE different rows", updates), deleteChoice = new JCheckBox("DELETE target-only rows", deletes);
        deleteChoice.setForeground(new Color(155, 79, 21));
        Runnable refresh = () -> preview.setText(RowSqlExporter.export(rows, insertChoice.isSelected(), updateChoice.isSelected(), deleteChoice.isSelected()));
        insertChoice.addActionListener(e -> refresh.run()); updateChoice.addActionListener(e -> refresh.run()); deleteChoice.addActionListener(e -> refresh.run());
        JPanel choices = new JPanel(new FlowLayout(FlowLayout.LEFT)); choices.add(insertChoice); choices.add(updateChoice); choices.add(deleteChoice);
        JPanel wrapper = new JPanel(new BorderLayout(8, 8)); wrapper.add(choices, BorderLayout.NORTH); wrapper.add(new JScrollPane(preview), BorderLayout.CENTER);
        JLabel warning = new JLabel("Export only. Never executed. DELETE is opt-in; review every statement."); warning.setForeground(new Color(155, 79, 21)); wrapper.add(warning, BorderLayout.SOUTH); wrapper.setPreferredSize(new Dimension(820, 520));
        if (JOptionPane.showConfirmDialog(frame, wrapper, "Review row SQL", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("db-diff-row-export.sql"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        try { Files.writeString(chooser.getSelectedFile().toPath(), preview.getText(), StandardCharsets.UTF_8); JOptionPane.showMessageDialog(frame, "Row SQL saved. It was not executed.", "Export complete", JOptionPane.INFORMATION_MESSAGE); }
        catch (IOException ex) { error("Could not save row export", ex); }
    }

    private void export() {
        List<DiffEngine.Result> chosen = resultList.getSelectedValuesList().stream().filter(DiffEngine.Result.class::isInstance).map(DiffEngine.Result.class::cast).toList();
        if (chosen.isEmpty()) { JOptionPane.showMessageDialog(frame, "Select one or more compared results to export. Objects without a selection are not exported.", "Select comparison results", JOptionPane.INFORMATION_MESSAGE); return; }
        final List<DiffEngine.Result> exportResults = List.copyOf(chosen);
        EnumSet<DiffEngine.Status> ops = EnumSet.of(DiffEngine.Status.CHANGED, DiffEngine.Status.SOURCE_ONLY, DiffEngine.Status.TARGET_ONLY);
        String sql = SqlExporter.export(chosen, ops, targetDatabase);
        JTextArea preview = area(); preview.setText(sql); preview.setCaretPosition(0);
        JCheckBox[] checks = new JCheckBox[3]; JPanel choices = new JPanel(new GridLayout(0, 1));
        DiffEngine.Status[] statuses = {DiffEngine.Status.SOURCE_ONLY, DiffEngine.Status.CHANGED, DiffEngine.Status.TARGET_ONLY};
        String[] labels = {"CREATE source-only objects", "ALTER / replace changed objects", "DROP target-only objects"};
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
        JLabel warning = new JLabel("Export only. DB-Diff Studio never runs this SQL. Review every statement before use."); warning.setForeground(new Color(155, 79, 21)); wrapper.add(warning, BorderLayout.SOUTH);
        int answer = JOptionPane.showConfirmDialog(frame, wrapper, "Review SQL export", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("db-diff-export.sql")); if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        try { Files.writeString(chooser.getSelectedFile().toPath(), preview.getText(), StandardCharsets.UTF_8); JOptionPane.showMessageDialog(frame, "SQL saved. It was not executed.", "Export complete", JOptionPane.INFORMATION_MESSAGE); }
        catch (IOException ex) { error("Could not save export", ex); }
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
                label.setText("  ○  " + catalog.key().name() + "   ·   " + catalog.key().kind().name().toLowerCase(Locale.ROOT) + "   ·   not compared");
                if (!selected) label.setForeground(new Color(105, 118, 136));
            } else if (value instanceof DiffEngine.Result result) {
                String marker = switch (result.status()) { case IDENTICAL -> "●"; case CHANGED -> "●"; case SOURCE_ONLY -> "◀"; case TARGET_ONLY -> "▶"; };
                label.setText("  " + marker + "  " + result.key().name() + "   ·   " + result.key().kind().name().toLowerCase(Locale.ROOT));
                if (!selected) label.setForeground(switch (result.status()) { case IDENTICAL -> new Color(49, 130, 97); case CHANGED -> new Color(178, 112, 24); case SOURCE_ONLY -> new Color(56, 112, 184); case TARGET_ONLY -> new Color(111, 122, 140); });
            }
            label.setBorder(new EmptyBorder(10, 8, 10, 8)); return label;
        }
    }

    private record CatalogItem(SchemaObject.Key key, boolean sourcePresent, boolean targetPresent) {
        @Override public String toString() { return key.name() + " · " + key.kind() + " · not compared"; }
    }

    private static final class EndpointDialog extends JDialog {
        private Endpoint[] endpoints;
        private final JTextField[][] fields = new JTextField[2][4];
        private final JPasswordField[] passwords = {new JPasswordField(), new JPasswordField()};
        private final JCheckBox[] tls = {new JCheckBox("Use TLS and verify server certificate"), new JCheckBox("Use TLS and verify server certificate")};
        EndpointDialog(JFrame owner) {
            super(owner, "Connect endpoints", true); setLayout(new BorderLayout(10, 10)); JPanel form = new JPanel(new GridBagLayout()); form.setBorder(new EmptyBorder(16, 20, 10, 20));
            String[] labels = {"Host", "Port", "Database", "Username"}; String[] defaults = {"127.0.0.1", "3306", "", ""};
            for (int side = 0; side < 2; side++) {
                GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(5, 6, 5, 6); c.anchor = GridBagConstraints.WEST;
                c.gridx = side; c.gridy = 0; c.gridwidth = 2; JLabel header = new JLabel(side == 0 ? "SOURCE DATABASE" : "TARGET DATABASE"); header.setFont(new Font("Segoe UI", Font.BOLD, 11)); form.add(header, c);
                for (int row = 0; row < labels.length; row++) {
                    c.gridy = row + 1; c.gridwidth = 1; c.gridx = side * 2; form.add(new JLabel(labels[row]), c);
                    c.gridx = side * 2 + 1; JTextField f = new JTextField(defaults[row], 15); fields[side][row] = f; form.add(f, c);
                }
                c.gridy = 5; c.gridx = side * 2; form.add(new JLabel("Password"), c); c.gridx = side * 2 + 1; passwords[side].setEchoChar('•'); form.add(passwords[side], c);
                c.gridy = 6; c.gridx = side * 2; c.gridwidth = 2; form.add(tls[side], c);
            }
            JLabel secure = new JLabel("Passwords are held only in memory for this session; TLS is recommended for remote servers."); secure.setBorder(new EmptyBorder(0, 20, 0, 20));
            JButton cancel = new JButton("Cancel"), connect = button("Connect", true); JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT)); buttons.add(cancel); buttons.add(connect);
            cancel.addActionListener(e -> dispose()); connect.addActionListener(e -> accept()); add(form, BorderLayout.CENTER); add(secure, BorderLayout.NORTH); add(buttons, BorderLayout.SOUTH); pack(); setLocationRelativeTo(owner);
        }
        private void accept() {
            try {
                endpoints = new Endpoint[2];
                for (int i = 0; i < 2; i++) endpoints[i] = new Endpoint(fields[i][0].getText().trim(), Integer.parseInt(fields[i][1].getText().trim()), fields[i][2].getText().trim(), fields[i][3].getText().trim(), passwords[i].getPassword(), tls[i].isSelected());
                dispose();
            } catch (RuntimeException ex) { JOptionPane.showMessageDialog(this, "Check the endpoint fields: " + ex.getMessage(), "Invalid connection details", JOptionPane.WARNING_MESSAGE); }
        }
    }
}
