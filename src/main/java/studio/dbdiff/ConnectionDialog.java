package studio.dbdiff;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
final class ConnectionDialog extends JDialog {
    enum SourceMode {
        DATABASE("Base de datos"), SNAPSHOT("Archivos SQL (CREATE)"), FLYWAY("Migraciones Flyway");
        private final String label;
        SourceMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    record Selection(SourceMode mode, Endpoint origin, Endpoint destination, java.nio.file.Path sourceFolder, boolean autoRestored) { }

    private Selection selection;
    static Selection showDialog(JFrame owner, String theme) {
        return showDialog(owner, theme, null, true);
    }

    static Selection showDialog(JFrame owner, String theme, LastComparisonPreferences.SavedComparison lastComparison, boolean attemptAutoConnect) {
        ConnectionDialog dialog = new ConnectionDialog(owner, lastComparison, attemptAutoConnect);
        AppTheme.apply(dialog, null, theme);
        dialog.setVisible(true);
        return dialog.selection;
    }

    private static JButton actionButton(String name) { return new JButton(name); }
    private final JTextField[][] fields = new JTextField[2][3];
    private final JPasswordField[] passwords = {new JPasswordField(15), new JPasswordField(15)};
    private final JCheckBox[] rememberPasswords = {new JCheckBox("Recordar contraseña en este equipo"), new JCheckBox("Recordar contraseña en este equipo")};
    private final JCheckBox[] tls = {new JCheckBox("Usar TLS y verificar certificado"), new JCheckBox("Usar TLS y verificar certificado")};
    private final JComboBox<DatabaseEngine> engine = new JComboBox<>(DatabaseEngine.values());
    private final JComboBox<SourceMode> sourceMode = new JComboBox<>(SourceMode.values());
    private final List<JComboBox<String>> databases = List.of(new JComboBox<>(), new JComboBox<>());
    private final List<JComboBox<String>> savedChoices = List.of(new JComboBox<>(), new JComboBox<>());
    private final JButton[] testButtons = new JButton[2];
    private final JLabel[] testStatus = new JLabel[2];
    private final Map<String, ConnectionProfile> savedProfiles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final boolean[] changingSavedChoice = new boolean[2];
    private final boolean[] updatingDatabaseChoice = new boolean[2];
    private final boolean[] testedSuccessfully = new boolean[2];
    private boolean applyingSavedProfile;
    private final int[] testGenerations = new int[2];
    private final String[] preferredDatabase = new String[2];
    private boolean autoConnectRequested;
    private final JPanel originCards = new JPanel(new CardLayout());
    private final JTextField sourceFolderField = new JTextField();
    private final JLabel sourceCardTitle = new JLabel("ORIGEN");
    private final JLabel sourceCardDescription = new JLabel();
    private java.nio.file.Path selectedSourceFolder;
    private JButton connectButton;
    private final JTextField[] firstFields = new JTextField[2];
    private boolean restoreCredentialsAvailable;
    private boolean restorePasswordsAvailable = true;
    private boolean autoRestored;
    private String restoreNotice;

    ConnectionDialog(JFrame owner, LastComparisonPreferences.SavedComparison lastComparison, boolean attemptAutoConnect) {
        super(owner, "Nueva comparación", true);
        setLayout(new BorderLayout(10, 10));
        engine.addActionListener(e -> {
            if (applyingSavedProfile) return;
            String port = Integer.toString(((DatabaseEngine) engine.getSelectedItem()).defaultPort());
            for (int i = 0; i < 2; i++) { fields[i][1].setText(port); invalidateTest(i); }
        });
        engine.setPrototypeDisplayValue(DatabaseEngine.SQLSERVER);
        engine.setPreferredSize(new Dimension(195, engine.getPreferredSize().height));
        engine.setToolTipText("El motor seleccionado se usa para origen y destino.");
        try { for (ConnectionProfile profile : ConnectionProfiles.load()) savedProfiles.put(profile.name(), profile); }
        catch (IOException ex) { JOptionPane.showMessageDialog(owner, "No se pudieron cargar los perfiles guardados: " + ex.getMessage(), "Perfiles no disponibles", JOptionPane.WARNING_MESSAGE); }
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setBorder(new EmptyBorder(12, 16, 8, 16));
        JPanel selectionRow = new JPanel(new GridBagLayout());
        GridBagConstraints hc = new GridBagConstraints(); hc.insets = new Insets(4, 6, 4, 6); hc.anchor = GridBagConstraints.WEST;
        hc.gridx = 0; hc.gridy = 0; selectionRow.add(new JLabel("Tipo de origen"), hc);
        sourceMode.setPrototypeDisplayValue(SourceMode.SNAPSHOT); hc.gridx = 1; selectionRow.add(sourceMode, hc);
        hc.gridx = 2; hc.insets = new Insets(4, 20, 4, 6); selectionRow.add(new JLabel("Motor (ambos lados)"), hc);
        hc.gridx = 3; hc.insets = new Insets(4, 6, 4, 6); selectionRow.add(engine, hc);
        header.add(selectionRow, BorderLayout.NORTH);
        JLabel steps = new JLabel("1 · Configurá el origen     2 · Configurá el destino");
        steps.setForeground(UIManager.getColor("Label.disabledForeground"));
        steps.setBorder(new EmptyBorder(0, 6, 0, 0)); header.add(steps, BorderLayout.SOUTH);

        originCards.add(buildDatabasePanel(0), "database");
        originCards.add(buildFileSourcePanel(), "file");
        JPanel destinationPanel = buildDatabasePanel(1);
        JPanel endpoints = new JPanel(new GridLayout(1, 2, 12, 0));
        endpoints.setBorder(new EmptyBorder(4, 16, 10, 16));
        endpoints.add(originCards); endpoints.add(destinationPanel);
        sourceMode.addActionListener(event -> updateSourceMode());

        connectButton = actionButton("Iniciar comparación");
        JButton cancel = new JButton("Cancelar");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT)); buttons.add(cancel); buttons.add(connectButton);
        cancel.addActionListener(e -> { autoConnectRequested = false; dispose(); });
        connectButton.addActionListener(e -> requestConnect());
        JPanel content = new JPanel(new BorderLayout()); content.add(header, BorderLayout.NORTH); content.add(endpoints, BorderLayout.CENTER);
        add(content, BorderLayout.CENTER); add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(connectButton);
        getRootPane().registerKeyboardAction(event -> { autoConnectRequested = false; dispose(); },
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        updateSourceMode();
        if (lastComparison != null) restoreLastComparison(lastComparison);
        setMinimumSize(new Dimension(850, 480)); pack(); setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(() -> {
            if (!isDisplayable()) return;
            if (restoreNotice != null) JOptionPane.showMessageDialog(this, restoreNotice,
                    "Se requiere revisar la conexión", JOptionPane.INFORMATION_MESSAGE);
            if (lastComparison != null && restoreCredentialsAvailable && attemptAutoConnect) {
                autoRestored = true;
                requestConnect();
            } else if (lastComparison != null) passwords[lastComparison.mode() == SourceMode.DATABASE ? 0 : 1].requestFocusInWindow();
            else firstFields[sourceMode.getSelectedItem() == SourceMode.DATABASE ? 0 : 1].requestFocusInWindow();
        });
    }

    private void restoreLastComparison(LastComparisonPreferences.SavedComparison saved) {
        // Restore non-secret fields first; passwords are recovered only from an exact matching saved profile.
        sourceMode.setSelectedItem(saved.mode());
        updateSourceMode();
        applyingSavedProfile = true;
        try { engine.setSelectedItem(saved.destination().engine()); }
        finally { applyingSavedProfile = false; }
        restoreCredentialsAvailable = true;
        int firstSide = saved.mode() == SourceMode.DATABASE ? 0 : 1;
        if (firstSide == 0) restoreEndpoint(0, saved.origin());
        restoreEndpoint(1, saved.destination());
        if (saved.mode() != SourceMode.DATABASE) {
            if (saved.sourceFolder() == null) {
                restoreCredentialsAvailable = false;
                restoreNotice = "La última comparación no tenía una carpeta de origen válida. Elegí una carpeta para continuar.";
            } else {
                selectedSourceFolder = saved.sourceFolder();
                sourceFolderField.setText(saved.sourceFolder().toString());
                sourceFolderField.setToolTipText(saved.sourceFolder().toString());
                if (!java.nio.file.Files.isDirectory(saved.sourceFolder())) {
                    restoreCredentialsAvailable = false;
                    restoreNotice = "La carpeta de origen guardada ya no existe. Elegí una carpeta válida para continuar.";
                }
            }
        }
        if (!restorePasswordsAvailable) {
            String missing = "Se cargaron los datos de la última comparación. Ingresá las contraseñas que no estén guardadas y probá las conexiones.";
            restoreNotice = restoreNotice == null ? missing : restoreNotice + "\n\n" + missing;
        }
    }

    private void restoreEndpoint(int side, LastComparisonPreferences.SavedEndpoint saved) {
        if (saved == null) { restoreCredentialsAvailable = false; restorePasswordsAvailable = false; return; }
        fields[side][0].setText(saved.host());
        fields[side][1].setText(Integer.toString(saved.port()));
        fields[side][2].setText(saved.user());
        tls[side].setSelected(saved.useTls());
        preferredDatabase[side] = saved.database();
        ConnectionProfile profile = savedProfiles.values().stream().filter(candidate ->
                candidate.engine() == saved.engine() && candidate.host().equals(saved.host())
                        && candidate.port() == saved.port() && candidate.database().equals(saved.database())
                        && candidate.user().equals(saved.user()) && candidate.useTls() == saved.useTls())
                .findFirst().orElse(null);
        refreshSavedChoices(side, profile == null ? null : profile.name());
        if (profile == null || !profile.rememberPassword()) {
            // Keep the endpoint prefilled, but require the user to enter credentials before testing it.
            restoreCredentialsAvailable = false;
            restorePasswordsAvailable = false;
            return;
        }
        try {
            char[] stored = ConnectionCredentialStore.load(profile.name());
            try { passwords[side].setText(new String(stored)); }
            finally { Arrays.fill(stored, '\0'); }
            rememberPasswords[side].setSelected(true);
        } catch (Exception ex) {
            restoreCredentialsAvailable = false;
            restorePasswordsAvailable = false;
            rememberPasswords[side].setSelected(false);
        }
    }

    private JPanel buildDatabasePanel(int side) {
        String[] labels = {"Servidor", "Puerto", "Usuario"};
        String[] defaults = {"127.0.0.1", Integer.toString(((DatabaseEngine) engine.getSelectedItem()).defaultPort()), ""};
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(""), new EmptyBorder(9, 10, 9, 10)));
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(4, 4, 4, 4); c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; JLabel title = new JLabel(side == 0 ? "BASE DE ORIGEN" : "BASE DE DESTINO"); title.setFont(javax.swing.UIManager.getFont("Label.font").deriveFont(Font.BOLD)); panel.add(title, c);
        c.gridy++; c.gridwidth = 1; c.weightx = 0; panel.add(new JLabel("Perfil"), c);
        JComboBox<String> savedChoice = savedChoices.get(side); savedChoice.setPrototypeDisplayValue("Elegí un perfil guardado"); refreshSavedChoices(side, null);
        JButton saveProfile = new JButton("Guardar perfil"), deleteProfile = new JButton("Eliminar");
        saveProfile.setToolTipText("Guardar la conexión actual con un nombre para reutilizarla");
        deleteProfile.setToolTipText("Eliminar el perfil seleccionado para este lado");
        JPanel profileControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0)); profileControls.add(saveProfile); profileControls.add(deleteProfile);
        JPanel profileRow = new JPanel(new BorderLayout(4, 0)); profileRow.add(savedChoice, BorderLayout.CENTER); profileRow.add(profileControls, BorderLayout.EAST);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; panel.add(profileRow, c);
        c.gridy++; c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE; panel.add(new JLabel(labels[0]), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JTextField hostField = new JTextField(defaults[0], 15); fields[side][0] = hostField; firstFields[side] = hostField; panel.add(hostField, c);
        c.gridy++; c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE; panel.add(new JLabel(labels[1]), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JTextField portField = new JTextField(defaults[1], 15); fields[side][1] = portField; panel.add(portField, c);
        c.gridy++; c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE; panel.add(new JLabel("Base de datos"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JComboBox<String> database = databases.get(side); database.setEnabled(false); database.setPrototypeDisplayValue("Elegí una base de datos"); panel.add(database, c);
        c.gridy++; c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE; panel.add(new JLabel(labels[2]), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JTextField userField = new JTextField(defaults[2], 15); fields[side][2] = userField; panel.add(userField, c);
        c.gridy++; c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE; panel.add(new JLabel("Contraseña"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; passwords[side].setEchoChar('•'); panel.add(passwords[side], c);
        c.gridy++; c.gridx = 0; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; rememberPasswords[side].setToolTipText("Solo si activás esta opción se guarda la contraseña en el almacén seguro del sistema operativo."); panel.add(rememberPasswords[side], c);
        c.gridy++; panel.add(tls[side], c);
        c.gridy++; panel.add(new JSeparator(), c);
        testButtons[side] = new JButton("Probar conexión"); testStatus[side] = new JLabel("Sin probar"); testStatus[side].setForeground(UIManager.getColor("Label.disabledForeground"));
        JPanel testRow = new JPanel(new BorderLayout(8, 0)); testRow.add(testButtons[side], BorderLayout.WEST); testRow.add(testStatus[side], BorderLayout.CENTER);
        c.gridy++; panel.add(testRow, c);
        final int endpointIndex = side;
        testButtons[side].addActionListener(e -> testConnection(endpointIndex));
        savedChoice.addActionListener(e -> {
            String name = (String) savedChoice.getSelectedItem();
            if (!changingSavedChoice[endpointIndex] && name != null && !name.equals("Elegí un perfil guardado")) applyProfile(endpointIndex, savedProfiles.get(name));
        });
        databases.get(side).addActionListener(e -> { if (!updatingDatabaseChoice[endpointIndex]) finishAutomaticConnect(); });
        saveProfile.addActionListener(e -> saveProfile(endpointIndex)); deleteProfile.addActionListener(e -> deleteProfile(endpointIndex));
        observeChanges(side);
        return panel;
    }

    private JPanel buildFileSourcePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(""), new EmptyBorder(12, 12, 12, 12)));
        GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(5, 4, 8, 4);
        sourceCardTitle.setFont(javax.swing.UIManager.getFont("Label.font").deriveFont(Font.BOLD)); panel.add(sourceCardTitle, c);
        c.gridy++; sourceCardDescription.setVerticalAlignment(SwingConstants.TOP); sourceCardDescription.setPreferredSize(new Dimension(360, 72)); panel.add(sourceCardDescription, c);
        c.gridy++; c.gridwidth = 1; c.insets = new Insets(4, 4, 4, 4); panel.add(new JLabel("Carpeta"), c);
        sourceFolderField.setEditable(false); sourceFolderField.setText("Seleccioná una carpeta…");
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; panel.add(sourceFolderField, c);
        JButton chooseFolder = new JButton("Examinar…"); c.gridy++; c.gridx = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE; c.anchor = GridBagConstraints.EAST; panel.add(chooseFolder, c);
        chooseFolder.addActionListener(event -> chooseSourceFolder());
        return panel;
    }

    private void updateSourceMode() {
        SourceMode mode = (SourceMode) sourceMode.getSelectedItem();
        if (mode == null) return;
        boolean databaseOrigin = mode == SourceMode.DATABASE;
        ((CardLayout) originCards.getLayout()).show(originCards, databaseOrigin ? "database" : "file");
        if (!databaseOrigin) {
            invalidateTest(0);
            passwords[0].setText("");
            selectedSourceFolder = null; sourceFolderField.setText("Seleccioná una carpeta…");
            sourceCardTitle.setText(mode == SourceMode.FLYWAY ? "ORIGEN · MIGRACIONES FLYWAY" : "ORIGEN · SNAPSHOT SQL");
            sourceCardDescription.setText(mode == SourceMode.FLYWAY
                    ? "<html><div style='width:350px'>Elegí la carpeta con migraciones versionadas. Se ejecutan en una base temporal del servidor destino; la base destino no se modifica directamente.</div></html>"
                    : "<html><div style='width:350px'>Elegí una carpeta con definiciones CREATE completas, una por objeto. El SQL se lee para comparar; no se ejecuta.</div></html>");
            testButtons[0].setEnabled(false);
        } else {
            testButtons[0].setEnabled(true);
        }
        connectButton.setText("Iniciar comparación");
        setTitle("Nueva comparación");
        revalidate(); pack(); setMinimumSize(new Dimension(850, 480));
    }

    private void chooseSourceFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(sourceMode.getSelectedItem() == SourceMode.FLYWAY
                ? "Seleccionar carpeta de migraciones Flyway" : "Seleccionar carpeta de definiciones SQL");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        selectedSourceFolder = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        sourceFolderField.setText(selectedSourceFolder.toString());
        sourceFolderField.setToolTipText(selectedSourceFolder.toString());
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
        DatabaseEngine previousEngine = (DatabaseEngine) engine.getSelectedItem();
        applyingSavedProfile = true;
        try {
            engine.setSelectedItem(profile.engine());
            if (previousEngine != profile.engine()) {
                String defaultPort = Integer.toString(profile.engine().defaultPort());
                for (int endpoint = 0; endpoint < fields.length; endpoint++) {
                    if (endpoint != side) fields[endpoint][1].setText(defaultPort);
                    invalidateTest(endpoint);
                }
            }
            fields[side][0].setText(profile.host());
            fields[side][1].setText(Integer.toString(profile.port()));
            fields[side][2].setText(profile.user());
        } finally {
            applyingSavedProfile = false;
        }
        passwords[side].setText(""); tls[side].setSelected(profile.useTls()); rememberPasswords[side].setSelected(profile.rememberPassword());
        if (profile.rememberPassword()) {
            try {
                char[] stored = ConnectionCredentialStore.load(profile.name());
                try { passwords[side].setText(new String(stored)); }
                finally { Arrays.fill(stored, '\0'); }
            } catch (Exception ex) {
                rememberPasswords[side].setSelected(false);
                JOptionPane.showMessageDialog(this, "No se pudo recuperar la contraseña guardada. Ingresala nuevamente.\n" + ex.getMessage(), "Contraseña no disponible", JOptionPane.WARNING_MESSAGE);
            }
        }
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
        char[] password = passwords[side].getPassword();
        try {
            ConnectionProfile previous = savedProfiles.get(name.trim());
            ConnectionProfile profile = new ConnectionProfile(name.trim(), (DatabaseEngine) engine.getSelectedItem(),
                    fields[side][0].getText().trim(), Integer.parseInt(fields[side][1].getText().trim()),
                    (String) databases.get(side).getSelectedItem(), fields[side][2].getText().trim(), tls[side].isSelected(), false);
            ConnectionProfiles.save(profile);
            if (rememberPasswords[side].isSelected()) {
                ConnectionCredentialStore.save(profile.name(), password);
                profile = new ConnectionProfile(profile.name(), profile.engine(), profile.host(), profile.port(), profile.database(), profile.user(), profile.useTls(), true);
                ConnectionProfiles.save(profile);
            } else if (previous != null && previous.rememberPassword()) ConnectionCredentialStore.delete(profile.name());
            savedProfiles.put(profile.name(), profile); refreshSavedChoices(side, profile.name());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo guardar el perfil: " + ex.getMessage(), "Error al guardar", JOptionPane.ERROR_MESSAGE);
        } finally { Arrays.fill(password, '\0'); }
    }

    private void deleteProfile(int side) {
        String name = (String) savedChoices.get(side).getSelectedItem();
        if (name == null || name.equals("Elegí un perfil guardado") || !savedProfiles.containsKey(name)) {
            JOptionPane.showMessageDialog(this, "Elegí un perfil guardado para eliminar.", "No hay un perfil seleccionado", JOptionPane.INFORMATION_MESSAGE); return;
        }
        if (JOptionPane.showConfirmDialog(this, "¿Eliminar el perfil de conexión '" + name + "'?", "Eliminar perfil", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            ConnectionProfile profile = savedProfiles.get(name);
            if (profile != null && profile.rememberPassword()) ConnectionCredentialStore.delete(name);
            ConnectionProfiles.remove(name); savedProfiles.remove(name); refreshSavedChoices(side, null);
        } catch (Exception ex) { JOptionPane.showMessageDialog(this, "No se pudo eliminar el perfil: " + ex.getMessage(), "Error al eliminar", JOptionPane.ERROR_MESSAGE); }
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
        if (testStatus[side] != null) { testStatus[side].setText("Probar nuevamente"); testStatus[side].setForeground(UIManager.getColor("Label.disabledForeground")); }
    }

    private void requestConnect() {
        autoConnectRequested = true;
        int firstSide = sourceMode.getSelectedItem() == SourceMode.DATABASE ? 0 : 1;
        for (int side = firstSide; side < 2; side++) if (!testedSuccessfully[side] && testButtons[side].isEnabled()) testConnection(side);
        finishAutomaticConnect();
    }

    private void finishAutomaticConnect() {
        if (!autoConnectRequested || !isDisplayable()) return;
        int firstSide = sourceMode.getSelectedItem() == SourceMode.DATABASE ? 0 : 1;
        for (int side = firstSide; side < 2; side++) if (!testButtons[side].isEnabled()) return;
        for (int side = firstSide; side < 2; side++) if (!testedSuccessfully[side]) { autoConnectRequested = false; return; }
        for (int side = firstSide; side < 2; side++) {
            if (databases.get(side).getItemCount() == 0) { autoConnectRequested = false; return; }
            if (databases.get(side).getSelectedItem() == null) {
                testStatus[side].setText("Elegí una base para continuar"); testStatus[side].setForeground(UIManager.getColor("Component.warning.focusedBorderColor")); return;
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
                        String status = available.isEmpty() ? "Conectado; no hay bases visibles" : choice.getSelectedItem() == null ? "Conectado · " + available.size() + " bases · elegí una" : "Conectado · base seleccionada";
                        testStatus[side].setText(status); testStatus[side].setToolTipText(status);
                        testStatus[side].setForeground(available.isEmpty() ? UIManager.getColor("Component.warning.focusedBorderColor") : UIManager.getColor("Component.success.focusedBorderColor"));
                        finishAutomaticConnect();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt(); testedSuccessfully[side] = false; autoConnectRequested = false; testStatus[side].setText("Prueba interrumpida");
                    } catch (ExecutionException ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        String message = actionableConnectionError(cause);
                        testedSuccessfully[side] = false; autoConnectRequested = false; testStatus[side].setText("Error · ver detalle"); testStatus[side].setToolTipText(message); testStatus[side].setForeground(UIManager.getColor("Component.error.focusedBorderColor"));
                        JOptionPane.showMessageDialog(ConnectionDialog.this, message, "Falló la prueba de conexión", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        } catch (RuntimeException ex) {
            autoConnectRequested = false;
            testStatus[side].setText("Datos inválidos · ver detalle"); testStatus[side].setToolTipText("Revisá servidor, puerto y usuario. Detalle: " + ex.getMessage());
            testStatus[side].setForeground(UIManager.getColor("Component.warning.focusedBorderColor"));
        }
    }

    private static String actionableConnectionError(Throwable failure) {
        String detail = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        String lower = detail.toLowerCase(Locale.ROOT);
        String suggestion = lower.contains("login") || lower.contains("password") || lower.contains("authentication")
                ? "Revisá usuario y contraseña."
                : lower.contains("timeout") || lower.contains("refused") || lower.contains("connect")
                ? "Revisá servidor, puerto, red y que el motor acepte conexiones."
                : lower.contains("certificate") || lower.contains("ssl") || lower.contains("tls")
                ? "Revisá TLS y el certificado del servidor."
                : "Revisá que el motor, la instancia y los datos de conexión sean correctos.";
        return suggestion + " Detalle: " + detail;
    }

    private void accept() {
        Endpoint origin = null, destination = null;
        try {
            DatabaseEngine selectedEngine = (DatabaseEngine) engine.getSelectedItem();
            SourceMode selectedMode = (SourceMode) sourceMode.getSelectedItem();
            int firstSide = selectedMode == SourceMode.DATABASE ? 0 : 1;
            for (int i = firstSide; i < 2; i++) {
                String database = (String) databases.get(i).getSelectedItem();
                if (database == null) throw new IllegalArgumentException(selectedMode == SourceMode.DATABASE
                        ? "Probá la conexión y elegí una base para origen y destino"
                        : "Probá la conexión y elegí una base de destino");
                char[] typedPassword = passwords[i].getPassword();
                Endpoint endpoint;
                try { endpoint = new Endpoint(selectedEngine, fields[i][0].getText().trim(), Integer.parseInt(fields[i][1].getText().trim()), database, fields[i][2].getText().trim(), typedPassword, tls[i].isSelected()); }
                finally { Arrays.fill(typedPassword, '\0'); }
                if (i == 0) origin = endpoint; else destination = endpoint;
            }
            if (selectedMode != SourceMode.DATABASE && selectedSourceFolder == null)
                throw new IllegalArgumentException("Seleccioná la carpeta del origen antes de continuar.");
            selection = new Selection(selectedMode, origin, destination, selectedSourceFolder, autoRestored);
            dispose();
        } catch (RuntimeException ex) {
            if (origin != null) origin.clearPassword();
            if (destination != null) destination.clearPassword();
            JOptionPane.showMessageDialog(this, "Revisá los datos de conexión: " + ex.getMessage(), "Datos de conexión no válidos", JOptionPane.WARNING_MESSAGE);
        }
    }
}
