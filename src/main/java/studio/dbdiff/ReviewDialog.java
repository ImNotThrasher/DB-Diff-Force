package studio.dbdiff;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** Shared resizable review window and compact secondary information. */
final class ReviewDialog {
    private ReviewDialog() { }

    static boolean confirm(JFrame owner, String title, JComponent content, Dimension size) {
        return confirm(owner, title, content, size, "Guardar SQL…");
    }

    static boolean confirm(JFrame owner, String title, JComponent content, Dimension size, String action) {
        JDialog dialog = new JDialog(owner, title, true);
        boolean[] accepted = {false};
        JButton save = new JButton(action), cancel = new JButton("Cancelar");
        save.addActionListener(e -> { accepted[0] = true; dialog.dispose(); });
        cancel.addActionListener(e -> dialog.dispose());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel); actions.add(save);
        JPanel wrapper = new JPanel(new BorderLayout(0, 12));
        wrapper.setBorder(new EmptyBorder(12, 16, 12, 16));
        wrapper.add(content, BorderLayout.CENTER); wrapper.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(wrapper);
        dialog.getRootPane().setDefaultButton(save);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setMinimumSize(new Dimension(680, 480));
        Rectangle screen = owner.getGraphicsConfiguration().getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(owner.getGraphicsConfiguration());
        dialog.setSize(Math.min(size.width, screen.width - insets.left - insets.right),
                Math.min(size.height, screen.height - insets.top - insets.bottom));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return accepted[0];
    }

    static JTextArea note(String text) {
        JTextArea note = new JTextArea(text);
        note.setEditable(false); note.setOpaque(false);
        note.setLineWrap(true); note.setWrapStyleWord(true); note.setRows(2);
        note.setFont(UIManager.getFont("Label.font"));
        return note;
    }

    static JPanel disclosure(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        JToggleButton toggle = new JToggleButton("▸ " + title);
        toggle.setHorizontalAlignment(SwingConstants.LEFT);
        content.setVisible(false);
        toggle.addActionListener(e -> {
            content.setVisible(toggle.isSelected());
            toggle.setText((toggle.isSelected() ? "▾ " : "▸ ") + title);
            panel.revalidate(); panel.repaint();
        });
        panel.add(toggle, BorderLayout.NORTH); panel.add(content, BorderLayout.CENTER);
        return panel;
    }
}
