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
import java.awt.Image;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.MouseEvent;
import java.awt.Insets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
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
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;

public final class Main {
    private static final Path UI_PREFERENCES = Path.of(System.getProperty("user.home"), ".db-diff-studio", "ui.properties");
    private final JFrame frame = new JFrame(AppInfo.NAME);
    private final DefaultListModel<Object> resultModel = new DefaultListModel<>();
    private final List<Object> allResultItems = new ArrayList<>();
    private final JTextField objectSearch = new JTextField();
    private final JPanel objectSearchBox = new JPanel(new BorderLayout(6, 0));
    private final JLabel objectSearchIcon = new JLabel(UiIcons.themed(UiIcons.SEARCH, 16));
    private final List<JLabel> objectLegendMarkers = new ArrayList<>();
    private final JList<Object> resultList = new JList<>(resultModel) {
        @Override public String getToolTipText(MouseEvent event) {
            int index = locationToIndex(event.getPoint());
            if (index < 0 || !getCellBounds(index, index).contains(event.getPoint())) return null;
            return ResultRenderer.tooltip(getModel().getElementAt(index));
        }
    };
    private final ComparisonView comparisonView = new ComparisonView();
    private final RowComparisonPanel rowComparisonPanel = new RowComparisonPanel((rows, options) ->
            saveRowSql(rows, options.inserts(), options.updates(), options.deletes()), this::rowPanelExpanded);
    private final JLabel connectionStatus = new JLabel("Sin conexión");
    private final EnumSet<SchemaObject.Kind> selectedKinds = EnumSet.allOf(SchemaObject.Kind.class);
    private DatabaseConnector source, target;
    private String sourceDatabase = "", targetDatabase = "";
    private List<SchemaObject> sourceObjects = List.of(), targetObjects = List.of();
    private boolean compared;
    private boolean stackedLayout;
    private String theme = "Sistema";
    private JPanel headerPanel, leftPanel;
    private JSplitPane workspaceSplit;
    private JButton layoutButton;
    private final Set<SchemaObject.Key> comparedKeys = new TreeSet<>();
    private final Set<SchemaObject.Key> loadedSourceKeys = ConcurrentHashMap.newKeySet();
    private final Set<SchemaObject.Key> loadedTargetKeys = ConcurrentHashMap.newKeySet();
    private DiffEngine.Result selectedSchemaResult;
    private long selectionRequest;
    private long rowComparisonRequest;
    private long comparisonRequest;
    private boolean updatingResultItems;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "schema-reader"); t.setDaemon(true); return t; });

    private Main() { }
    public static void main(String[] args) { SwingUtilities.invokeLater(() -> new Main().show()); }

    private void show() {
        loadUiPreferences();
        frame.setIconImages(BrandMark.windowImages());
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { close(); } });
        frame.setMinimumSize(new Dimension(1020, 680)); frame.setSize(1260, 820); frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout(0, 0)); frame.add(header(), BorderLayout.NORTH); frame.add(content(), BorderLayout.CENTER); frame.add(statusBar(), BorderLayout.SOUTH);
        applyTheme(); frame.setVisible(true);
    }

    private JComponent header() {
        JPanel panel = new JPanel(new BorderLayout(16, 0)); headerPanel = panel; panel.putClientProperty("dbdiff.header", Boolean.TRUE); panel.setBackground(new Color(23, 36, 54)); panel.setBorder(new EmptyBorder(16, 22, 16, 20));
        JPanel brand = new JPanel(new BorderLayout(12, 0)); brand.setOpaque(false);
        JLabel logo = new JLabel(BrandMark.icon(46)); logo.setBorder(new EmptyBorder(0, 0, 0, 2));
        JLabel title = new JLabel(AppInfo.NAME); title.setForeground(Color.WHITE); title.setFont(new Font("Segoe UI", Font.BOLD, 21));
        JLabel sub = new JLabel("Compará esquemas. Revisá los cambios. Exportá con seguridad."); sub.setForeground(new Color(184, 198, 216)); sub.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        JPanel brandText = new JPanel(); brandText.setOpaque(false); brandText.setLayout(new BoxLayout(brandText, BoxLayout.Y_AXIS));
        brandText.add(title); brandText.add(Box.createVerticalStrut(3)); brandText.add(sub);
        brand.add(logo, BorderLayout.WEST); brand.add(brandText, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4)); actions.setOpaque(false);
        JButton connect = button("Conectar bases", true); connect.setIcon(UiIcons.themed(UiIcons.CONNECT, 16)); connect.setIconTextGap(7); connect.putClientProperty("dbdiff.primary", Boolean.TRUE); connect.addActionListener(e -> connect());
        JButton compare = button("Comparar", false); compare.setIcon(UiIcons.themed(UiIcons.COMPARE, 16)); compare.setIconTextGap(7); compare.addActionListener(e -> compare());
        JButton rows = button("Comparar filas", false); rows.setIcon(UiIcons.themed(UiIcons.COMPARE_ROWS, 16)); rows.setIconTextGap(7); rows.addActionListener(e -> compareRows());
        JButton export = button("Exportar SQL", false); export.setIcon(UiIcons.themed(UiIcons.EXPORT, 16)); export.setIconTextGap(7); export.addActionListener(e -> export());
        JComboBox<String> themeChoice = new JComboBox<>(new String[]{"Sistema", "Claro", "Oscuro"}); themeChoice.setSelectedItem(theme);
        themeChoice.setToolTipText("Apariencia de la aplicación"); themeChoice.addActionListener(e -> { theme = (String) themeChoice.getSelectedItem(); saveUiPreferences(); applyTheme(); });
        actions.add(connect); actions.add(compare); actions.add(rows); actions.add(export); actions.add(themeChoice); panel.add(brand, BorderLayout.WEST); panel.add(actions, BorderLayout.EAST); return panel;
    }

    private JComponent content() {
        JPanel split = new JPanel(new BorderLayout(0, 0));
        JPanel left = new JPanel(new BorderLayout(0, 10)); leftPanel = left; left.setBorder(new EmptyBorder(16, 16, 16, 12)); left.setPreferredSize(new Dimension(370, 100)); left.setBackground(new Color(246, 248, 251));
        JPanel controls = new JPanel(new BorderLayout(6, 0)); controls.setOpaque(false);
        JLabel objectsHeading = new JLabel("OBJETOS"); objectsHeading.setFont(new Font("Segoe UI", Font.BOLD, 11)); objectsHeading.setForeground(new Color(84, 99, 119));
        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0)); leftActions.setOpaque(false);
        JButton kinds = new JButton("Tipos ▾"); kinds.addActionListener(e -> kindMenu(kinds));
        layoutButton = new JButton(); layoutButton.addActionListener(e -> { stackedLayout = !stackedLayout; saveUiPreferences(); updateLayout(); });
        leftActions.add(layoutButton); leftActions.add(kinds); controls.add(objectsHeading, BorderLayout.WEST); controls.add(leftActions, BorderLayout.EAST);
        resultList.setCellRenderer(new ResultRenderer()); resultList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); resultList.setFixedCellHeight(34); resultList.addListSelectionListener(this::selectResult); resultList.setToolTipText("");
        JScrollPane listScroll = new JScrollPane(resultList); listScroll.setBorder(BorderFactory.createLineBorder(new Color(223, 228, 235))); listScroll.getViewport().setBackground(Color.WHITE);
        objectSearch.setToolTipText("Buscar objetos por nombre o tipo");
        objectSearch.getAccessibleContext().setAccessibleName("Buscar objetos");
        objectSearch.setBorder(new EmptyBorder(5, 2, 5, 2));
        objectSearchBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(223, 228, 235)), new EmptyBorder(1, 8, 1, 7)));
        objectSearchBox.add(objectSearchIcon, BorderLayout.WEST); objectSearchBox.add(objectSearch, BorderLayout.CENTER);
        objectSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { applyResultFilter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { applyResultFilter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { applyResultFilter(); }
        });
        JPanel listTop = new JPanel(); listTop.setOpaque(false); listTop.setLayout(new BoxLayout(listTop, BoxLayout.Y_AXIS));
        listTop.add(objectSearchBox); listTop.add(Box.createVerticalStrut(5)); listTop.add(objectColumnsHeader());
        JPanel listArea = new JPanel(new BorderLayout(0, 4)); listArea.setOpaque(false); listArea.add(listTop, BorderLayout.NORTH); listArea.add(listScroll, BorderLayout.CENTER);
        JPanel legend = objectStatusLegend();
        left.add(controls, BorderLayout.NORTH); left.add(listArea, BorderLayout.CENTER); left.add(legend, BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout(0, 8)); right.setBorder(new EmptyBorder(15, 12, 15, 16)); right.setBackground(Color.WHITE);
        JPanel comparisonArea = new JPanel(new BorderLayout(0, 6)); comparisonArea.setOpaque(false);
        comparisonArea.add(comparisonView, BorderLayout.CENTER); comparisonArea.add(rowComparisonPanel, BorderLayout.SOUTH);
        right.add(comparisonArea, BorderLayout.CENTER);
        workspaceSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right); workspaceSplit.setResizeWeight(.29); workspaceSplit.setDividerLocation(360); workspaceSplit.setBorder(null); split.add(workspaceSplit, BorderLayout.CENTER);
        updateLayout();
        return split;
    }

    private JComponent statusBar() { JPanel p = new JPanel(new BorderLayout()); p.setBorder(new EmptyBorder(7, 16, 7, 16)); p.setBackground(new Color(247, 249, 252)); p.add(connectionStatus); connectionStatus.setFont(new Font("Segoe UI", Font.PLAIN, 11)); connectionStatus.setForeground(new Color(88, 101, 120)); return p; }
    private static JTextArea area() { JTextArea area = new JTextArea(); area.setEditable(false); area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); area.setBorder(new EmptyBorder(8, 10, 8, 10)); area.setLineWrap(false); return area; }
    private static JButton button(String name, boolean primary) { JButton b = new JButton(name); b.setFont(new Font("Segoe UI", Font.BOLD, 12)); if (primary) { b.setBackground(new Color(61, 117, 207)); b.setForeground(Color.WHITE); b.setFocusPainted(false); } return b; }

    private void kindMenu(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        for (SchemaObject.Kind kind : SchemaObject.Kind.values()) { JCheckBoxMenuItem item = new JCheckBoxMenuItem(kind.toString(), selectedKinds.contains(kind)); item.addActionListener(e -> { if (item.isSelected()) selectedKinds.add(kind); else selectedKinds.remove(kind); refreshResults(); }); menu.add(item); }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void connect() {
        Endpoint[] endpoints = ConnectionDialog.showDialog(frame, theme); if (endpoints == null) return;
        comparisonRequest++; selectionRequest++; rowComparisonRequest++;
        setBusy(true, "Conectando y leyendo las listas de objetos…");
        worker.submit(() -> {
            DatabaseConnector a = DatabaseConnectorFactory.create(endpoints[0]), b = DatabaseConnectorFactory.create(endpoints[1]);
            try {
                a.connect(endpoints[0]); b.connect(endpoints[1]);
                List<SchemaObject> ao = a.listObjects(), bo = b.listObjects();
                SwingUtilities.invokeLater(() -> { closeConnectors(); source = a; target = b; sourceDatabase = endpoints[0].database(); targetDatabase = endpoints[1].database(); comparisonView.setDatabases(sourceDatabase, targetDatabase); sourceObjects = ao; targetObjects = bo; loadedSourceKeys.clear(); loadedTargetKeys.clear(); selectedSchemaResult = null; compared = false; comparedKeys.clear(); showCatalog(); setBusy(false, "Conectado · Origen: " + sourceDatabase + " · Destino: " + targetDatabase + " · " + ao.size() + " / " + bo.size() + " objetos encontrados · seleccioná un objeto para cargar su definición"); });
            } catch (SQLException | RuntimeException ex) { a.close(); b.close(); SwingUtilities.invokeLater(() -> { setBusy(false, "Error de conexión"); error("No se pudo conectar", ex); }); }
            finally { endpoints[0].clearPassword(); endpoints[1].clearPassword(); }
        });
    }

    private void compare() {
        if (source == null || target == null) { JOptionPane.showMessageDialog(frame, "Conectá ambas bases antes de comparar.", "Bases sin conexión", JOptionPane.INFORMATION_MESSAGE); return; }
        long request = ++comparisonRequest;
        selectionRequest++;
        rowComparisonRequest++;
        Set<SchemaObject.Key> selectedKeys = selectedObjectKeys();
        Set<SchemaObject.Kind> kinds = Set.copyOf(selectedKinds);
        Set<SchemaObject.Key> loadedA = Set.copyOf(loadedSourceKeys), loadedB = Set.copyOf(loadedTargetKeys);
        List<SchemaObject> sourceCatalog = sourceObjects, targetCatalog = targetObjects;
        DatabaseConnector sourceConnector = source, targetConnector = target;
        setBusy(true, "Comparando los tipos de objetos seleccionados…");
        // Las lecturas JDBC y el diff corren fuera del EDT. El contador invalida
        // respuestas viejas si el usuario inicia otra comparación mientras tanto.
        worker.submit(() -> {
            try {
                List<SchemaObject> a = loadSelected(sourceConnector, sourceCatalog, kinds, selectedKeys, loadedA), b = loadSelected(targetConnector, targetCatalog, kinds, selectedKeys, loadedB);
                List<DiffEngine.Result> results = new DiffEngine().compare(a, b, kinds, selectedKeys);
                SwingUtilities.invokeLater(() -> {
                    if (request != comparisonRequest) return;
                    sourceObjects = mergeLoaded(sourceObjects, a); targetObjects = mergeLoaded(targetObjects, b);
                    a.forEach(o -> loadedSourceKeys.add(o.key())); b.forEach(o -> loadedTargetKeys.add(o.key()));
                    compared = true; results.forEach(r -> comparedKeys.add(r.key())); setResultItems(results);
                    rowComparisonPanel.clearResult(false); selectedSchemaResult = null; comparisonView.showMessage("");
                    if (selectedKeys.size() == 1 && !results.isEmpty()) resultList.setSelectedValue(results.get(0), true);
                    setBusy(false, "Comparación lista · " + results.size() + " objetos · " + countChanged(results) + " con cambios");
                });
            } catch (SQLException ex) { SwingUtilities.invokeLater(() -> { if (request != comparisonRequest) return; setBusy(false, "Error al comparar"); error("No se pudieron leer las definiciones del esquema", ex); }); }
        });
    }

    private static List<SchemaObject> loadSelected(DatabaseConnector connector, List<SchemaObject> catalog, Set<SchemaObject.Kind> kinds,
                                                   Set<SchemaObject.Key> selected, Set<SchemaObject.Key> cachedKeys) throws SQLException {
        // El catálogo solo trae nombres; las definiciones se cargan bajo demanda y
        // las ya leídas se reutilizan para evitar consultas repetidas al servidor.
        List<SchemaObject> result = new ArrayList<>();
        for (SchemaObject object : catalog) if (kinds.contains(object.key().kind()) && (selected.isEmpty() || selected.contains(object.key())))
            result.add(cachedKeys.contains(object.key()) ? object : connector.loadObject(object.key()));
        return result;
    }

    private void refreshResults() {
        if (!compared) { showCatalog(); return; }
        List<SchemaObject> left = sourceObjects.stream().filter(o -> comparedKeys.contains(o.key())).toList();
        List<SchemaObject> right = targetObjects.stream().filter(o -> comparedKeys.contains(o.key())).toList();
        List<DiffEngine.Result> values = new DiffEngine().compare(left, right, selectedKinds, comparedKeys); setResultItems(values);
    }

    private void showCatalog() {
        rowComparisonPanel.clear();
        TreeMap<SchemaObject.Key, SchemaObject.Key> keys = new TreeMap<>();
        sourceObjects.forEach(o -> keys.put(o.key(), o.key())); targetObjects.forEach(o -> keys.put(o.key(), o.key()));
        List<CatalogItem> items = new ArrayList<>();
        for (SchemaObject.Key key : keys.keySet()) if (selectedKinds.contains(key.kind())) items.add(new CatalogItem(key, sourceObjects.stream().anyMatch(o -> o.key().equals(key)), targetObjects.stream().anyMatch(o -> o.key().equals(key))));
        setResultItems(items);
        comparisonView.showMessage("Objetos encontrados. Seleccioná uno o más y elegí Comparar.\nTodavía no se compararon las definiciones.");
    }

    private JPanel objectColumnsHeader() {
        JPanel header = new JPanel(new BorderLayout()); header.setOpaque(false);
        JLabel name = new JLabel("NOMBRE"); name.setFont(new Font("Segoe UI", Font.BOLD, 10)); name.setForeground(new Color(105, 118, 136));
        JLabel type = new JLabel("TIPO", JLabel.LEFT); type.setFont(name.getFont()); type.setForeground(name.getForeground()); type.setPreferredSize(new Dimension(120, 22));
        header.setBorder(new EmptyBorder(2, 26, 2, 6)); header.add(name, BorderLayout.CENTER); header.add(type, BorderLayout.EAST);
        return header;
    }

    private JPanel objectStatusLegend() {
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)); legend.setOpaque(false);
        addLegendItem(legend, "●", "Igual", new Color(49, 130, 97));
        addLegendItem(legend, "●", "Modificado", new Color(178, 112, 24));
        addLegendItem(legend, "◀", "Solo origen", new Color(56, 112, 184));
        addLegendItem(legend, "▶", "Solo destino", new Color(111, 122, 140));
        return legend;
    }

    private void addLegendItem(JPanel legend, String symbol, String description, Color color) {
        JLabel marker = new JLabel(symbol); marker.setFont(new Font("Segoe UI", Font.BOLD, 11)); marker.setForeground(color);
        objectLegendMarkers.add(marker);
        JLabel text = new JLabel(description); text.setFont(new Font("Segoe UI", Font.PLAIN, 10)); text.setForeground(new Color(105, 118, 136));
        JPanel item = new JPanel(new BorderLayout(3, 0)); item.setOpaque(false); item.add(marker, BorderLayout.WEST); item.add(text, BorderLayout.CENTER);
        legend.add(item);
    }

    private void setResultItems(List<?> items) {
        List<Object> selected = resultList.getSelectedValuesList();
        allResultItems.clear(); allResultItems.addAll(items);
        updatingResultItems = true;
        try {
            resultModel.clear();
            String query = objectSearch.getText().trim().toLowerCase(Locale.ROOT);
            for (Object item : allResultItems) if (matchesObjectSearch(item, query)) resultModel.addElement(item);
            for (Object item : selected) {
                int index = resultModel.indexOf(item);
                if (index >= 0) resultList.addSelectionInterval(index, index);
            }
        } finally {
            updatingResultItems = false;
        }
    }

    private void applyResultFilter() {
        String query = objectSearch.getText().trim().toLowerCase(Locale.ROOT);
        List<Object> selected = resultList.getSelectedValuesList();
        updatingResultItems = true;
        try {
            resultModel.clear();
            for (Object item : allResultItems) if (matchesObjectSearch(item, query)) resultModel.addElement(item);
            for (Object item : selected) {
                int index = resultModel.indexOf(item);
                if (index >= 0) resultList.addSelectionInterval(index, index);
            }
        } finally {
            updatingResultItems = false;
        }
    }

    private static boolean matchesObjectSearch(Object item, String query) {
        return query.isEmpty() || (objectKey(item).name() + " " + kindName(objectKey(item).kind()))
                .toLowerCase(Locale.ROOT).contains(query);
    }

    private static SchemaObject.Key objectKey(Object item) {
        return item instanceof CatalogItem catalog ? catalog.key() : ((DiffEngine.Result) item).key();
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
        if (updatingResultItems || e.getValueIsAdjusting()) return;
        long request = ++selectionRequest;
        rowComparisonRequest++;
        selectedSchemaResult = null;
        rowComparisonPanel.clearResult(false);
        if (resultList.getSelectedValuesList().size() > 1) { setBusy(false, "Varios objetos seleccionados"); comparisonView.showMessage("Seleccionaste varios objetos. Elegí Comparar para analizarlos o seleccioná un resultado para ver sus detalles."); if (rowComparisonPanel.isExpanded()) rowComparisonPanel.showMessage("Seleccioná una sola tabla para comparar filas."); return; }
        Object item = resultList.getSelectedValue();
        if (item != null) setBusy(false, "Objeto seleccionado · " + objectKey(item).name());
        if (item instanceof CatalogItem catalog) { loadCatalogSelection(catalog, request); return; }
        if (!(item instanceof DiffEngine.Result selected)) { if (rowComparisonPanel.isExpanded()) rowComparisonPanel.showMessage("Seleccioná una tabla para comparar sus filas."); return; }
        selectedSchemaResult = selected;
        comparisonView.showResult(selected, AppTheme.isDark(theme));
        if (rowComparisonPanel.isExpanded()) {
            if (selected.key().kind() == SchemaObject.Kind.TABLE) compareRowsForSelection();
            else rowComparisonPanel.showMessage("La comparación de filas está disponible para tablas.");
        }
    }

    private void compareRows() {
        if (!rowComparisonPanel.isExpanded()) { rowComparisonPanel.setExpanded(true); return; }
        compareRowsForSelection();
    }

    private void rowPanelExpanded(boolean expanded) {
        if (expanded) compareRowsForSelection();
    }

    private void compareRowsForSelection() {
        if (source == null || target == null) {
            rowComparisonPanel.showMessage("Conectá ambas bases para comparar filas.");
            setBusy(false, "Sin conexión");
            return;
        }
        List<Object> selectedItems = resultList.getSelectedValuesList();
        if (selectedItems.size() == 1 && selectedItems.get(0) instanceof CatalogItem catalog) {
            if (selectedSchemaResult != null && selectedSchemaResult.key().equals(catalog.key())) startRowComparison(selectedSchemaResult);
            else loadCatalogSelection(catalog, selectionRequest);
            return;
        }
        if (selectedItems.size() != 1 || !(selectedItems.get(0) instanceof DiffEngine.Result selected)) {
            rowComparisonPanel.showMessage("Seleccioná una tabla para comparar sus filas.");
            return;
        }
        startRowComparison(selected);
    }

    private void startRowComparison(DiffEngine.Result selected) {
        if (selected.key().kind() != SchemaObject.Kind.TABLE || selected.source() == null || selected.target() == null) {
            rowComparisonPanel.showMessage("La comparación de filas requiere una tabla presente en ambas bases.");
            return;
        }
        long request = ++rowComparisonRequest;
        DatabaseConnector sourceConnector = source, targetConnector = target;
        rowComparisonPanel.showLoading(selected.key().name());
        setBusy(true, "Leyendo las filas de " + selected.key().name() + "…");
        // Consultar en el worker evita bloquear Swing; el token descarta respuestas
        // antiguas si el usuario seleccionó otra tabla antes de terminar.
        worker.submit(() -> {
            try {
                RowDiff.Result rows = sourceConnector.compareTableRows(targetConnector, selected.key(), 10000);
                SwingUtilities.invokeLater(() -> {
                    if (request != rowComparisonRequest || !isSelected(selected.key())) return;
                    try {
                        // La consulta pudo terminar bien aunque la vista falle; dar
                        // un mensaje explícito evita dejar el panel en “Comparando”.
                        rowComparisonPanel.showResult(rows);
                        setBusy(false, rowSummary(rows));
                    } catch (RuntimeException renderError) {
                        renderError.printStackTrace();
                        rowComparisonPanel.showMessage("La comparación terminó, pero no se pudo mostrar el resultado: " + errorMessage(renderError));
                        setBusy(false, "Filas comparadas; error al mostrar el detalle");
                    }
                });
            } catch (SQLException | RuntimeException ex) { SwingUtilities.invokeLater(() -> {
                if (request != rowComparisonRequest || !isSelected(selected.key())) return;
                setBusy(false, "No se compararon las filas"); rowComparisonPanel.showMessage("No se pudieron comparar las filas: " + errorMessage(ex));
            }); }
        });
    }

    private void loadCatalogSelection(CatalogItem catalog, long request) {
        if (source == null || target == null) {
            comparisonView.showMessage("Conectá ambas bases para cargar la definición del objeto.");
            return;
        }
        SchemaObject.Key key = catalog.key();
        boolean sourceReady = !catalog.sourcePresent() || loadedSourceKeys.contains(key);
        boolean targetReady = !catalog.targetPresent() || loadedTargetKeys.contains(key);
        if (sourceReady && targetReady) {
            DiffEngine.Result result = compareLoadedObject(catalog);
            selectedSchemaResult = result;
            comparisonView.showResult(result, AppTheme.isDark(theme));
            connectionStatus.setText("Definición comparada · " + key.name());
            if (rowComparisonPanel.isExpanded() && key.kind() == SchemaObject.Kind.TABLE) startRowComparison(result);
            else if (rowComparisonPanel.isExpanded()) rowComparisonPanel.showMessage("La comparación de filas está disponible para tablas.");
            return;
        }
        if (rowComparisonPanel.isExpanded() && key.kind() == SchemaObject.Kind.TABLE) rowComparisonPanel.showLoading(key.name());
        else if (rowComparisonPanel.isExpanded()) rowComparisonPanel.showMessage("La comparación de filas está disponible para tablas.");
        comparisonView.showMessage("Cargando y comparando la definición de " + key.name() + "…");
        connectionStatus.setText("Leyendo definición de " + key.name() + " en segundo plano…");
        DatabaseConnector sourceConnector = source, targetConnector = target;
        // Se carga el DDL al seleccionar el objeto y se conserva en caché aunque
        // la selección cambie durante la consulta.
        worker.submit(() -> {
            try {
                SchemaObject a = catalog.sourcePresent() ? sourceConnector.loadObject(key) : null;
                SchemaObject b = catalog.targetPresent() ? targetConnector.loadObject(key) : null;
                DiffEngine.Result result = compareLoadedObject(key, a, b);
                SwingUtilities.invokeLater(() -> {
                    if (a != null) { sourceObjects = mergeLoaded(sourceObjects, List.of(a)); loadedSourceKeys.add(key); }
                    if (b != null) { targetObjects = mergeLoaded(targetObjects, List.of(b)); loadedTargetKeys.add(key); }
                    if (request != selectionRequest || !isSelected(key)) return;
                    selectedSchemaResult = result;
                    comparisonView.showResult(result, AppTheme.isDark(theme));
                    connectionStatus.setText("Definición comparada · " + key.name());
                    if (rowComparisonPanel.isExpanded() && key.kind() == SchemaObject.Kind.TABLE) startRowComparison(result);
                });
            } catch (SQLException | RuntimeException ex) { SwingUtilities.invokeLater(() -> {
                if (request != selectionRequest || !isSelected(key)) return;
                setBusy(false, "No se pudo cargar la definición");
                comparisonView.showMessage("No se pudo cargar " + key.name() + ": " + errorMessage(ex));
                if (rowComparisonPanel.isExpanded()) rowComparisonPanel.showMessage("No se pudo cargar la tabla para comparar filas.");
            }); }
        });
    }

    private DiffEngine.Result compareLoadedObject(CatalogItem catalog) {
        SchemaObject a = catalog.sourcePresent() ? findObject(sourceObjects, catalog.key()) : null;
        SchemaObject b = catalog.targetPresent() ? findObject(targetObjects, catalog.key()) : null;
        return compareLoadedObject(catalog.key(), a, b);
    }

    private static DiffEngine.Result compareLoadedObject(SchemaObject.Key key, SchemaObject source, SchemaObject target) {
        return new DiffEngine().compare(source == null ? List.of() : List.of(source), target == null ? List.of() : List.of(target),
                EnumSet.of(key.kind()), Set.of(key)).get(0);
    }

    private static SchemaObject findObject(List<SchemaObject> objects, SchemaObject.Key key) {
        return objects.stream().filter(object -> object.key().equals(key)).findFirst().orElse(null);
    }

    private boolean isSelected(SchemaObject.Key key) {
        Object selected = resultList.getSelectedValue();
        return selected != null && objectKey(selected).equals(key);
    }

    private static String rowSummary(RowDiff.Result rows) {
        return "Filas revisadas · " + rows.matchedRows() + " claves coincidentes · " + rows.sourceOnly()
                + " solo en origen · " + rows.targetOnly() + " solo en destino · " + rows.different() + " diferentes"
                + (rows.truncated() ? " · resultados limitados" : "");
    }

    private static String errorMessage(Exception ex) { return ex.getMessage() == null ? ex.toString() : ex.getMessage(); }

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
        JLabel warning = new JLabel("Solo se exporta. " + AppInfo.NAME + " nunca ejecuta este SQL. Revisá cada instrucción antes de usarla."); warning.setForeground(new Color(155, 79, 21)); wrapper.add(warning, BorderLayout.SOUTH);
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

    private void updateLayout() {
        if (workspaceSplit == null || leftPanel == null) return;
        layoutButton.setText(stackedLayout ? "Lista lateral" : "Lista superior");
        layoutButton.setToolTipText(stackedLayout ? "Mostrar objetos en un panel lateral" : "Mostrar objetos en una franja superior");
        workspaceSplit.setOrientation(stackedLayout ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT);
        if (stackedLayout) {
            leftPanel.setPreferredSize(new Dimension(100, 195));
            workspaceSplit.setResizeWeight(.25); workspaceSplit.setDividerLocation(Math.min(210, Math.max(150, frame.getHeight() / 4)));
        } else {
            leftPanel.setPreferredSize(new Dimension(370, 100));
            workspaceSplit.setResizeWeight(.29); workspaceSplit.setDividerLocation(Math.max(300, Math.min(390, frame.getWidth() / 3)));
        }
        workspaceSplit.revalidate(); workspaceSplit.repaint();
    }

    private void loadUiPreferences() {
        try {
            if (!Files.exists(UI_PREFERENCES)) return;
            java.util.Properties values = new java.util.Properties();
            try (var input = Files.newInputStream(UI_PREFERENCES)) { values.load(input); }
            theme = Set.of("Sistema", "Claro", "Oscuro").contains(values.getProperty("theme")) ? values.getProperty("theme") : "Sistema";
            stackedLayout = Boolean.parseBoolean(values.getProperty("stackedLayout", "false"));
        } catch (IOException ignored) { }
    }

    private void saveUiPreferences() {
        try {
            Files.createDirectories(UI_PREFERENCES.getParent());
            java.util.Properties values = new java.util.Properties(); values.setProperty("theme", theme); values.setProperty("stackedLayout", Boolean.toString(stackedLayout));
            try (var output = Files.newOutputStream(UI_PREFERENCES)) { values.store(output, AppInfo.NAME + " appearance"); }
        } catch (IOException ex) { connectionStatus.setText("No se pudo guardar la apariencia: " + ex.getMessage()); }
    }

    private void applyTheme() {
        AppTheme.apply(frame, headerPanel, theme);
        boolean dark = AppTheme.isDark(theme);
        comparisonView.setThemeColors(dark);
        rowComparisonPanel.setThemeColors(dark);
        ThemePalette palette = ThemePalette.forMode(dark);
        objectSearchBox.setBackground(palette.panel());
        objectSearchBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.border()), new EmptyBorder(1, 8, 1, 7)));
        objectSearchIcon.setForeground(palette.foreground());
        Color[] lightLegendColors = {new Color(49, 130, 97), new Color(178, 112, 24), new Color(56, 112, 184), new Color(111, 122, 140)};
        Color[] darkLegendColors = {new Color(111, 204, 148), new Color(230, 174, 85), new Color(107, 166, 239), new Color(154, 166, 181)};
        for (int i = 0; i < objectLegendMarkers.size(); i++) objectLegendMarkers.get(i).setForeground(dark ? darkLegendColors[i] : lightLegendColors[i]);
    }

    private static final class ResultRenderer extends JPanel implements ListCellRenderer<Object> {
        private final JLabel marker = new JLabel(" ", JLabel.CENTER);
        private final JLabel name = new JLabel();
        private final JLabel type = new JLabel();

        ResultRenderer() {
            super(new BorderLayout(4, 0));
            marker.setPreferredSize(new Dimension(18, 20));
        type.setPreferredSize(new Dimension(120, 20));
            add(marker, BorderLayout.WEST); add(name, BorderLayout.CENTER); add(type, BorderLayout.EAST);
            setBorder(new EmptyBorder(5, 6, 5, 6));
            name.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            type.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        }

        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            SchemaObject.Key key = objectKey(value);
            name.setText(key.name());
            type.setText(kindName(key.kind()));
            Color foreground = selected ? list.getSelectionForeground() : list.getForeground();
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(foreground); type.setForeground(selected ? foreground : new Color(105, 118, 136));
            marker.setForeground(selected ? foreground : markerColor(value, list.getBackground().getRed() < 90));
            marker.setText(markerText(value));
            type.setIcon(switch (key.kind()) {
                case TABLE -> UiIcons.themed(UiIcons.TABLE, 14);
                case VIEW -> UiIcons.themed(UiIcons.VIEW, 14);
                case PROCEDURE -> UiIcons.themed(UiIcons.PROCEDURE, 14);
                case FUNCTION -> UiIcons.themed(UiIcons.FUNCTION, 14);
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

        private static Color markerColor(Object value, boolean dark) {
            if (value instanceof CatalogItem catalog) {
                if (!catalog.sourcePresent()) return dark ? new Color(107, 166, 239) : new Color(56, 112, 184);
                if (!catalog.targetPresent()) return dark ? new Color(154, 166, 181) : new Color(111, 122, 140);
                return dark ? new Color(154, 166, 181) : new Color(105, 118, 136);
            }
            return switch (((DiffEngine.Result) value).status()) {
                case IDENTICAL -> dark ? new Color(111, 204, 148) : new Color(49, 130, 97);
                case CHANGED -> dark ? new Color(230, 174, 85) : new Color(178, 112, 24);
                case SOURCE_ONLY -> dark ? new Color(107, 166, 239) : new Color(56, 112, 184);
                case TARGET_ONLY -> dark ? new Color(154, 166, 181) : new Color(111, 122, 140);
            };
        }
    }

    private record CatalogItem(SchemaObject.Key key, boolean sourcePresent, boolean targetPresent) {
        @Override public String toString() { return key.name() + " · " + kindName(key.kind()) + " · sin comparar"; }
    }

    private static String kindName(SchemaObject.Kind kind) {
        return switch (kind) { case TABLE -> "tabla"; case VIEW -> "vista"; case PROCEDURE -> "procedimiento"; case FUNCTION -> "función"; };
    }

}
