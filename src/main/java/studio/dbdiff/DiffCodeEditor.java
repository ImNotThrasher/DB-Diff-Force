package studio.dbdiff;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.border.EmptyBorder;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/** Code editor, line numbers, and change markers for one diff pane. */
final class DiffCodeEditor extends JPanel {
    private static final Font CODE_FONT = preferredCodeFont();
    private final boolean unifiedMode;
    private final boolean sourceSide;
    private final DiffTextPane text = new DiffTextPane();
    private final DiffGutter gutter;
    private final JScrollPane scrollPane;

    DiffCodeEditor(boolean unifiedMode, boolean sourceSide) {
        super(new BorderLayout());
        this.unifiedMode = unifiedMode;
        this.sourceSide = sourceSide;
        gutter = new DiffGutter();
        text.setBorder(new EmptyBorder(unifiedMode ? 24 : 7, 8, 7, 8));
        scrollPane = new JScrollPane(text);
        scrollPane.setBorder(BorderFactory.createEtchedBorder());
        scrollPane.setRowHeaderView(gutter);
        add(scrollPane, BorderLayout.CENTER);
    }

    void showContent(String definition, List<DiffEngine.DiffLine> rows, boolean dark, boolean sidePresent) {
        text.setText(definition);
        text.setRows(rows);
        text.setDark(dark);
        text.applyLineColors(rows, dark);
        gutter.setRows(sidePresent ? rows : List.of());
        text.setCaretPosition(0);
    }

    void clear() {
        text.setRows(List.of());
        text.setText("");
        gutter.setRows(List.of());
    }

    void setThemeColors(boolean dark) {
        text.setDark(dark);
        text.applyLineColors(text.rows(), dark);
        gutter.setDark(dark);
    }

    void setFontSize(int size) {
        text.setFont(CODE_FONT.deriveFont((float) size));
        text.applyLineColors(text.rows(), text.dark);
        gutter.revalidate();
        gutter.repaint();
    }

    JScrollPane scrollPane() { return scrollPane; }

    private static String tooltip(DiffEngine.DiffLine line) {
        return switch (line.type()) {
            case CONTEXT -> "Línea idéntica";
            case REMOVED -> "Presente en origen · falta en destino";
            case ADDED -> "Presente en destino · falta en origen";
        };
    }

    /** Prefer JetBrains Mono when installed; keep a readable system monospace fallback. */
    private static Font preferredCodeFont() {
        String[] installed = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        String family = java.util.Arrays.stream(installed).filter(name -> name.equalsIgnoreCase("JetBrains Mono")).findFirst()
                .orElseGet(() -> java.util.Arrays.stream(installed).filter(name -> name.equalsIgnoreCase("Consolas")).findFirst()
                        .orElse(Font.MONOSPACED));
        return new Font(family, Font.PLAIN, 13);
    }

    private static final class DiffTextPane extends JTextPane {
        private List<DiffEngine.DiffLine> rows = List.of();
        private boolean dark;

        DiffTextPane() {
            setEditable(false);
            setOpaque(false);
            setFont(CODE_FONT);
            setToolTipText("");
        }

        List<DiffEngine.DiffLine> rows() { return rows; }
        void setRows(List<DiffEngine.DiffLine> rows) { this.rows = rows; }
        void setDark(boolean dark) { this.dark = dark; repaint(); }

        @Override public boolean getScrollableTracksViewportWidth() { return false; }

        void applyLineColors(List<DiffEngine.DiffLine> rows, boolean dark) {
            // El color de párrafo marca toda la línea y se fija también el texto
            // para conservar el contraste en ambos temas.
            ThemePalette palette = ThemePalette.forMode(dark);
            setBackground(palette.editor());
            setForeground(palette.foreground());
            StyledDocument document = getStyledDocument();
            SimpleAttributeSet textAttributes = new SimpleAttributeSet();
            StyleConstants.setForeground(textAttributes, palette.foreground());
            StyleConstants.setFontFamily(textAttributes, getFont().getFamily());
            StyleConstants.setFontSize(textAttributes, getFont().getSize());
            StyleConstants.setBold(textAttributes, getFont().isBold());
            StyleConstants.setItalic(textAttributes, getFont().isItalic());
            document.setCharacterAttributes(0, document.getLength(), textAttributes, true);
            for (int i = 0; i < rows.size() && i < document.getDefaultRootElement().getElementCount(); i++) {
                Element paragraph = document.getDefaultRootElement().getElement(i);
                DiffEngine.LineType type = rows.get(i).type();
                Color background = switch (type) {
                    case ADDED -> palette.addedBackground();
                    case REMOVED -> palette.removedBackground();
                    case CONTEXT -> palette.editor();
                };
                SimpleAttributeSet attributes = new SimpleAttributeSet();
                StyleConstants.setBackground(attributes, background);
                StyleConstants.setForeground(attributes, palette.foreground());
                document.setParagraphAttributes(paragraph.getStartOffset(),
                        paragraph.getEndOffset() - paragraph.getStartOffset(), attributes, true);
            }
        }

        @Override protected void paintComponent(Graphics graphics) {
            // Pintar el fondo antes del texto mantiene el resaltado continuo
            // incluso cuando una línea supera el ancho visible del editor.
            Graphics2D g = (Graphics2D) graphics.create();
            ThemePalette palette = ThemePalette.forMode(dark);
            g.setColor(palette.editor());
            g.fillRect(0, 0, getWidth(), getHeight());
            FontMetrics metrics = g.getFontMetrics(getFont());
            for (int i = 0; i < rows.size(); i++) {
                DiffEngine.LineType type = rows.get(i).type();
                if (type == DiffEngine.LineType.CONTEXT) continue;
                try {
                    Element line = getDocument().getDefaultRootElement().getElement(i);
                    Rectangle2D bounds = modelToView2D(line.getStartOffset());
                    if (bounds == null) continue;
                    int y = (int) Math.floor(bounds.getY());
                    if (y + metrics.getHeight() < 0 || y > getHeight()) continue;
                    g.setColor(type == DiffEngine.LineType.ADDED
                            ? palette.addedBackground() : palette.removedBackground());
                    g.fillRect(0, y, getWidth(), metrics.getHeight());
                } catch (javax.swing.text.BadLocationException ignored) { break; }
            }
            g.dispose();
            super.paintComponent(graphics);
        }

        @Override public String getToolTipText(MouseEvent event) {
            int line = getDocument().getDefaultRootElement().getElementIndex(viewToModel2D(event.getPoint()));
            return line >= 0 && line < rows.size() ? tooltip(rows.get(line)) : null;
        }
    }

    private final class DiffGutter extends javax.swing.JComponent {
        private List<DiffEngine.DiffLine> rows = List.of();
        private ThemePalette palette = ThemePalette.forMode(false);

        DiffGutter() { setOpaque(true); setToolTipText(""); }
        void setRows(List<DiffEngine.DiffLine> rows) { this.rows = rows; revalidate(); repaint(); }
        void setDark(boolean dark) { palette = ThemePalette.forMode(dark); setBackground(palette.gutterBackground()); repaint(); }

        @Override public Dimension getPreferredSize() {
            FontMetrics metrics = text.getFontMetrics(text.getFont());
            int maxLine = Math.max(1, rows.size());
            int digits = Integer.toString(maxLine).length();
            int numberWidth = metrics.charWidth('0') * Math.max(3, digits);
            int width = unifiedMode ? 22 + numberWidth * 2 + 18 : 24 + numberWidth;
            int height = Math.max(text.getHeight(), rows.size() * metrics.getHeight() + 14 + (unifiedMode ? 20 : 0));
            return new Dimension(width, height);
        }

        @Override protected void paintComponent(Graphics graphics) {
            // El margen usa las mismas filas visuales del editor para que números
            // y marcadores acompañen el desplazamiento del texto.
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(palette.gutterBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            FontMetrics metrics = g.getFontMetrics(text.getFont());
            g.setFont(text.getFont());
            if (unifiedMode) {
                g.setColor(palette.lineNumber());
                int oldRight = getWidth() / 2 - 3, newRight = getWidth() - 5;
                g.drawString("O", oldRight - metrics.stringWidth("O"), metrics.getAscent() + 1);
                g.drawString("D", newRight - metrics.stringWidth("D"), metrics.getAscent() + 1);
                g.setColor(palette.markerBackground());
                g.drawLine(0, 20, getWidth(), 20);
            }
            for (int i = 0; i < rows.size(); i++) {
                try {
                    Element line = text.getDocument().getDefaultRootElement().getElement(i);
                    Rectangle2D bounds = text.modelToView2D(line.getStartOffset());
                    if (bounds == null) continue;
                    int y = (int) Math.floor(bounds.getY());
                    if (y + metrics.getHeight() < 0 || y > getHeight()) continue;
                    DiffEngine.DiffLine row = rows.get(i);
                    Color changeColor = row.type() == DiffEngine.LineType.ADDED ? palette.addedForeground()
                            : row.type() == DiffEngine.LineType.REMOVED ? palette.removedForeground() : palette.lineNumber();
                    if (row.type() != DiffEngine.LineType.CONTEXT) {
                        g.setColor(palette.markerBackground());
                        g.fillRect(0, y, getWidth(), metrics.getHeight());
                        g.setColor(changeColor);
                        g.fillRect(2, y + 2, 3, Math.max(2, metrics.getHeight() - 4));
                        String marker = row.type() == DiffEngine.LineType.ADDED ? "+" : "−";
                        g.drawString(marker, 7, y + metrics.getAscent());
                    }
                    g.setColor(changeColor);
                    int baseline = y + metrics.getAscent();
                    if (unifiedMode) {
                        String oldNumber = row.sourceLineNumber() == null ? "" : row.sourceLineNumber().toString();
                        String newNumber = row.targetLineNumber() == null ? "" : row.targetLineNumber().toString();
                        int oldRight = getWidth() / 2 - 3;
                        int newRight = getWidth() - 5;
                        g.drawString(oldNumber, oldRight - metrics.stringWidth(oldNumber), baseline);
                        g.drawString(newNumber, newRight - metrics.stringWidth(newNumber), baseline);
                    } else {
                        Integer number = sourceSide ? row.sourceLineNumber() : row.targetLineNumber();
                        if (number != null) {
                            String value = number.toString();
                            int right = getWidth() - 5;
                            g.drawString(value, right - metrics.stringWidth(value), baseline);
                        }
                    }
                } catch (javax.swing.text.BadLocationException ignored) { break; }
            }
            g.setColor(palette.markerBackground());
            g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
            g.dispose();
        }

        @Override public String getToolTipText(MouseEvent event) {
            int line = text.getDocument().getDefaultRootElement().getElementIndex(
                    text.viewToModel2D(new java.awt.Point(0, event.getY())));
            return line >= 0 && line < rows.size() ? tooltip(rows.get(line)) : null;
        }
    }
}
