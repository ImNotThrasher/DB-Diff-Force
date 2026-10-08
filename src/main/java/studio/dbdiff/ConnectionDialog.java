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
    private Endpoint[] endpoints;
    static Endpoint[] showDialog(JFrame owner, String theme) {
        ConnectionDialog dialog = new ConnectionDialog(owner);
        AppTheme.apply(dialog, null, theme);
        dialog.setVisible(true);
        return dialog.endpoints;
    }

    private static JButton actionButton(String name, boolean primary) {
        JButton button = new JButton(name);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        if (primary) { button.setBackground(new Color(61, 117, 207)); button.setForeground(Color.WHITE); button.setFocusPainted(false); }
        return button;
    }
    private final JTextField[][] fields = new JTextField[2][3];
    private final JPasswordField[] passwords = {new JPasswordField(15), new JPasswordField(15)};
    private final JCheckBox[] rememberPasswords = {new JCheckBox("Recordar contraseña en este equipo"), new JCheckBox("Recordar contraseña en este equipo")};
    private final JCheckBox[] tls = {new JCheckBox("Usar TLS y verificar certificado"), new JCheckBox("Usar TLS y verificar certificado")};
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

    ConnectionDialog(JFrame owner) {
        super(owner, "Conectar bases", true); setLayout(new BorderLayout(10, 10)); JPanel form = new JPanel(new GridBagLayout()); form.setBorder(new EmptyBorder(16, 20, 10, 20));
        engine.addActionListener(e -> {
            String port = Integer.toString(((DatabaseEngine) engine.getSelectedItem()).defaultPort());
            for (int i = 0; i < 2; i++) { fields[i][1].setText(port); invalidateTest(i); }
        });
        engine.setPrototypeDisplayValue(DatabaseEngine.SQLSERVER);
        engine.setPreferredSize(new Dimension(195, engine.getPreferredSize().height));
        engine.setToolTipText("Elegí el motor que usan las dos bases de esta comparación.");
        GridBagConstraints engineConstraints = new GridBagConstraints(); engineConstraints.insets = new Insets(5, 6, 5, 6); engineConstraints.anchor = GridBagConstraints.WEST; engineConstraints.gridx = 0; engineConstraints.gridy = 0;
        form.add(new JLabel("Motor de base de datos", UiIcons.themed(UiIcons.CONNECT, 16), JLabel.LEFT), engineConstraints); engineConstraints.gridx = 1; engineConstraints.gridwidth = 3; form.add(engine, engineConstraints);
        GridBagConstraints separatorConstraints = new GridBagConstraints(); separatorConstraints.gridx = 0; separatorConstraints.gridy = 1; separatorConstraints.gridwidth = 4; separatorConstraints.fill = GridBagConstraints.HORIZONTAL; separatorConstraints.insets = new Insets(7, 6, 3, 6);
        form.add(new JSeparator(SwingConstants.HORIZONTAL), separatorConstraints);
        try { for (ConnectionProfile profile : ConnectionProfiles.load()) savedProfiles.put(profile.name(), profile); }
        catch (IOException ex) { JOptionPane.showMessageDialog(owner, "No se pudieron cargar los perfiles guardados: " + ex.getMessage(), "Perfiles no disponibles", JOptionPane.WARNING_MESSAGE); }
        String[] labels = {"Servidor", "Puerto", "Usuario"}; String[] defaults = {"127.0.0.1", "3306", ""};
        for (int side = 0; side < 2; side++) {
            GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(5, 6, 5, 6); c.anchor = GridBagConstraints.WEST;
            c.gridx = side * 2; c.gridy = 2; c.gridwidth = 2; JLabel header = new JLabel(side == 0 ? "BASE DE ORIGEN" : "BASE DE DESTINO"); header.setFont(new Font("Segoe UI", Font.BOLD, 11)); form.add(header, c);
            c.gridy = 3; c.gridwidth = 1; c.weightx = 0; form.add(new JLabel("Perfil"), c);
            c.gridx = side * 2 + 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            JComboBox<String> savedChoice = savedChoices.get(side); savedChoice.setPrototypeDisplayValue("Elegí un perfil guardado"); refreshSavedChoices(side, null);
            JButton saveProfile = new JButton("Guardar"); JButton deleteProfile = new JButton("Eliminar");
            JPanel profileControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0)); profileControls.add(saveProfile); profileControls.add(deleteProfile);
            JPanel profileRow = new JPanel(new BorderLayout(4, 0)); profileRow.add(savedChoice, BorderLayout.CENTER); profileRow.add(profileControls, BorderLayout.EAST);
            c.gridx = side * 2 + 1; c.gridwidth = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(profileRow, c);
            for (int row = 0; row < labels.length; row++) {
                c.gridy = row + 4; c.gridwidth = 1; c.gridx = side * 2; c.weightx = 0; c.fill = GridBagConstraints.NONE; form.add(new JLabel(labels[row]), c);
                c.gridx = side * 2 + 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; JTextField f = new JTextField(defaults[row], 15); fields[side][row] = f; form.add(f, c);
            }
            c.gridy = 7; c.gridx = side * 2; c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE; form.add(new JLabel("Base de datos"), c);
            c.gridx = side * 2 + 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            JComboBox<String> database = databases.get(side); database.setEnabled(false); database.setPrototypeDisplayValue("Elegí una base de datos"); form.add(database, c);
            c.gridy = 8; c.gridx = side * 2; c.weightx = 0; c.fill = GridBagConstraints.NONE; form.add(new JLabel("Contraseña"), c);
            c.gridx = side * 2 + 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; passwords[side].setEchoChar('•'); form.add(passwords[side], c);
            c.gridy = 9; c.gridx = side * 2; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(rememberPasswords[side], c);
            c.gridy = 10; form.add(tls[side], c);
            testButtons[side] = new JButton("Probar conexión"); testStatus[side] = new JLabel("Sin probar"); testStatus[side].setForeground(new Color(105, 118, 136));
            JPanel testRow = new JPanel(new BorderLayout(8, 0)); testRow.add(testButtons[side], BorderLayout.WEST); testRow.add(testStatus[side], BorderLayout.CENTER);
            c.gridy = 11; c.gridx = side * 2; c.gridwidth = 2; form.add(testRow, c);
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
        JLabel secure = new JLabel("La contraseña solo se guarda si activás la opción; se usa el almacén seguro del sistema operativo."); secure.setBorder(new EmptyBorder(0, 20, 0, 20));
        JButton cancel = new JButton("Cancelar"), connect = actionButton("Conectar", true); JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT)); buttons.add(cancel); buttons.add(connect);
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
                        JOptionPane.showMessageDialog(ConnectionDialog.this, cause.getMessage() == null ? cause.toString() : cause.getMessage(), "Falló la prueba de conexión", JOptionPane.ERROR_MESSAGE);
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
