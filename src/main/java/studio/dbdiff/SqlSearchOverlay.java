package studio.dbdiff;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/** Adds a Ctrl+F search overlay to a SQL preview without taking space from the text. */
final class SqlSearchOverlay {
    private SqlSearchOverlay() { }

    static JComponent wrap(JTextArea text) {
        JScrollPane scroll = new JScrollPane(text);
        JTextField query = new JTextField(20);
        query.putClientProperty("JTextField.placeholderText", "Buscar texto...");
        query.putClientProperty("JTextField.leadingIcon", UiIcons.themed(UiIcons.SEARCH, 15));
        query.setToolTipText("Buscar texto en el SQL");
        javax.swing.JLabel count = new javax.swing.JLabel("0/0");
        JButton previous = new JButton("‹");
        JButton next = new JButton("›");
        previous.setToolTipText("Coincidencia anterior (Shift+Enter)");
        next.setToolTipText("Coincidencia siguiente (Enter)");

        JPanel search = new TranslucentPanel();
        search.setLayout(new FlowLayout(FlowLayout.RIGHT, 4, 3));
        search.setBorder(new EmptyBorder(1, 3, 1, 3));
        search.add(query); search.add(count); search.add(previous); search.add(next);
        search.setVisible(false);

        JLayeredPane layers = new JLayeredPane() {
            @Override public void doLayout() {
                scroll.setBounds(0, 0, getWidth(), getHeight());
                Dimension preferred = search.getPreferredSize();
                int width = Math.min(preferred.width, Math.max(0, getWidth() - 16));
                search.setBounds(Math.max(8, getWidth() - width - 8), 8, width, preferred.height);
            }

            @Override public Dimension getPreferredSize() { return scroll.getPreferredSize(); }
        };
        layers.add(scroll, JLayeredPane.DEFAULT_LAYER);
        layers.add(search, JLayeredPane.PALETTE_LAYER);
        JPanel container = new JPanel(new BorderLayout());
        container.add(layers, BorderLayout.CENTER);

        List<Integer> matches = new ArrayList<>();
        List<Object> highlights = new ArrayList<>();
        int[] active = {-1};
        Runnable refresh = () -> {
            Highlighter highlighter = text.getHighlighter();
            highlights.forEach(highlighter::removeHighlight);
            highlights.clear(); matches.clear();
            String term = query.getText();
            String content = text.getText();
            if (term != null && !term.isEmpty()) {
                for (int offset = 0; offset <= content.length() - term.length(); offset++) {
                    if (content.regionMatches(true, offset, term, 0, term.length())) {
                        matches.add(offset);
                        offset += term.length() - 1;
                    }
                }
            }
            active[0] = matches.isEmpty() ? -1 : Math.floorMod(active[0], matches.size());
            for (int index = 0; index < matches.size(); index++) {
                int offset = matches.get(index);
                Color color = index == active[0] ? new Color(230, 151, 53, 210) : new Color(214, 184, 50, 150);
                try {
                    highlights.add(highlighter.addHighlight(offset, offset + term.length(),
                            new DefaultHighlighter.DefaultHighlightPainter(color)));
                } catch (javax.swing.text.BadLocationException ignored) { }
            }
            count.setText(matches.isEmpty() ? "0/0" : (active[0] + 1) + "/" + matches.size());
            if (active[0] >= 0) {
                int offset = matches.get(active[0]);
                text.setCaretPosition(Math.min(offset, text.getDocument().getLength()));
                try {
                    java.awt.geom.Rectangle2D bounds = text.modelToView2D(offset);
                    if (bounds != null) text.scrollRectToVisible(bounds.getBounds());
                } catch (javax.swing.text.BadLocationException ignored) { }
            }
        };
        Runnable open = () -> {
            search.setVisible(true);
            layers.doLayout(); layers.repaint();
            query.requestFocusInWindow(); query.selectAll();
        };
        Runnable close = () -> {
            search.setVisible(false);
            query.setText("");
            text.requestFocusInWindow();
        };
        java.util.function.IntConsumer move = direction -> {
            if (matches.isEmpty()) return;
            active[0] = Math.floorMod(active[0] + direction, matches.size());
            refresh.run();
        };
        previous.addActionListener(event -> move.accept(-1));
        next.addActionListener(event -> move.accept(1));
        query.addActionListener(event -> move.accept(1));
        query.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "previous-match");
        query.getActionMap().put("previous-match", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { move.accept(-1); }
        });
        query.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-search");
        query.getActionMap().put("close-search", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { close.run(); }
        });
        query.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), "select-search");
        query.getActionMap().put("select-search", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { query.requestFocusInWindow(); query.selectAll(); }
        });
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), "open-search");
        text.getActionMap().put("open-search", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { open.run(); }
        });
        query.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { active[0] = 0; refresh.run(); }
            @Override public void removeUpdate(DocumentEvent event) { active[0] = 0; refresh.run(); }
            @Override public void changedUpdate(DocumentEvent event) { active[0] = 0; refresh.run(); }
        });
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refresh.run(); }
            @Override public void removeUpdate(DocumentEvent event) { refresh.run(); }
            @Override public void changedUpdate(DocumentEvent event) { refresh.run(); }
        });
        return container;
    }

    private static final class TranslucentPanel extends JPanel {
        TranslucentPanel() { setOpaque(false); }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            Color background = UIManager.getColor("Panel.background");
            if (background == null) background = getBackground();
            g.setColor(new Color(background.getRed(), background.getGreen(), background.getBlue(), 225));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            Color border = UIManager.getColor("Component.borderColor");
            g.setColor(border == null ? background.darker() : border);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            g.dispose();
            super.paintComponent(graphics);
        }
    }
}
