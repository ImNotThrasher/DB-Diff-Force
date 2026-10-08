package studio.dbdiff;

import javax.swing.JTextField;

/** Delegates placeholder and clear-button painting to FlatLaf. */
final class PlaceholderTextField extends JTextField {
    PlaceholderTextField(String placeholder) {
        putClientProperty("JTextField.placeholderText", placeholder);
        putClientProperty("JTextField.showClearButton", true);
    }
}
