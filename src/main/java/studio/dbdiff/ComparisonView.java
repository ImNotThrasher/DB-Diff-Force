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
    private static final String EMPTY = "empty";
    private String selectedMode = SPLIT;
    private final JTextArea emptyMessage = new JTextArea();
    private final JLabel sourceEndpoint = new JLabel("Sin seleccionar");
    private final JLabel targetEndpoint = new JLabel("Sin seleccionar");
    private final JLabel sourceProfileBadge = new JLabel();
    private final JLabel targetProfileBadge = new JLabel();
    private final JLabel modeNotice = new JLabel("Solo comparación de esquema");
    private final JTextArea summary = new JTextArea();
    private final DiffCodeEditor source = new DiffCodeEditor(false, true);
    private final DiffCodeEditor target = new DiffCodeEditor(false, false);
    private final DiffCodeEditor unified = new DiffCodeEditor(true, true);
    private final JLabel sourceHeading = new JLabel("ORIGEN");
    private final JLabel targetHeading = new JLabel("DESTINO");
    private final JLabel unifiedHeading = new JLabel("DIFERENCIAS · origen (O) / destino (D)");
    private JToggleButton splitModeButton, unifiedModeButton;
    private JPanel modeNoticeBlock;
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
        unifiedHeading.setFont(javax.swing.UIManager.getFont("Label.font").deriveFont(Font.BOLD)); unifiedHeading.setBorder(new EmptyBorder(0, 0, 6, 0));
        unifiedPanel.add(unifiedHeading, BorderLayout.NORTH); unifiedPanel.add(unified, BorderLayout.CENTER);
        cards.add(unifiedPanel, UNIFIED);
        emptyMessage.setEditable(false); emptyMessage.setOpaque(false);
        emptyMessage.setLineWrap(true);
        emptyMessage.setWrapStyleWord(true);
        emptyMessage.setBorder(new EmptyBorder(24, 24, 24, 24));
        cards.add(emptyMessage, EMPTY);
        modeNotice.setVisible(false);
        modeNotice.setFont(javax.swing.UIManager.getFont("Label.font"));
        modeNotice.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        add(cards, BorderLayout.CENTER);
        showMode(SPLIT);
        syncVerticalScroll(source, target);
    }

    public void setDatabases(String sourceName, String targetName) {
        setDatabases(sourceName, targetName, "", "");
    }

    public void setDatabases(String sourceName, String targetName, String sourceServer, String targetServer) {
        setDatabases(sourceName, targetName, sourceServer, targetServer, null);
    }

    public void setDatabases(String sourceName, String targetName, String sourceServer, String targetServer,
                             String sourceLocationTooltip) {
        setDatabases(sourceName, targetName, sourceServer, targetServer, sourceLocationTooltip, null, null);
    }

    public void setDatabases(String sourceName, String targetName, String sourceServer, String targetServer,
                             String sourceLocationTooltip, ConnectionProfile sourceProfile,
                             ConnectionProfile targetProfile) {
        String source = endpointName(sourceServer, sourceName);
        String target = endpointName(targetServer, targetName);
        String sourceTooltip = sourceLocationTooltip == null || sourceLocationTooltip.isBlank()
                ? source : "Carpeta: " + sourceLocationTooltip;
        sourceEndpoint.setText(source); sourceEndpoint.setToolTipText(sourceTooltip);
        targetEndpoint.setText(target); targetEndpoint.setToolTipText(target);
        sourceHeading.setText("ORIGEN · " + displayName(sourceName)); sourceHeading.setToolTipText(sourceTooltip);
        targetHeading.setText("DESTINO · " + displayName(targetName));
        setProfileBadge(sourceProfileBadge, sourceProfile);
        setProfileBadge(targetProfileBadge, targetProfile);
    }

    public void setFlywayMode(boolean flyway) {
        modeNotice.setVisible(flyway);
        if (modeNoticeBlock != null) modeNoticeBlock.setVisible(flyway);
        revalidate();
    }

    public void setThemeColors(boolean dark) {
        this.dark = dark;
        modeNotice.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
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
        summary.setText("Comparación de esquema");
        emptyMessage.setText(text == null || text.isBlank() ? "Seleccioná un objeto de la lista para ver su definición y sus diferencias." : text);
        source.clear(); target.clear(); unified.clear();
        ((CardLayout) cards.getLayout()).show(cards, EMPTY);
    }

    public void showResult(DiffEngine.Result result, boolean dark) {
        current = result; this.dark = dark;
        showMode(selectedMode);
        summary.setText(kindName(result.key().kind()) + " · " + result.key().qualifiedName()
                + " · Estado: " + statusName(result.status()));
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
        JPanel panel = new JPanel(new BorderLayout(10, 12));
        JPanel labels = new JPanel(new BorderLayout(10, 0)); labels.setOpaque(false);
        JPanel endpoints = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints endpoint = new java.awt.GridBagConstraints();
        endpoint.gridy = 0; endpoint.fill = java.awt.GridBagConstraints.HORIZONTAL; endpoint.weightx = 1;
        endpoint.gridx = 0; endpoints.add(endpointPanel("Origen", sourceEndpoint, sourceProfileBadge), endpoint);
        JLabel direction = new JLabel(UiIcons.themed(UiIcons.TARGET_ONLY, 18));
        direction.setToolTipText("Dirección de comparación: origen → destino");
        endpoint.gridx = 1; endpoint.weightx = 0; endpoint.insets = new java.awt.Insets(0, 12, 0, 12);
        endpoints.add(direction, endpoint);
        endpoint.gridx = 2; endpoint.weightx = 1; endpoint.insets = new java.awt.Insets(0, 0, 0, 0);
        endpoints.add(endpointPanel("Destino", targetEndpoint, targetProfileBadge), endpoint);
        summary.setRows(1); summary.setEditable(false); summary.setLineWrap(true); summary.setWrapStyleWord(true);
        summary.setOpaque(false); summary.setFont(javax.swing.UIManager.getFont("Label.font")); summary.setBorder(null);
        labels.add(endpoints, BorderLayout.CENTER);
        modeNoticeBlock = InfoNote.wrap(modeNotice);
        modeNoticeBlock.setVisible(false);
        labels.add(modeNoticeBlock, BorderLayout.EAST);
        splitModeButton = new JToggleButton("Dividido", UiIcons.themed(UiIcons.SPLIT, 16));
        unifiedModeButton = new JToggleButton("Unificado", UiIcons.themed(UiIcons.UNIFIED, 16));
        splitModeButton.setIconTextGap(6); unifiedModeButton.setIconTextGap(6);
        splitModeButton.setToolTipText("Ver origen y destino en paralelo"); unifiedModeButton.setToolTipText("Ver el diff con números de origen y destino");
        ButtonGroup modes = new ButtonGroup(); modes.add(splitModeButton); modes.add(unifiedModeButton); splitModeButton.setSelected(true);
        splitModeButton.addActionListener(e -> showMode(SPLIT)); unifiedModeButton.addActionListener(e -> showMode(UNIFIED));
        JPanel choices = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0)); choices.setOpaque(false); choices.add(splitModeButton); choices.add(unifiedModeButton);
        JPanel objectBar = new JPanel(new BorderLayout(8, 0));
        objectBar.setBorder(new EmptyBorder(0, 0, 8, 0));
        objectBar.add(summary, BorderLayout.CENTER); objectBar.add(choices, BorderLayout.EAST);
        panel.setBorder(new EmptyBorder(2, 4, 4, 4)); panel.add(labels, BorderLayout.NORTH); panel.add(objectBar, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel endpointPanel(String title, JLabel value, JLabel profileBadge) {
        JPanel panel = new JPanel(new BorderLayout(0, 3));
        JLabel heading = new JLabel(title); heading.putClientProperty("FlatLaf.styleClass", "small");
        value.setMinimumSize(new java.awt.Dimension(0, value.getPreferredSize().height));
        JPanel details = new JPanel(new BorderLayout(8, 0)); details.setOpaque(false);
        details.add(value, BorderLayout.CENTER); details.add(profileBadge, BorderLayout.EAST);
        panel.add(heading, BorderLayout.NORTH); panel.add(details, BorderLayout.CENTER);
        return panel;
    }

    private static void setProfileBadge(JLabel badge, ConnectionProfile profile) {
        badge.setVisible(profile != null && profile.hasMarker());
        if (profile == null) { badge.setIcon(null); badge.setText(""); badge.setToolTipText(null); return; }
        badge.setIcon(UiIcons.colorDot(profile.markerColor(), 14));
        badge.setText(profile.environment());
        badge.setToolTipText(profile.environment().isBlank() ? "Perfil: " + profile.name()
                : "Perfil: " + profile.name() + " · Ambiente: " + profile.environment());
    }

    private void showMode(String mode) {
        // Preserve the preferred view while messages temporarily replace the editors.
        selectedMode = mode;
        ((CardLayout) cards.getLayout()).show(cards, current == null ? EMPTY : mode);
        if (splitModeButton != null) {
            splitModeButton.setSelected(SPLIT.equals(mode));
            unifiedModeButton.setSelected(UNIFIED.equals(mode));
            AppTheme.styleToggleButton(splitModeButton, dark);
            AppTheme.styleToggleButton(unifiedModeButton, dark);
        }
    }

    private static String displayName(String name) { return name == null || name.isBlank() ? "(sin seleccionar)" : name; }

    private static String kindName(SchemaObject.Kind kind) {
        return switch (kind) {
            case TABLE -> "Tabla";
            case VIEW -> "Vista";
            case PROCEDURE -> "Procedimiento";
            case FUNCTION -> "Función";
            case TRIGGER -> "Trigger";
        };
    }

    private static String endpointName(String server, String database) {
        String name = displayName(database);
        return server == null || server.isBlank() ? name : server + " / " + name;
    }

    private static JPanel titled(JLabel heading, DiffCodeEditor editor) {
        JPanel panel = new JPanel(new BorderLayout()); heading.setFont(javax.swing.UIManager.getFont("Label.font").deriveFont(Font.BOLD)); heading.setBorder(new EmptyBorder(0, 0, 6, 0));
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
