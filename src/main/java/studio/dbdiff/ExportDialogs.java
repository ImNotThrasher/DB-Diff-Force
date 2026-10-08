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
        JPanel wrapper = new JPanel(new BorderLayout(8, 8)); wrapper.add(options, BorderLayout.NORTH); wrapper.add(new JScrollPane(preview), BorderLayout.CENTER);
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
        JTextArea warning = ReviewDialog.note("");
        JTextArea planPreview = area();
        planPreview.setRows(7);
        List<DiffEngine.Result> currentlySelected = context.selected().stream()
                .filter(DiffEngine.Result.class::isInstance).map(DiffEngine.Result.class::cast).toList();
        DefaultListModel<DiffEngine.Result> exportModel = new DefaultListModel<>();
        JList<DiffEngine.Result> exportSelection = new JList<>(exportModel);
        exportSelection.setCellRenderer(new Main.ResultRenderer());
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
            String exportSql = SqlExporter.export(exportResults, ops, context.targetDatabase(), transaction.isSelected(), dropIfExists.isSelected(), strategy, schemaEngine, context.sourceDependencies(), context.targetDependencies());
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
        JPanel advancedOptions = new JPanel(new GridLayout(0, 1));
        advancedOptions.add(transaction); advancedOptions.add(dropIfExists);
        JPanel strategy = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel strategyLabel = new JLabel("Estrategia para objetos modificados:");
        boolean dark = AppTheme.isDark(theme); ThemePalette palette = ThemePalette.forMode(dark);
        warning.setForeground(javax.swing.UIManager.getColor("Component.warning.focusedBorderColor"));
        strategy.add(strategyLabel); strategy.add(createStrategy);
        JPanel optionStack = new JPanel(new BorderLayout(4, 4)); optionStack.add(choices, BorderLayout.NORTH); optionStack.add(strategy, BorderLayout.CENTER); optionStack.add(ReviewDialog.disclosure("Opciones avanzadas", advancedOptions), BorderLayout.SOUTH);
        JPanel previewPanel = new JPanel(new BorderLayout(6, 6));
        previewPanel.add(optionStack, BorderLayout.NORTH);
        JTabbedPane reviewTabs = new JTabbedPane();
        reviewTabs.addTab("SQL", new JScrollPane(preview));
        reviewTabs.addTab("Plan y dependencias", new JScrollPane(planPreview));
        previewPanel.add(reviewTabs, BorderLayout.CENTER);
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
        if (!ReviewDialog.confirm(frame, "Exportar esquema", wrapper, new Dimension(980, 780))) return;
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("db-diff-exportacion.sql")); if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        try { Files.writeString(chooser.getSelectedFile().toPath(), preview.getText(), StandardCharsets.UTF_8); JOptionPane.showMessageDialog(frame, "Se guardó el SQL. No se ejecutó.", "Exportación lista", JOptionPane.INFORMATION_MESSAGE); }
        catch (IOException ex) { error("No se pudo guardar la exportación", ex); }
    }
    private void error(String title, Exception ex) {
        JOptionPane.showMessageDialog(frame, AppUtils.errorMessage(ex), title, JOptionPane.ERROR_MESSAGE);
    }
}
