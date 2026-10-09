package studio.dbdiff;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;

/** Edits appearance independently from preference storage and the main controller. */
final class SettingsDialog {
    record Selection(String theme, String fontFamily, int fontSize, boolean rememberWindowBounds, boolean startMaximized) { }
    private static final String AUTOMATIC = "Automática (JetBrains Mono / Consolas)";

    static Selection show(JFrame owner, String theme, String family, int size, boolean rememberBounds, boolean maximized) {
        JComboBox<String> themes = new JComboBox<>(new String[]{"Sistema", "Claro", "Oscuro"});
        themes.setSelectedItem(theme);
        JComboBox<String> fonts = new JComboBox<>(); fonts.addItem(AUTOMATIC);
        DiffCodeEditor.availableCodeFontFamilies().forEach(fonts::addItem);
        fonts.setSelectedItem(family.isBlank() ? AUTOMATIC : family);
        JSpinner sizes = new JSpinner(new SpinnerNumberModel(size, 10, 24, 1));
        JTextArea preview = new JTextArea("SELECT id, nombre\nFROM clientes\nWHERE activo = 1;", 4, 30);
        preview.setEditable(false);
        Runnable refresh = () -> preview.setFont(DiffCodeEditor.codeFont(fontFamily(fonts), (Integer) sizes.getValue()));
        fonts.addActionListener(e -> refresh.run()); sizes.addChangeListener(e -> refresh.run()); refresh.run();
        JPanel appearance = section("Apariencia");
        appearance.add(row("Tema", themes), BorderLayout.CENTER);
        JPanel window = section("Ventana");
        JCheckBox remember = new JCheckBox("Recordar tamaño y posición de la ventana", rememberBounds);
        JCheckBox startMaximizedChoice = new JCheckBox("Iniciar maximizada", maximized);
        JPanel windowChoices = new JPanel(new GridLayout(0, 1, 0, 6));
        windowChoices.add(remember); windowChoices.add(startMaximizedChoice);
        window.add(windowChoices, BorderLayout.CENTER);
        JPanel code = section("Código");
        JPanel choices = new JPanel(new GridLayout(0, 1, 0, 8));
        choices.add(row("Fuente", fonts)); choices.add(row("Tamaño", sizes));
        code.add(choices, BorderLayout.NORTH); code.add(new JScrollPane(preview), BorderLayout.CENTER);
        code.add(ReviewDialog.note("Automática usa JetBrains Mono, Consolas o la fuente monoespaciada del sistema, según disponibilidad."), BorderLayout.SOUTH);
        JPanel form = new JPanel(new BorderLayout(0, 16));
        JPanel top = new JPanel(new GridLayout(0, 1, 0, 12));
        top.add(appearance); top.add(window);
        form.add(top, BorderLayout.NORTH); form.add(code, BorderLayout.CENTER);
        JTextArea storage = ReviewDialog.note("Perfiles y preferencias: " + Path.of(System.getProperty("user.home"), ".db-diff-studio")
                + "\nContraseñas: almacén seguro del sistema operativo.");
        storage.setRows(3);
        form.add(ReviewDialog.disclosure("Almacenamiento", storage), BorderLayout.SOUTH);
        if (!ReviewDialog.confirm(owner, "Configuración", form, new Dimension(720, 570), "Guardar")) return null;
        return new Selection((String) themes.getSelectedItem(), fontFamily(fonts), (Integer) sizes.getValue(), remember.isSelected(), startMaximizedChoice.isSelected());
    }

    private static String fontFamily(JComboBox<String> fonts) {
        String selected = (String) fonts.getSelectedItem();
        return AUTOMATIC.equals(selected) || selected == null ? "" : selected;
    }

    private static JPanel section(String title) {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title), new EmptyBorder(8, 12, 8, 12)));
        return panel;
    }

    private static JPanel row(String title, JComponent control) {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        JLabel label = new JLabel(title); label.setLabelFor(control); label.setPreferredSize(new Dimension(65, 25));
        panel.add(label, BorderLayout.WEST); panel.add(control, BorderLayout.CENTER);
        return panel;
    }
}
