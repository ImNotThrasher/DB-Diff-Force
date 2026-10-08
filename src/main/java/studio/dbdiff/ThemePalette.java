package studio.dbdiff;

import java.awt.Color;

/** Shared colors for the application theme and the schema diff editor. */
record ThemePalette(
        Color panel,
        Color editor,
        Color foreground,
        Color border,
        Color selection,
        Color addedBackground,
        Color removedBackground,
        Color gutterBackground,
        Color lineNumber,
        Color addedForeground,
        Color removedForeground,
        Color markerBackground) {

    /** Standard surfaces come from the active theme, never a parallel application palette. */
    static ThemePalette forMode(boolean dark) {
        return new ThemePalette(color("Panel.background"), color("TextPane.background"),
                color("TextPane.foreground"), color("Component.borderColor"), color("TextPane.selectionBackground"),
                dark ? new Color(38, 75, 53) : new Color(226, 247, 231),
                dark ? new Color(86, 48, 52) : new Color(255, 232, 232),
                color("Panel.background"), color("Label.disabledForeground"),
                dark ? new Color(111, 204, 148) : new Color(35, 130, 74),
                dark ? new Color(226, 132, 137) : new Color(180, 55, 58), color("Component.borderColor"));
    }

    private static Color color(String key) {
        Color color = javax.swing.UIManager.getColor(key);
        return color != null ? color : javax.swing.UIManager.getColor("Label.foreground");
    }

    static Color modifiedBackground(boolean dark) { return dark ? new Color(91, 69, 34) : new Color(255, 241, 204); }
    static Color addedRow(boolean dark) { return dark ? new Color(35, 84, 66) : new Color(222, 246, 229); }
    static Color removedRow(boolean dark) { return dark ? new Color(104, 48, 52) : new Color(255, 226, 226); }
    static Color status(int index, boolean dark) {
        return (dark ? new Color[]{new Color(111,204,148), new Color(230,174,85), new Color(107,166,239), new Color(154,166,181)}
                : new Color[]{new Color(49,130,97), new Color(178,112,24), new Color(56,112,184), new Color(111,122,140)})[index];
    }
}
