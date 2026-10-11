package studio.dbdiff;

import com.formdev.flatlaf.FlatClientProperties;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;

/** Shared FlatLaf outline treatment for compact informational blocks. */
final class InfoNote {
    private InfoNote() { }

    static JPanel wrap(JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        JScrollPane outlined = new JScrollPane(content,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        outlined.setOpaque(false);
        outlined.getViewport().setOpaque(false);
        outlined.setViewportBorder(new EmptyBorder(4, 8, 4, 8));
        outlined.putClientProperty(FlatClientProperties.OUTLINE, accentColor());
        panel.add(outlined, BorderLayout.CENTER);
        return panel;
    }

    static Color accentColor() {
        Color color = UIManager.getColor("Component.focusColor");
        return color != null ? color : UIManager.getColor("Label.foreground");
    }
}
