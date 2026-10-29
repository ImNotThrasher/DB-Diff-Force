package studio.dbdiff;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds spreadsheet-friendly clipboard text from the unabridged comparison values. */
final class RowClipboardText {
    enum Side { SOURCE, DESTINATION, BOTH }

    private RowClipboardText() { }

    static boolean hasSide(RowDiff.RowChange change, Side side) {
        return switch (side) {
            case SOURCE -> !change.source().isEmpty();
            case DESTINATION -> !change.target().isEmpty();
            case BOTH -> !change.source().isEmpty() && !change.target().isEmpty();
        };
    }

    static String cell(RowDiff.RowChange change, String column, Side side) {
        return switch (side) {
            case SOURCE -> value(change.source(), column);
            case DESTINATION -> value(change.target(), column);
            case BOTH -> tsv(column + " (Origen)") + "\t" + tsv(column + " (Destino)") + "\n"
                    + tsv(value(change.source(), column)) + "\t" + tsv(value(change.target(), column));
        };
    }

    static String row(List<RowDiff.Column> columns, RowDiff.RowChange change, Side side) {
        return switch (side) {
            case SOURCE -> rowValues(columns, change.source());
            case DESTINATION -> rowValues(columns, change.target());
            case BOTH -> block("Origen", columns, change.source()) + "\n\n"
                    + block("Destino", columns, change.target());
        };
    }

    static String table(List<RowDiff.Column> columns, List<RowDiff.RowChange> rows, Side side) {
        return switch (side) {
            case SOURCE -> sideTable(columns, rows, true);
            case DESTINATION -> sideTable(columns, rows, false);
            case BOTH -> block("Origen", columns, rows, true) + "\n\n"
                    + block("Destino", columns, rows, false);
        };
    }

    private static String sideTable(List<RowDiff.Column> columns, List<RowDiff.RowChange> rows, boolean source) {
        StringBuilder text = new StringBuilder(header(columns));
        for (RowDiff.RowChange row : rows) {
            Map<String, Object> values = source ? row.source() : row.target();
            if (!values.isEmpty()) text.append('\n').append(rowValues(columns, values));
        }
        return text.toString();
    }

    private static String block(String title, List<RowDiff.Column> columns, Map<String, Object> values) {
        return title + "\n" + header(columns) + "\n" + rowValues(columns, values);
    }

    private static String block(String title, List<RowDiff.Column> columns, List<RowDiff.RowChange> rows, boolean source) {
        StringBuilder text = new StringBuilder(title).append('\n').append(header(columns));
        for (RowDiff.RowChange row : rows) {
            Map<String, Object> values = source ? row.source() : row.target();
            if (!values.isEmpty()) text.append('\n').append(rowValues(columns, values));
        }
        return text.toString();
    }

    private static String header(List<RowDiff.Column> columns) {
        return columns.stream().map(column -> tsv(column.name())).collect(Collectors.joining("\t"));
    }

    private static String rowValues(List<RowDiff.Column> columns, Map<String, Object> values) {
        return columns.stream().map(column -> tsv(value(values, column.name()))).collect(Collectors.joining("\t"));
    }

    private static String value(Map<String, Object> values, String column) {
        Object value = values.get(column);
        if (value == null) return "NULL";
        if (value instanceof Boolean bool) return bool ? "1" : "0";
        if (value instanceof byte[] bytes) return "0x" + java.util.HexFormat.of().formatHex(bytes);
        return value.toString();
    }

    private static String tsv(String value) {
        if (value.indexOf('\t') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0 && value.indexOf('"') < 0)
            return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
