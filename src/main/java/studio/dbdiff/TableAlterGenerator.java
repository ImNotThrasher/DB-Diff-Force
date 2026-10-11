package studio.dbdiff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a conservative ALTER TABLE from two SHOW CREATE TABLE definitions. */
final class TableAlterGenerator {
    private static final Pattern COLUMN = Pattern.compile("^`((?:``|[^`])+)`\\s+(.+)$", Pattern.DOTALL);
    private static final Pattern CONSTRAINT = Pattern.compile("(?is)^CONSTRAINT\\s+`((?:``|[^`])+)`\\s+(.+)$");
    private static final Pattern FOREIGN_KEY = Pattern.compile("(?is)^FOREIGN\\s+KEY\\b.*$");
    private static final Pattern UNIQUE_CONSTRAINT = Pattern.compile("(?is)^UNIQUE\\b.*$");
    private static final Pattern KEY = Pattern.compile("(?is)^(?:(UNIQUE|FULLTEXT|SPATIAL)\\s+)?(?:KEY|INDEX)\\s+`((?:``|[^`])+)`\\s+.*$");
    private static final Pattern ENGINE = Pattern.compile("(?i)\\bENGINE\\s*=\\s*([^\\s]+)");
    private static final Pattern CHARSET = Pattern.compile("(?i)(?:DEFAULT\\s+)?CHARSET\\s*=\\s*([^\\s]+)");
    private static final Pattern COLLATION = Pattern.compile("(?i)\\bCOLLATE\\s*=\\s*([^\\s]+)");
    private static final Pattern ROW_FORMAT = Pattern.compile("(?i)\\bROW_FORMAT\\s*=\\s*([^\\s]+)");

    private TableAlterGenerator() { }

    static Optional<String> generate(String sourceDdl, String targetDdl, String database, String tableName) {
        ParsedTable source = parse(sourceDdl).orElse(null);
        ParsedTable target = parse(targetDdl).orElse(null);
        if (source == null || target == null) return Optional.empty();
        if (!canPreserveColumnOrder(source.columns, target.columns)) return Optional.empty();

        List<String> clauses = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> keysToRecreate = new TreeSet<>();

        // MySQL/MariaDB often expose a foreign key and its supporting index as
        // separate clauses with the same name. Recreate the pair together so
        // changing either side never drops an index still used by a constraint.
        for (Map.Entry<String, Definition> entry : target.keys.entrySet()) {
            Definition desired = source.keys.get(entry.getKey());
            if (desired == null || !same(entry.getValue().ddl, desired.ddl)) {
                keysToRecreate.add(entry.getKey());
                addPairedKey(keysToRecreate, entry.getValue(), target.keys, source.keys);
            }
        }
        for (Map.Entry<String, Definition> entry : source.keys.entrySet()) {
            if (!target.keys.containsKey(entry.getKey())) {
                keysToRecreate.add(entry.getKey());
                addPairedKey(keysToRecreate, entry.getValue(), target.keys, source.keys);
            }
        }
        boolean columnsChanged = source.columns.size() != target.columns.size()
                || source.columns.entrySet().stream().anyMatch(entry -> {
                    Definition current = target.columns.get(entry.getKey());
                    return current == null || !same(current.ddl, entry.getValue().ddl);
                });
        boolean indexesChanged = keysToRecreate.stream().anyMatch(identity -> identity.startsWith("INDEX:"));
        if (columnsChanged || indexesChanged) {
            // Constraints can depend on changed columns or supporting indexes
            // whose names differ from the FK name. Recreate them around changes.
            for (Definition key : target.keys.values()) if (key.kind.equals("FOREIGN")) {
                keysToRecreate.add(key.identity);
                addPairedKey(keysToRecreate, key, target.keys, source.keys);
            }
            for (Definition key : source.keys.values()) if (key.kind.equals("FOREIGN")) {
                keysToRecreate.add(key.identity);
                addPairedKey(keysToRecreate, key, target.keys, source.keys);
            }
        }

        // Drop dependent keys before changing or removing their columns.
        for (Map.Entry<String, Definition> entry : target.keys.entrySet()) {
            if (keysToRecreate.contains(entry.getKey())) {
                switch (entry.getValue().kind) {
                    case "PRIMARY" -> clauses.add("DROP PRIMARY KEY");
                    case "FOREIGN" -> clauses.add("DROP FOREIGN KEY " + quote(entry.getValue().name));
                    case "INDEX" -> clauses.add("DROP INDEX " + quote(entry.getValue().name));
                    default -> { return Optional.empty(); }
                }
            }
        }

        for (Map.Entry<String, Definition> entry : target.columns.entrySet()) {
            if (!source.columns.containsKey(entry.getKey())) {
                clauses.add("DROP COLUMN " + quote(entry.getValue().name));
                warnings.add("dropping column " + entry.getValue().name + " removes its existing data");
            }
        }
        for (Map.Entry<String, Definition> entry : source.columns.entrySet()) {
            Definition current = target.columns.get(entry.getKey());
            if (current == null) {
                clauses.add("ADD COLUMN " + entry.getValue().ddl);
            } else if (!same(current.ddl, entry.getValue().ddl)) {
                clauses.add("MODIFY COLUMN " + entry.getValue().ddl);
                warnings.add("modifying column " + entry.getValue().name + " can convert or truncate existing values");
            }
        }

        source.keys.entrySet().stream().filter(entry -> keysToRecreate.contains(entry.getKey()))
                .sorted((left, right) -> Integer.compare(keyAddOrder(left.getValue()), keyAddOrder(right.getValue())))
                .forEach(entry -> clauses.add("ADD " + entry.getValue().ddl));

        if (!sameTableOptions(source.options, target.options)) {
            if (!appendTableOptionChanges(clauses, source.options, target.options)) return Optional.empty();
        }
        if (clauses.isEmpty()) return Optional.of("-- No column, key, or supported table-option changes were found.");

        StringBuilder sql = new StringBuilder();
        if (!warnings.isEmpty()) {
            sql.append("-- REVIEW: ").append(String.join("; ", warnings)).append(".\n");
        }
        sql.append("ALTER TABLE ").append(quote(database)).append('.').append(quote(tableName)).append("\n  ")
                .append(String.join(",\n  ", clauses)).append(";\n");
        return Optional.of(sql.toString());
    }

    private static void addPairedKey(Set<String> affected, Definition key,
                                     Map<String, Definition> current, Map<String, Definition> desired) {
        if (key.kind.equals("FOREIGN")) {
            String paired = "INDEX:" + key.name.toLowerCase(Locale.ROOT);
            if (current.containsKey(paired) || desired.containsKey(paired)) affected.add(paired);
        } else if (key.kind.equals("INDEX")) {
            String paired = "FOREIGN:" + key.name.toLowerCase(Locale.ROOT);
            if (current.containsKey(paired) || desired.containsKey(paired)) affected.add(paired);
        }
    }

    private static int keyAddOrder(Definition key) {
        return switch (key.kind) { case "INDEX" -> 0; case "FOREIGN" -> 1; default -> 2; };
    }

    private static Optional<ParsedTable> parse(String ddl) {
        if (ddl == null || ddl.isBlank() || ddl.startsWith("-- ")) return Optional.empty();
        int open = findCreateBody(ddl);
        if (open < 0) return Optional.empty();
        int close = matchingClose(ddl, open);
        if (close < 0) return Optional.empty();
        List<String> fragments = splitTopLevel(ddl.substring(open + 1, close));
        if (fragments.isEmpty()) return Optional.empty();

        Map<String, Definition> columns = new LinkedHashMap<>();
        Map<String, Definition> keys = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String raw : fragments) {
            String item = raw.strip();
            if (item.isEmpty()) continue;
            Definition key = parseKey(item);
            if (key != null) {
                if (keys.put(key.identity, key) != null) return Optional.empty();
                continue;
            }
            Matcher column = COLUMN.matcher(item);
            if (!column.matches()) return Optional.empty();
            String name = unquote(column.group(1));
            String identity = name.toLowerCase(Locale.ROOT);
            Definition definition = new Definition("COLUMN", name, identity, item);
            if (columns.put(identity, definition) != null) return Optional.empty();
        }
        if (columns.isEmpty()) return Optional.empty();
        return Optional.of(new ParsedTable(columns, keys, ddl.substring(close + 1).strip()));
    }

    private static Definition parseKey(String item) {
        String upper = item.toUpperCase(Locale.ROOT);
        if (upper.startsWith("PRIMARY KEY")) return new Definition("PRIMARY", "PRIMARY", "PRIMARY", item);
        Matcher constraint = CONSTRAINT.matcher(item);
        if (constraint.matches()) {
            String name = unquote(constraint.group(1));
            String clause = constraint.group(2).strip();
            if (FOREIGN_KEY.matcher(clause).matches()) return new Definition("FOREIGN", name, "FOREIGN:" + name.toLowerCase(Locale.ROOT), item);
            if (UNIQUE_CONSTRAINT.matcher(clause).matches()) return new Definition("INDEX", name, "INDEX:" + name.toLowerCase(Locale.ROOT), item);
            return null;
        }
        Matcher key = KEY.matcher(item);
        if (key.matches()) {
            String name = unquote(key.group(2));
            return new Definition("INDEX", name, "INDEX:" + name.toLowerCase(Locale.ROOT), item);
        }
        if (upper.startsWith("CHECK")) return null;
        return null;
    }

    private static int findCreateBody(String ddl) {
        boolean backtick = false, single = false, dbl = false, escaped = false;
        for (int i = 0; i < ddl.length(); i++) {
            char c = ddl.charAt(i);
            if (escaped) { escaped = false; continue; }
            if ((single || dbl) && c == '\\') { escaped = true; continue; }
            if (!single && !dbl && c == '`') {
                if (backtick && i + 1 < ddl.length() && ddl.charAt(i + 1) == '`') { i++; continue; }
                backtick = !backtick; continue;
            }
            if (!backtick && !dbl && c == '\'') { single = !single; continue; }
            if (!backtick && !single && c == '"') { dbl = !dbl; continue; }
            if (!backtick && !single && !dbl && c == '(') return i;
        }
        return -1;
    }

    private static int matchingClose(String ddl, int open) {
        int depth = 0; boolean backtick = false, single = false, dbl = false, escaped = false;
        for (int i = open; i < ddl.length(); i++) {
            char c = ddl.charAt(i);
            if (escaped) { escaped = false; continue; }
            if ((single || dbl) && c == '\\') { escaped = true; continue; }
            if (!single && !dbl && c == '`') {
                if (backtick && i + 1 < ddl.length() && ddl.charAt(i + 1) == '`') { i++; continue; }
                backtick = !backtick; continue;
            }
            if (!backtick && !dbl && c == '\'') { single = !single; continue; }
            if (!backtick && !single && c == '"') { dbl = !dbl; continue; }
            if (backtick || single || dbl) continue;
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static List<String> splitTopLevel(String body) {
        List<String> result = new ArrayList<>(); int start = 0, depth = 0;
        boolean backtick = false, single = false, dbl = false, escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaped) { escaped = false; continue; }
            if ((single || dbl) && c == '\\') { escaped = true; continue; }
            if (!single && !dbl && c == '`') {
                if (backtick && i + 1 < body.length() && body.charAt(i + 1) == '`') { i++; continue; }
                backtick = !backtick; continue;
            }
            if (!backtick && !dbl && c == '\'') { single = !single; continue; }
            if (!backtick && !single && c == '"') { dbl = !dbl; continue; }
            if (backtick || single || dbl) continue;
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) { result.add(body.substring(start, i)); start = i + 1; }
        }
        result.add(body.substring(start));
        return result;
    }

    private static boolean appendTableOptionChanges(List<String> clauses, String desired, String current) {
        Map<String, String> wanted = tableOptions(desired), existing = tableOptions(current);
        if (wanted.isEmpty() && existing.isEmpty()) return sameTableOptions(desired, current);
        if (!unsupportedOptions(desired).equalsIgnoreCase(unsupportedOptions(current))) return false;
        if (!wanted.keySet().containsAll(existing.keySet().stream().filter(key -> !key.equals("AUTO_INCREMENT")).toList())) return false;
        for (String option : List.of("ENGINE", "CHARSET", "COLLATE", "ROW_FORMAT")) {
            String value = wanted.get(option);
            if (value != null && !value.equalsIgnoreCase(existing.getOrDefault(option, ""))) {
                String name = switch (option) {
                    case "CHARSET" -> "DEFAULT CHARACTER SET";
                    default -> option;
                };
                clauses.add(name + " = " + value);
            }
        }
        return wanted.keySet().stream().allMatch(List.of("ENGINE", "CHARSET", "COLLATE", "ROW_FORMAT")::contains)
                && existing.keySet().stream().allMatch(List.of("ENGINE", "CHARSET", "COLLATE", "ROW_FORMAT", "AUTO_INCREMENT")::contains);
    }

    private static String unsupportedOptions(String options) {
        return options.replaceAll("(?i)\\bENGINE\\s*=\\s*[^\\s]+", " ")
                .replaceAll("(?i)(?:DEFAULT\\s+)?CHARSET\\s*=\\s*[^\\s]+", " ")
                .replaceAll("(?i)\\bCOLLATE\\s*=\\s*[^\\s]+", " ")
                .replaceAll("(?i)\\bROW_FORMAT\\s*=\\s*[^\\s]+", " ")
                .replaceAll("(?i)\\bAUTO_INCREMENT\\s*=\\s*\\d+", " ")
                .replaceAll("\\s+", " ").strip();
    }

    private static boolean canPreserveColumnOrder(Map<String, Definition> source, Map<String, Definition> target) {
        List<String> sourceExisting = source.keySet().stream().filter(target::containsKey).toList();
        List<String> targetExisting = target.keySet().stream().filter(source::containsKey).toList();
        if (!sourceExisting.equals(targetExisting)) return false;
        boolean sawNewColumn = false;
        for (String name : source.keySet()) {
            if (!target.containsKey(name)) sawNewColumn = true;
            else if (sawNewColumn) return false; // ADD appends; it cannot preserve a newly inserted middle column.
        }
        return true;
    }

    private static Map<String, String> tableOptions(String options) {
        Map<String, String> result = new LinkedHashMap<>();
        putOption(result, "ENGINE", ENGINE, options); putOption(result, "CHARSET", CHARSET, options);
        putOption(result, "COLLATE", COLLATION, options); putOption(result, "ROW_FORMAT", ROW_FORMAT, options);
        Matcher auto = Pattern.compile("(?i)\\bAUTO_INCREMENT\\s*=\\s*(\\d+)").matcher(options);
        if (auto.find()) result.put("AUTO_INCREMENT", auto.group(1));
        return result;
    }

    private static void putOption(Map<String, String> out, String name, Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        if (matcher.find()) out.put(name, matcher.group(1));
    }

    private static boolean sameTableOptions(String left, String right) {
        String a = left.replaceAll("(?i)AUTO_INCREMENT\\s*=\\s*\\d+", "AUTO_INCREMENT=<value>").replaceAll("\\s+", " ").strip();
        String b = right.replaceAll("(?i)AUTO_INCREMENT\\s*=\\s*\\d+", "AUTO_INCREMENT=<value>").replaceAll("\\s+", " ").strip();
        return a.equalsIgnoreCase(b);
    }

    private static boolean same(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String sql) {
        StringBuilder out = new StringBuilder(); char quote = 0; boolean escaped = false, space = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (escaped) { out.append(c); escaped = false; continue; }
            if (quote != 0) {
                out.append(c);
                if ((quote == '\'' || quote == '"') && c == '\\') escaped = true;
                else if (c == quote) {
                    if (quote == '`' && i + 1 < sql.length() && sql.charAt(i + 1) == '`') out.append(sql.charAt(++i));
                    else quote = 0;
                }
                continue;
            }
            if (c == '`' || c == '\'' || c == '"') {
                if (space && out.length() > 0) out.append(' ');
                space = false; quote = c; out.append(c);
            } else if (Character.isWhitespace(c)) space = true;
            else {
                if (space && out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                space = false; out.append(c);
            }
        }
        return out.toString().strip();
    }

    private static String unquote(String identifier) { return identifier.replace("``", "`"); }
    private static String quote(String identifier) { return "`" + identifier.replace("`", "``") + "`"; }

    private record Definition(String kind, String name, String identity, String ddl) { }
    private record ParsedTable(Map<String, Definition> columns, Map<String, Definition> keys, String options) { }
}
