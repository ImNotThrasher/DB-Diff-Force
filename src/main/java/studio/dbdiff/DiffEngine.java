package studio.dbdiff;

import java.util.*;

public final class DiffEngine {
    public enum Status { IDENTICAL, CHANGED, SOURCE_ONLY, TARGET_ONLY }
    public enum LineType { CONTEXT, ADDED, REMOVED }
    public record DiffLine(LineType type, String text, Integer sourceLineNumber, Integer targetLineNumber) { }
    public record Result(SchemaObject.Key key, Status status, SchemaObject source, SchemaObject target, List<DiffLine> diffLines) { }

    public List<Result> compare(List<SchemaObject> source, List<SchemaObject> target, Set<SchemaObject.Kind> kinds, Set<SchemaObject.Key> selected) {
        return compare(source, target, kinds, selected, false);
    }

    public List<Result> compare(List<SchemaObject> source, List<SchemaObject> target, Set<SchemaObject.Kind> kinds,
                                Set<SchemaObject.Key> selected, boolean ignoreMySqlViewDefiner) {
        Map<SchemaObject.Key, SchemaObject> left = index(source), right = index(target);
        TreeSet<SchemaObject.Key> keys = new TreeSet<>(); keys.addAll(left.keySet()); keys.addAll(right.keySet());
        List<Result> results = new ArrayList<>();
        for (SchemaObject.Key key : keys) {
            if (!kinds.contains(key.kind()) || (!selected.isEmpty() && !selected.contains(key))) continue;
            SchemaObject a = left.get(key), b = right.get(key);
            boolean identical = a != null && b != null && (ignoreMySqlViewDefiner && key.kind() == SchemaObject.Kind.VIEW
                    ? comparableSignature(a).equals(comparableSignature(b))
                    : a.signature().equals(b.signature()));
            Status status = a == null ? Status.TARGET_ONLY : b == null ? Status.SOURCE_ONLY : identical ? Status.IDENTICAL : Status.CHANGED;
            List<DiffLine> diff = a != null && b != null
                    ? status == Status.IDENTICAL ? equivalentLines(a.definition(), b.definition()) : unified(a.definition(), b.definition())
                    : a != null ? oneSided(a.definition(), false) : oneSided(b.definition(), true);
            results.add(new Result(key, status, a, b, diff));
        }
        return results;
    }

    private static String comparableSignature(SchemaObject object) {
        String definition = SqlDefinitionCanonicalizer.withoutMySqlViewDefiner(object.definition());
        return SqlDefinitionCanonicalizer.signature(definition, SqlDefinitionCanonicalizer.Dialect.MYSQL, false);
    }

    private static Map<SchemaObject.Key, SchemaObject> index(List<SchemaObject> values) {
        Map<SchemaObject.Key, SchemaObject> result = new HashMap<>(); for (SchemaObject value : values) result.put(value.key(), value); return result;
    }

    private static List<DiffLine> unified(String before, String after) {
        String[] a = before.split("\n", -1), b = after.split("\n", -1);
        // LCS conserva el orden de las líneas comunes y permite asignar a cada
        // cambio su número real en origen o destino. Tiempo y memoria son O(n*m).
        int n = a.length, m = b.length; int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
            lcs[i][j] = a[i].equals(b[j]) ? 1 + lcs[i + 1][j + 1] : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        List<DiffLine> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a[i].equals(b[j])) {
                out.add(new DiffLine(LineType.CONTEXT, a[i], i + 1, j + 1)); i++; j++;
            } else if (j < m && (i == n || lcs[i][j + 1] > lcs[i + 1][j])) {
                out.add(new DiffLine(LineType.ADDED, b[j], null, j + 1)); j++;
            } else {
                out.add(new DiffLine(LineType.REMOVED, a[i], i + 1, null)); i++;
            }
        }
        return List.copyOf(out);
    }

    private static List<DiffLine> oneSided(String definition, boolean targetOnly) {
        // Si el objeto existe en una sola base, todas sus líneas son altas o
        // bajas y el margen deja vacío el número del lado ausente.
        String[] lines = definition.split("\n", -1);
        List<DiffLine> result = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) result.add(targetOnly
                ? new DiffLine(LineType.ADDED, lines[i], null, i + 1)
                : new DiffLine(LineType.REMOVED, lines[i], i + 1, null));
        return List.copyOf(result);
    }

    /** Avoid red/green line markers when only server formatting differs. */
    private static List<DiffLine> equivalentLines(String source, String target) {
        String[] left = source.split("\n", -1), right = target.split("\n", -1);
        int count = Math.max(left.length, right.length);
        List<DiffLine> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String text = i < left.length ? left[i] : right[i];
            result.add(new DiffLine(LineType.CONTEXT, text,
                    i < left.length ? i + 1 : null,
                    i < right.length ? i + 1 : null));
        }
        return List.copyOf(result);
    }
}
