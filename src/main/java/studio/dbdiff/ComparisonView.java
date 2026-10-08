package studio.dbdiff;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** Side-by-side definitions or a unified diff for one schema object. */
public final class ComparisonView extends JPanel {
    private static final String SPLIT = "split";
    private static final String UNIFIED = "unified";
    private final JLabel databasePair = new JLabel("Base origen  →  Base destino");
    private final JTextArea summary = new JTextArea();
    private final DiffTextPane source = new DiffTextPane();
    private final DiffTextPane target = new DiffTextPane();
    private final JEditorPane unified = new JEditorPane();
    private final JLabel sourceHeading = new JLabel("ORIGEN");
    private final JLabel targetHeading = new JLabel("DESTINO");
    private final JPanel cards = new JPanel(new CardLayout());
    private DiffEngine.Result current;
    private boolean dark;

    public ComparisonView() {
        super(new BorderLayout(0, 8));
        add(headerPanel(), BorderLayout.NORTH);
        JSplitPane definitions = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, titled(sourceHeading, source), titled(targetHeading, target));
        definitions.setResizeWeight(.5); definitions.setBorder(null); definitions.setDividerSize(7);
        cards.add(definitions, SPLIT);
        JPanel unifiedPanel = new JPanel(new BorderLayout());
        JLabel heading = new JLabel("DIFERENCIAS · líneas agregadas y quitadas"); heading.setFont(new Font("Segoe UI", Font.BOLD, 11)); heading.setBorder(new EmptyBorder(0, 0, 6, 0));
        unified.setContentType("text/html"); unified.setEditable(false); unified.setBorder(new EmptyBorder(5, 8, 5, 8));
        unifiedPanel.add(heading, BorderLayout.NORTH); unifiedPanel.add(new JScrollPane(unified), BorderLayout.CENTER);
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

    public void showMessage(String text) {
        current = null;
        summary.setText(text); source.setText(""); target.setText(""); unified.setText("");
        source.setLineHints(List.of()); target.setLineHints(List.of());
        showMode(SPLIT);
    }

    public void showResult(DiffEngine.Result result, boolean dark) {
        current = result; this.dark = dark;
        summary.setText(result.key() + "   ·   Estado: " + statusName(result.status()));
        String left = result.source() == null ? "El objeto no existe en el origen." : result.source().definition();
        String right = result.target() == null ? "El objeto no existe en el destino." : result.target().definition();
        source.setText(left); target.setText(right);
        source.setLineHints(lineHints(result, true, left)); target.setLineHints(lineHints(result, false, right));
        highlight(source, source.getLineHints(), dark); highlight(target, target.getLineHints(), dark);
        source.setCaretPosition(0); target.setCaretPosition(0);
        unified.setText(renderDiff(result.diffLines(), result.source() == null, result.target() == null, dark)); unified.setCaretPosition(0);
    }

    private JPanel headerPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 4));
        JPanel labels = new JPanel(new BorderLayout(0, 4)); labels.setOpaque(false);
        databasePair.setFont(new Font("Segoe UI", Font.BOLD, 12));
        summary.setRows(2); summary.setEditable(false); summary.setLineWrap(true); summary.setWrapStyleWord(true); summary.setOpaque(false); summary.setFont(new Font("Segoe UI", Font.PLAIN, 12)); summary.setBorder(null);
        labels.add(databasePair, BorderLayout.NORTH); labels.add(summary, BorderLayout.CENTER);
        JToggleButton split = new JToggleButton("Dividido"), single = new JToggleButton("Unificado");
        split.setToolTipText("Ver origen y destino en paralelo"); single.setToolTipText("Ver las líneas agregadas y quitadas en una sola columna");
        ButtonGroup modes = new ButtonGroup(); modes.add(split); modes.add(single); split.setSelected(true);
        split.addActionListener(e -> showMode(SPLIT)); single.addActionListener(e -> showMode(UNIFIED));
        JPanel choices = new JPanel(); choices.setOpaque(false); choices.add(split); choices.add(single);
        panel.setBorder(new EmptyBorder(2, 4, 4, 4)); panel.add(labels, BorderLayout.CENTER); panel.add(choices, BorderLayout.EAST);
        return panel;
    }

    private void showMode(String mode) { ((CardLayout) cards.getLayout()).show(cards, mode); }

    private static String displayName(String name) { return name == null || name.isBlank() ? "(sin seleccionar)" : name; }

    private static JPanel titled(JLabel heading, JTextPane text) {
        JPanel panel = new JPanel(new BorderLayout()); heading.setFont(new Font("Segoe UI", Font.BOLD, 11)); heading.setBorder(new EmptyBorder(0, 0, 6, 0));
        JScrollPane scroll = new JScrollPane(text); scroll.setBorder(BorderFactory.createEtchedBorder()); panel.add(heading, BorderLayout.NORTH); panel.add(scroll, BorderLayout.CENTER); return panel;
    }

    private static void syncVerticalScroll(JSplitPane definitions) {
        JScrollPane left = (JScrollPane) ((JPanel) definitions.getLeftComponent()).getComponent(1);
        JScrollPane right = (JScrollPane) ((JPanel) definitions.getRightComponent()).getComponent(1);
        boolean[] syncing = {false};
        left.getVerticalScrollBar().addAdjustmentListener(e -> { if (!syncing[0]) { syncing[0] = true; right.getVerticalScrollBar().setValue(e.getValue()); syncing[0] = false; } });
        right.getVerticalScrollBar().addAdjustmentListener(e -> { if (!syncing[0]) { syncing[0] = true; left.getVerticalScrollBar().setValue(e.getValue()); syncing[0] = false; } });
    }

    private static List<LineHint> lineHints(DiffEngine.Result result, boolean sourceSide, String text) {
        String[] actual = text.split("\n", -1);
        List<LineHint> hints = new ArrayList<>(actual.length);
        for (int i = 0; i < actual.length; i++) hints.add(new LineHint(LineKind.SAME));
        if (result.source() == null || result.target() == null) {
            LineKind kind = sourceSide
                    ? result.source() == null ? LineKind.MISSING_OBJECT : LineKind.REMOVED
                    : result.target() == null ? LineKind.MISSING_OBJECT : LineKind.ADDED;
            for (int i = 0; i < hints.size(); i++) hints.set(i, new LineHint(kind));
            return hints;
        }
        int sourceLine = 0, targetLine = 0;
        for (String line : result.diffLines()) {
            if (line.startsWith("---") || line.startsWith("+++")) continue;
            if (line.startsWith("- ")) { if (sourceSide && sourceLine < hints.size()) hints.set(sourceLine, new LineHint(LineKind.REMOVED)); sourceLine++; }
            else if (line.startsWith("+ ")) { if (!sourceSide && targetLine < hints.size()) hints.set(targetLine, new LineHint(LineKind.ADDED)); targetLine++; }
            else if (line.startsWith("  ")) { sourceLine++; targetLine++; }
        }
        return hints;
    }

    private static void highlight(JTextPane pane, List<LineHint> hints, boolean dark) {
        StyledDocument document = pane.getStyledDocument();
        SimpleAttributeSet plain = attributes(dark ? new Color(30, 34, 42) : new Color(251, 252, 254));
        document.setCharacterAttributes(0, document.getLength(), plain, true);
        int line = 0;
        for (int offset = 0; offset < document.getLength() && line < hints.size(); line++) {
            try {
                int end = document.getText(offset, document.getLength() - offset).indexOf('\n');
                int length = end < 0 ? document.getLength() - offset : end + 1;
                LineKind kind = hints.get(line).kind();
                if (kind != LineKind.SAME) {
                    Color background = switch (kind) {
                        case ADDED -> dark ? new Color(38, 75, 53) : new Color(226, 247, 231);
                        case REMOVED, MISSING_OBJECT -> dark ? new Color(86, 48, 52) : new Color(255, 232, 232);
                        case SAME -> plain.getAttribute(StyleConstants.Background) instanceof Color c ? c : Color.WHITE;
                    };
                    document.setCharacterAttributes(offset, Math.max(length, 1), attributes(background), true);
                }
                offset += Math.max(length, 1);
            } catch (javax.swing.text.BadLocationException ignored) { break; }
        }
    }

    private static SimpleAttributeSet attributes(Color background) {
        SimpleAttributeSet attributes = new SimpleAttributeSet(); StyleConstants.setBackground(attributes, background); return attributes;
    }

    private static String renderDiff(List<String> lines, boolean sourceMissing, boolean targetMissing, boolean dark) {
        String added = dark ? "#264b35" : "#e2f7e7", removed = dark ? "#563034" : "#ffe8e8";
        String context = dark ? "#252a33" : "#ffffff", foreground = dark ? "#dce1e8" : "#263238";
        StringBuilder html = new StringBuilder("<html><body style='margin:0;background:").append(context).append(";color:").append(foreground).append(";font-family:Consolas,monospace;font-size:13px'>");
        if (sourceMissing || targetMissing) {
            String message = sourceMissing ? "El objeto existe solo en el destino." : "El objeto existe solo en el origen.";
            html.append("<div style='background:").append(sourceMissing ? (dark ? "#564526" : "#fff3d9") : added).append(";padding:4px'>").append(escapeHtml(message)).append("</div>");
        } else for (String line : lines) {
            if (line.startsWith("---") || line.startsWith("+++")) continue;
            String color = line.startsWith("+ ") ? added : line.startsWith("- ") ? removed : context;
            html.append("<div style='white-space:pre;background:").append(color).append(";padding:1px 5px'>").append(escapeHtml(line)).append("&nbsp;</div>");
        }
        return html.append("</body></html>").toString();
    }

    private static String escapeHtml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    private static String statusName(DiffEngine.Status status) {
        return switch (status) { case IDENTICAL -> "idéntico"; case CHANGED -> "modificado"; case SOURCE_ONLY -> "solo en origen"; case TARGET_ONLY -> "solo en destino"; };
    }

    private enum LineKind { SAME, ADDED, REMOVED, MISSING_OBJECT }
    private record LineHint(LineKind kind) { }

    private final class DiffTextPane extends JTextPane {
        private List<LineHint> hints = List.of();
        DiffTextPane() { setEditable(false); setFont(new Font("Consolas", Font.PLAIN, 13)); setBorder(new EmptyBorder(8, 10, 8, 10)); setToolTipText(""); }
        void setLineHints(List<LineHint> value) { hints = value; }
        List<LineHint> getLineHints() { return hints; }
        @Override public String getToolTipText(MouseEvent event) {
            try {
                int offset = viewToModel2D(event.getPoint());
                int line = getDocument().getDefaultRootElement().getElementIndex(offset);
                if (line < 0 || line >= hints.size() || current == null) return null;
                return switch (hints.get(line).kind()) {
                    case SAME -> "Línea idéntica";
                    case REMOVED -> "Presente en origen · falta en destino";
                    case ADDED -> "Presente en destino · falta en origen";
                    case MISSING_OBJECT -> current.source() == null ? "Objeto faltante en origen" : "Objeto faltante en destino";
                };
            } catch (RuntimeException ignored) { return null; }
        }
    }
}
