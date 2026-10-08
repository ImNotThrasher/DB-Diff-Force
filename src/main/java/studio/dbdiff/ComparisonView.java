package studio.dbdiff;

import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.AdjustmentListener;
import java.util.List;

/** Side-by-side object definitions and a colored, review-only unified diff. */
public final class ComparisonView extends JPanel {
    private final JTextArea summary = textArea();
    private final JTextArea source = textArea();
    private final JTextArea target = textArea();
    private final JEditorPane diff = new JEditorPane();

    public ComparisonView() {
        super(new BorderLayout(0, 8));
        add(summaryPanel(), BorderLayout.NORTH);
        diff.setContentType("text/html"); diff.setEditable(false); diff.setBorder(new EmptyBorder(5, 8, 5, 8));
        diff.setText("<html><body></body></html>");
        JSplitPane definitions = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, titled("ORIGEN", source), titled("DESTINO", target));
        definitions.setResizeWeight(.5); definitions.setBorder(null); definitions.setDividerSize(7);
        JPanel diffPanel = new JPanel(new BorderLayout());
        JLabel heading = new JLabel("DIFERENCIAS · líneas agregadas y quitadas"); heading.setFont(new Font("Segoe UI", Font.BOLD, 11)); heading.setBorder(new EmptyBorder(0, 0, 6, 0));
        diffPanel.add(heading, BorderLayout.NORTH); diffPanel.add(new JScrollPane(diff), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, definitions, diffPanel);
        split.setResizeWeight(.55); split.setBorder(null); split.setDividerSize(7); add(split, BorderLayout.CENTER);
        syncVerticalScroll(definitions);
    }

    public void showMessage(String text) {
        summary.setText(text); source.setText(""); target.setText(""); diff.setText("");
    }

    public void showResult(DiffEngine.Result result, boolean dark) {
        summary.setText(result.key() + "   ·   Estado: " + statusName(result.status()));
        source.setText(result.source() == null ? "El objeto no existe en el origen." : result.source().definition());
        target.setText(result.target() == null ? "El objeto no existe en el destino." : result.target().definition());
        source.setCaretPosition(0); target.setCaretPosition(0);
        diff.setText(renderDiff(result.diffLines(), result.source() == null, result.target() == null, dark)); diff.setCaretPosition(0);
    }

    private JPanel summaryPanel() {
        JPanel panel = new JPanel(new BorderLayout()); summary.setRows(3); summary.setColumns(80); summary.setEditable(false); summary.setLineWrap(true); summary.setWrapStyleWord(true);
        summary.setFont(new Font("Segoe UI", Font.PLAIN, 12)); summary.setBorder(new EmptyBorder(5, 8, 5, 8));
        panel.add(summary, BorderLayout.CENTER); return panel;
    }

    private static JTextArea textArea() {
        JTextArea area = new JTextArea(); area.setEditable(false); area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); area.setLineWrap(false); area.setBorder(new EmptyBorder(8, 10, 8, 10)); return area;
    }

    private static JPanel titled(String title, JTextArea text) {
        JPanel panel = new JPanel(new BorderLayout()); JLabel heading = new JLabel(title); heading.setFont(new Font("Segoe UI", Font.BOLD, 11)); heading.setBorder(new EmptyBorder(0, 0, 6, 0));
        JScrollPane scroll = new JScrollPane(text); scroll.setBorder(BorderFactory.createEtchedBorder()); panel.add(heading, BorderLayout.NORTH); panel.add(scroll, BorderLayout.CENTER); return panel;
    }

    private static void syncVerticalScroll(JSplitPane definitions) {
        JScrollPane left = (JScrollPane) ((JPanel) definitions.getLeftComponent()).getComponent(1);
        JScrollPane right = (JScrollPane) ((JPanel) definitions.getRightComponent()).getComponent(1);
        boolean[] syncing = {false};
        AdjustmentListener fromLeft = e -> { if (!syncing[0]) { syncing[0] = true; right.getVerticalScrollBar().setValue(e.getValue()); syncing[0] = false; } };
        AdjustmentListener fromRight = e -> { if (!syncing[0]) { syncing[0] = true; left.getVerticalScrollBar().setValue(e.getValue()); syncing[0] = false; } };
        left.getVerticalScrollBar().addAdjustmentListener(fromLeft); right.getVerticalScrollBar().addAdjustmentListener(fromRight);
    }

    private static String renderDiff(List<String> lines, boolean sourceMissing, boolean targetMissing, boolean dark) {
        String added = dark ? "#264b35" : "#e2f7e7", removed = dark ? "#563034" : "#ffe8e8";
        String context = dark ? "#252a33" : "#ffffff", foreground = dark ? "#dce1e8" : "#263238";
        StringBuilder html = new StringBuilder("<html><body style='margin:0;background:").append(context).append(";color:").append(foreground).append(";font-family:Consolas,monospace;font-size:12px'>");
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

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String statusName(DiffEngine.Status status) {
        return switch (status) { case IDENTICAL -> "idéntico"; case CHANGED -> "modificado"; case SOURCE_ONLY -> "solo en origen"; case TARGET_ONLY -> "solo en destino"; };
    }
}
