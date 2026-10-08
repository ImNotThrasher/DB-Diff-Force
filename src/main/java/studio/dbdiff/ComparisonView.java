package studio.dbdiff;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.List;

/** Side-by-side definitions or a unified diff for one schema object. */
public final class ComparisonView extends JPanel {
    private static final String SPLIT = "split";
    private static final String UNIFIED = "unified";
    private final JLabel databasePair = new JLabel("Base origen  →  Base destino");
    private final JTextArea summary = new JTextArea();
    private final DiffCodeEditor source = new DiffCodeEditor(false, true);
    private final DiffCodeEditor target = new DiffCodeEditor(false, false);
    private final DiffCodeEditor unified = new DiffCodeEditor(true, true);
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
                titled(sourceHeading, source), titled(targetHeading, target));
        definitions.setResizeWeight(.5); definitions.setBorder(null); definitions.setDividerSize(7);
        cards.add(definitions, SPLIT);
        JPanel unifiedPanel = new JPanel(new BorderLayout());
        unifiedHeading.setFont(new Font("Segoe UI", Font.BOLD, 11)); unifiedHeading.setBorder(new EmptyBorder(0, 0, 6, 0));
        unifiedPanel.add(unifiedHeading, BorderLayout.NORTH); unifiedPanel.add(unified, BorderLayout.CENTER);
        cards.add(unifiedPanel, UNIFIED);
        add(cards, BorderLayout.CENTER);
        showMode(SPLIT);
        syncVerticalScroll(source, target);
    }

    public void setDatabases(String sourceName, String targetName) {
        databasePair.setText("Base origen: " + displayName(sourceName) + "   →   Base destino: " + displayName(targetName));
        sourceHeading.setText("ORIGEN · " + displayName(sourceName));
        targetHeading.setText("DESTINO · " + displayName(targetName));
    }

    public void setThemeColors(boolean dark) {
        this.dark = dark;
        ThemePalette palette = ThemePalette.forMode(dark);
        Color panel = palette.panel();
        Color foreground = palette.foreground();
        setBackground(panel); cards.setBackground(panel);
        databasePair.setForeground(foreground); summary.setForeground(foreground);
        sourceHeading.setForeground(foreground); targetHeading.setForeground(foreground); unifiedHeading.setForeground(foreground);
        for (DiffCodeEditor editor : List.of(source, target, unified)) editor.setThemeColors(dark);
        if (splitModeButton != null) {
            AppTheme.styleToggleButton(splitModeButton, dark);
            AppTheme.styleToggleButton(unifiedModeButton, dark);
        }
        if (current != null) showResult(current, dark);
    }

    public void setCodeFont(String family, int size) {
        for (DiffCodeEditor editor : List.of(source, target, unified)) editor.setCodeFont(family, size);
    }

    public void showMessage(String text) {
        current = null;
        summary.setText(text);
        source.clear(); target.clear(); unified.clear();
        showMode(SPLIT);
    }

    public void showResult(DiffEngine.Result result, boolean dark) {
        current = result; this.dark = dark;
        summary.setText(result.key() + "   ·   Estado: " + statusName(result.status()));
        String left = result.source() == null ? "El objeto no existe en el origen." : result.source().definition();
        String right = result.target() == null ? "El objeto no existe en el destino." : result.target().definition();
        List<DiffEngine.DiffLine> sourceRows = result.diffLines().stream().filter(line -> line.sourceLineNumber() != null).toList();
        List<DiffEngine.DiffLine> targetRows = result.diffLines().stream().filter(line -> line.targetLineNumber() != null).toList();
        source.showContent(left, sourceRows, dark, result.source() != null);
        target.showContent(right, targetRows, dark, result.target() != null);
        String unifiedText = String.join("\n", result.diffLines().stream().map(DiffEngine.DiffLine::text).toList());
        unified.showContent(unifiedText, result.diffLines(), dark, true);
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

    private void showMode(String mode) {
        ((CardLayout) cards.getLayout()).show(cards, mode);
        if (splitModeButton != null) {
            AppTheme.styleToggleButton(splitModeButton, dark);
            AppTheme.styleToggleButton(unifiedModeButton, dark);
        }
    }

    private static String displayName(String name) { return name == null || name.isBlank() ? "(sin seleccionar)" : name; }

    private static JPanel titled(JLabel heading, DiffCodeEditor editor) {
        JPanel panel = new JPanel(new BorderLayout()); heading.setFont(new Font("Segoe UI", Font.BOLD, 11)); heading.setBorder(new EmptyBorder(0, 0, 6, 0));
        panel.add(heading, BorderLayout.NORTH); panel.add(editor, BorderLayout.CENTER); return panel;
    }

    private static void syncVerticalScroll(DiffCodeEditor source, DiffCodeEditor target) {
        javax.swing.JScrollPane left = source.scrollPane();
        javax.swing.JScrollPane right = target.scrollPane();
        boolean[] syncing = {false};
        left.getVerticalScrollBar().addAdjustmentListener(e -> { if (!syncing[0]) { syncing[0] = true; right.getVerticalScrollBar().setValue(e.getValue()); syncing[0] = false; } });
        right.getVerticalScrollBar().addAdjustmentListener(e -> { if (!syncing[0]) { syncing[0] = true; left.getVerticalScrollBar().setValue(e.getValue()); syncing[0] = false; } });
    }

    private static String statusName(DiffEngine.Status status) {
        return switch (status) { case IDENTICAL -> "idéntico"; case CHANGED -> "modificado"; case SOURCE_ONLY -> "solo en origen"; case TARGET_ONLY -> "solo en destino"; };
    }
}
