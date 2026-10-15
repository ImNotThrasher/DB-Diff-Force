package studio.dbdiff;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowClipboardTextTest {
    private static final List<RowDiff.Column> COLUMNS = List.of(
            new RowDiff.Column("id", "int", "int", false),
            new RowDiff.Column("text", "varchar", "varchar(1000)", true));

    @Test void copiesCellFromChosenSideOrBothWithoutTruncating() {
        String longText = "x".repeat(600);
        RowDiff.RowChange row = change(RowDiff.Status.DIFFERENT,
                values(1, longText), values(1, "destino"));

        assertEquals(longText, RowClipboardText.cell(row, "text", RowClipboardText.Side.SOURCE));
        assertEquals("destino", RowClipboardText.cell(row, "text", RowClipboardText.Side.DESTINATION));
        assertEquals("text (Origen)\ttext (Destino)\n" + longText + "\tdestino",
                RowClipboardText.cell(row, "text", RowClipboardText.Side.BOTH));
    }

    @Test void copiesRowsAsTsvAndLabelsBothSides() {
        RowDiff.RowChange row = change(RowDiff.Status.DIFFERENT,
                values(7, "origen"), values(7, "destino"));

        assertEquals("7\torigen", RowClipboardText.row(COLUMNS, row, RowClipboardText.Side.SOURCE));
        assertEquals("Origen\nid\ttext\n7\torigen\n\nDestino\nid\ttext\n7\tdestino",
                RowClipboardText.row(COLUMNS, row, RowClipboardText.Side.BOTH));
    }

    @Test void copiesOnlyVisibleRowsAndOnlyRowsPresentOnEachSide() {
        RowDiff.RowChange same = change(RowDiff.Status.IDENTICAL, values(1, "igual"), values(1, "igual"));
        RowDiff.RowChange onlySource = change(RowDiff.Status.SOURCE_ONLY, values(2, null), null);
        RowDiff.RowChange onlyTarget = change(RowDiff.Status.TARGET_ONLY, null, values(3, "destino"));

        assertEquals("id\ttext\n1\tigual\n2\tNULL",
                RowClipboardText.table(COLUMNS, List.of(same, onlySource), RowClipboardText.Side.SOURCE));
        assertEquals("id\ttext\n1\tigual\n3\tdestino",
                RowClipboardText.table(COLUMNS, List.of(same, onlyTarget), RowClipboardText.Side.DESTINATION));
        assertEquals("Origen\nid\ttext\n2\tNULL\n\nDestino\nid\ttext\n3\tdestino",
                RowClipboardText.table(COLUMNS, List.of(onlySource, onlyTarget), RowClipboardText.Side.BOTH));
        assertTrue(RowClipboardText.hasSide(onlySource, RowClipboardText.Side.SOURCE));
        assertFalse(RowClipboardText.hasSide(onlySource, RowClipboardText.Side.DESTINATION));
        assertFalse(RowClipboardText.hasSide(onlySource, RowClipboardText.Side.BOTH));
    }

    @Test void preservesNullBinaryBooleanAndTsvSpecialCharacters() {
        RowDiff.RowChange row = change(RowDiff.Status.SOURCE_ONLY,
                Map.of("id", 1, "text", "uno\tdos\"tres\ncuatro"), null);
        RowDiff.RowChange special = change(RowDiff.Status.SOURCE_ONLY,
                new LinkedHashMap<>(Map.of("id", true, "text", new byte[]{0x0a, 0x2f})), null);

        assertEquals("1\t\"uno\tdos\"\"tres\ncuatro\"",
                RowClipboardText.row(COLUMNS, row, RowClipboardText.Side.SOURCE));
        assertEquals("1\t0x0a2f", RowClipboardText.row(COLUMNS, special, RowClipboardText.Side.SOURCE));
    }

    private static Map<String, Object> values(Object id, Object text) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("text", text);
        return values;
    }

    private static RowDiff.RowChange change(RowDiff.Status status, Map<String, Object> source, Map<String, Object> target) {
        return new RowDiff.RowChange(status, Map.of(), source, target);
    }
}
