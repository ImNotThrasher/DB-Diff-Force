package studio.dbdiff;

import java.util.*;

public final class DiffEngine {
    public enum Status { IDENTICAL, CHANGED, SOURCE_ONLY, TARGET_ONLY }
    public record Result(SchemaObject.Key key, Status status, SchemaObject source, SchemaObject target, List<String> diffLines) { }

    public List<Result> compare(List<SchemaObject> source, List<SchemaObject> target, Set<SchemaObject.Kind> kinds, Set<SchemaObject.Key> selected) {
        Map<SchemaObject.Key, SchemaObject> left = index(source), right = index(target);
        TreeSet<SchemaObject.Key> keys = new TreeSet<>(); keys.addAll(left.keySet()); keys.addAll(right.keySet());
        List<Result> results = new ArrayList<>();
        for (SchemaObject.Key key : keys) {
            if (!kinds.contains(key.kind()) || (!selected.isEmpty() && !selected.contains(key))) continue;
            SchemaObject a = left.get(key), b = right.get(key);
            Status status = a == null ? Status.TARGET_ONLY : b == null ? Status.SOURCE_ONLY : a.signature().equals(b.signature()) ? Status.IDENTICAL : Status.CHANGED;
            results.add(new Result(key, status, a, b, a != null && b != null && status == Status.CHANGED ? unified(a.definition(), b.definition()) : List.of()));
        }
        return results;
    }

    private static Map<SchemaObject.Key, SchemaObject> index(List<SchemaObject> values) {
        Map<SchemaObject.Key, SchemaObject> result = new HashMap<>(); for (SchemaObject value : values) result.put(value.key(), value); return result;
    }

    private static List<String> unified(String before, String after) {
        String[] a = before.split("\n", -1), b = after.split("\n", -1);
        int n = a.length, m = b.length; int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
            lcs[i][j] = a[i].equals(b[j]) ? 1 + lcs[i + 1][j + 1] : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        List<String> out = new ArrayList<>(); out.add("--- Source"); out.add("+++ Target");
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a[i].equals(b[j])) { out.add("  " + a[i]); i++; j++; }
            else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) out.add("+ " + b[j++]);
            else out.add("- " + a[i++]);
        }
        return out;
    }
}
