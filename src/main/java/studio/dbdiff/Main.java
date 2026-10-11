package studio.dbdiff;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;

public final class Main {
    private static final Path UI_PREFERENCES = Path.of(System.getProperty("user.home"), ".db-diff-studio", "ui.properties");
    private final JFrame frame = new JFrame(AppInfo.NAME);
    private ObjectCatalogPanel objectCatalog;
    private final ComparisonView comparisonView = new ComparisonView();
    private final RowComparisonPanel rowComparisonPanel = new RowComparisonPanel((rows, options) ->
            saveRowSql(rows, options.inserts(), options.updates(), options.deletes()), this::rowPanelExpanded,
            this::reconfigureRowComparisonKey);
    private final JLabel connectionStatus = new JLabel("Sin conexión");
    private final JProgressBar activityIndicator = new JProgressBar();
    private final EnumSet<SchemaObject.Kind> selectedKinds = EnumSet.allOf(SchemaObject.Kind.class);
    private final Map<SchemaObject.Key, List<String>> customRowKeys = new HashMap<>();
    private SchemaSource source;
    private DatabaseConnector target;
    private DatabaseEngine schemaEngine = DatabaseEngine.MARIADB;
    private String sourceDatabase = "", targetDatabase = "";
    private List<SchemaObject> sourceObjects = List.of(), targetObjects = List.of();
    private Map<SchemaObject.Key, Set<SchemaObject.Key>> sourceDependencies = Map.of(), targetDependencies = Map.of();
    private DependencyReport sourceDependencyReport = new DependencyReport(Map.of(), List.of(), List.of());
    private DependencyReport targetDependencyReport = new DependencyReport(Map.of(), List.of(), List.of());
    private boolean compared;
    private boolean schemaComparisonRunning;
    private boolean stackedLayout;
    private String theme = "Sistema";
    private int codeFontSize = 13;
    private String codeFontFamily = "";
    private boolean rememberWindowBounds;
    private boolean startMaximized;
    private boolean keepRowComparisonExpanded;
    private boolean rememberLastComparison;
    private JPanel headerPanel, leftPanel;
    private JSplitPane workspaceSplit;
    private JSplitPane comparisonSplit;
    private JButton compareButton, exportButton;
    private final Set<SchemaObject.Key> comparedKeys = new TreeSet<>();
    private final Set<SchemaObject.Key> loadedSourceKeys = ConcurrentHashMap.newKeySet();
    private final Set<SchemaObject.Key> loadedTargetKeys = ConcurrentHashMap.newKeySet();
    private DiffEngine.Result selectedSchemaResult;
    private ConnectionDialog.Selection currentSelection;
    private long selectionRequest;
    private long rowComparisonRequest;
    private volatile long comparisonRequest;
    private long elapsedStartedAtNanos;
    private String elapsedDetail = "";
    private final javax.swing.Timer elapsedTimer = new javax.swing.Timer(1000, event -> refreshElapsedStatus());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "schema-reader"); t.setDaemon(true); return t; });

    private Main() { }
    public static void main(String[] args) { SwingUtilities.invokeLater(() -> { AppTheme.initialize(startupTheme()); new Main().show(); }); }

    private static String startupTheme() {
        java.util.Properties preferences = new java.util.Properties();
        try (var input = Files.newInputStream(UI_PREFERENCES)) { preferences.load(input); }
        catch (IOException ignored) { }
        return preferences.getProperty("theme", "Sistema");
    }

    private void show() {
        loadUiPreferences();
        frame.setIconImages(UiIcons.windowImages());
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { close(); }
            @Override public void windowDeactivated(WindowEvent e) { saveWindowBounds(); }
        });
        frame.setMinimumSize(new Dimension(1020, 680));
        Rectangle savedBounds = loadWindowBounds();
        frame.setSize(savedBounds == null ? new Dimension(1260, 820) : savedBounds.getSize());
        frame.setLocation(savedBounds == null ? centeredLocation(frame.getSize()) : savedBounds.getLocation());
        frame.setLayout(new BorderLayout(0, 0)); frame.add(header(), BorderLayout.NORTH); frame.add(content(), BorderLayout.CENTER); frame.add(statusBar(), BorderLayout.SOUTH);
        if (startMaximized) frame.setExtendedState(frame.getExtendedState() | JFrame.MAXIMIZED_BOTH);
        setConnectionAvailable(false);
        comparisonView.showMessage("Elegí Nueva comparación para configurar el origen y la base destino. La comparación del esquema comienza automáticamente.");
        applyTheme(); frame.setVisible(true);
        SwingUtilities.invokeLater(() -> {
            comparisonSplit.setDividerLocation(.89);
            // The opt-in restore path uses the same connection dialog so stale credentials or files remain editable.
            LastComparisonPreferences.SavedComparison last = rememberLastComparison
                    ? LastComparisonPreferences.load().orElse(null) : null;
            if (last == null) connect();
            else connect(last, true);
        });
    }

    private JComponent header() {
        JPanel panel = new JPanel(new HeaderLayout()); headerPanel = panel; panel.putClientProperty("dbdiff.header", Boolean.TRUE); panel.setBorder(new EmptyBorder(10, 16, 10, 16));
        JPanel brand = new JPanel(new BorderLayout(12, 0)); brand.setOpaque(false);
        JLabel logo = new JLabel(UiIcons.themed(UiIcons.CONNECT, 46)); logo.setBorder(new EmptyBorder(0, 0, 0, 2));
        JLabel title = new JLabel(AppInfo.NAME); title.putClientProperty("FlatLaf.styleClass", "h2");
        JLabel version = new JLabel("versión " + AppInfo.VERSION);
        version.setFont(javax.swing.UIManager.getFont("Label.font").deriveFont(Font.PLAIN, 11f));
        version.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        JPanel brandText = new JPanel(); brandText.setOpaque(false); brandText.setLayout(new BoxLayout(brandText, BoxLayout.Y_AXIS));
        brandText.add(title); brandText.add(version);
        brand.add(logo, BorderLayout.WEST); brand.add(brandText, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4)); actions.setOpaque(false);
        JButton connect = button("Nueva comparación", true); connect.setIcon(UiIcons.themed(UiIcons.CONNECT, 16)); connect.setIconTextGap(7);  connect.setToolTipText("Elegir el modo de origen y configurar las conexiones."); connect.addActionListener(e -> connect());
        compareButton = button("Actualizar", false); compareButton.setIcon(UiIcons.themed(UiIcons.COMPARE, 16)); compareButton.setIconTextGap(7); compareButton.setToolTipText("Volver a leer los objetos; con selección múltiple, actualizar los seleccionados."); compareButton.addActionListener(e -> compare(true));
        exportButton = button("Exportar esquema", false); exportButton.setIcon(UiIcons.themed(UiIcons.EXPORT, 16)); exportButton.setIconTextGap(7); exportButton.addActionListener(e -> export());
        JButton settings = button("Configuración", false); settings.setIcon(UiIcons.themed(UiIcons.SETTINGS, 16)); settings.setIconTextGap(6);
        settings.setToolTipText("Apariencia, ventana y comportamiento del comparador"); settings.addActionListener(e -> showSettings());
        actions.add(connect); actions.add(compareButton); actions.add(exportButton); actions.add(settings); panel.add(brand, BorderLayout.WEST); panel.add(actions, BorderLayout.EAST); return panel;
    }

    private JComponent content() {
        JPanel split = new JPanel(new BorderLayout(0, 0));
        JPanel left = new JPanel(new BorderLayout()); leftPanel = left; left.setBorder(new EmptyBorder(16, 16, 16, 12)); left.setPreferredSize(new Dimension(370, 100));
        objectCatalog = new ObjectCatalogPanel(this::selectResult,
                () -> comparisonView.showMessage("No hay objetos que coincidan con los filtros. Cambiá la búsqueda o los tipos seleccionados."),
                this::kindMenu, this::showCatalogDiagnostics);
        left.add(objectCatalog, BorderLayout.CENTER);
        JPanel right = new JPanel(new BorderLayout(0, 8)); right.setBorder(new EmptyBorder(15, 12, 15, 16));
        comparisonSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, comparisonView, rowComparisonPanel);
        comparisonSplit.setResizeWeight(.72); comparisonSplit.setDividerSize(9); comparisonSplit.setContinuousLayout(true);
        comparisonSplit.setBorder(null); comparisonView.setMinimumSize(new Dimension(100, 180));
        rowComparisonPanel.setMinimumSize(new Dimension(100, 48));
        right.add(comparisonSplit, BorderLayout.CENTER);
        workspaceSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right); workspaceSplit.setResizeWeight(.29); workspaceSplit.setDividerLocation(360); workspaceSplit.setBorder(null); split.add(workspaceSplit, BorderLayout.CENTER);
        updateLayout();
        return split;
    }

    private JComponent statusBar() {
        JPanel p = new JPanel(new BorderLayout(12, 0));
        p.setBorder(new EmptyBorder(7, 16, 7, 16));
        p.add(connectionStatus, BorderLayout.CENTER);
        activityIndicator.setIndeterminate(true); activityIndicator.setBorderPainted(false);
        activityIndicator.setPreferredSize(new Dimension(130, 12)); activityIndicator.setVisible(false);
        activityIndicator.setToolTipText("La operación sigue ejecutándose en segundo plano.");
        p.add(activityIndicator, BorderLayout.EAST);
        connectionStatus.setFont(javax.swing.UIManager.getFont("Label.font"));
        return p;
    }
    private static JButton button(String name, boolean primary) { return new JButton(name); }

    private void kindMenu(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        for (SchemaObject.Kind kind : SchemaObject.Kind.values()) { JCheckBoxMenuItem item = new JCheckBoxMenuItem(CatalogDisplay.kindName(kind), selectedKinds.contains(kind)); item.addActionListener(e -> { if (item.isSelected()) selectedKinds.add(kind); else selectedKinds.remove(kind); refreshResults(); }); menu.add(item); }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void connect() {
        connect(null, true);
    }

    private void connect(LastComparisonPreferences.SavedComparison lastComparison, boolean attemptAutoConnect) {
        ConnectionDialog.Selection selection = ConnectionDialog.showDialog(frame, theme, lastComparison, attemptAutoConnect);
        if (selection == null) {
            if (source == null || target == null) {
                comparisonView.showMessage("Conectá dos bases para empezar a comparar.");
                setBusy(false, "Sin conexión · conectá las bases para habilitar la comparación");
            }
            return;
        }
        // Una búsqueda puede contener el esquema de SQL Server (por ejemplo,
        // "dbo.clientes"). No arrastrarla a una nueva conexión confunde el
        // listado del nuevo catálogo con tablas ausentes.
        objectCatalog.clearSearch();
        if (selection.mode() == ConnectionDialog.SourceMode.FLYWAY) {
            Endpoint destination = selection.destination();
            String message = "Flyway ejecutará las migraciones de:\n" + selection.sourceFolder() + "\n\n"
                    + "Se creará una base temporal de nombre único en el servidor de destino "
                    + destination.host() + ":" + destination.port() + " (" + destination.engine() + "). "
                    + "Se capturará el esquema y la aplicación borrará la base temporal antes de comparar.\n\n"
                    + "No se modificará directamente la base de destino. Revisá las migraciones: SQL que haga referencia explícita "
                    + "a otras bases o servidores podría afectarlos. Se requieren permisos para crear y borrar bases.\n\n"
                    + "¿Autorizar la creación de la base temporal y ejecutar Flyway?";
            if (JOptionPane.showConfirmDialog(frame, message, "Reconstruir esquema desde Flyway",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                destination.clearPassword();
                if (selection.origin() != null) selection.origin().clearPassword();
                return;
            }
        }
        comparisonRequest++; selectionRequest++; rowComparisonRequest++;
        schemaComparisonRunning = false;
        String modeStatus = switch (selection.mode()) {
            case DATABASE -> "Conectando las dos bases…";
            case SNAPSHOT -> "Conectando el destino y leyendo el snapshot SQL…";
            case FLYWAY -> "Preparando la base temporal Flyway…";
        };
        setBusy(true, modeStatus);
        long request = comparisonRequest;
        worker.submit(() -> {
            try {
                PreparedComparison prepared = prepareComparison(selection);
                SwingUtilities.invokeLater(() -> {
                    if (request != comparisonRequest) { prepared.close(); return; }
                    closeConnectors();
                    currentSelection = selection;
                    updateExportButton();
                    if (rememberLastComparison) saveLastComparison(selection);
                    source = prepared.source(); target = prepared.target();
                    sourceDependencyReport = prepared.sourceDependencies(); targetDependencyReport = prepared.targetDependencies();
                    sourceDependencies = sourceDependencyReport.dependencies(); targetDependencies = targetDependencyReport.dependencies();
                    schemaEngine = prepared.engine();
                    sourceDatabase = prepared.sourceName(); targetDatabase = prepared.targetName();
                    sourceObjects = prepared.sourceObjects(); targetObjects = prepared.targetObjects();
                    customRowKeys.clear();
                    comparisonView.setFlywayMode(selection.mode() == ConnectionDialog.SourceMode.FLYWAY);
                    String sourceServer = selection.mode() == ConnectionDialog.SourceMode.DATABASE
                            ? endpointAddress(selection.origin()) : "";
                    String sourcePath = selection.sourceFolder() == null ? null
                            : selection.sourceFolder().toAbsolutePath().normalize().toString();
                    comparisonView.setDatabases(sourceDatabase, targetDatabase, sourceServer,
                            endpointAddress(selection.destination()), sourcePath,
                            selection.originProfile(), selection.destinationProfile());
                    loadedSourceKeys.clear(); loadedTargetKeys.clear(); selectedSchemaResult = null;
                    compared = false; comparedKeys.clear(); rowComparisonPanel.setExpanded(false); rowComparisonPanel.clearResult(false);
                    showCatalog(); setConnectionAvailable(true); refreshDependencyStatus();
                    if (chooseInitialObjectKinds()) compare(false);
                    else setBusy(false, "Catálogo listo · elegí los tipos y pulsá Actualizar para comparar");
                });
            } catch (SQLException | RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    if (request != comparisonRequest) return;
                    setBusy(false, "No se pudo preparar la comparación"); error("Error al conectar o cargar el origen", ex);
                    // A basic connection test can pass while catalog loading fails; reopen the saved settings for correction.
                    if (selection.autoRestored()) connect(LastComparisonPreferences.SavedComparison.from(selection), false);
                });
            } finally {
                selection.destination().clearPassword();
                if (selection.origin() != null) selection.origin().clearPassword();
            }
        });
    }

    private PreparedComparison prepareComparison(ConnectionDialog.Selection selection) throws SQLException {
        DatabaseConnector originConnection = null;
        DatabaseConnector destinationConnection = null;
        SchemaSource loadedSource = null;
        try {
            Endpoint destinationEndpoint = selection.destination();
            String sourceName;
            if (selection.mode() == ConnectionDialog.SourceMode.DATABASE) {
                originConnection = DatabaseConnectorFactory.create(selection.origin());
                originConnection.connect(selection.origin());
                loadedSource = originConnection;
                sourceName = selection.origin().database();
            } else if (selection.mode() == ConnectionDialog.SourceMode.SNAPSHOT) {
                loadedSource = new SqlSnapshotSource(selection.sourceFolder(), destinationEndpoint.engine());
                sourceName = sourceFolderLabel("Snapshot", selection.sourceFolder());
            } else {
                loadedSource = FlywayReconstructionSource.build(selection.sourceFolder(), destinationEndpoint);
                sourceName = sourceFolderLabel("Flyway", selection.sourceFolder());
            }

            List<SchemaObject> sourceCatalog = loadedSource.listObjects();
            DependencyReport sourceReport = selection.mode() == ConnectionDialog.SourceMode.SNAPSHOT
                    ? DependencyReport.unknown(Map.of(), "El snapshot SQL no aporta un catálogo confiable de dependencias.")
                    : safeDependencyReport(loadedSource);
            destinationConnection = DatabaseConnectorFactory.create(destinationEndpoint);
            destinationConnection.connect(destinationEndpoint);
            List<SchemaObject> destinationCatalog = destinationConnection.listObjects();
            DependencyReport destinationReport = safeDependencyReport(destinationConnection);
            return new PreparedComparison(loadedSource, destinationConnection, sourceCatalog, destinationCatalog,
                    sourceReport, destinationReport, sourceName, destinationEndpoint.database(), destinationEndpoint.engine());
        } catch (SQLException | RuntimeException ex) {
            if (loadedSource != null) loadedSource.close();
            if (originConnection != null && loadedSource != originConnection) originConnection.close();
            if (destinationConnection != null) destinationConnection.close();
            throw ex;
        }
    }

    private static String sourceFolderLabel(String kind, Path folder) {
        Path name = folder.toAbsolutePath().normalize().getFileName();
        String displayName = name == null || name.toString().isBlank()
                ? folder.toAbsolutePath().normalize().toString() : name.toString();
        return kind + ": " + displayName;
    }

    private static String endpointAddress(Endpoint endpoint) {
        return endpoint.host() + ":" + endpoint.port();
    }

    private void saveLastComparison(ConnectionDialog.Selection selection) {
        try { LastComparisonPreferences.save(selection); }
        catch (IOException ex) { connectionStatus.setText("No se pudo recordar la última comparación: " + ex.getMessage()); }
    }

    private record PreparedComparison(SchemaSource source, DatabaseConnector target,
                                      List<SchemaObject> sourceObjects, List<SchemaObject> targetObjects,
                                      DependencyReport sourceDependencies, DependencyReport targetDependencies,
                                      String sourceName, String targetName, DatabaseEngine engine) {
        void close() { source.close(); target.close(); }
    }

    private static DependencyReport safeDependencyReport(SchemaSource connector) {
        try { return connector.dependencyReport(); }
        catch (SQLException ex) {
            return DependencyReport.unknown(Map.of(), "No se pudieron consultar los metadatos de dependencias: " + AppUtils.errorMessage(ex));
        }
    }

    private void compare(boolean refreshCatalog) {
        if (source == null || target == null) { JOptionPane.showMessageDialog(frame, "Conectá ambas bases antes de comparar.", "Bases sin conexión", JOptionPane.INFORMATION_MESSAGE); return; }
        long request = ++comparisonRequest;
        selectionRequest++;
        rowComparisonRequest++;
        Set<SchemaObject.Key> selectedKeys = refreshCatalog && objectCatalog.selectedValues().size() > 1
                ? selectedObjectKeys() : Set.of();
        if (refreshCatalog) objectCatalog.clearSelection();
        Set<SchemaObject.Kind> kinds = Set.copyOf(selectedKinds);
        Set<SchemaObject.Key> loadedA = Set.copyOf(loadedSourceKeys), loadedB = Set.copyOf(loadedTargetKeys);
        List<SchemaObject> existingSourceCatalog = sourceObjects, existingTargetCatalog = targetObjects;
        SchemaSource sourceConnector = source; DatabaseConnector targetConnector = target;
        schemaComparisonRunning = true;
        setBusy(true, refreshCatalog ? "Actualizando catálogo y comparación del esquema…" : "Comparando definiciones del esquema…");
        // Las lecturas JDBC y el diff corren fuera del EDT. El contador invalida
        // respuestas viejas si el usuario inicia otra comparación mientras tanto.
        worker.submit(() -> {
            try {
                List<SchemaObject> sourceCatalog = refreshCatalog ? sourceConnector.listObjects() : existingSourceCatalog;
                ensureComparisonCurrent(request);
                List<SchemaObject> targetCatalog = refreshCatalog ? targetConnector.listObjects() : existingTargetCatalog;
                ensureComparisonCurrent(request);
                DependencyReport refreshedSource = refreshCatalog && sourceConnector instanceof DatabaseConnector
                        ? safeDependencyReport(sourceConnector) : sourceDependencyReport;
                DependencyReport refreshedTarget = refreshCatalog ? safeDependencyReport(targetConnector) : targetDependencyReport;
                ensureComparisonCurrent(request);
                Set<SchemaObject.Key> sourceCache = refreshCatalog ? Set.of() : loadedA;
                Set<SchemaObject.Key> targetCache = refreshCatalog ? Set.of() : loadedB;
                int totalObjects = countSelected(sourceCatalog, kinds, selectedKeys) + countSelected(targetCatalog, kinds, selectedKeys);
                SwingUtilities.invokeLater(() -> {
                    if (request == comparisonRequest && schemaComparisonRunning && totalObjects > 0)
                        showProgress(0, totalObjects, "Leyendo definiciones");
                });
                int[] completed = {0};
                IntConsumer progress = ignored -> {
                    int value = ++completed[0];
                    SwingUtilities.invokeLater(() -> {
                        if (request == comparisonRequest && schemaComparisonRunning)
                            showProgress(value, totalObjects, "Leyendo definiciones");
                    });
                };
                BooleanSupplier current = () -> request == comparisonRequest;
                List<SchemaObject> a = loadSelected(sourceConnector, sourceCatalog, kinds, selectedKeys, sourceCache, progress, current);
                List<SchemaObject> b = loadSelected(targetConnector, targetCatalog, kinds, selectedKeys, targetCache, progress, current);
                ensureComparisonCurrent(request);
                List<DiffEngine.Result> results = new DiffEngine().compare(a, b, kinds, selectedKeys);
                SwingUtilities.invokeLater(() -> {
                    if (request != comparisonRequest) return;
                    schemaComparisonRunning = false;
                    sourceObjects = mergeLoaded(refreshCatalog ? sourceCatalog : sourceObjects, a);
                    targetObjects = mergeLoaded(refreshCatalog ? targetCatalog : targetObjects, b);
                    if (refreshCatalog) {
                        loadedSourceKeys.clear(); loadedTargetKeys.clear(); comparedKeys.clear();
                        sourceDependencyReport = refreshedSource; targetDependencyReport = refreshedTarget;
                        sourceDependencies = sourceDependencyReport.dependencies(); targetDependencies = targetDependencyReport.dependencies();
                    }
                    a.forEach(o -> loadedSourceKeys.add(o.key())); b.forEach(o -> loadedTargetKeys.add(o.key()));
                    compared = true; results.forEach(r -> comparedKeys.add(r.key())); setResultItems(results);
                    rowComparisonPanel.clearResult(false); selectedSchemaResult = null; comparisonView.showMessage("");
                    refreshDependencyStatus();
                    setBusy(false, "Comparación lista · " + results.size() + " objetos · " + countChanged(results) + " con cambios");
                });
            } catch (SQLException | RuntimeException ex) { SwingUtilities.invokeLater(() -> { if (request != comparisonRequest) return; schemaComparisonRunning = false; setBusy(false, "Error al comparar"); error("No se pudieron leer las definiciones del esquema", ex); }); }
        });
    }

    private static int countSelected(List<SchemaObject> catalog, Set<SchemaObject.Kind> kinds, Set<SchemaObject.Key> selected) {
        return (int) catalog.stream().filter(object -> kinds.contains(object.key().kind()))
                .filter(object -> selected.isEmpty() || selected.contains(object.key())).count();
    }

    private void ensureComparisonCurrent(long request) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
        if (request != comparisonRequest) throw new CancellationException();
    }

    private static List<SchemaObject> loadSelected(SchemaSource connector, List<SchemaObject> catalog, Set<SchemaObject.Kind> kinds,
                                                   Set<SchemaObject.Key> selected, Set<SchemaObject.Key> cachedKeys,
                                                   IntConsumer progress, BooleanSupplier current) throws SQLException {
        // El catálogo solo trae nombres; las definiciones se cargan bajo demanda y
        // las ya leídas se reutilizan para evitar consultas repetidas al servidor.
        List<SchemaObject> result = new ArrayList<>();
        for (SchemaObject object : catalog) {
            if (!current.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (kinds.contains(object.key().kind()) && (selected.isEmpty() || selected.contains(object.key()))) {
                result.add(cachedKeys.contains(object.key()) ? object : connector.loadObject(object.key()));
                progress.accept(1);
            }
        }
        return result;
    }

    private void showProgress(int completed, int total, String phase) {
        int percent = total == 0 ? 100 : Math.min(100, completed * 100 / total);
        ensureElapsedTimer();
        activityIndicator.setIndeterminate(false);
        activityIndicator.setStringPainted(true);
        activityIndicator.setMinimum(0); activityIndicator.setMaximum(total); activityIndicator.setValue(completed);
        activityIndicator.setString(percent + "%");
        elapsedDetail = phase + " · " + completed + "/" + total;
        refreshElapsedStatus();
    }

    private void beginElapsed(String detail) {
        elapsedStartedAtNanos = System.nanoTime();
        elapsedDetail = detail;
        elapsedTimer.start();
        refreshElapsedStatus();
    }

    private void ensureElapsedTimer() {
        if (!elapsedTimer.isRunning()) beginElapsed(elapsedDetail);
    }

    private void showRowProgress(long rowsRead, long totalRows) {
        ensureElapsedTimer();
        activityIndicator.setStringPainted(true);
        if (totalRows < 0) {
            activityIndicator.setIndeterminate(true);
            activityIndicator.setString("…");
            elapsedDetail = "Contando filas…";
        } else {
            int percent = totalRows == 0 ? 100 : (int) Math.min(100, rowsRead * 100 / totalRows);
            activityIndicator.setIndeterminate(false);
            activityIndicator.setMinimum(0); activityIndicator.setMaximum(100); activityIndicator.setValue(percent);
            activityIndicator.setString(percent + "%");
            elapsedDetail = String.format(Locale.ROOT, "Comparando filas · %,d / %,d", rowsRead, totalRows);
        }
        refreshElapsedStatus();
    }

    private void refreshElapsedStatus() {
        if (elapsedStartedAtNanos == 0) return;
        long elapsedSeconds = Math.max(0, (System.nanoTime() - elapsedStartedAtNanos) / 1_000_000_000L);
        long hours = elapsedSeconds / 3600, minutes = (elapsedSeconds % 3600) / 60, seconds = elapsedSeconds % 60;
        String elapsed = hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
        connectionStatus.setText(elapsedDetail + " · " + elapsed);
    }

    private void refreshResults() {
        if (!compared && !schemaComparisonRunning) { showCatalog(); return; }
        // compare(false) reutiliza las definiciones en caché y solo consulta las
        // de los tipos que se acaban de activar por primera vez. Al cambiar el
        // filtro durante una carga, invalida el recorrido anterior; ese worker
        // se detiene antes de iniciar la siguiente consulta de objeto.
        compare(false);
    }

    private boolean chooseInitialObjectKinds() {
        JPanel choices = new JPanel(new GridLayout(0, 1, 4, 2));
        Map<SchemaObject.Kind, JCheckBox> boxes = new java.util.EnumMap<>(SchemaObject.Kind.class);
        for (SchemaObject.Kind kind : SchemaObject.Kind.values()) {
            long sourceCount = sourceObjects.stream().filter(object -> object.key().kind() == kind).count();
            long targetCount = targetObjects.stream().filter(object -> object.key().kind() == kind).count();
            JCheckBox box = new JCheckBox(CatalogDisplay.kindName(kind) + "  ·  origen " + sourceCount + " / destino " + targetCount,
                    selectedKinds.contains(kind));
            boxes.put(kind, box); choices.add(box);
        }
        int answer = JOptionPane.showConfirmDialog(frame, choices, "Elegir tipos para comparar",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return false;
        selectedKinds.clear();
        boxes.forEach((kind, box) -> { if (box.isSelected()) selectedKinds.add(kind); });
        return true;
    }

    private void showCatalog() {
        rowComparisonPanel.clear();
        setRowComparisonVisible(false);
        TreeMap<SchemaObject.Key, SchemaObject.Key> keys = new TreeMap<>();
        sourceObjects.forEach(o -> keys.put(o.key(), o.key())); targetObjects.forEach(o -> keys.put(o.key(), o.key()));
        List<CatalogItem> items = new ArrayList<>();
        for (SchemaObject.Key key : keys.keySet()) if (selectedKinds.contains(key.kind())) items.add(new CatalogItem(key, sourceObjects.stream().anyMatch(o -> o.key().equals(key)), targetObjects.stream().anyMatch(o -> o.key().equals(key))));
        setResultItems(items);
        comparisonView.showMessage("Objetos encontrados. Preparando la comparación automática del esquema…");
    }

    private void setResultItems(List<?> items) { objectCatalog.setItems(items); }

    private Set<SchemaObject.Key> selectedObjectKeys() {
        Set<SchemaObject.Key> keys = new TreeSet<>();
        for (Object item : objectCatalog.selectedValues()) {
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
        long request = ++selectionRequest;
        rowComparisonRequest++;
        selectedSchemaResult = null;
        rowComparisonPanel.clearResult(false);
        if (objectCatalog.selectedValues().size() > 1) setRowComparisonVisible(false);
        if (objectCatalog.selectedValues().size() > 1) { setBusy(false, "Varios objetos seleccionados"); comparisonView.showMessage("Seleccionaste varios objetos. Usá Exportar esquema para preparar sus scripts o Actualizar para volver a leerlos. Seleccioná un solo objeto para ver su definición."); return; }
        Object item = objectCatalog.selectedValue();
        if (item != null) setBusy(false, "Objeto seleccionado · " + CatalogDisplay.objectKey(item).qualifiedName());
        if (item == null) { setRowComparisonVisible(false); comparisonView.showMessage(""); }
        if (item instanceof CatalogItem catalog) { setRowComparisonVisible(catalog.key().kind() == SchemaObject.Kind.TABLE); loadCatalogSelection(catalog, request); return; }
        if (!(item instanceof DiffEngine.Result selected)) return;
        setRowComparisonVisible(selected.key().kind() == SchemaObject.Kind.TABLE);
        selectedSchemaResult = selected;
        comparisonView.showResult(selected, AppTheme.isDark(theme));
        if (rowComparisonPanel.isExpanded()) {
            if (selected.key().kind() == SchemaObject.Kind.TABLE) compareRowsForSelection();
            else rowComparisonPanel.showMessage("La comparación de filas está disponible para tablas.");
        }
    }

    private void rowPanelExpanded(boolean expanded) {
        if (comparisonSplit != null) comparisonSplit.setDividerLocation(expanded ? .55 : .88);
        if (expanded) compareRowsForSelection();
    }

    private void setRowComparisonVisible(boolean visible) {
        if (currentSelection != null && currentSelection.mode() != ConnectionDialog.SourceMode.DATABASE) visible = false;
        boolean wasVisible = rowComparisonPanel.isVisible();
        if (wasVisible == visible) return;
        if (!visible) {
            rowComparisonRequest++;
            rowComparisonPanel.setExpanded(false);
            rowComparisonPanel.clearResult(false);
        }
        rowComparisonPanel.setVisible(visible);
        if (comparisonSplit != null) {
            comparisonSplit.setDividerSize(visible ? 9 : 0);
            comparisonSplit.setDividerLocation(visible ? .72 : 1.0);
            comparisonSplit.revalidate();
        }
    }

    private void compareRowsForSelection() {
        if (source == null || target == null) {
            rowComparisonPanel.showMessage("Conectá ambas bases para comparar filas.");
            setBusy(false, "Sin conexión");
            return;
        }
        List<Object> selectedItems = objectCatalog.selectedValues();
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
        List<String> customKey = customRowKeys.getOrDefault(selected.key(), List.of());
        rowComparisonPanel.showLoading(selected.key().qualifiedName());
        setBusy(true, "Leyendo las filas de " + selected.key().qualifiedName() + "…");
        beginElapsed("Contando filas…");
        showRowProgress(0, -1);
        // Consultar en el worker evita bloquear Swing; el token descarta respuestas
        // antiguas si el usuario seleccionó otra tabla antes de terminar.
        worker.submit(() -> {
            try {
                RowComparisonMetadata metadata = sourceConnector.rowComparisonMetadata(selected.key());
                RowComparisonMetadata peerMetadata = targetConnector.rowComparisonMetadata(selected.key());
                if (metadata.primaryKey().isEmpty() && peerMetadata.primaryKey().isEmpty() && customKey.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        if (request != rowComparisonRequest || !isSelected(selected.key())) return;
                        rowComparisonPanel.showKeyRequired(selected.key().name(), () -> configureRowComparisonKey(selected));
                        setBusy(false, "Elegí una clave para comparar las filas");
                    });
                    return;
                }
                RowDiff.Result rows = sourceConnector.compareTableRows(targetConnector, selected.key(), Integer.MAX_VALUE, customKey,
                        (rowsRead, totalRows) -> SwingUtilities.invokeLater(() -> {
                            if (request == rowComparisonRequest && isSelected(selected.key())) showRowProgress(rowsRead, totalRows);
                        }));
                SwingUtilities.invokeLater(() -> {
                    if (request != rowComparisonRequest || !isSelected(selected.key())) return;
                    try {
                        // La consulta pudo terminar bien aunque la vista falle; dar
                        // un mensaje explícito evita dejar el panel en “Comparando”.
                        rowComparisonPanel.showResult(rows);
                        setBusy(false, rowSummary(rows));
                    } catch (RuntimeException renderError) {
                        renderError.printStackTrace();
                        rowComparisonPanel.showErrorMessage("La comparación terminó, pero no se pudo mostrar el resultado: " + AppUtils.errorMessage(renderError));
                        setBusy(false, "Filas comparadas; error al mostrar el detalle");
                    }
                });
            } catch (SQLException | RuntimeException ex) { SwingUtilities.invokeLater(() -> {
                if (request != rowComparisonRequest || !isSelected(selected.key())) return;
                String message = AppUtils.errorMessage(ex);
                if (!customKey.isEmpty() && (message.equals(RowComparisonMessages.customKeyNotUnique())
                        || message.equals(RowComparisonMessages.customKeyContainsNull()))) {
                    rowComparisonPanel.showKeyRequired(selected.key().name(), message,
                            () -> configureRowComparisonKey(selected));
                    setBusy(false, "La clave elegida no identifica las filas de forma segura");
                } else {
                    setBusy(false, "No se compararon las filas");
                    rowComparisonPanel.showErrorMessage("No se pudieron comparar las filas: " + message);
                }
            }); }
        });
    }

    private void configureRowComparisonKey(DiffEngine.Result selected) {
        if (!(source instanceof DatabaseConnector sourceConnector) || target == null) return;
        long request = ++rowComparisonRequest;
        rowComparisonPanel.showLoading(selected.key().qualifiedName());
        setBusy(true, "Leyendo columnas disponibles para la clave…");
        worker.submit(() -> {
            try {
                RowComparisonMetadata metadata = sourceConnector.rowComparisonMetadata(selected.key());
                SwingUtilities.invokeLater(() -> {
                    if (request != rowComparisonRequest || !isSelected(selected.key())) return;
                    List<String> existingKey = customRowKeys.getOrDefault(selected.key(), List.of());
                    List<String> chosen = RowComparisonKeyDialog.show(frame, selected.key().name(), metadata.columns(), schemaEngine, existingKey);
                    if (chosen == null) {
                        rowComparisonPanel.showKeyRequired(selected.key().name(), () -> configureRowComparisonKey(selected));
                        setBusy(false, "Elegí una clave para comparar las filas");
                        return;
                    }
                    customRowKeys.put(selected.key(), chosen);
                    startRowComparison(selected);
                });
            } catch (SQLException | RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    if (request != rowComparisonRequest || !isSelected(selected.key())) return;
                    rowComparisonPanel.showErrorMessage("No se pudieron cargar las columnas: " + AppUtils.errorMessage(ex));
                    setBusy(false, "No se pudo configurar la clave");
                });
            }
        });
    }

    private void reconfigureRowComparisonKey(RowDiff.Result rows) {
        DiffEngine.Result selected = selectedSchemaResult;
        if (selected != null && selected.key().equals(rows.table())) configureRowComparisonKey(selected);
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
                if (rowComparisonPanel.isExpanded()) rowComparisonPanel.showErrorMessage("No se pudo cargar la tabla para comparar filas.");
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
        Object selected = objectCatalog.selectedValue();
        return selected != null && CatalogDisplay.objectKey(selected).equals(key);
    }

    private static String rowSummary(RowDiff.Result rows) {
        long totalRows = rows.matchedRows() + rows.sourceOnly() + rows.targetOnly();
        return "Filas revisadas · " + totalRows + " en total · " + rows.matchedRows() + " claves coincidentes · " + rows.sourceOnly()
                + " solo en origen · " + rows.targetOnly() + " solo en destino · " + rows.different() + " diferentes"
                + (rows.truncated() ? " · resultados limitados" : "");
    }

    private void showCatalogDiagnostics() {
        if (source == null || target == null) return;
        SchemaSource left = source, right = target;
        List<SchemaObject> leftCatalog = sourceObjects, rightCatalog = targetObjects;
        String leftName = sourceDatabase, rightName = targetDatabase;
        setBusy(true, "Consultando diagnóstico de catálogos…");
        worker.submit(() -> {
            try {
                String report = "ORIGEN · " + leftName + "\n" + left.catalogDiagnostics(leftCatalog)
                        + "\n\nDESTINO · " + rightName + "\n" + right.catalogDiagnostics(rightCatalog);
                SwingUtilities.invokeLater(() -> {
                    setBusy(false, "Diagnóstico de catálogos listo");
                    JTextArea details = new JTextArea(report, 28, 76);
                    details.setEditable(false); details.setCaretPosition(0);
                    JOptionPane.showMessageDialog(frame, new JScrollPane(details), "Diagnóstico del catálogo", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (SQLException | RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false, "Falló el diagnóstico del catálogo");
                    JOptionPane.showMessageDialog(frame, "No se pudo consultar el diagnóstico: " + AppUtils.errorMessage(ex),
                            "Diagnóstico del catálogo", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    private ExportDialogs exportDialogs() {
        return new ExportDialogs(frame, theme, codeFontFamily, codeFontSize, schemaEngine);
    }

    private void saveRowSql(RowDiff.Result rows, boolean inserts, boolean updates, boolean deletes) {
        exportDialogs().saveRowSql(rows, inserts, updates, deletes);
    }

    private void export() {
        if (currentSelection != null && currentSelection.mode() == ConnectionDialog.SourceMode.FLYWAY) {
            createFlywayMigration();
            return;
        }
        exportDialogs().export(new ExportDialogs.SchemaContext(objectCatalog.allItems(), objectCatalog.selectedValues(),
                targetDatabase, sourceObjects, targetObjects, sourceDependencies, targetDependencies,
                sourceDependencyReport, targetDependencyReport));
    }

    private void updateExportButton() {
        if (exportButton == null) return;
        boolean flyway = currentSelection != null && currentSelection.mode() == ConnectionDialog.SourceMode.FLYWAY;
        exportButton.setText(flyway ? "Migración Flyway: origen → destino" : "Exportar esquema");
        exportButton.setToolTipText(flyway
                ? "Genera una migración nueva para llevar el esquema Flyway de origen al estado actual de la base de datos destino seleccionada. No modifica ni ejecuta SQL sobre la base destino."
                : "Exportar SQL para aplicar cambios del origen sobre el destino.");
    }

    private void createFlywayMigration() {
        if (source == null || target == null || currentSelection == null || currentSelection.sourceFolder() == null) return;
        if (schemaComparisonRunning) {
            JOptionPane.showMessageDialog(frame, "Esperá a que termine la comparación actual antes de generar la migración.",
                    "Comparación en curso", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        long request = ++comparisonRequest;
        SchemaSource sourceConnector = source;
        DatabaseConnector targetConnector = target;
        Path folder = currentSelection.sourceFolder();
        String database = targetDatabase;
        DatabaseEngine engine = schemaEngine;
        schemaComparisonRunning = true;
        setBusy(true, "Preparando migración Flyway con el catálogo completo…");
        worker.submit(() -> {
            try {
                Set<SchemaObject.Kind> allKinds = EnumSet.allOf(SchemaObject.Kind.class);
                List<SchemaObject> oldCatalog = sourceConnector.listObjects();
                ensureComparisonCurrent(request);
                List<SchemaObject> currentCatalog = targetConnector.listObjects();
                ensureComparisonCurrent(request);
                List<SchemaObject> oldSchema = loadSelected(sourceConnector, oldCatalog, allKinds, Set.of(), Set.of(), ignored -> { }, () -> request == comparisonRequest);
                List<SchemaObject> currentSchema = loadSelected(targetConnector, currentCatalog, allKinds, Set.of(), Set.of(), ignored -> { }, () -> request == comparisonRequest);
                ensureComparisonCurrent(request);
                List<DiffEngine.Result> differences = new DiffEngine().compare(oldSchema, currentSchema, allKinds, Set.of());
                DependencyReport oldReport = safeDependencyReport(sourceConnector);
                DependencyReport currentReport = safeDependencyReport(targetConnector);
                String sql = FlywayMigrationGenerator.generate(differences, database, engine,
                        oldReport.dependencies(), currentReport.dependencies());
                SwingUtilities.invokeLater(() -> {
                    if (request != comparisonRequest) return;
                    schemaComparisonRunning = false;
                    setBusy(false, "Migración Flyway preparada · " + differences.stream().filter(result -> result.status() != DiffEngine.Status.IDENTICAL).count() + " objetos con cambios");
                    if (differences.stream().allMatch(result -> result.status() == DiffEngine.Status.IDENTICAL)) {
                        JOptionPane.showMessageDialog(frame, "El esquema Flyway ya coincide con la base actual.", "Sin cambios", JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    exportDialogs().createFlywayMigration(folder, sql);
                });
            } catch (SQLException | RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    if (request != comparisonRequest) return;
                    schemaComparisonRunning = false;
                    setBusy(false, "No se pudo preparar la migración Flyway");
                    error("No se pudieron cargar todos los objetos para la migración", ex);
                });
            }
        });
    }

    private void setBusy(boolean busy, String text) {
        if (busy || !schemaComparisonRunning) connectionStatus.setText(text);
        boolean active = busy || schemaComparisonRunning;
        activityIndicator.setVisible(active);
        if (!active) {
            activityIndicator.setIndeterminate(true);
            activityIndicator.setStringPainted(false);
            elapsedTimer.stop(); elapsedStartedAtNanos = 0; elapsedDetail = "";
        }
        frame.setCursor(active ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    /** Keep workspace actions unavailable until both catalogs are connected. */
    private void setConnectionAvailable(boolean available) {
        if (leftPanel != null) setEnabledRecursively(leftPanel, available);
        if (workspaceSplit != null) setEnabledRecursively(workspaceSplit.getRightComponent(), available);
        if (compareButton != null) compareButton.setEnabled(available);
        if (exportButton != null) exportButton.setEnabled(available);
        if (rowComparisonPanel != null) setEnabledRecursively(rowComparisonPanel, available && source instanceof DatabaseConnector);
    }

    private static void setEnabledRecursively(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container container)
            for (Component child : container.getComponents()) setEnabledRecursively(child, enabled);
    }

    private static long countChanged(List<DiffEngine.Result> values) { return values.stream().filter(r -> r.status() != DiffEngine.Status.IDENTICAL).count(); }
    private void error(String title, Exception ex) { JOptionPane.showMessageDialog(frame, ex.getMessage() == null ? ex.toString() : ex.getMessage(), title, JOptionPane.ERROR_MESSAGE); }
    private void refreshDependencyStatus() {
        List<String> notes = new ArrayList<>();
        notes.addAll(DependencySummary.notes("Origen", sourceDependencyReport));
        notes.addAll(DependencySummary.notes("Destino", targetDependencyReport));
        connectionStatus.setToolTipText(String.join("\n", notes));
    }
    private void closeConnectors() {
        if (source != null) source.close();
        if (target != null) target.close();
        source = null; target = null; sourceDependencies = targetDependencies = Map.of();
        sourceDependencyReport = new DependencyReport(Map.of(), List.of(), List.of());
        targetDependencyReport = new DependencyReport(Map.of(), List.of(), List.of());
    }
    private void close() { closeConnectors(); worker.shutdownNow(); frame.dispose(); }

    private void updateLayout() {
        if (workspaceSplit == null || leftPanel == null) return;
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
            rememberWindowBounds = Boolean.parseBoolean(values.getProperty("rememberWindowBounds", "true"));
            startMaximized = Boolean.parseBoolean(values.getProperty("startMaximized", "true"));
            keepRowComparisonExpanded = Boolean.parseBoolean(values.getProperty("keepRowComparisonExpanded", "false"));
            rememberLastComparison = Boolean.parseBoolean(values.getProperty("rememberLastComparison", "false"));
            try { codeFontSize = Math.clamp(Integer.parseInt(values.getProperty("codeFontSize", "13")), 10, 24); }
            catch (NumberFormatException ignored) { codeFontSize = 13; }
        } catch (IOException ignored) { }
    }

    private void saveUiPreferences() {
        try {
            Files.createDirectories(UI_PREFERENCES.getParent());
            java.util.Properties values = new java.util.Properties(); values.setProperty("theme", theme); values.setProperty("stackedLayout", Boolean.toString(stackedLayout)); values.setProperty("codeFontSize", Integer.toString(codeFontSize)); values.setProperty("codeFontFamily", codeFontFamily); values.setProperty("rememberWindowBounds", Boolean.toString(rememberWindowBounds)); values.setProperty("startMaximized", Boolean.toString(startMaximized)); values.setProperty("keepRowComparisonExpanded", Boolean.toString(keepRowComparisonExpanded)); values.setProperty("rememberLastComparison", Boolean.toString(rememberLastComparison));
            try (var output = Files.newOutputStream(UI_PREFERENCES)) { values.store(output, AppInfo.NAME + " appearance"); }
        } catch (IOException ex) { connectionStatus.setText("No se pudo guardar la apariencia: " + ex.getMessage()); }
    }

    private void applyTheme() {
        AppTheme.apply(frame, headerPanel, theme);
        boolean dark = AppTheme.isDark(theme);
        comparisonView.setThemeColors(dark);
        rowComparisonPanel.setThemeColors(dark);
        comparisonView.setCodeFont(codeFontFamily, codeFontSize);
        if (objectCatalog != null) objectCatalog.refreshTheme(dark);
    }

    private void showSettings() {
        SettingsDialog.Selection selected = SettingsDialog.show(frame, theme, codeFontFamily, codeFontSize, rememberWindowBounds, startMaximized,
                stackedLayout, keepRowComparisonExpanded, rememberLastComparison);
        if (selected == null) return;
        theme = selected.theme(); codeFontFamily = selected.fontFamily(); codeFontSize = selected.fontSize();
        rememberWindowBounds = selected.rememberWindowBounds(); startMaximized = selected.startMaximized();
        stackedLayout = selected.stackedLayout(); keepRowComparisonExpanded = selected.keepRowComparisonExpanded();
        rememberLastComparison = selected.rememberLastComparison();
        updateLayout();
        saveUiPreferences(); applyTheme();
        if (rememberLastComparison && currentSelection != null) saveLastComparison(currentSelection);
        else if (!rememberLastComparison) try { LastComparisonPreferences.clear(); }
        catch (IOException ex) { connectionStatus.setText("No se pudo borrar la última comparación guardada: " + ex.getMessage()); }
    }

    private Rectangle loadWindowBounds() {
        if (!rememberWindowBounds) return null;
        try {
            java.util.Properties values = new java.util.Properties();
            try (var input = Files.newInputStream(UI_PREFERENCES)) { values.load(input); }
            int x = Integer.parseInt(values.getProperty("windowX"));
            int y = Integer.parseInt(values.getProperty("windowY"));
            int width = Math.max(1020, Integer.parseInt(values.getProperty("windowWidth")));
            int height = Math.max(680, Integer.parseInt(values.getProperty("windowHeight")));
            Rectangle candidate = new Rectangle(x, y, width, height);
            return isOnScreen(candidate) ? candidate : null;
        } catch (IOException | NumberFormatException | NullPointerException ignored) { return null; }
    }

    private static boolean isOnScreen(Rectangle bounds) {
        for (java.awt.GraphicsDevice device : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            java.awt.GraphicsConfiguration configuration = device.getDefaultConfiguration();
            if (configuration.getBounds().intersects(bounds)) return true;
        }
        return false;
    }

    private static java.awt.Point centeredLocation(Dimension size) {
        Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        return new java.awt.Point(screen.x + (screen.width - size.width) / 2, screen.y + (screen.height - size.height) / 2);
    }

    private void saveWindowBounds() {
        if (!rememberWindowBounds || !frame.isDisplayable() || (frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0) return;
        try {
            java.util.Properties values = new java.util.Properties();
            if (Files.exists(UI_PREFERENCES)) try (var input = Files.newInputStream(UI_PREFERENCES)) { values.load(input); }
            Rectangle bounds = frame.getBounds();
            values.setProperty("windowX", Integer.toString(bounds.x)); values.setProperty("windowY", Integer.toString(bounds.y));
            values.setProperty("windowWidth", Integer.toString(bounds.width)); values.setProperty("windowHeight", Integer.toString(bounds.height));
            Files.createDirectories(UI_PREFERENCES.getParent());
            try (var output = Files.newOutputStream(UI_PREFERENCES)) { values.store(output, AppInfo.NAME + " appearance"); }
        } catch (IOException ignored) { }
    }

}
