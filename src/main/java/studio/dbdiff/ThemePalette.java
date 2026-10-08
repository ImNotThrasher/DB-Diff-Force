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

    private static final ThemePalette LIGHT = new ThemePalette(
            new Color(246, 248, 251), new Color(251, 252, 254), new Color(35, 45, 58),
            new Color(223, 228, 235), new Color(190, 211, 235),
            new Color(226, 247, 231), new Color(255, 232, 232), new Color(239, 243, 248),
            new Color(105, 116, 130), new Color(35, 130, 74), new Color(180, 55, 58),
            new Color(228, 233, 240));
    private static final ThemePalette DARK = new ThemePalette(
            new Color(37, 42, 51), new Color(30, 34, 42), new Color(220, 225, 232),
            new Color(65, 73, 84), new Color(57, 78, 105),
            new Color(38, 75, 53), new Color(86, 48, 52), new Color(35, 40, 49),
            new Color(142, 153, 168), new Color(111, 204, 148), new Color(226, 132, 137),
            new Color(49, 57, 68));

    static ThemePalette forMode(boolean dark) { return dark ? DARK : LIGHT; }
}
