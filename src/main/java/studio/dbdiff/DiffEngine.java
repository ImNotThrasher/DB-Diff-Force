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
        return compare(source, target, kinds, selected, ignoreMySqlViewDefiner, false, DatabaseEngine.MARIADB);
    }

    public List<Result> compare(List<SchemaObject> source, List<SchemaObject> target, Set<SchemaObject.Kind> kinds,
                                Set<SchemaObject.Key> selected, boolean ignoreMySqlViewDefiner,
                                boolean ignoreComments, DatabaseEngine engine) {
        var dialect = engine == DatabaseEngine.SQLSERVER ? SqlDefinitionCanonicalizer.Dialect.SQL_SERVER
                : SqlDefinitionCanonicalizer.Dialect.MYSQL;
        Map<SchemaObject.Key, SchemaObject> left = index(source), right = index(target);
        TreeSet<SchemaObject.Key> keys = new TreeSet<>(); keys.addAll(left.keySet()); keys.addAll(right.keySet());
        List<Result> results = new ArrayList<>();
        for (SchemaObject.Key key : keys) {
            if (!kinds.contains(key.kind()) || (!selected.isEmpty() && !selected.contains(key))) continue;
            SchemaObject a = left.get(key), b = right.get(key);
            boolean normalizeMySqlDefinition = ignoreMySqlViewDefiner && engine != DatabaseEngine.SQLSERVER
                    && (key.kind() == SchemaObject.Kind.VIEW || key.kind() == SchemaObject.Kind.TRIGGER
                        || key.kind() == SchemaObject.Kind.PROCEDURE || key.kind() == SchemaObject.Kind.FUNCTION);
            String leftDefinition = a == null ? "" : displayDefinition(a.definition(), key.kind(), normalizeMySqlDefinition);
            String rightDefinition = b == null ? "" : displayDefinition(b.definition(), key.kind(), normalizeMySqlDefinition);
            if (ignoreComments) {
                leftDefinition = SqlDefinitionCanonicalizer.withoutComments(leftDefinition, dialect);
                rightDefinition = SqlDefinitionCanonicalizer.withoutComments(rightDefinition, dialect);
            }
            boolean identical = a != null && b != null && (normalizeMySqlDefinition || ignoreComments
                    ? SqlDefinitionCanonicalizer.signature(leftDefinition, dialect, key.kind() == SchemaObject.Kind.TABLE)
                        .equals(SqlDefinitionCanonicalizer.signature(rightDefinition, dialect, key.kind() == SchemaObject.Kind.TABLE))
                    : a.signature().equals(b.signature()));
            Status status = a == null ? Status.TARGET_ONLY : b == null ? Status.SOURCE_ONLY : identical ? Status.IDENTICAL : Status.CHANGED;
            List<DiffLine> diff = a != null && b != null
                    ? status == Status.IDENTICAL ? equivalentLines(a.definition(), b.definition())
                        : unified(leftDefinition, rightDefinition)
                    : a != null ? oneSided(a.definition(), false) : oneSided(b.definition(), true);
            if (a != null && b != null) {
                String[] originalLeft = a.definition().split("\n", -1), originalRight = b.definition().split("\n", -1);
                diff = diff.stream().map(line -> new DiffLine(line.type(),
                        line.sourceLineNumber() != null ? originalLeft[Math.min(line.sourceLineNumber() - 1, originalLeft.length - 1)]
                                : originalRight[Math.min(line.targetLineNumber() - 1, originalRight.length - 1)],
                        line.sourceLineNumber(), line.targetLineNumber())).toList();
            }
            results.add(new Result(key, status, a, b, diff));
        }
        return results;
    }

    private static String displayDefinition(String definition, SchemaObject.Kind kind, boolean normalizeMySqlDefinition) {
        if (!normalizeMySqlDefinition) return definition;
        return switch (kind) {
            case VIEW, PROCEDURE, FUNCTION -> SqlDefinitionCanonicalizer.withoutMySqlViewDefiner(definition);
            case TRIGGER -> SqlDefinitionCanonicalizer.withoutMySqlTriggerDatabase(definition);
            default -> definition;
        };
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
            lcs[i][j] = a[i].stripTrailing().equals(b[j].stripTrailing()) ? 1 + lcs[i + 1][j + 1] : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        List<DiffLine> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a[i].stripTrailing().equals(b[j].stripTrailing())) {
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
