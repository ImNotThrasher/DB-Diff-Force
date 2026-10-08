package studio.dbdiff;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Installs native FlatLaf defaults; only diff renderers own semantic colors. */
public final class AppTheme {
    private AppTheme() { }

    public static boolean isDark(String mode) {
        return "Oscuro".equals(mode) || ("Sistema".equals(mode) && systemUsesDarkTheme());
    }

    /** Called before constructing any Swing component, including Main's fields. */
    static void initialize(String mode) {
        boolean dark = isDark(mode);
        if (!(UIManager.getLookAndFeel() instanceof FlatLaf) || FlatLaf.isLafDark() != dark) {
            if (dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
        }
    }

    public static void apply(Window window, JComponent header, String mode) {
        initialize(mode);
        FlatLaf.updateUI();
    }

    static void styleToggleButton(JToggleButton button, boolean dark) {
        // Selection, focus and disabled appearance belong to the installed theme.
        button.repaint();
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
