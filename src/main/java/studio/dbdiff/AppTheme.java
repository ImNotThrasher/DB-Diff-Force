package studio.dbdiff;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.SystemColor;
import java.awt.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Applies the selected theme to the application and standard Swing dialogs. */
public final class AppTheme {
    private static final Color DARK_BACKGROUND = new Color(30, 34, 42);
    private static final Color DARK_PANEL = new Color(37, 42, 51);
    private static final Color DARK_FOREGROUND = new Color(220, 225, 232);
    private static final Color DARK_BORDER = new Color(65, 73, 84);

    private AppTheme() { }

    public static boolean isDark(String mode) {
        return "Oscuro".equals(mode) || ("Sistema".equals(mode) && systemUsesDarkTheme());
    }

    public static void apply(Window window, JComponent header, String mode) {
        boolean dark = isDark(mode);
        Color panel = dark ? DARK_PANEL : new Color(246, 248, 251);
        Color foreground = dark ? DARK_FOREGROUND : new Color(35, 45, 58);
        Color border = dark ? DARK_BORDER : new Color(223, 228, 235);
        // Use the same Swing delegate for every mode. Native Windows controls can
        // ignore custom colors, which makes the app palette appear mixed.
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch (Exception ignored) { }
        UIManager.put("Panel.background", panel); UIManager.put("Panel.foreground", foreground);
        UIManager.put("OptionPane.background", panel); UIManager.put("OptionPane.messageForeground", foreground);
        UIManager.put("CheckBox.background", panel); UIManager.put("CheckBox.foreground", foreground);
        UIManager.put("ComboBox.background", panel); UIManager.put("ComboBox.foreground", foreground);
        UIManager.put("ComboBox.buttonBackground", panel);
        UIManager.put("ComboBox.buttonShadow", border); UIManager.put("ComboBox.buttonDarkShadow", border);
        UIManager.put("ComboBox.buttonHighlight", panel);
        UIManager.put("ComboBox.disabledBackground", dark ? DARK_BACKGROUND : Color.WHITE);
        UIManager.put("ComboBox.disabledForeground", dark ? new Color(157, 166, 178) : new Color(105, 116, 130));
        UIManager.put("ComboBox.selectionBackground", dark ? new Color(57, 78, 105) : new Color(190, 211, 235));
        UIManager.put("ComboBox.selectionForeground", foreground);
        UIManager.put("TextField.background", dark ? DARK_BACKGROUND : Color.WHITE); UIManager.put("TextField.foreground", foreground);
        UIManager.put("PasswordField.background", dark ? DARK_BACKGROUND : Color.WHITE); UIManager.put("PasswordField.foreground", foreground);
        UIManager.put("Button.background", panel); UIManager.put("Button.foreground", foreground);
        UIManager.put("Button.disabledText", dark ? new Color(135, 144, 157) : new Color(130, 140, 151));
        UIManager.put("List.background", dark ? DARK_BACKGROUND : Color.WHITE); UIManager.put("List.foreground", foreground);
        UIManager.put("Table.background", panel); UIManager.put("Table.foreground", foreground);
        UIManager.put("ScrollPane.background", panel); UIManager.put("Viewport.background", panel);
        UIManager.put("Menu.background", panel); UIManager.put("Menu.foreground", foreground);
        UIManager.put("MenuItem.background", panel); UIManager.put("MenuItem.foreground", foreground);
        UIManager.put("CheckBoxMenuItem.background", panel); UIManager.put("CheckBoxMenuItem.foreground", foreground);
        UIManager.put("PopupMenu.background", panel); UIManager.put("PopupMenu.foreground", foreground);
        UIManager.put("TitledBorder.titleColor", foreground);
        if (window != null) SwingUtilities.updateComponentTreeUI(window);
        if (header != null) header.setBackground(dark ? new Color(20, 25, 34) : new Color(23, 36, 54));
        if (window instanceof RootPaneContainer root) applyTree(root.getContentPane(), header, panel, foreground, border, dark);
        if (window != null) window.repaint();
    }

    private static void applyTree(Component node, JComponent header, Color panel, Color foreground, Color border, boolean dark) {
        if ((node instanceof JPanel && node != header) || node instanceof JScrollPane || node instanceof JViewport || node instanceof JSplitPane) node.setBackground(panel);
        if (node instanceof JLabel label) {
            label.setOpaque(false);
            boolean inHeader = header != null && SwingUtilities.isDescendingFrom(label, header);
            if (inHeader) {
                label.setForeground(label.getText() != null && label.getText().equals(AppInfo.NAME) ? Color.WHITE : new Color(184, 198, 216));
            } else {
                String text = label.getText();
                if (text != null && (text.startsWith("Solo se exporta") || text.startsWith("Se conservan"))) label.setForeground(dark ? new Color(236, 184, 95) : new Color(155, 79, 21));
                else label.setForeground(foreground);
            }
        } else if (node instanceof JTextArea area) {
            area.setBackground(dark ? DARK_BACKGROUND : new Color(251, 252, 254)); area.setForeground(foreground); area.setCaretColor(foreground);
        } else if (node instanceof JEditorPane editor) {
            editor.setBackground(dark ? DARK_BACKGROUND : Color.WHITE); editor.setForeground(foreground);
        } else if (node instanceof JList<?> list) {
            list.setBackground(dark ? DARK_BACKGROUND : Color.WHITE); list.setForeground(foreground);
        } else if (node instanceof JTable table) {
            table.setBackground(panel); table.setForeground(foreground); table.setGridColor(border);
            table.setSelectionBackground(dark ? new Color(57, 78, 105) : new Color(190, 211, 235)); table.setSelectionForeground(foreground);
            table.getTableHeader().setBackground(panel); table.getTableHeader().setForeground(foreground);
        } else if (node instanceof JButton button) {
            boolean primary = Boolean.TRUE.equals(button.getClientProperty("dbdiff.primary"));
            button.setBackground(primary ? new Color(61, 117, 207) : panel); button.setForeground(primary ? Color.WHITE : foreground);
            if (!primary) button.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(border), javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        } else if (node instanceof JCheckBox check) {
            check.setBackground(panel); check.setForeground(foreground);
        } else if (node instanceof JComboBox<?> combo) {
            combo.setBackground(panel); combo.setForeground(foreground);
        } else if (node instanceof JTextField field) {
            field.setBackground(dark ? DARK_BACKGROUND : Color.WHITE); field.setForeground(foreground); field.setCaretColor(foreground);
        }
        if (node instanceof Container container) for (Component child : container.getComponents()) applyTree(child, header, panel, foreground, border, dark);
    }

    private static boolean systemUsesDarkTheme() {
        try {
            Process process = new ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
            String output;
            try (var input = process.getInputStream()) { output = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
            if (process.waitFor() == 0 && output.matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0.*")) return true;
        } catch (IOException | InterruptedException | UnsupportedOperationException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        Color window = SystemColor.window;
        return (window.getRed() * 299 + window.getGreen() * 587 + window.getBlue() * 114) / 1000 < 128;
    }
}
