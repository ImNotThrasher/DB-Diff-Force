package studio.dbdiff;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;

/** Edits appearance independently from preference storage and the main controller. */
final class SettingsDialog {
    record Selection(String theme, String fontFamily, int fontSize, boolean rememberWindowBounds, boolean startMaximized,
                     boolean stackedLayout, boolean keepRowComparisonExpanded, boolean rememberLastComparison,
                     boolean ignoreMySqlViewDefiner, boolean ignoreComments, boolean ignoreAutoIncrement) { }
    private static final String AUTOMATIC = "Automática (JetBrains Mono / Consolas)";

    static Selection show(JFrame owner, String theme, String family, int size, boolean rememberBounds, boolean maximized,
                          boolean stackedLayout, boolean keepRowComparisonExpanded, boolean rememberLastComparison,
                          boolean ignoreMySqlViewDefiner, boolean ignoreComments, boolean ignoreAutoIncrement) {
        JComboBox<String> themes = new JComboBox<>(new String[]{"Sistema", "Claro", "Oscuro"});
        themes.setMaximumRowCount(3);
        themes.setPreferredSize(new Dimension(220, themes.getPreferredSize().height));
        themes.setSelectedItem(theme);
        themes.setAlignmentX(Component.LEFT_ALIGNMENT);
        JComboBox<String> fonts = new JComboBox<>(); fonts.addItem(AUTOMATIC);
        fonts.setMaximumRowCount(12);
        fonts.setPreferredSize(new Dimension(320, fonts.getPreferredSize().height));
        DiffCodeEditor.availableCodeFontFamilies().forEach(fonts::addItem);
        fonts.setSelectedItem(family.isBlank() ? AUTOMATIC : family);
        JSpinner sizes = new JSpinner(new SpinnerNumberModel(size, 10, 24, 1));
        JTextArea preview = new JTextArea("SELECT id, nombre\nFROM clientes\nWHERE activo = 1;", 4, 30);
        preview.setEditable(false);
        Runnable refresh = () -> preview.setFont(DiffCodeEditor.codeFont(fontFamily(fonts), (Integer) sizes.getValue()));
        fonts.addActionListener(e -> refresh.run()); sizes.addChangeListener(e -> refresh.run()); refresh.run();
        JPanel appearance = section("Apariencia");
        appearance.add(row("Tema", themes), BorderLayout.NORTH);
        appearance.setPreferredSize(new Dimension(680, 78));
        appearance.setMinimumSize(new Dimension(0, 78));
        JPanel window = section("Ventana");
        JCheckBox remember = new JCheckBox("Recordar tamaño y posición de la ventana", rememberBounds);
        JCheckBox startMaximizedChoice = new JCheckBox("Iniciar maximizada", maximized);
        JCheckBox stacked = new JCheckBox("Mostrar listado de objetos arriba (desmarcado: lateral)", !stackedLayout);
        JCheckBox keepRowsExpanded = new JCheckBox("Mantener Comparación de filas desplegada al cambiar de objeto", keepRowComparisonExpanded);
        JCheckBox rememberLast = new JCheckBox("Recordar y reconectar la última comparación al iniciar", rememberLastComparison);
        JCheckBox ignoreDefiner = new JCheckBox("Ignorar DEFINER y calificadores de entorno MySQL/MariaDB", ignoreMySqlViewDefiner);
        ignoreDefiner.setToolTipText("En vistas y rutinas omite DEFINER; en triggers omite DEFINER y la base del nombre. El SQL original se conserva para mostrar y exportar.");
        JCheckBox ignoreCommentsChoice = new JCheckBox("Ignorar comentarios al comparar SQL", ignoreComments);
        ignoreCommentsChoice.setToolTipText("Conserva el SQL original y los comentarios ejecutables; ignora solo comentarios comunes.");
        JCheckBox ignoreAutoIncrementChoice = new JCheckBox("Ignorar contador AUTO_INCREMENT de las tablas", ignoreAutoIncrement);
        ignoreAutoIncrementChoice.setToolTipText("Ignora el próximo valor de la secuencia, no la columna AUTO_INCREMENT ni la clave primaria.");
        rememberLast.setToolTipText("Guarda servidores, bases y carpeta de origen, nunca las contraseñas. La reconexión automática requiere credenciales guardadas en el almacén seguro del sistema.");
        JPanel windowChoices = new JPanel(new GridLayout(0, 1, 0, 6));
        windowChoices.add(remember); windowChoices.add(startMaximizedChoice); windowChoices.add(stacked); windowChoices.add(keepRowsExpanded); windowChoices.add(rememberLast); windowChoices.add(ignoreDefiner); windowChoices.add(ignoreCommentsChoice); windowChoices.add(ignoreAutoIncrementChoice);
        window.add(windowChoices, BorderLayout.CENTER);
        JPanel code = section("Código");
        JPanel choices = new JPanel(new GridLayout(0, 1, 0, 6));
        choices.add(row("Fuente", fonts)); choices.add(row("Tamaño", sizes));
        JPanel codeTop = new JPanel(new BorderLayout(0, 8));
        codeTop.add(choices, BorderLayout.NORTH);
        codeTop.add(ReviewDialog.note("Automática usa JetBrains Mono, Consolas o la fuente monoespaciada del sistema, según disponibilidad."), BorderLayout.SOUTH);
        code.add(codeTop, BorderLayout.NORTH); code.add(new JScrollPane(preview), BorderLayout.CENTER);
        JPanel form = new JPanel(new BorderLayout(0, 16));
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(appearance);
        top.add(Box.createVerticalStrut(12));
        top.add(window);
        form.add(top, BorderLayout.NORTH); form.add(code, BorderLayout.CENTER);
        JTextArea storage = ReviewDialog.note("Perfiles y preferencias: " + Path.of(System.getProperty("user.home"), ".db-diff-studio")
                + "\nContraseñas: almacén seguro del sistema operativo.");
        storage.setRows(3);
        form.add(ReviewDialog.disclosure("Almacenamiento", storage), BorderLayout.SOUTH);
        if (!ReviewDialog.confirm(owner, "Configuración", form, new Dimension(720, 780), "Guardar")) return null;
        return new Selection((String) themes.getSelectedItem(), fontFamily(fonts), (Integer) sizes.getValue(), remember.isSelected(),
                startMaximizedChoice.isSelected(), !stacked.isSelected(), keepRowsExpanded.isSelected(), rememberLast.isSelected(), ignoreDefiner.isSelected(), ignoreCommentsChoice.isSelected(), ignoreAutoIncrementChoice.isSelected());
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
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, control.getPreferredSize().height));
        panel.setPreferredSize(new Dimension(520, control.getPreferredSize().height));
        JLabel label = new JLabel(title); label.setLabelFor(control); label.setPreferredSize(new Dimension(72, 25));
        panel.add(label, BorderLayout.WEST); panel.add(control, BorderLayout.CENTER);
        return panel;
    }
}
