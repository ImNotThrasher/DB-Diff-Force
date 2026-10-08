package studio.dbdiff;

import javax.swing.JTextField;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;

/** JTextField with an in-place hint that does not become part of its value. */
final class PlaceholderTextField extends JTextField {
    private final String placeholder;
    private Color placeholderColor = new Color(128, 137, 149);

    PlaceholderTextField(String placeholder) {
        this.placeholder = placeholder;
    }

    void setPlaceholderColor(Color color) {
        placeholderColor = color;
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (!getText().isEmpty()) return;
        Graphics2D g = (Graphics2D) graphics.create();
        g.setColor(isEnabled() ? placeholderColor : new Color(placeholderColor.getRed(), placeholderColor.getGreen(), placeholderColor.getBlue(), 135));
        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();
        Insets insets = getInsets();
        int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
        g.drawString(placeholder, insets.left + 1, y);
        g.dispose();
    }
}
