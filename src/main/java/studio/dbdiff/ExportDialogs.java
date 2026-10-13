package studio.dbdiff;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import java.util.List;

/** Owns export selection, preview and save dialogs; SQL generation stays in the exporters. */
final class ExportDialogs {
    record SchemaContext(List<Object> items, List<Object> selected, String targetDatabase,
                         List<SchemaObject> sourceObjects, List<SchemaObject> targetObjects,
                         Map<SchemaObject.Key, Set<SchemaObject.Key>> sourceDependencies,
                         Map<SchemaObject.Key, Set<SchemaObject.Key>> targetDependencies,
                         DependencyReport sourceDependencyReport, DependencyReport targetDependencyReport) { }
    private final JFrame frame;
    private final String theme, codeFontFamily;
    private final int codeFontSize;
    private final DatabaseEngine schemaEngine;

    ExportDialogs(JFrame frame, String theme, String codeFontFamily, int codeFontSize, DatabaseEngine engine) {
        this.frame = frame; this.theme = theme; this.codeFontFamily = codeFontFamily;
        this.codeFontSize = codeFontSize; this.schemaEngine = engine;
    }

    private JTextArea area() {
        JTextArea area = new JTextArea(); area.setEditable(false);
        area.setFont(DiffCodeEditor.codeFont(codeFontFamily, codeFontSize));
        area.setBorder(new EmptyBorder(8, 10, 8, 10)); area.setLineWrap(false); return area;
    }
    void saveRowSql(RowDiff.Result rows, boolean inserts, boolean updates, boolean deletes) {
        JTextArea preview = area();
        JCheckBox insertChoice = new JCheckBox("INSERT: filas solo en origen", inserts), updateChoice = new JCheckBox("UPDATE: filas diferentes", updates), deleteChoice = new JCheckBox("DELETE: filas solo en destino", deletes);
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
        JPanel choices = new JPanel(new GridLayout(0, 1)); choices.add(insertChoice); choices.add(updateChoice); choices.add(deleteChoice);
        JPanel format = new JPanel(new FlowLayout(FlowLayout.LEFT)); format.add(new JLabel("Formato:")); format.add(fileMode);
        JPanel options = new JPanel(); options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS)); options.add(choices); options.add(format); options.add(ReviewDialog.disclosure("Opciones avanzadas", transaction));
        JPanel wrapper = new JPanel(new BorderLayout(8, 8)); wrapper.add(options, BorderLayout.NORTH); wrapper.add(SqlSearchOverlay.wrap(preview), BorderLayout.CENTER);
        JTextArea warning = ReviewDialog.note("Solo se exporta; nunca se ejecuta. En modo separado, cada archivo tiene su propia transacción."); wrapper.add(warning, BorderLayout.SOUTH); wrapper.setPreferredSize(new Dimension(900, 540));
        refresh.run();
        if (!ReviewDialog.confirm(frame, "Exportar SQL de filas", wrapper, new Dimension(960, 650))) return;
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
            case IDENTICAL -> false;
        });
    }

    void export(SchemaContext context) {
        List<DiffEngine.Result> available = context.items().stream().filter(DiffEngine.Result.class::isInstance)
                .map(DiffEngine.Result.class::cast).filter(result -> result.status() != DiffEngine.Status.IDENTICAL).toList();
        if (available.isEmpty()) { JOptionPane.showMessageDialog(frame, "No hay objetos comparados con cambios para exportar.", "Sin cambios", JOptionPane.INFORMATION_MESSAGE); return; }
        EnumSet<DiffEngine.Status> ops = EnumSet.of(DiffEngine.Status.CHANGED, DiffEngine.Status.SOURCE_ONLY);
        JTextArea preview = area();
        JCheckBox[] checks = new JCheckBox[3]; JPanel choices = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        boolean sqlServerExport = schemaEngine == DatabaseEngine.SQLSERVER;
        JCheckBox transaction = new JCheckBox(sqlServerExport
                ? "Envolver el script en BEGIN TRANSACTION / COMMIT"
                : "Envolver el script en START TRANSACTION / COMMIT");
        JCheckBox rollbackOnError = new JCheckBox("Revertir toda la transacción si falla una operación", true);
        rollbackOnError.setToolTipText("En SQL Server, ejecuta cada definición en un bloque con manejo de errores y revierte los cambios si una operación falla.");
        rollbackOnError.setEnabled(sqlServerExport && transaction.isSelected());
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
        JTextArea warning = ReviewDialog.note("");
        JTextArea planPreview = area();
        planPreview.setRows(7);
        List<DiffEngine.Result> currentlySelected = context.selected().stream()
                .filter(DiffEngine.Result.class::isInstance).map(DiffEngine.Result.class::cast).toList();
        ExportSelectionModel selection = new ExportSelectionModel(available, currentlySelected);
        DefaultListModel<DiffEngine.Result> exportModel = new DefaultListModel<>();
        JList<DiffEngine.Result> exportSelection = new JList<>(exportModel);
        exportSelection.setCellRenderer(new ObjectCatalogPanel.ResultRenderer());
        exportSelection.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        exportSelection.setVisibleRowCount(4);
        JTextField objectSearch = new PlaceholderTextField("Buscar por nombre o tipo...");
        JLabel selectionSummary = new JLabel();
        JLabel operationSummary = new JLabel();
        operationSummary.setBorder(new EmptyBorder(4, 2, 4, 2));
        JButton selectVisible = new JButton("Seleccionar visibles");
        JButton clearSelection = new JButton("Limpiar");
        JPanel selectionActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        selectionActions.add(selectVisible); selectionActions.add(clearSelection);
        JPanel selectionHeader = new JPanel(new BorderLayout());
        selectionHeader.add(new JLabel("Objetos con cambios"), BorderLayout.WEST);
        selectionHeader.add(selectionActions, BorderLayout.EAST);
        JPanel selectionFilter = new JPanel(new BorderLayout(8, 0));
        selectionFilter.add(objectSearch, BorderLayout.CENTER);
        selectionFilter.add(selectionSummary, BorderLayout.EAST);
        JPanel selectionTools = new JPanel(new BorderLayout(4, 5));
        selectionTools.add(selectionHeader, BorderLayout.NORTH);
        selectionTools.add(selectionFilter, BorderLayout.SOUTH);
        JPanel selectionPanel = new JPanel(new BorderLayout(4, 4));
        selectionPanel.add(selectionTools, BorderLayout.NORTH);
        selectionPanel.add(new JScrollPane(exportSelection), BorderLayout.CENTER);
        selectionPanel.setPreferredSize(new Dimension(760, 225));
        selectionPanel.setMinimumSize(new Dimension(360, 115));
        boolean[] refreshingSelection = {false};
        JButton[] saveButton = {null};
        Runnable[] refreshPreviewRef = {() -> { }};
        Runnable refreshVisibleItems = () -> {
            refreshingSelection[0] = true;
            try {
                exportModel.clear();
                for (DiffEngine.Result result : selection.visibleItems()) {
                    exportModel.addElement(result);
                    if (selection.isSelected(result.key())) {
                        int index = exportModel.size() - 1;
                        exportSelection.addSelectionInterval(index, index);
                    }
                }
            } finally { refreshingSelection[0] = false; }
            selectionSummary.setText(selection.selectedCount() + " seleccionados · "
                    + exportModel.size() + " visibles / " + selection.availableCount() + " con cambios");
            refreshPreviewRef[0].run();
        };
        Runnable refreshPreview = () -> {
            List<DiffEngine.Result> exportResults = selection.selectedItems();
            long createCount = ops.contains(DiffEngine.Status.SOURCE_ONLY)
                    ? exportResults.stream().filter(result -> result.status() == DiffEngine.Status.SOURCE_ONLY).count() : 0;
            long alterCount = ops.contains(DiffEngine.Status.CHANGED)
                    ? exportResults.stream().filter(result -> result.status() == DiffEngine.Status.CHANGED).count() : 0;
            long dropCount = ops.contains(DiffEngine.Status.TARGET_ONLY)
                    ? exportResults.stream().filter(result -> result.status() == DiffEngine.Status.TARGET_ONLY).count() : 0;
            operationSummary.setText("Incluye: " + createCount + " CREATE · " + alterCount + " ALTER · " + dropCount + " DROP");
            operationSummary.setForeground(dropCount > 0
                    ? javax.swing.UIManager.getColor("Component.warning.focusedBorderColor")
                    : javax.swing.UIManager.getColor("Label.foreground"));
            operationSummary.setToolTipText(dropCount > 0
                    ? dropCount + " objetos se eliminarán del destino porque no existen en el origen."
                    : "Resumen de operaciones incluidas en la vista previa.");
            SqlExporter.Strategy strategy = switch (createStrategy.getSelectedIndex()) {
                case 1 -> SqlExporter.Strategy.CREATE;
                case 2 -> sqlServerExport ? SqlExporter.Strategy.CREATE_OR_ALTER : SqlExporter.Strategy.CREATE_OR_REPLACE;
                default -> SqlExporter.Strategy.ALTER;
            };
            String exportSql = SqlExporter.export(exportResults, ops, context.targetDatabase(), transaction.isSelected(),
                    rollbackOnError.isSelected(), dropIfExists.isSelected(), strategy, schemaEngine,
                    context.sourceDependencies(), context.targetDependencies());
            preview.setText(exportSql);
            List<String> metadataNotes = new ArrayList<>();
            metadataNotes.addAll(DependencySummary.notes("Origen", context.sourceDependencyReport()));
            metadataNotes.addAll(DependencySummary.notes("Destino", context.targetDependencyReport()));
            Set<SchemaObject.Key> sourceKeys = context.sourceObjects().stream().map(SchemaObject::key).collect(java.util.stream.Collectors.toSet());
            Set<SchemaObject.Key> targetKeys = context.targetObjects().stream().map(SchemaObject::key).collect(java.util.stream.Collectors.toSet());
            planPreview.setText(SqlExportPlan.create(exportResults, ops, context.sourceDependencies(), context.targetDependencies(), sourceKeys, targetKeys,
                    metadataNotes, exportSql).render());
            planPreview.setCaretPosition(0);
            preview.setCaretPosition(0);
            boolean hasApplicableOperation = selection.canExport(ops);
            List<String> notices = new ArrayList<>();
            if (exportResults.isEmpty()) notices.add("Seleccioná al menos un objeto para ver el script.");
            else if (ops.isEmpty()) notices.add("Activá al menos una operación para generar el SQL.");
            else if (!hasApplicableOperation) notices.add("La selección no tiene objetos para las operaciones activas. Activá DROP para incluir objetos solo en destino.");
            else {
                if (dropCount > 0)
                    notices.add("⚠ Se eliminarán " + dropCount + " objetos del destino que no existen en el origen. Revisá la selección y el SQL antes de exportar.");
                boolean recreatesMariaDbTables = strategy == SqlExporter.Strategy.CREATE_OR_REPLACE
                        && schemaEngine == DatabaseEngine.MARIADB
                        && exportResults.stream().anyMatch(result -> result.key().kind() == SchemaObject.Kind.TABLE
                                && result.status() == DiffEngine.Status.CHANGED);
                if (recreatesMariaDbTables)
                    notices.add("⚠ CREATE OR REPLACE TABLE elimina y recrea la tabla destino, incluidos sus datos.");
                if (schemaEngine == DatabaseEngine.SQLSERVER && strategy == SqlExporter.Strategy.CREATE_OR_ALTER)
                    notices.add("CREATE OR ALTER requiere SQL Server 2016 SP1 o posterior; los cambios de tablas quedan para revisión manual.");
                else if (strategy == SqlExporter.Strategy.CREATE_OR_REPLACE && schemaEngine == DatabaseEngine.MYSQL)
                    notices.add("MySQL admite CREATE OR REPLACE para vistas. Las tablas modificadas se exportan como ALTER si se pueden derivar.");
                if (transaction.isSelected() && schemaEngine == DatabaseEngine.SQLSERVER && rollbackOnError.isSelected())
                    notices.add("Si una operación falla, el script revierte la transacción completa. Revisá el SQL antes de aplicarlo.");
                else if (transaction.isSelected() && schemaEngine == DatabaseEngine.SQLSERVER)
                    notices.add("El script usa transacción explícita sin rollback automático ante errores. Revisá cada sentencia antes de aplicarlo.");
                else if (transaction.isSelected())
                    notices.add("MariaDB puede confirmar DDL automáticamente; la transacción no garantiza rollback del esquema.");
                if (notices.isEmpty()) notices.add("El SQL solo se exporta; la aplicación nunca lo ejecuta.");
            }
            warning.setText(String.join("\n", notices));
            if (saveButton[0] != null) saveButton[0].setEnabled(!exportResults.isEmpty() && !ops.isEmpty() && hasApplicableOperation);
        };
        refreshPreviewRef[0] = refreshPreview;
        DiffEngine.Status[] statuses = {DiffEngine.Status.SOURCE_ONLY, DiffEngine.Status.CHANGED, DiffEngine.Status.TARGET_ONLY};
        String[] labels = {"CREATE: solo en origen", "ALTER: objetos modificados", "DROP: solo en destino"};
        String[] operationTooltips = {
                "Crear objetos presentes solo en el origen.",
                sqlServerExport ? "Modificar objetos existentes; CREATE OR ALTER aplica a vistas y rutinas según la estrategia elegida."
                        : "Modificar objetos existentes; la estrategia elegida determina cómo se reemplazan vistas y rutinas.",
                "Eliminar del destino objetos que no existen en el origen. Revisá el SQL antes de aplicarlo."
        };
        for (int i = 0; i < checks.length; i++) {
            checks[i] = new JCheckBox(labels[i], i != 2);
            checks[i].setToolTipText(operationTooltips[i]);
            final DiffEngine.Status status = statuses[i];
            final JCheckBox check = checks[i];
            checks[i].addActionListener(e -> {
                if (check.isSelected()) ops.add(status); else ops.remove(status);
                refreshPreview.run();
            });
            choices.add(checks[i]);
        }
        transaction.addActionListener(event -> {
            rollbackOnError.setEnabled(sqlServerExport && transaction.isSelected());
            refreshPreview.run();
        });
        rollbackOnError.addActionListener(event -> refreshPreview.run());
        dropIfExists.addActionListener(event -> refreshPreview.run());
        createStrategy.addActionListener(event -> refreshPreview.run());
        exportSelection.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || refreshingSelection[0]) return;
            selection.replaceVisibleSelection(exportSelection.getSelectedValuesList());
            selectionSummary.setText(selection.selectedCount() + " seleccionados · "
                    + exportModel.size() + " visibles / " + selection.availableCount() + " con cambios");
            refreshPreview.run();
        });
        objectSearch.getDocument().addDocumentListener(documentListener(() -> {
            selection.setQuery(objectSearch.getText());
            refreshVisibleItems.run();
        }));
        selectVisible.addActionListener(event -> { selection.selectVisible(); refreshVisibleItems.run(); });
        clearSelection.addActionListener(event -> { selection.clear(); refreshVisibleItems.run(); });
        JPanel advancedOptions = new JPanel(new GridLayout(0, 1));
        advancedOptions.add(transaction);
        if (sqlServerExport) advancedOptions.add(rollbackOnError);
        advancedOptions.add(dropIfExists);
        JPanel strategy = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel strategyLabel = new JLabel("Estrategia para objetos modificados:");
        warning.setForeground(javax.swing.UIManager.getColor("Component.warning.focusedBorderColor"));
        strategy.add(strategyLabel); strategy.add(createStrategy);
        JPanel optionStack = new JPanel(new BorderLayout(4, 4)); optionStack.add(choices, BorderLayout.NORTH); optionStack.add(strategy, BorderLayout.CENTER); optionStack.add(ReviewDialog.disclosure("Opciones avanzadas", advancedOptions), BorderLayout.SOUTH);
        JPanel previewPanel = new JPanel(new BorderLayout(6, 6));
        previewPanel.add(optionStack, BorderLayout.NORTH);
        JTabbedPane reviewTabs = new JTabbedPane();
        reviewTabs.addTab("SQL", SqlSearchOverlay.wrap(preview));
        reviewTabs.addTab("Plan y dependencias", SqlSearchOverlay.wrap(planPreview));
        JPanel reviewContent = new JPanel(new BorderLayout(0, 4));
        reviewContent.add(operationSummary, BorderLayout.NORTH);
        reviewContent.add(reviewTabs, BorderLayout.CENTER);
        previewPanel.add(reviewContent, BorderLayout.CENTER);
        selectionPanel.setMinimumSize(new Dimension(0, 0));
        previewPanel.setMinimumSize(new Dimension(0, 0));
        JSplitPane exportSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, selectionPanel, previewPanel);
        exportSplit.setContinuousLayout(true);
        exportSplit.setResizeWeight(.30);
        exportSplit.setDividerSize(9);
        exportSplit.setDividerLocation(.30);
        JButton restoreSplit = new JButton("Restaurar vista");
        restoreSplit.setToolTipText("Mostrar nuevamente los dos paneles con el divisor centrado.");
        JPanel splitControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        splitControls.add(restoreSplit);
        Runnable updateSplitControls = () -> {
            int location = exportSplit.getDividerLocation();
            int maxLocation = Math.max(0, exportSplit.getHeight() - exportSplit.getDividerSize());
            boolean objectsHidden = maxLocation > 0 && location <= 1;
            boolean previewHidden = maxLocation > 0 && location >= maxLocation - 1;
            splitControls.setVisible(objectsHidden || previewHidden);
        };
        restoreSplit.addActionListener(event -> {
            exportSplit.setDividerLocation(.30);
            updateSplitControls.run();
        });
        exportSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                event -> updateSplitControls.run());
        exportSplit.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) { updateSplitControls.run(); }
        });
        refreshVisibleItems.run();
        JPanel splitArea = new JPanel(new BorderLayout(0, 4));
        splitArea.add(splitControls, BorderLayout.NORTH);
        splitArea.add(exportSplit, BorderLayout.CENTER);
        JPanel wrapper = new JPanel(new BorderLayout(8, 8)); wrapper.add(splitArea, BorderLayout.CENTER); wrapper.setPreferredSize(new Dimension(900, 740));
        wrapper.add(warning, BorderLayout.SOUTH);
        if (!ReviewDialog.confirm(frame, "Exportar esquema", wrapper, new Dimension(980, 780), "Guardar SQL…",
                () -> selection.canExport(ops),
                button -> saveButton[0] = button)) return;
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("db-diff-exportacion.sql")); if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        try { Files.writeString(chooser.getSelectedFile().toPath(), preview.getText(), StandardCharsets.UTF_8); JOptionPane.showMessageDialog(frame, "Se guardó el SQL. No se ejecutó.", "Exportación lista", JOptionPane.INFORMATION_MESSAGE); }
        catch (IOException ex) { error("No se pudo guardar la exportación", ex); }
    }

    void createFlywayMigration(Path folder, String sql) {
        if (!Files.isDirectory(folder)) {
            error("Carpeta Flyway no válida", new IOException("La carpeta de migraciones ya no existe o no está disponible."));
            return;
        }
        JTextField version = new JTextField(12);
        JTextField description = new JTextField("sincronizar_con_db_actual", 24);
        FlywayMigrationGenerator.VersionSuggestion versionSuggestion;
        try {
            versionSuggestion = FlywayMigrationGenerator.versionSuggestion(folder);
            version.setText(versionSuggestion.suggested());
        }
        catch (IOException ex) { error("No se pudo proponer la versión Flyway", ex); return; }
        JTextArea preview = area();
        preview.setEditable(true);
        preview.setText(sql);
        preview.setCaretPosition(0);
        JLabel fileName = new JLabel();
        Runnable updateFileName = () -> {
            try { fileName.setText("Archivo: " + FlywayMigrationGenerator.fileName(version.getText().trim(), description.getText().trim())); }
            catch (IllegalArgumentException ex) { fileName.setText("Nombre de archivo inválido: " + ex.getMessage()); }
        };
        version.getDocument().addDocumentListener(documentListener(updateFileName));
        description.getDocument().addDocumentListener(documentListener(updateFileName));
        updateFileName.run();

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(3, 4, 3, 8); c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0; fields.add(new JLabel("Versión"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; fields.add(version, c);
        c.gridx = 0; c.gridy = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE; fields.add(new JLabel("Descripción"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; fields.add(description, c);
        c.gridx = 0; c.gridy = 2; c.gridwidth = 2; c.weightx = 1; fields.add(fileName, c);
        JLabel destination = new JLabel("Se guardará en: " + folder.toAbsolutePath().normalize());
        destination.setToolTipText(destination.getText());
        JLabel versionSummary = new JLabel(versionSuggestion.detected() == null
                ? "No se encontraron migraciones versionadas · Se sugiere la versión inicial V" + versionSuggestion.suggested()
                : "Última versión detectada: V" + versionSuggestion.detected() + " · Nueva migración sugerida: V" + versionSuggestion.suggested());
        versionSummary.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        JPanel destinationDetails = new JPanel(new GridLayout(0, 1, 0, 3));
        destinationDetails.setOpaque(false);
        destinationDetails.add(destination);
        destinationDetails.add(versionSummary);
        JPanel north = new JPanel(new BorderLayout(4, 6)); north.add(fields, BorderLayout.CENTER); north.add(destinationDetails, BorderLayout.SOUTH);
        JPanel note = InfoNote.wrap(ReviewDialog.note("Revisá y editá el SQL. El archivo se guardará sin ejecutarse; los bloques REVIEW ONLY son referencias y requieren decisión manual."));
        JPanel wrapper = new JPanel(new BorderLayout(8, 8));
        wrapper.add(north, BorderLayout.NORTH); wrapper.add(SqlSearchOverlay.wrap(preview), BorderLayout.CENTER); wrapper.add(note, BorderLayout.SOUTH);
        wrapper.setPreferredSize(new Dimension(900, 650));
        JOptionPane options = new JOptionPane(wrapper, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        JDialog dialog = options.createDialog(frame, "Crear migración Flyway");
        dialog.setResizable(true);
        dialog.setMinimumSize(new Dimension(700, 500));
        while (true) {
            dialog.setVisible(true);
            Object choice = options.getValue();
            if (!(choice instanceof Integer value) || value != JOptionPane.OK_OPTION) { dialog.dispose(); return; }
            try {
                Path saved = FlywayMigrationGenerator.save(folder, version.getText().trim(), description.getText().trim(), preview.getText());
                dialog.dispose();
                JOptionPane.showMessageDialog(frame, "Se guardó la migración:\n" + saved + "\nNo se ejecutó.", "Migración Flyway lista", JOptionPane.INFORMATION_MESSAGE);
                return;
            } catch (IOException | IllegalArgumentException ex) {
                options.setValue(JOptionPane.UNINITIALIZED_VALUE);
                JOptionPane.showMessageDialog(dialog, AppUtils.errorMessage(ex), "No se pudo guardar la migración Flyway", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static javax.swing.event.DocumentListener documentListener(Runnable action) {
        return new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
        };
    }

    private void error(String title, Exception ex) {
        JOptionPane.showMessageDialog(frame, AppUtils.errorMessage(ex), title, JOptionPane.ERROR_MESSAGE);
    }
}
