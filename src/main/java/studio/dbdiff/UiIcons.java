package studio.dbdiff;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;

/** Central icon choices for the Swing interface. */
final class UiIcons {
    static final Ikon CONNECT = Material2MZ.STORAGE;
    static final Ikon COMPARE = Material2AL.COMPARE_ARROWS;
    static final Ikon COMPARE_ROWS = Material2MZ.TABLE_ROWS;
    static final Ikon EXPORT = Material2AL.FILE_UPLOAD;
    static final Ikon SPLIT = Material2MZ.VIEW_COLUMN;
    static final Ikon UNIFIED = Material2MZ.VIEW_STREAM;

    private UiIcons() { }

    static Icon themed(Ikon icon, int size) { return new ThemeIcon(icon, size); }

    private static final class ThemeIcon implements Icon {
        private final Ikon icon;
        private final int size;
        private Color renderedColor;
        private FontIcon renderedIcon;

        private ThemeIcon(Ikon icon, int size) { this.icon = icon; this.size = size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Color color = component.isEnabled() ? component.getForeground() : UIManager.getColor("Button.disabledText");
            if (color == null) color = Color.GRAY;
            if (renderedIcon == null || !color.equals(renderedColor)) {
                renderedColor = color;
                renderedIcon = FontIcon.of(icon, size, color);
            }
            renderedIcon.paintIcon(component, graphics, x, y);
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }
}
