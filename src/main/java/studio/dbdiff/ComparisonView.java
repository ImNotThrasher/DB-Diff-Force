package studio.dbdiff;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.List;

/** Side-by-side definitions or a unified diff for one schema object. */
public final class ComparisonView extends JPanel {
    private static final String SPLIT = "split";
    private static final String UNIFIED = "unified";
    private final JLabel databasePair = new JLabel("Base origen  →  Base destino");
    private final JTextArea summary = new JTextArea();
    private final DiffTextPane source = new DiffTextPane();
    private final DiffTextPane target = new DiffTextPane();
    private final DiffTextPane unified = new DiffTextPane();
    private final DiffGutter sourceGutter = new DiffGutter(source, false, true);
    private final DiffGutter targetGutter = new DiffGutter(target, false, false);
    private final DiffGutter unifiedGutter = new DiffGutter(unified, true, true);
    private final JLabel sourceHeading = new JLabel("ORIGEN");
    private final JLabel targetHeading = new JLabel("DESTINO");
    private final JLabel unifiedHeading = new JLabel("DIFERENCIAS · origen (O) / destino (D)");
    private JToggleButton splitModeButton, unifiedModeButton;
    private final JPanel cards = new JPanel(new CardLayout());
    private DiffEngine.Result current;
    private boolean dark;

    public ComparisonView() {
        super(new BorderLayout(0, 8));
        add(headerPanel(), BorderLayout.NORTH);
        JSplitPane definitions = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                titled(sourceHeading, source, sourceGutter), titled(targetHeading, target, targetGutter));
        definitions.setResizeWeight(.5); definitions.setBorder(null); definitions.setDividerSize(7);
        cards.add(definitions, SPLIT);
        JPanel unifiedPanel = new JPanel(new BorderLayout());
        unifiedHeading.setFont(new Font("Segoe UI", Font.BOLD, 11)); unifiedHeading.setBorder(new EmptyBorder(0, 0, 6, 0));
        unified.setBorder(new EmptyBorder(24, 8, 7, 8));
        JScrollPane unifiedScroll = new JScrollPane(unified); unifiedScroll.setBorder(BorderFactory.createEtchedBorder()); unifiedScroll.setRowHeaderView(unifiedGutter);
        unifiedPanel.add(unifiedHeading, BorderLayout.NORTH); unifiedPanel.add(unifiedScroll, BorderLayout.CENTER);
        cards.add(unifiedPanel, UNIFIED);
        add(cards, BorderLayout.CENTER);
        showMode(SPLIT);
        syncVerticalScroll(definitions);
    }

    public void setDatabases(String sourceName, String targetName) {
        databasePair.setText("Base origen: " + displayName(sourceName) + "   →   Base destino: " + displayName(targetName));
        sourceHeading.setText("ORIGEN · " + displayName(sourceName));
        targetHeading.setText("DESTINO · " + displayName(targetName));
    }

    public void setThemeColors(boolean dark) {
        this.dark = dark;
        Color panel = dark ? new Color(37, 42, 51) : new Color(246, 248, 251);
        Color editor = dark ? new Color(30, 34, 42) : new Color(251, 252, 254);
        Color foreground = dark ? new Color(220, 225, 232) : new Color(35, 45, 58);
        Color selected = dark ? new Color(57, 78, 105) : new Color(190, 211, 235);
        setBackground(panel); cards.setBackground(panel);
        databasePair.setForeground(foreground); summary.setForeground(foreground);
        sourceHeading.setForeground(foreground); targetHeading.setForeground(foreground); unifiedHeading.setForeground(foreground);
        for (DiffTextPane pane : List.of(source, target, unified)) {
            pane.setBackground(editor); pane.setForeground(foreground); pane.setCaretColor(foreground);
        }
        if (splitModeButton != null) {
            splitModeButton.setForeground(foreground); splitModeButton.setBackground(splitModeButton.isSelected() ? selected : panel);
            unifiedModeButton.setForeground(foreground); unifiedModeButton.setBackground(unifiedModeButton.isSelected() ? selected : panel);
        }
        sourceGutter.setDark(dark); targetGutter.setDark(dark); unifiedGutter.setDark(dark);
        if (current != null) showResult(current, dark);
    }

    public void showMessage(String text) {
        current = null;
        summary.setText(text);
        source.setRows(List.of()); target.setRows(List.of()); unified.setRows(List.of());
        source.setText(""); target.setText(""); unified.setText("");
        sourceGutter.setRows(List.of()); targetGutter.setRows(List.of()); unifiedGutter.setRows(List.of());
        showMode(SPLIT);
    }

    public void showResult(DiffEngine.Result result, boolean dark) {
        current = result; this.dark = dark;
        summary.setText(result.key() + "   ·   Estado: " + statusName(result.status()));
        String left = result.source() == null ? "El objeto no existe en el origen." : result.source().definition();
        String right = result.target() == null ? "El objeto no existe en el destino." : result.target().definition();
        List<DiffEngine.DiffLine> sourceRows = result.diffLines().stream().filter(line -> line.sourceLineNumber() != null).toList();
        List<DiffEngine.DiffLine> targetRows = result.diffLines().stream().filter(line -> line.targetLineNumber() != null).toList();
        source.setText(left); source.setRows(sourceRows); applyLineColors(source, sourceRows, dark);
        target.setText(right); target.setRows(targetRows); applyLineColors(target, targetRows, dark);
        unified.setText(String.join("\n", result.diffLines().stream().map(DiffEngine.DiffLine::text).toList()));
        unified.setRows(result.diffLines()); applyLineColors(unified, result.diffLines(), dark);
        sourceGutter.setRows(result.source() == null ? List.of() : sourceRows);
        targetGutter.setRows(result.target() == null ? List.of() : targetRows);
        unifiedGutter.setRows(result.diffLines());
        source.setCaretPosition(0); target.setCaretPosition(0); unified.setCaretPosition(0);
    }

    private JPanel headerPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 4));
        JPanel labels = new JPanel(new BorderLayout(0, 4)); labels.setOpaque(false);
        databasePair.setFont(new Font("Segoe UI", Font.BOLD, 12));
        summary.setRows(2); summary.setEditable(false); summary.setLineWrap(true); summary.setWrapStyleWord(true);
        summary.setOpaque(false); summary.setFont(new Font("Segoe UI", Font.PLAIN, 12)); summary.setBorder(null);
        labels.add(databasePair, BorderLayout.NORTH); labels.add(summary, BorderLayout.CENTER);
        splitModeButton = new JToggleButton("Dividido", UiIcons.themed(UiIcons.SPLIT, 16));
        unifiedModeButton = new JToggleButton("Unificado", UiIcons.themed(UiIcons.UNIFIED, 16));
        splitModeButton.setIconTextGap(6); unifiedModeButton.setIconTextGap(6);
        splitModeButton.setToolTipText("Ver origen y destino en paralelo"); unifiedModeButton.setToolTipText("Ver el diff con números de origen y destino");
        ButtonGroup modes = new ButtonGroup(); modes.add(splitModeButton); modes.add(unifiedModeButton); splitModeButton.setSelected(true);
        splitModeButton.addActionListener(e -> showMode(SPLIT)); unifiedModeButton.addActionListener(e -> showMode(UNIFIED));
        JPanel choices = new JPanel(); choices.setOpaque(false); choices.add(splitModeButton); choices.add(unifiedModeButton);
        panel.setBorder(new EmptyBorder(2, 4, 4, 4)); panel.add(labels, BorderLayout.CENTER); panel.add(choices, BorderLayout.EAST);
        return panel;
    }

    private void showMode(String mode) { ((CardLayout) cards.getLayout()).show(cards, mode); }

    private static String displayName(String name) { return name == null || name.isBlank() ? "(sin seleccionar)" : name; }

    private static JPanel titled(JLabel heading, DiffTextPane text, DiffGutter gutter) {
        JPanel panel = new JPanel(new BorderLayout()); heading.setFont(new Font("Segoe UI", Font.BOLD, 11)); heading.setBorder(new EmptyBorder(0, 0, 6, 0));
        JScrollPane scroll = new JScrollPane(text); scroll.setBorder(BorderFactory.createEtchedBorder()); scroll.setRowHeaderView(gutter);
        panel.add(heading, BorderLayout.NORTH); panel.add(scroll, BorderLayout.CENTER); return panel;
    }

    private static void syncVerticalScroll(JSplitPane definitions) {
        JScrollPane left = (JScrollPane) ((JPanel) definitions.getLeftComponent()).getComponent(1);
        JScrollPane right = (JScrollPane) ((JPanel) definitions.getRightComponent()).getComponent(1);
        boolean[] syncing = {false};
        left.getVerticalScrollBar().addAdjustmentListener(e -> { if (!syncing[0]) { syncing[0] = true; right.getVerticalScrollBar().setValue(e.getValue()); syncing[0] = false; } });
        right.getVerticalScrollBar().addAdjustmentListener(e -> { if (!syncing[0]) { syncing[0] = true; left.getVerticalScrollBar().setValue(e.getValue()); syncing[0] = false; } });
    }

    private static void applyLineColors(DiffTextPane pane, List<DiffEngine.DiffLine> rows, boolean dark) {
        pane.setDark(dark);
        StyledDocument document = pane.getStyledDocument();
        Color base = dark ? new Color(30, 34, 42) : new Color(251, 252, 254);
        Color added = dark ? new Color(38, 75, 53) : new Color(226, 247, 231);
        Color removed = dark ? new Color(86, 48, 52) : new Color(255, 232, 232);
        Color foreground = dark ? new Color(220, 225, 232) : new Color(35, 45, 58);
        pane.setBackground(base);
        pane.setForeground(foreground);
        SimpleAttributeSet textAttributes = new SimpleAttributeSet(); StyleConstants.setForeground(textAttributes, foreground);
        document.setCharacterAttributes(0, document.getLength(), textAttributes, true);
        for (int i = 0; i < rows.size(); i++) {
            if (i >= document.getDefaultRootElement().getElementCount()) break;
            Element paragraph = document.getDefaultRootElement().getElement(i);
            Color background = switch (rows.get(i).type()) { case ADDED -> added; case REMOVED -> removed; case CONTEXT -> base; };
            SimpleAttributeSet attributes = new SimpleAttributeSet();
            StyleConstants.setBackground(attributes, background);
            StyleConstants.setForeground(attributes, foreground);
            document.setParagraphAttributes(paragraph.getStartOffset(), paragraph.getEndOffset() - paragraph.getStartOffset(), attributes, true);
        }
    }

    private static String statusName(DiffEngine.Status status) {
        return switch (status) { case IDENTICAL -> "idéntico"; case CHANGED -> "modificado"; case SOURCE_ONLY -> "solo en origen"; case TARGET_ONLY -> "solo en destino"; };
    }

    private final class DiffTextPane extends JTextPane {
        private List<DiffEngine.DiffLine> rows = List.of();
        private boolean dark;
        DiffTextPane() { setEditable(false); setOpaque(false); setFont(new Font("Consolas", Font.PLAIN, 13)); setBorder(new EmptyBorder(7, 8, 7, 8)); setToolTipText(""); }
        void setRows(List<DiffEngine.DiffLine> rows) { this.rows = rows; }
        void setDark(boolean dark) { this.dark = dark; repaint(); }
        @Override public boolean getScrollableTracksViewportWidth() { return false; }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(dark ? new Color(30, 34, 42) : new Color(251, 252, 254));
            g.fillRect(0, 0, getWidth(), getHeight());
            FontMetrics metrics = g.getFontMetrics(getFont());
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).type() == DiffEngine.LineType.CONTEXT) continue;
                try {
                    Element line = getDocument().getDefaultRootElement().getElement(i);
                    Rectangle2D bounds = modelToView2D(line.getStartOffset());
                    if (bounds == null) continue;
                    int y = (int) Math.floor(bounds.getY());
                    if (y + metrics.getHeight() < 0 || y > getHeight()) continue;
                    Color color = rows.get(i).type() == DiffEngine.LineType.ADDED
                            ? (dark ? new Color(38, 75, 53) : new Color(226, 247, 231))
                            : (dark ? new Color(86, 48, 52) : new Color(255, 232, 232));
                    g.setColor(color);
                    g.fillRect(0, y, getWidth(), metrics.getHeight());
                } catch (javax.swing.text.BadLocationException ignored) { break; }
            }
            g.dispose();
            // Paint text after the line backgrounds so the code remains visible.
            super.paintComponent(graphics);
        }
        @Override public String getToolTipText(MouseEvent event) {
            int line = getDocument().getDefaultRootElement().getElementIndex(viewToModel2D(event.getPoint()));
            return line >= 0 && line < rows.size() ? tooltip(rows.get(line)) : null;
        }
    }

    private final class DiffGutter extends javax.swing.JComponent {
        private final DiffTextPane text;
        private final boolean unifiedMode;
        private final boolean sourceSide;
        private List<DiffEngine.DiffLine> rows = List.of();
        private Color background, numberColor, addedColor, removedColor, markerBackground;

        DiffGutter(DiffTextPane text, boolean unifiedMode, boolean sourceSide) {
            this.text = text; this.unifiedMode = unifiedMode; this.sourceSide = sourceSide;
            setOpaque(true); setToolTipText(""); setDark(false);
        }

        void setRows(List<DiffEngine.DiffLine> rows) { this.rows = rows; revalidate(); repaint(); }
        void setDark(boolean dark) {
            background = dark ? new Color(35, 40, 49) : new Color(239, 243, 248);
            numberColor = dark ? new Color(142, 153, 168) : new Color(105, 116, 130);
            addedColor = dark ? new Color(111, 204, 148) : new Color(35, 130, 74);
            removedColor = dark ? new Color(226, 132, 137) : new Color(180, 55, 58);
            markerBackground = dark ? new Color(49, 57, 68) : new Color(228, 233, 240);
            setBackground(background); repaint();
        }

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
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(background); g.fillRect(0, 0, getWidth(), getHeight());
            FontMetrics metrics = g.getFontMetrics(text.getFont()); g.setFont(text.getFont());
            if (unifiedMode) {
                g.setColor(numberColor);
                int oldRight = getWidth() / 2 - 3, newRight = getWidth() - 5;
                g.drawString("O", oldRight - metrics.stringWidth("O"), metrics.getAscent() + 1);
                g.drawString("D", newRight - metrics.stringWidth("D"), metrics.getAscent() + 1);
                g.setColor(markerBackground); g.drawLine(0, 20, getWidth(), 20);
            }
            for (int i = 0; i < rows.size(); i++) {
                try {
                    Element line = text.getDocument().getDefaultRootElement().getElement(i);
                    Rectangle2D bounds = text.modelToView2D(line.getStartOffset());
                    if (bounds == null) continue;
                    int y = (int) Math.floor(bounds.getY());
                    if (y + metrics.getHeight() < 0 || y > getHeight()) continue;
                    DiffEngine.DiffLine row = rows.get(i);
                    Color changeColor = row.type() == DiffEngine.LineType.ADDED ? addedColor
                            : row.type() == DiffEngine.LineType.REMOVED ? removedColor : numberColor;
                    if (row.type() != DiffEngine.LineType.CONTEXT) {
                        g.setColor(markerBackground); g.fillRect(0, y, getWidth(), metrics.getHeight());
                        g.setColor(changeColor); g.fillRect(2, y + 2, 3, Math.max(2, metrics.getHeight() - 4));
                        String marker = row.type() == DiffEngine.LineType.ADDED ? "+" : "−";
                        g.drawString(marker, 7, y + metrics.getAscent());
                    }
                    g.setColor(changeColor);
                    int baseline = y + metrics.getAscent();
                    if (unifiedMode) {
                        String oldNumber = row.sourceLineNumber() == null ? "" : row.sourceLineNumber().toString();
                        String newNumber = row.targetLineNumber() == null ? "" : row.targetLineNumber().toString();
                        int oldRight = getWidth() / 2 - 3, newRight = getWidth() - 5;
                        g.drawString(oldNumber, oldRight - metrics.stringWidth(oldNumber), baseline);
                        g.drawString(newNumber, newRight - metrics.stringWidth(newNumber), baseline);
                    } else {
                        Integer number = sourceSide ? row.sourceLineNumber() : row.targetLineNumber();
                        if (number != null) {
                            String value = number.toString(); int right = getWidth() - 5;
                            g.drawString(value, right - metrics.stringWidth(value), baseline);
                        }
                    }
                } catch (javax.swing.text.BadLocationException ignored) { break; }
            }
            g.setColor(markerBackground); g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
            g.dispose();
        }

        @Override public String getToolTipText(MouseEvent event) {
            int line = text.getDocument().getDefaultRootElement().getElementIndex(text.viewToModel2D(new java.awt.Point(0, event.getY())));
            return line >= 0 && line < rows.size() ? tooltip(rows.get(line)) : null;
        }
    }

    private static String tooltip(DiffEngine.DiffLine line) {
        return switch (line.type()) {
            case CONTEXT -> "Línea idéntica";
            case REMOVED -> "Presente en origen · falta en destino";
            case ADDED -> "Presente en destino · falta en origen";
        };
    }
}
