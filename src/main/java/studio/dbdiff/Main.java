package studio.dbdiff;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
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
import java.util.Comparator;
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
import javax.swing.JProgressBar;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JSpinner;
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
    private final JTextField objectSearch = new PlaceholderTextField("Buscar objetos...");
    private final JPanel objectSearchBox = new JPanel(new BorderLayout(6, 0));
    private final JLabel objectSearchIcon = new JLabel(UiIcons.themed(UiIcons.SEARCH, 18));
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
    private final JProgressBar activityIndicator = new JProgressBar();
    private final EnumSet<SchemaObject.Kind> selectedKinds = EnumSet.allOf(SchemaObject.Kind.class);
    private SchemaSource source;
    /** Keeps the initially connected database available while a file-based source is active. */
    private DatabaseConnector databaseSource;
    private DatabaseConnector target;
    /** Ephemeral origin credentials used only when the user requests Flyway reconstruction. */
    private Endpoint flywayEndpoint;
    private DatabaseEngine schemaEngine = DatabaseEngine.MARIADB;
    private String sourceDatabase = "", targetDatabase = "";
    private List<SchemaObject> sourceObjects = List.of(), targetObjects = List.of();
    private String databaseSourceName = "";
    private List<SchemaObject> databaseSourceObjects = List.of();
    private DependencyReport databaseSourceDependencyReport = new DependencyReport(Map.of(), List.of(), List.of());
    private Map<SchemaObject.Key, Set<SchemaObject.Key>> sourceDependencies = Map.of(), targetDependencies = Map.of();
    private DependencyReport sourceDependencyReport = new DependencyReport(Map.of(), List.of(), List.of());
    private DependencyReport targetDependencyReport = new DependencyReport(Map.of(), List.of(), List.of());
    private boolean compared;
    private boolean schemaComparisonRunning;
    private boolean stackedLayout;
    private String theme = "Sistema";
    private int codeFontSize = 13;
    private String codeFontFamily = "";
    private JPanel headerPanel, leftPanel;
    private JPanel modeBarPanel;
    private JLabel modeTitle;
    private JSplitPane workspaceSplit;
    private JSplitPane comparisonSplit;
    private JButton layoutButton;
    private JButton compareButton, exportButton, chooseSourceButton;
    private JComboBox<ComparisonMode> modeSelector;
    private JLabel modeDescription;
    private ComparisonMode comparisonMode = ComparisonMode.DATABASES;
    private boolean updatingModeSelector;
    private final Set<SchemaObject.Key> comparedKeys = new TreeSet<>();
    private final Set<SchemaObject.Key> loadedSourceKeys = ConcurrentHashMap.newKeySet();
    private final Set<SchemaObject.Key> loadedTargetKeys = ConcurrentHashMap.newKeySet();
    private DiffEngine.Result selectedSchemaResult;
    private long selectionRequest;
    private long rowComparisonRequest;
    private long comparisonRequest;
    private boolean updatingResultItems;
    private int objectSortColumn = 1;
    private boolean objectSortAscending = true;
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
        setConnectionAvailable(false);
        comparisonView.showMessage("Conectá dos bases para empezar a comparar.");
        applyTheme(); frame.setVisible(true);
        SwingUtilities.invokeLater(() -> {
            comparisonSplit.setDividerLocation(.89);
            connect();
        });
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
        compareButton = button("Comparar", false); compareButton.setIcon(UiIcons.themed(UiIcons.COMPARE, 16)); compareButton.setIconTextGap(7); compareButton.addActionListener(e -> compare());
        exportButton = button("Exportar SQL", false); exportButton.setIcon(UiIcons.themed(UiIcons.EXPORT, 16)); exportButton.setIconTextGap(7); exportButton.addActionListener(e -> export());
        JButton settings = button("Configuración", false); settings.setIcon(UiIcons.themed(UiIcons.SETTINGS, 16)); settings.setIconTextGap(6);
        settings.setToolTipText("Tema y fuente del comparador"); settings.addActionListener(e -> showSettings());
        actions.add(connect); actions.add(compareButton); actions.add(exportButton); actions.add(settings); panel.add(brand, BorderLayout.WEST); panel.add(actions, BorderLayout.EAST); return panel;
    }

    /** Gives database, SQL snapshot, and Flyway comparisons separate, explicit workflows. */
    private JComponent modeBar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        modeBarPanel = bar;
        bar.setBorder(new EmptyBorder(9, 18, 9, 18));
        bar.setBackground(new Color(237, 241, 246));
        modeTitle = new JLabel("MODO");
        modeTitle.setFont(new Font("Segoe UI", Font.BOLD, 11));
        modeSelector = new JComboBox<>(ComparisonMode.values());
        modeSelector.setSelectedItem(comparisonMode);
        modeSelector.setToolTipText("Elegí qué tipo de origen comparar con la base destino.");
        modeSelector.addActionListener(event -> {
            if (updatingModeSelector) return;
            ComparisonMode selected = (ComparisonMode) modeSelector.getSelectedItem();
            if (selected == null || selected == comparisonMode) return;
            switch (selected) {
                case DATABASES -> activateDatabaseMode();
                case SNAPSHOT -> openSnapshot();
                case FLYWAY -> openFlyway();
            }
        });
        chooseSourceButton = button("Elegir carpeta…", false);
        chooseSourceButton.addActionListener(event -> {
            if (comparisonMode == ComparisonMode.SNAPSHOT) openSnapshot();
            else if (comparisonMode == ComparisonMode.FLYWAY) openFlyway();
        });
        modeDescription = new JLabel(comparisonMode.description);
        modeDescription.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        modeDescription.setForeground(new Color(88, 101, 120));
        JPanel selection = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        selection.setOpaque(false);
        selection.add(modeTitle);
        selection.add(modeSelector);
        selection.add(chooseSourceButton);
        bar.add(selection, BorderLayout.WEST);
        bar.add(modeDescription, BorderLayout.CENTER);
        updateModeBar();
        return bar;
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
        listTop.add(objectSearchBox); listTop.add(Box.createVerticalStrut(5));
        objectColumnsHeader = objectColumnsHeader(); listTop.add(objectColumnsHeader);
        JPanel listArea = new JPanel(new BorderLayout(0, 4)); listArea.setOpaque(false); listArea.add(listTop, BorderLayout.NORTH); listArea.add(listScroll, BorderLayout.CENTER);
        JPanel legend = objectStatusLegend();
        left.add(controls, BorderLayout.NORTH); left.add(listArea, BorderLayout.CENTER); left.add(legend, BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout(0, 8)); right.setBorder(new EmptyBorder(15, 12, 15, 16)); right.setBackground(Color.WHITE);
        comparisonSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, comparisonView, rowComparisonPanel);
        comparisonSplit.setResizeWeight(.72); comparisonSplit.setDividerSize(9); comparisonSplit.setContinuousLayout(true);
        comparisonSplit.setBorder(null); comparisonView.setMinimumSize(new Dimension(100, 180));
        rowComparisonPanel.setMinimumSize(new Dimension(100, 48));
        right.add(comparisonSplit, BorderLayout.CENTER);
        workspaceSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right); workspaceSplit.setResizeWeight(.29); workspaceSplit.setDividerLocation(360); workspaceSplit.setBorder(null); split.add(workspaceSplit, BorderLayout.CENTER);
        updateLayout();
        JPanel content = new JPanel(new BorderLayout(0, 0));
        content.add(modeBar(), BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        return content;
    }

    private JComponent statusBar() {
        JPanel p = new JPanel(new BorderLayout(12, 0));
        p.setBorder(new EmptyBorder(7, 16, 7, 16)); p.setBackground(new Color(247, 249, 252));
        p.add(connectionStatus, BorderLayout.CENTER);
        activityIndicator.setIndeterminate(true); activityIndicator.setBorderPainted(false);
        activityIndicator.setPreferredSize(new Dimension(130, 12)); activityIndicator.setVisible(false);
        activityIndicator.setToolTipText("La operación sigue ejecutándose en segundo plano.");
        p.add(activityIndicator, BorderLayout.EAST);
        connectionStatus.setFont(new Font("Segoe UI", Font.PLAIN, 11)); connectionStatus.setForeground(new Color(88, 101, 120));
        return p;
    }
    private JTextArea area() {
        JTextArea area = new JTextArea(); area.setEditable(false);
        area.setFont(DiffCodeEditor.codeFont(codeFontFamily, codeFontSize));
        ThemePalette palette = ThemePalette.forMode(AppTheme.isDark(theme));
        area.setBackground(palette.editor()); area.setForeground(palette.foreground());
        area.setCaretColor(palette.foreground()); area.setSelectionColor(palette.selection());
        area.setSelectedTextColor(palette.foreground());
        area.setBorder(new EmptyBorder(8, 10, 8, 10)); area.setLineWrap(false); return area;
    }
    private static JButton button(String name, boolean primary) { JButton b = new JButton(name); b.setFont(new Font("Segoe UI", Font.BOLD, 12)); if (primary) { b.setBackground(new Color(61, 117, 207)); b.setForeground(Color.WHITE); b.setFocusPainted(false); } return b; }

    private void kindMenu(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        for (SchemaObject.Kind kind : SchemaObject.Kind.values()) { JCheckBoxMenuItem item = new JCheckBoxMenuItem(kind.toString(), selectedKinds.contains(kind)); item.addActionListener(e -> { if (item.isSelected()) selectedKinds.add(kind); else selectedKinds.remove(kind); refreshResults(); }); menu.add(item); }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void connect() {
        Endpoint[] endpoints = ConnectionDialog.showDialog(frame, theme);
        if (endpoints == null) {
            if (source == null || target == null) {
                comparisonView.showMessage("Conectá dos bases para empezar a comparar.");
                setBusy(false, "Sin conexión · conectá las bases para habilitar la comparación");
            }
            return;
        }
        comparisonRequest++; selectionRequest++; rowComparisonRequest++;
        schemaComparisonRunning = false;
        setBusy(true, "Conectando y leyendo las listas de objetos…");
        worker.submit(() -> {
            DatabaseConnector a = DatabaseConnectorFactory.create(endpoints[0]), b = DatabaseConnectorFactory.create(endpoints[1]);
            try {
                a.connect(endpoints[0]); b.connect(endpoints[1]);
                List<SchemaObject> ao = a.listObjects(), bo = b.listObjects();
                DependencyReport ad = safeDependencyReport(a), bd = safeDependencyReport(b);
                Endpoint originForFlyway = copyEndpoint(endpoints[0]);
                SwingUtilities.invokeLater(() -> {
                    closeConnectors();
                    source = databaseSource = a;
                    target = b;
                    flywayEndpoint = originForFlyway;
                    sourceDependencyReport = databaseSourceDependencyReport = ad;
                    targetDependencyReport = bd;
                    sourceDependencies = ad.dependencies(); targetDependencies = bd.dependencies();
                    schemaEngine = endpoints[0].engine();
                    sourceDatabase = databaseSourceName = endpoints[0].database();
                    targetDatabase = endpoints[1].database();
                    databaseSourceObjects = ao;
                    sourceObjects = ao; targetObjects = bo;
                    comparisonView.setDatabases(sourceDatabase, targetDatabase);
                    loadedSourceKeys.clear(); loadedTargetKeys.clear(); selectedSchemaResult = null;
                    compared = false; comparedKeys.clear(); setComparisonMode(ComparisonMode.DATABASES);
                    showCatalog(); setConnectionAvailable(true); refreshDependencyStatus();
                    setBusy(false, "Conectado · Origen: " + sourceDatabase + " · Destino: " + targetDatabase + " · " + ao.size() + " / " + bo.size() + " objetos · dependencias catalogadas " + ad.edgeCount() + " / " + bd.edgeCount() + " · seleccioná un objeto para cargar su definición");
                });
            } catch (SQLException | RuntimeException ex) { a.close(); b.close(); SwingUtilities.invokeLater(() -> { setBusy(false, "Error de conexión"); error("No se pudo conectar", ex); }); }
            finally { endpoints[0].clearPassword(); endpoints[1].clearPassword(); }
        });
    }

    private static DependencyReport safeDependencyReport(SchemaSource connector) {
        try { return connector.dependencyReport(); }
        catch (SQLException ex) {
            return DependencyReport.unknown(Map.of(), "No se pudieron consultar los metadatos de dependencias: " + AppUtils.errorMessage(ex));
        }
    }

    /** Replays migrations only after explicit approval, then replaces the source with captured metadata. */
    private void openFlyway() {
        if (target == null || flywayEndpoint == null) {
            JOptionPane.showMessageDialog(frame, "Conectá las bases primero para usar el servidor de origen como entorno temporal.",
                    "Falta la conexión de origen", JOptionPane.INFORMATION_MESSAGE);
            syncModeSelector();
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Seleccionar carpeta de migraciones Flyway (V1__..., V2__...)");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) { syncModeSelector(); return; }
        Path folder = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        Endpoint origin = copyEndpoint(flywayEndpoint);
        String message = "Se va a crear una base temporal con nombre único en el servidor de origen "
                + origin.host() + ":" + origin.port() + " (" + origin.engine() + ").\n\n"
                + "Flyway ejecutará las migraciones de:\n" + folder + "\n\n"
                + "La aplicación leerá el esquema resultante en memoria y borrará la base temporal antes de mostrar la comparación. "
                + "No aplicará cambios directamente en las bases conectadas (" + databaseSourceName + " y " + targetDatabase
                + "); Flyway sí ejecutará las migraciones en la base temporal.\n\n"
                + "Necesitás permisos para crear y borrar bases de datos. Revisá las migraciones: SQL que haga referencia explícita a otras bases o servidores podría modificarlos.\n\n"
                + "¿Autorizar la creación de la base temporal y ejecutar Flyway?";
        int answer = JOptionPane.showConfirmDialog(frame, message, "Reconstruir esquema desde Flyway",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) { origin.clearPassword(); syncModeSelector(); return; }

        long request = ++comparisonRequest;
        selectionRequest++; rowComparisonRequest++;
        setBusy(true, "Creando base temporal y ejecutando migraciones Flyway…");
        worker.submit(() -> {
            try {
                FlywayReconstructionSource reconstructed = FlywayReconstructionSource.build(folder, origin);
                List<SchemaObject> catalog = reconstructed.listObjects();
                SwingUtilities.invokeLater(() -> {
                    if (request != comparisonRequest) { reconstructed.close(); return; }
                    closeCurrentSource();
                    source = reconstructed;
                    sourceDependencyReport = safeDependencyReport(reconstructed);
                    sourceDependencies = sourceDependencyReport.dependencies();
                    sourceObjects = catalog;
                    sourceDatabase = "Flyway: " + (folder.getFileName() == null ? folder : folder.getFileName());
                    comparisonView.setDatabases(sourceDatabase, targetDatabase);
                    loadedSourceKeys.clear(); loadedTargetKeys.clear(); selectedSchemaResult = null;
                    compared = false; comparedKeys.clear(); rowComparisonPanel.setExpanded(false); rowComparisonPanel.clearResult(false);
                    setComparisonMode(ComparisonMode.FLYWAY);
                    showCatalog(); setConnectionAvailable(true); refreshDependencyStatus();
                    setBusy(false, "Flyway reconstruido · " + reconstructed.migrationsExecuted() + " migraciones · " + catalog.size()
                            + " objetos · base temporal eliminada; seleccioná un objeto o Comparar");
                });
            } catch (SQLException | RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    if (request != comparisonRequest) return;
                    syncModeSelector();
                    setBusy(false, "No se pudo reconstruir Flyway"); error("Error al reconstruir las migraciones", ex);
                });
            } finally { origin.clearPassword(); }
        });
    }

    private static Endpoint copyEndpoint(Endpoint endpoint) {
        char[] password = endpoint.password();
        try { return new Endpoint(endpoint.engine(), endpoint.host(), endpoint.port(), endpoint.database(), endpoint.user(), password, endpoint.useTls()); }
        finally { Arrays.fill(password, '\0'); }
    }

    private void closeCurrentSource() {
        if (source != null && source != databaseSource) source.close();
        source = null;
    }

    /** Loads a read-only folder of complete SQL definitions as the active comparison source. */
    private void openSnapshot() {
        if (target == null) {
            JOptionPane.showMessageDialog(frame, "Conectá las bases primero; el destino debe ser una base de datos.",
                    "Falta la base destino", JOptionPane.INFORMATION_MESSAGE);
            syncModeSelector();
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Seleccionar carpeta de snapshot SQL");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) { syncModeSelector(); return; }
        Path folder = chooser.getSelectedFile().toPath();
        long request = ++comparisonRequest;
        selectionRequest++; rowComparisonRequest++;
        setBusy(true, "Leyendo definiciones del snapshot…");
        worker.submit(() -> {
            SqlSnapshotSource snapshot = new SqlSnapshotSource(folder, schemaEngine);
            try {
                List<SchemaObject> catalog = snapshot.listObjects();
                SwingUtilities.invokeLater(() -> {
                    if (request != comparisonRequest) { snapshot.close(); return; }
                    closeCurrentSource();
                    source = snapshot;
                    sourceDependencyReport = DependencyReport.unknown(Map.of(), "El snapshot SQL no aporta un catálogo confiable de dependencias.");
                    sourceDependencies = sourceDependencyReport.dependencies(); sourceObjects = catalog;
                    sourceDatabase = "Snapshot: " + (folder.getFileName() == null ? folder : folder.getFileName());
                    comparisonView.setDatabases(sourceDatabase, targetDatabase);
                    loadedSourceKeys.clear(); loadedTargetKeys.clear(); selectedSchemaResult = null;
                    compared = false; comparedKeys.clear(); rowComparisonPanel.setExpanded(false); rowComparisonPanel.clearResult(false);
                    setComparisonMode(ComparisonMode.SNAPSHOT);
                    showCatalog(); setConnectionAvailable(true);
                    refreshDependencyStatus();
                    setBusy(false, "Snapshot cargado · " + catalog.size() + " definiciones · dependencias catalogadas " + targetDependencyReport.edgeCount() + " en destino");
                });
            } catch (SQLException | RuntimeException ex) {
                snapshot.close();
                SwingUtilities.invokeLater(() -> {
                    if (request != comparisonRequest) return;
                    syncModeSelector();
                    setBusy(false, "No se pudo leer el snapshot"); error("Error al cargar la carpeta SQL", ex);
                });
            }
        });
    }

    /** Restores the original database as the active source without reconnecting. */
    private void activateDatabaseMode() {
        if (databaseSource == null || target == null) {
            syncModeSelector();
            JOptionPane.showMessageDialog(frame, "Conectá las dos bases para usar este modo.", "Bases sin conexión", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        comparisonRequest++; selectionRequest++; rowComparisonRequest++;
        schemaComparisonRunning = false;
        closeCurrentSource();
        source = databaseSource;
        sourceDatabase = databaseSourceName;
        sourceObjects = databaseSourceObjects;
        sourceDependencyReport = databaseSourceDependencyReport;
        sourceDependencies = sourceDependencyReport.dependencies();
        loadedSourceKeys.clear();
        selectedSchemaResult = null; compared = false; comparedKeys.clear();
        rowComparisonPanel.setExpanded(false); rowComparisonPanel.clearResult(false);
        comparisonView.setDatabases(sourceDatabase, targetDatabase);
        setComparisonMode(ComparisonMode.DATABASES);
        showCatalog(); setConnectionAvailable(true); refreshDependencyStatus();
        setBusy(false, "Modo Bases de datos · " + sourceDatabase + " → " + targetDatabase + " · seleccioná objetos y apretá Comparar");
    }

    private void setComparisonMode(ComparisonMode mode) {
        comparisonMode = mode;
        syncModeSelector();
        if (modeDescription != null) modeDescription.setText(mode.description);
        if (chooseSourceButton != null) chooseSourceButton.setVisible(mode != ComparisonMode.DATABASES);
        if (chooseSourceButton != null) chooseSourceButton.setText(mode == ComparisonMode.FLYWAY ? "Elegir migraciones…" : "Elegir carpeta…");
    }

    private void updateModeBar() {
        setComparisonMode(comparisonMode);
        if (modeSelector != null) modeSelector.setEnabled(source != null && target != null);
        if (chooseSourceButton != null) chooseSourceButton.setEnabled(source != null && target != null);
    }

    private void syncModeSelector() {
        if (modeSelector == null) return;
        updatingModeSelector = true;
        modeSelector.setSelectedItem(comparisonMode);
        updatingModeSelector = false;
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
        SchemaSource sourceConnector = source; DatabaseConnector targetConnector = target;
        schemaComparisonRunning = true;
        setBusy(true, "Comparando los tipos de objetos seleccionados…");
        // Las lecturas JDBC y el diff corren fuera del EDT. El contador invalida
        // respuestas viejas si el usuario inicia otra comparación mientras tanto.
        worker.submit(() -> {
            try {
                List<SchemaObject> a = loadSelected(sourceConnector, sourceCatalog, kinds, selectedKeys, loadedA), b = loadSelected(targetConnector, targetCatalog, kinds, selectedKeys, loadedB);
                List<DiffEngine.Result> results = new DiffEngine().compare(a, b, kinds, selectedKeys);
                SwingUtilities.invokeLater(() -> {
                    if (request != comparisonRequest) return;
                    schemaComparisonRunning = false;
                    sourceObjects = mergeLoaded(sourceObjects, a); targetObjects = mergeLoaded(targetObjects, b);
                    a.forEach(o -> loadedSourceKeys.add(o.key())); b.forEach(o -> loadedTargetKeys.add(o.key()));
                    compared = true; results.forEach(r -> comparedKeys.add(r.key())); setResultItems(results);
                    rowComparisonPanel.clearResult(false); selectedSchemaResult = null; comparisonView.showMessage("");
                    if (selectedKeys.size() == 1 && !results.isEmpty()) resultList.setSelectedValue(results.get(0), true);
                    setBusy(false, "Comparación lista · " + results.size() + " objetos · " + countChanged(results) + " con cambios");
                });
            } catch (SQLException | RuntimeException ex) { SwingUtilities.invokeLater(() -> { if (request != comparisonRequest) return; schemaComparisonRunning = false; setBusy(false, "Error al comparar"); error("No se pudieron leer las definiciones del esquema", ex); }); }
        });
    }

    private static List<SchemaObject> loadSelected(SchemaSource connector, List<SchemaObject> catalog, Set<SchemaObject.Kind> kinds,
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
        JPanel header = new JPanel(new BorderLayout(4, 0)); header.setOpaque(false);
        header.setBorder(new EmptyBorder(2, 6, 2, 6));
        header.add(sortHeader("ESTADO", 0, 58), BorderLayout.WEST);
        header.add(sortHeader("NOMBRE", 1, 0), BorderLayout.CENTER);
        header.add(sortHeader("TIPO", 2, 120), BorderLayout.EAST);
        return header;
    }

    private JLabel sortHeader(String title, int column, int width) {
        JLabel label = new JLabel(title);
        label.setFont(new Font("Segoe UI", Font.BOLD, 10));
        label.setForeground(new Color(105, 118, 136));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setToolTipText("Ordenar por " + title.toLowerCase(Locale.ROOT));
        if (width > 0) label.setPreferredSize(new Dimension(width, 22));
        label.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { sortObjectsBy(column); }
        });
        updateSortHeader(label, title, column);
        return label;
    }

    private void sortObjectsBy(int column) {
        if (objectSortColumn == column) objectSortAscending = !objectSortAscending;
        else { objectSortColumn = column; objectSortAscending = true; }
        if (objectColumnsHeader != null) updateSortHeaderLabels(objectColumnsHeader);
        applyResultFilter();
    }

    private JPanel objectColumnsHeader;

    private void updateSortHeader(JLabel label, String title, int column) {
        label.setText(title + (objectSortColumn == column ? (objectSortAscending ? " ▲" : " ▼") : ""));
    }

    private void updateSortHeaderLabels(Container header) {
        for (Component component : header.getComponents()) {
            if (component instanceof JLabel label) {
                String title = label.getText().replaceAll(" [▲▼]$", "");
                int column = switch (title) { case "ESTADO" -> 0; case "TIPO" -> 2; default -> 1; };
                updateSortHeader(label, title, column);
            }
        }
    }

    private JPanel objectStatusLegend() {
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)); legend.setOpaque(false);
        addLegendItem(legend, "●", "Igual", new Color(49, 130, 97));
        addLegendItem(legend, "●", "Modificado", new Color(178, 112, 24));
        addLegendItem(legend, UiIcons.themed(UiIcons.SOURCE_ONLY, 16), "Solo origen", new Color(56, 112, 184));
        addLegendItem(legend, UiIcons.themed(UiIcons.TARGET_ONLY, 16), "Solo destino", new Color(111, 122, 140));
        return legend;
    }

    private void addLegendItem(JPanel legend, String symbol, String description, Color color) {
        JLabel marker = new JLabel(symbol); marker.setFont(new Font("Segoe UI", Font.BOLD, 11)); marker.setForeground(color);
        objectLegendMarkers.add(marker);
        JLabel text = new JLabel(description); text.setFont(new Font("Segoe UI", Font.PLAIN, 10)); text.setForeground(new Color(105, 118, 136));
        JPanel item = new JPanel(new BorderLayout(3, 0)); item.setOpaque(false); item.add(marker, BorderLayout.WEST); item.add(text, BorderLayout.CENTER);
        legend.add(item);
    }

    private void addLegendItem(JPanel legend, javax.swing.Icon icon, String description, Color color) {
        JLabel marker = new JLabel(icon); marker.setPreferredSize(new Dimension(12, 14)); marker.setForeground(color);
        objectLegendMarkers.add(marker);
        JLabel text = new JLabel(description); text.setFont(new Font("Segoe UI", Font.PLAIN, 10)); text.setForeground(new Color(105, 118, 136));
        JPanel item = new JPanel(new BorderLayout(3, 0)); item.setOpaque(false); item.add(marker, BorderLayout.WEST); item.add(text, BorderLayout.CENTER);
        legend.add(item);
    }

    private void setResultItems(List<?> items) {
        List<Object> selected = resultList.getSelectedValuesList();
        allResultItems.clear(); allResultItems.addAll(items);
        sortObjectItems(allResultItems);
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
        sortObjectItems(allResultItems);
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

    private void sortObjectItems(List<Object> items) {
        Comparator<Object> comparator = switch (objectSortColumn) {
            case 0 -> Comparator.comparingInt(Main::objectStatusRank);
            case 2 -> Comparator.comparing(item -> kindName(objectKey(item).kind()), String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(item -> objectKey(item).qualifiedName(), String.CASE_INSENSITIVE_ORDER);
        };
        if (!objectSortAscending) comparator = comparator.reversed();
        items.sort(comparator.thenComparing(item -> objectKey(item).qualifiedName(), String.CASE_INSENSITIVE_ORDER));
    }

    private static int objectStatusRank(Object item) {
        if (item instanceof CatalogItem catalog) {
            if (!catalog.sourcePresent()) return 2;
            if (!catalog.targetPresent()) return 3;
            return 4; // Aún no comparado.
        }
        return switch (((DiffEngine.Result) item).status()) {
            case IDENTICAL -> 0;
            case CHANGED -> 1;
            case SOURCE_ONLY -> 2;
            case TARGET_ONLY -> 3;
        };
    }

    private static boolean matchesObjectSearch(Object item, String query) {
        return query.isEmpty() || (objectKey(item).qualifiedName() + " " + kindName(objectKey(item).kind()))
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
        if (item != null) setBusy(false, "Objeto seleccionado · " + objectKey(item).qualifiedName());
        if (item instanceof CatalogItem catalog) { loadCatalogSelection(catalog, request); return; }
        if (!(item instanceof DiffEngine.Result selected)) { if (rowComparisonPanel.isExpanded()) rowComparisonPanel.showMessage("Seleccioná una tabla para comparar sus filas."); return; }
        selectedSchemaResult = selected;
        comparisonView.showResult(selected, AppTheme.isDark(theme));
        if (rowComparisonPanel.isExpanded()) {
            if (selected.key().kind() == SchemaObject.Kind.TABLE) compareRowsForSelection();
            else rowComparisonPanel.showMessage("La comparación de filas está disponible para tablas.");
        }
    }

    private void rowPanelExpanded(boolean expanded) {
        if (comparisonSplit != null) comparisonSplit.setDividerLocation(expanded ? .64 : .88);
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
        if (!(source instanceof DatabaseConnector sourceConnector)) {
            rowComparisonPanel.showMessage("La comparación de filas requiere dos bases conectadas; el snapshot solo contiene definiciones SQL.");
            return;
        }
        DatabaseConnector targetConnector = target;
        rowComparisonPanel.showLoading(selected.key().qualifiedName());
        setBusy(true, "Leyendo las filas de " + selected.key().qualifiedName() + "…");
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
                        rowComparisonPanel.showMessage("La comparación terminó, pero no se pudo mostrar el resultado: " + AppUtils.errorMessage(renderError));
                        setBusy(false, "Filas comparadas; error al mostrar el detalle");
                    }
                });
            } catch (SQLException | RuntimeException ex) { SwingUtilities.invokeLater(() -> {
                if (request != rowComparisonRequest || !isSelected(selected.key())) return;
                setBusy(false, "No se compararon las filas"); rowComparisonPanel.showMessage("No se pudieron comparar las filas: " + AppUtils.errorMessage(ex));
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
            connectionStatus.setText("Definición comparada · " + key.qualifiedName());
            if (rowComparisonPanel.isExpanded() && key.kind() == SchemaObject.Kind.TABLE) startRowComparison(result);
            else if (rowComparisonPanel.isExpanded()) rowComparisonPanel.showMessage("La comparación de filas está disponible para tablas.");
            return;
        }
        if (rowComparisonPanel.isExpanded() && key.kind() == SchemaObject.Kind.TABLE) rowComparisonPanel.showLoading(key.qualifiedName());
        else if (rowComparisonPanel.isExpanded()) rowComparisonPanel.showMessage("La comparación de filas está disponible para tablas.");
        comparisonView.showMessage("Cargando y comparando la definición de " + key.qualifiedName() + "…");
        connectionStatus.setText("Leyendo definición de " + key.qualifiedName() + " en segundo plano…");
        SchemaSource sourceConnector = source; DatabaseConnector targetConnector = target;
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
                    connectionStatus.setText("Definición comparada · " + key.qualifiedName());
                    if (rowComparisonPanel.isExpanded() && key.kind() == SchemaObject.Kind.TABLE) startRowComparison(result);
                });
            } catch (SQLException | RuntimeException ex) { SwingUtilities.invokeLater(() -> {
                if (request != selectionRequest || !isSelected(key)) return;
                setBusy(false, "No se pudo cargar la definición");
                comparisonView.showMessage("No se pudo cargar " + key.qualifiedName() + ": " + AppUtils.errorMessage(ex));
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

    private void saveRowSql(RowDiff.Result rows, boolean inserts, boolean updates, boolean deletes) {
        JTextArea preview = area();
        JCheckBox insertChoice = new JCheckBox("INSERT: filas solo en origen", inserts), updateChoice = new JCheckBox("UPDATE: filas diferentes", updates), deleteChoice = new JCheckBox("DELETE: filas solo en destino", deletes);
        deleteChoice.setForeground(new Color(155, 79, 21));
        JCheckBox transaction = new JCheckBox("Envolver el archivo (o cada archivo separado) en una transacción");
        JComboBox<String> fileMode = new JComboBox<>(new String[]{"Un archivo SQL", "Separar por operación (INSERT / UPDATE / DELETE)"});
        fileMode.setToolTipText("Al separar, se genera un archivo por cada tipo de instrucción seleccionado.");
        Runnable refresh = () -> {
            try {
                preview.setText(rowSqlPreview(rows, insertChoice.isSelected(), updateChoice.isSelected(), deleteChoice.isSelected(),
                        transaction.isSelected(), fileMode.getSelectedIndex() == 1, schemaEngine));
                preview.setCaretPosition(0);
            } catch (RuntimeException ex) {
                preview.setText("No se pudo preparar la exportación: " + AppUtils.errorMessage(ex));
            }
        };
        insertChoice.addActionListener(e -> refresh.run()); updateChoice.addActionListener(e -> refresh.run()); deleteChoice.addActionListener(e -> refresh.run());
        transaction.addActionListener(e -> refresh.run()); fileMode.addActionListener(e -> refresh.run());
        JPanel choices = new JPanel(new FlowLayout(FlowLayout.LEFT)); choices.add(insertChoice); choices.add(updateChoice); choices.add(deleteChoice);
        JPanel format = new JPanel(new FlowLayout(FlowLayout.LEFT)); format.add(new JLabel("Formato:")); format.add(fileMode); format.add(transaction);
        JPanel options = new JPanel(); options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS)); options.add(choices); options.add(format);
        JPanel wrapper = new JPanel(new BorderLayout(8, 8)); wrapper.add(options, BorderLayout.NORTH); wrapper.add(new JScrollPane(preview), BorderLayout.CENTER);
        JLabel warning = new JLabel("Solo se exporta; nunca se ejecuta. En modo separado, cada archivo tiene su propia transacción."); warning.setForeground(new Color(155, 79, 21)); wrapper.add(warning, BorderLayout.SOUTH); wrapper.setPreferredSize(new Dimension(900, 540));
        refresh.run();
        if (JOptionPane.showConfirmDialog(frame, wrapper, "Revisar SQL de filas", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        boolean includeInserts = insertChoice.isSelected(), includeUpdates = updateChoice.isSelected(), includeDeletes = deleteChoice.isSelected();
        if (!hasSelectedRowChanges(rows, includeInserts, includeUpdates, includeDeletes)) {
            JOptionPane.showMessageDialog(frame, "No hay filas para exportar con las operaciones seleccionadas.", "Exportación vacía", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            if (fileMode.getSelectedIndex() == 1) {
                exportRowsByOperation(rows, includeInserts, includeUpdates, includeDeletes, transaction.isSelected());
                return;
            }
            JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("db-diff-exportacion-filas.sql"));
            chooser.setDialogTitle("Guardar SQL de filas");
            if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            java.io.File output = chooser.getSelectedFile();
            if (output.exists() && JOptionPane.showConfirmDialog(frame, "El archivo ya existe. ¿Querés reemplazarlo?", "Confirmar reemplazo", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            String sql = RowSqlExporter.export(rows, includeInserts, includeUpdates, includeDeletes, transaction.isSelected(), schemaEngine);
            Files.writeString(output.toPath(), sql, StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(frame, "Se guardó el SQL de filas. No se ejecutó.\n" + output.getAbsolutePath(), "Exportación lista", JOptionPane.INFORMATION_MESSAGE);
        }
        catch (IOException ex) { error("No se pudo guardar la exportación de filas", ex); }
        catch (RuntimeException ex) { error("No se pudo preparar la exportación de filas", ex); }
    }

    private static String rowSqlPreview(RowDiff.Result rows, boolean inserts, boolean updates, boolean deletes,
                                        boolean transaction, boolean separateFiles, DatabaseEngine engine) {
        if (!hasSelectedRowChanges(rows, inserts, updates, deletes))
            return "-- No hay filas para exportar con estas operaciones.\n-- Activá DELETE si solo hay registros presentes en destino.";
        if (!separateFiles) return RowSqlExporter.export(rows, inserts, updates, deletes, transaction, engine);
        StringBuilder preview = new StringBuilder("-- Vista previa: cada bloque se guardará en un archivo separado.\n");
        appendRowSqlSection(preview, "INSERT", rows, inserts, false, false, transaction, engine);
        appendRowSqlSection(preview, "UPDATE", rows, false, updates, false, transaction, engine);
        appendRowSqlSection(preview, "DELETE", rows, false, false, deletes, transaction, engine);
        return preview.toString();
    }

    private static void appendRowSqlSection(StringBuilder preview, String name, RowDiff.Result rows,
                                            boolean inserts, boolean updates, boolean deletes, boolean transaction, DatabaseEngine engine) {
        if (!hasSelectedRowChanges(rows, inserts, updates, deletes)) return;
        preview.append("\n-- Archivo: db-diff-").append(name.toLowerCase(Locale.ROOT)).append(".sql\n");
        preview.append(RowSqlExporter.export(rows, inserts, updates, deletes, transaction, engine));
    }

    private void exportRowsByOperation(RowDiff.Result rows, boolean inserts, boolean updates, boolean deletes, boolean transaction) throws IOException {
        JFileChooser chooser = new JFileChooser(); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Elegir carpeta para exportar SQL por operación");
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path directory = chooser.getSelectedFile().toPath();
        List<Map.Entry<String, String>> files = new ArrayList<>();
        addRowExportFile(files, directory, "db-diff-inserts.sql", rows, inserts, false, false, transaction, schemaEngine);
        addRowExportFile(files, directory, "db-diff-updates.sql", rows, false, updates, false, transaction, schemaEngine);
        addRowExportFile(files, directory, "db-diff-deletes.sql", rows, false, false, deletes, transaction, schemaEngine);
        List<Path> existing = files.stream().map(entry -> Path.of(entry.getKey())).filter(Files::exists).toList();
        if (!existing.isEmpty() && JOptionPane.showConfirmDialog(frame,
                "Se reemplazarán estos archivos:\n" + existing.stream().map(Path::getFileName).map(Path::toString).collect(java.util.stream.Collectors.joining("\n")),
                "Confirmar reemplazo", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        for (Map.Entry<String, String> file : files) Files.writeString(Path.of(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
        JOptionPane.showMessageDialog(frame, "Se guardaron " + files.size() + " archivos SQL en:\n" + directory + "\nNo se ejecutaron.", "Exportación lista", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void addRowExportFile(List<Map.Entry<String, String>> files, Path directory, String fileName,
                                         RowDiff.Result rows, boolean inserts, boolean updates, boolean deletes, boolean transaction, DatabaseEngine engine) {
        if (hasSelectedRowChanges(rows, inserts, updates, deletes))
            files.add(Map.entry(directory.resolve(fileName).toString(), RowSqlExporter.export(rows, inserts, updates, deletes, transaction, engine)));
    }

    private static boolean hasSelectedRowChanges(RowDiff.Result rows, boolean inserts, boolean updates, boolean deletes) {
        return rows.changes().stream().anyMatch(change -> switch (change.status()) {
            case SOURCE_ONLY -> inserts;
            case DIFFERENT -> updates;
            case TARGET_ONLY -> deletes;
        });
    }

    private void export() {
        List<DiffEngine.Result> available = allResultItems.stream().filter(DiffEngine.Result.class::isInstance)
                .map(DiffEngine.Result.class::cast).filter(result -> result.status() != DiffEngine.Status.IDENTICAL).toList();
        if (available.isEmpty()) { JOptionPane.showMessageDialog(frame, "No hay objetos comparados con cambios para exportar.", "Sin cambios", JOptionPane.INFORMATION_MESSAGE); return; }
        EnumSet<DiffEngine.Status> ops = EnumSet.of(DiffEngine.Status.CHANGED, DiffEngine.Status.SOURCE_ONLY, DiffEngine.Status.TARGET_ONLY);
        JTextArea preview = area();
        JCheckBox[] checks = new JCheckBox[3]; JPanel choices = new JPanel(new GridLayout(0, 1));
        boolean sqlServerExport = schemaEngine == DatabaseEngine.SQLSERVER;
        JCheckBox transaction = new JCheckBox(sqlServerExport
                ? "Envolver el script en BEGIN TRANSACTION / COMMIT"
                : "Envolver el script en START TRANSACTION / COMMIT");
        JCheckBox dropIfExists = new JCheckBox(sqlServerExport
                ? "Usar DROP IF EXISTS al eliminar objetos"
                : "Usar DROP IF EXISTS al eliminar objetos o reemplazar rutinas");
        dropIfExists.setToolTipText("Solo evita errores si el objeto ya no existe. DROP TABLE IF EXISTS igualmente elimina la tabla y sus datos si está presente.");
        JComboBox<String> createStrategy = new JComboBox<>(sqlServerExport
                ? new String[]{"ALTER (revisión manual para tablas)", "CREATE", "CREATE OR ALTER"}
                : new String[]{"ALTER TABLE (recomendado)", "CREATE", "CREATE OR REPLACE"});
        createStrategy.setSelectedIndex(0);
        createStrategy.setToolTipText(sqlServerExport
                ? "ALTER modifica vistas y rutinas existentes; los cambios de tabla quedan para revisión. CREATE OR ALTER requiere SQL Server 2016 SP1 o posterior."
                : "Para tablas modificadas, ALTER genera cambios de columnas e índices cuando puede derivarlos. CREATE OR REPLACE en MariaDB elimina los datos de la tabla.");
        JLabel warning = new JLabel();
        JTextArea planPreview = area();
        planPreview.setRows(7);
        List<DiffEngine.Result> currentlySelected = resultList.getSelectedValuesList().stream()
                .filter(DiffEngine.Result.class::isInstance).map(DiffEngine.Result.class::cast).toList();
        DefaultListModel<DiffEngine.Result> exportModel = new DefaultListModel<>();
        JList<DiffEngine.Result> exportSelection = new JList<>(exportModel);
        exportSelection.setCellRenderer(new ResultRenderer());
        exportSelection.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        exportSelection.setVisibleRowCount(4);
        for (DiffEngine.Result result : available) exportModel.addElement(result);
        List<DiffEngine.Result> preselected = currentlySelected.isEmpty() ? available : currentlySelected;
        for (int i = 0; i < exportModel.size(); i++) if (preselected.contains(exportModel.get(i))) exportSelection.addSelectionInterval(i, i);
        JButton selectAll = new JButton("Seleccionar todos");
        JButton clearSelection = new JButton("Limpiar");
        JPanel selectionActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        selectionActions.add(selectAll); selectionActions.add(clearSelection);
        JPanel selectionHeader = new JPanel(new BorderLayout());
        selectionHeader.add(new JLabel("Objetos a exportar (Ctrl/Shift para selección múltiple)"), BorderLayout.WEST);
        selectionHeader.add(selectionActions, BorderLayout.EAST);
        JPanel selectionPanel = new JPanel(new BorderLayout(4, 4));
        selectionPanel.add(selectionHeader, BorderLayout.NORTH);
        selectionPanel.add(new JScrollPane(exportSelection), BorderLayout.CENTER);
        selectionPanel.setPreferredSize(new Dimension(760, 190));
        selectionPanel.setMinimumSize(new Dimension(360, 115));
        Runnable refreshPreview = () -> {
            List<DiffEngine.Result> exportResults = exportSelection.getSelectedValuesList();
            SqlExporter.Strategy strategy = switch (createStrategy.getSelectedIndex()) {
                case 1 -> SqlExporter.Strategy.CREATE;
                case 2 -> sqlServerExport ? SqlExporter.Strategy.CREATE_OR_ALTER : SqlExporter.Strategy.CREATE_OR_REPLACE;
                default -> SqlExporter.Strategy.ALTER;
            };
            String exportSql = SqlExporter.export(exportResults, ops, targetDatabase, transaction.isSelected(), dropIfExists.isSelected(), strategy, schemaEngine, sourceDependencies, targetDependencies);
            preview.setText(exportSql);
            List<String> metadataNotes = new ArrayList<>();
            metadataNotes.addAll(dependencyCoverageNotes("Origen", sourceDependencyReport));
            metadataNotes.addAll(dependencyCoverageNotes("Destino", targetDependencyReport));
            Set<SchemaObject.Key> sourceKeys = sourceObjects.stream().map(SchemaObject::key).collect(java.util.stream.Collectors.toSet());
            Set<SchemaObject.Key> targetKeys = targetObjects.stream().map(SchemaObject::key).collect(java.util.stream.Collectors.toSet());
            planPreview.setText(SqlExportPlan.create(exportResults, ops, sourceDependencies, targetDependencies, sourceKeys, targetKeys,
                    metadataNotes, exportSql).render());
            planPreview.setCaretPosition(0);
            preview.setCaretPosition(0);
            preview.setBackground(ThemePalette.forMode(AppTheme.isDark(theme)).editor());
            preview.setForeground(ThemePalette.forMode(AppTheme.isDark(theme)).foreground());
            if (exportResults.isEmpty()) warning.setText("Seleccioná al menos un objeto para ver el script.");
            else if (schemaEngine == DatabaseEngine.SQLSERVER && strategy == SqlExporter.Strategy.CREATE_OR_ALTER)
                warning.setText("CREATE OR ALTER requiere SQL Server 2016 SP1 o posterior; los cambios de tablas quedan para revisión manual.");
            else if (strategy == SqlExporter.Strategy.CREATE_OR_REPLACE && schemaEngine == DatabaseEngine.MARIADB && exportResults.stream().anyMatch(r -> r.key().kind() == SchemaObject.Kind.TABLE && r.status() == DiffEngine.Status.CHANGED))
                warning.setText("CREATE OR REPLACE TABLE elimina y recrea la tabla destino, incluidos sus datos.");
            else if (strategy == SqlExporter.Strategy.CREATE_OR_REPLACE && schemaEngine == DatabaseEngine.MYSQL)
                warning.setText("MySQL admite CREATE OR REPLACE para vistas. Las tablas modificadas se exportan como ALTER si se pueden derivar.");
            else if (transaction.isSelected() && schemaEngine == DatabaseEngine.SQLSERVER)
                warning.setText("El script usa transacción explícita; revisá cada sentencia y las restricciones del servidor antes de aplicarlo.");
            else if (transaction.isSelected())
                warning.setText("MariaDB puede confirmar DDL automáticamente; la transacción no garantiza rollback del esquema.");
            else warning.setText("El SQL solo se exporta; la aplicación nunca lo ejecuta.");
        };
        DiffEngine.Status[] statuses = {DiffEngine.Status.SOURCE_ONLY, DiffEngine.Status.CHANGED, DiffEngine.Status.TARGET_ONLY};
        String[] labels = {"CREATE: objetos solo en origen", sqlServerExport ? "ALTER / CREATE OR ALTER: objetos modificados" : "ALTER / reemplazar: objetos modificados", "DROP: objetos solo en destino"};
        for (int i = 0; i < checks.length; i++) {
            checks[i] = new JCheckBox(labels[i], i != 2);
            final DiffEngine.Status status = statuses[i];
            final JCheckBox check = checks[i];
            checks[i].addActionListener(e -> {
                if (check.isSelected()) ops.add(status); else ops.remove(status);
                refreshPreview.run();
            });
            choices.add(checks[i]);
        }
        transaction.addActionListener(event -> refreshPreview.run());
        dropIfExists.addActionListener(event -> refreshPreview.run());
        createStrategy.addActionListener(event -> refreshPreview.run());
        exportSelection.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) refreshPreview.run(); });
        selectAll.addActionListener(event -> { if (!exportModel.isEmpty()) exportSelection.setSelectionInterval(0, exportModel.size() - 1); });
        clearSelection.addActionListener(event -> exportSelection.clearSelection());
        choices.add(transaction); choices.add(dropIfExists);
        JPanel strategy = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel strategyLabel = new JLabel("Estrategia para objetos modificados:");
        boolean dark = AppTheme.isDark(theme); ThemePalette palette = ThemePalette.forMode(dark);
        strategyLabel.setForeground(palette.foreground()); createStrategy.setBackground(palette.panel()); createStrategy.setForeground(palette.foreground());
        warning.setForeground(dark ? new Color(236, 184, 95) : new Color(155, 79, 21));
        strategy.add(strategyLabel); strategy.add(createStrategy);
        JPanel optionStack = new JPanel(new BorderLayout(4, 4)); optionStack.add(choices, BorderLayout.NORTH); optionStack.add(strategy, BorderLayout.SOUTH);
        JPanel previewPanel = new JPanel(new BorderLayout(6, 6));
        previewPanel.add(optionStack, BorderLayout.NORTH);
        JSplitPane reviewAndSql = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(planPreview), new JScrollPane(preview));
        reviewAndSql.setContinuousLayout(true); reviewAndSql.setOneTouchExpandable(true);
        reviewAndSql.setResizeWeight(.23); reviewAndSql.setDividerSize(8); reviewAndSql.setDividerLocation(.23);
        previewPanel.add(reviewAndSql, BorderLayout.CENTER);
        previewPanel.setMinimumSize(new Dimension(360, 250));
        JSplitPane exportSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, selectionPanel, previewPanel);
        exportSplit.setContinuousLayout(true);
        exportSplit.setOneTouchExpandable(true);
        exportSplit.setResizeWeight(.27);
        exportSplit.setDividerSize(9);
        exportSplit.setDividerLocation(.27);
        refreshPreview.run();
        JPanel wrapper = new JPanel(new BorderLayout(8, 8)); wrapper.add(exportSplit, BorderLayout.CENTER); wrapper.setPreferredSize(new Dimension(900, 740));
        wrapper.add(warning, BorderLayout.SOUTH);
        JOptionPane optionPane = new JOptionPane(wrapper, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        JDialog dialog = optionPane.createDialog(frame, "Revisar exportación SQL");
        dialog.setResizable(true);
        dialog.setMinimumSize(new Dimension(760, 600));
        dialog.setSize(new Dimension(930, 770));
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        if (!Integer.valueOf(JOptionPane.OK_OPTION).equals(optionPane.getValue())) return;
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("db-diff-exportacion.sql")); if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        try { Files.writeString(chooser.getSelectedFile().toPath(), preview.getText(), StandardCharsets.UTF_8); JOptionPane.showMessageDialog(frame, "Se guardó el SQL. No se ejecutó.", "Exportación lista", JOptionPane.INFORMATION_MESSAGE); }
        catch (IOException ex) { error("No se pudo guardar la exportación", ex); }
    }
    private void setBusy(boolean busy, String text) {
        if (busy || !schemaComparisonRunning) connectionStatus.setText(text);
        boolean active = busy || schemaComparisonRunning;
        activityIndicator.setVisible(active);
        frame.setCursor(active ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    /** Keep workspace actions unavailable until both catalogs are connected. */
    private void setConnectionAvailable(boolean available) {
        if (leftPanel != null) setEnabledRecursively(leftPanel, available);
        if (workspaceSplit != null) setEnabledRecursively(workspaceSplit.getRightComponent(), available);
        if (compareButton != null) compareButton.setEnabled(available);
        if (exportButton != null) exportButton.setEnabled(available);
        if (modeSelector != null) modeSelector.setEnabled(available);
        if (chooseSourceButton != null) chooseSourceButton.setEnabled(available && comparisonMode != ComparisonMode.DATABASES);
        if (rowComparisonPanel != null) setEnabledRecursively(rowComparisonPanel, available && source instanceof DatabaseConnector);
    }

    private static void setEnabledRecursively(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container container)
            for (Component child : container.getComponents()) setEnabledRecursively(child, enabled);
    }

    private static long countChanged(List<DiffEngine.Result> values) { return values.stream().filter(r -> r.status() != DiffEngine.Status.IDENTICAL).count(); }
    private void error(String title, Exception ex) { JOptionPane.showMessageDialog(frame, ex.getMessage() == null ? ex.toString() : ex.getMessage(), title, JOptionPane.ERROR_MESSAGE); }
    private static List<String> dependencyCoverageNotes(String side, DependencyReport report) {
        List<String> notes = new ArrayList<>();
        if (report.coverage().isEmpty()) notes.add(side + ": cobertura de dependencias no disponible.");
        else {
            String summary = report.coverage().stream()
                    .map(item -> item.source() + " " + item.edges() + (item.warning().isBlank() ? "" : " (" + item.warning() + ")"))
                    .collect(java.util.stream.Collectors.joining(", "));
            notes.add(side + " · relaciones encontradas en catálogos: " + summary + ".");
        }
        report.limitations().forEach(limitation -> notes.add(side + " · " + limitation));
        return notes;
    }
    private void refreshDependencyStatus() {
        List<String> notes = new ArrayList<>();
        notes.addAll(dependencyCoverageNotes("Origen", sourceDependencyReport));
        notes.addAll(dependencyCoverageNotes("Destino", targetDependencyReport));
        connectionStatus.setToolTipText(String.join("\n", notes));
    }
    private void closeConnectors() {
        if (source != null) source.close();
        if (databaseSource != null && databaseSource != source) databaseSource.close();
        if (target != null) target.close();
        source = databaseSource = target = null; sourceDependencies = targetDependencies = Map.of();
        databaseSourceObjects = List.of(); databaseSourceName = "";
        if (flywayEndpoint != null) flywayEndpoint.clearPassword(); flywayEndpoint = null;
        sourceDependencyReport = new DependencyReport(Map.of(), List.of(), List.of());
        targetDependencyReport = new DependencyReport(Map.of(), List.of(), List.of());
    }
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
            String configuredFont = values.getProperty("codeFontFamily", "");
            codeFontFamily = DiffCodeEditor.installedFamily(configuredFont) == null ? "" : configuredFont;
            try { codeFontSize = Math.clamp(Integer.parseInt(values.getProperty("codeFontSize", "13")), 10, 24); }
            catch (NumberFormatException ignored) { codeFontSize = 13; }
        } catch (IOException ignored) { }
    }

    private void saveUiPreferences() {
        try {
            Files.createDirectories(UI_PREFERENCES.getParent());
            java.util.Properties values = new java.util.Properties(); values.setProperty("theme", theme); values.setProperty("stackedLayout", Boolean.toString(stackedLayout)); values.setProperty("codeFontSize", Integer.toString(codeFontSize)); values.setProperty("codeFontFamily", codeFontFamily);
            try (var output = Files.newOutputStream(UI_PREFERENCES)) { values.store(output, AppInfo.NAME + " appearance"); }
        } catch (IOException ex) { connectionStatus.setText("No se pudo guardar la apariencia: " + ex.getMessage()); }
    }

    private void applyTheme() {
        AppTheme.apply(frame, headerPanel, theme);
        boolean dark = AppTheme.isDark(theme);
        comparisonView.setThemeColors(dark);
        rowComparisonPanel.setThemeColors(dark);
        comparisonView.setCodeFont(codeFontFamily, codeFontSize);
        ThemePalette palette = ThemePalette.forMode(dark);
        activityIndicator.setForeground(dark ? new Color(93, 160, 245) : new Color(49, 112, 199));
        activityIndicator.setBackground(palette.panel());
        if (modeBarPanel != null) {
            modeBarPanel.setBackground(dark ? palette.panel() : new Color(237, 241, 246));
            modeTitle.setForeground(palette.foreground());
            modeDescription.setForeground(palette.foreground());
            modeBarPanel.repaint();
        }
        updateModeBar();
        if (comparisonSplit != null) comparisonSplit.setBackground(palette.border());
        objectSearchBox.setBackground(palette.panel());
        objectSearchBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.border()), new EmptyBorder(1, 8, 1, 7)));
        objectSearchIcon.setForeground(palette.foreground());
        objectSearch.setBackground(palette.editor()); objectSearch.setForeground(palette.foreground());
        objectSearch.setCaretColor(palette.foreground());
        if (objectSearch instanceof PlaceholderTextField field)
            field.setPlaceholderColor(dark ? new Color(142, 151, 164) : new Color(120, 130, 143));
        Color[] lightLegendColors = {new Color(49, 130, 97), new Color(178, 112, 24), new Color(56, 112, 184), new Color(111, 122, 140)};
        Color[] darkLegendColors = {new Color(111, 204, 148), new Color(230, 174, 85), new Color(107, 166, 239), new Color(154, 166, 181)};
        for (int i = 0; i < objectLegendMarkers.size(); i++) objectLegendMarkers.get(i).setForeground(dark ? darkLegendColors[i] : lightLegendColors[i]);
    }

    private void showSettings() {
        JComboBox<String> themeChoice = new JComboBox<>(new String[]{"Sistema", "Claro", "Oscuro"});
        themeChoice.setSelectedItem(theme);
        String automaticFont = "Automática (JetBrains Mono / Consolas)";
        JComboBox<String> codeFontChoice = new JComboBox<>();
        codeFontChoice.addItem(automaticFont);
        for (String family : DiffCodeEditor.availableCodeFontFamilies()) codeFontChoice.addItem(family);
        codeFontChoice.setSelectedItem(codeFontFamily.isBlank() ? automaticFont : DiffCodeEditor.installedFamily(codeFontFamily));
        JSpinner fontSize = new JSpinner(new javax.swing.SpinnerNumberModel(codeFontSize, 10, 24, 1));
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(7, 6, 7, 6); c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0; form.add(new JLabel("Tema"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; form.add(themeChoice, c);
        c.gridx = 0; c.gridy = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0; form.add(new JLabel("Fuente del código"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; form.add(codeFontChoice, c);
        c.gridx = 0; c.gridy = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0; form.add(new JLabel("Tamaño de fuente"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; form.add(fontSize, c);
        JLabel fontInfo = new JLabel("La opción automática prefiere JetBrains Mono, luego Consolas y finalmente una fuente monoespaciada del sistema.");
        fontInfo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        JLabel storageInfo = new JLabel("Perfiles y preferencias: " + Path.of(System.getProperty("user.home"), ".db-diff-studio").toAbsolutePath()
                + " · contraseñas: almacén seguro del sistema operativo.");
        storageInfo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        JPanel notes = new JPanel(); notes.setLayout(new BoxLayout(notes, BoxLayout.Y_AXIS));
        notes.add(fontInfo); notes.add(Box.createVerticalStrut(5)); notes.add(storageInfo);
        JButton cancel = new JButton("Cancelar"), save = new JButton("Guardar");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); actions.add(cancel); actions.add(save);
        JPanel content = new JPanel(new BorderLayout(8, 12)); content.setBorder(new EmptyBorder(12, 14, 12, 14));
        content.add(form, BorderLayout.CENTER); content.add(notes, BorderLayout.SOUTH);
        JDialog dialog = new JDialog(frame, "Configuración", true);
        dialog.setLayout(new BorderLayout(0, 8)); dialog.add(content, BorderLayout.CENTER); dialog.add(actions, BorderLayout.SOUTH);
        cancel.addActionListener(event -> dialog.dispose());
        save.addActionListener(event -> {
            theme = (String) themeChoice.getSelectedItem(); codeFontSize = (Integer) fontSize.getValue();
            String selectedFont = (String) codeFontChoice.getSelectedItem();
            codeFontFamily = automaticFont.equals(selectedFont) ? "" : DiffCodeEditor.installedFamily(selectedFont);
            if (codeFontFamily == null) codeFontFamily = "";
            saveUiPreferences(); applyTheme(); dialog.dispose();
        });
        dialog.getRootPane().setDefaultButton(save);
        dialog.pack(); AppTheme.apply(dialog, null, theme); dialog.setMinimumSize(new Dimension(590, dialog.getHeight()));
        dialog.setLocationRelativeTo(frame); dialog.setVisible(true);
    }

    private static final class ResultRenderer extends JPanel implements ListCellRenderer<Object> {
        private final JLabel marker = new JLabel(" ", JLabel.CENTER);
        private final JLabel name = new JLabel();
        private final JLabel type = new JLabel();

        ResultRenderer() {
            super(new BorderLayout(4, 0));
        marker.setPreferredSize(new Dimension(58, 20));
        type.setPreferredSize(new Dimension(120, 20));
            add(marker, BorderLayout.WEST); add(name, BorderLayout.CENTER); add(type, BorderLayout.EAST);
            setBorder(new EmptyBorder(5, 6, 5, 6));
            name.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            type.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        }

        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            SchemaObject.Key key = objectKey(value);
            boolean enabled = list.isEnabled();
            name.setText(key.qualifiedName());
            type.setText(kindName(key.kind()));
            Color foreground = !enabled ? (list.getBackground().getRed() < 90 ? new Color(112, 121, 134) : new Color(155, 163, 174))
                    : selected ? list.getSelectionForeground() : list.getForeground();
            setBackground(selected && enabled ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(foreground); type.setForeground(selected ? foreground : new Color(105, 118, 136));
            marker.setForeground(enabled ? markerColor(value, list.getBackground().getRed() < 90) : foreground);
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
            int status = objectStatusRank(value);
            if (status == 2) return UiIcons.themed(UiIcons.SOURCE_ONLY, 20);
            if (status == 3) return UiIcons.themed(UiIcons.TARGET_ONLY, 20);
            return null;
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
        @Override public String toString() { return key.qualifiedName() + " · " + kindName(key.kind()) + " · sin comparar"; }
    }

    private static String kindName(SchemaObject.Kind kind) {
        return switch (kind) { case TABLE -> "tabla"; case VIEW -> "vista"; case PROCEDURE -> "procedimiento"; case FUNCTION -> "función"; case TRIGGER -> "trigger"; };
    }

    private enum ComparisonMode {
        DATABASES("Bases de datos", "Compara las dos bases conectadas."),
        SNAPSHOT("Snapshot SQL → base", "Compara definiciones CREATE completas de una carpeta con la base destino; no ejecuta el SQL."),
        FLYWAY("Flyway → base", "Compara el esquema reconstruido en memoria con la base destino; no vuelve a conectarse al origen.");

        private final String label;
        private final String description;

        ComparisonMode(String label, String description) { this.label = label; this.description = description; }
        @Override public String toString() { return label; }
    }

}
