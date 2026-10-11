package studio.dbdiff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads a schema snapshot made of SQL files containing one full CREATE per file. */
final class SqlSnapshotSource implements SchemaSource {
    private static final String IDENTIFIER = "(`(?:``|[^`])+`|\\[(?:]]|[^]])+]|\"(?:\"\"|[^\"])+\"|[A-Za-z_@$#][\\w$#@]*)";
    private static final Pattern CREATE_HEADER = Pattern.compile(
            "(?im)^\\s*CREATE\\s+(?:OR\\s+(?:REPLACE|ALTER)\\s+)?" +
                    "(?:(?:ALGORITHM\\s*=\\s*\\w+)\\s+)?" +
                    "(?:(?:DEFINER\\s*=\\s*[^\\s]+)\\s+)?" +
                    "(?:(?:SQL\\s+SECURITY\\s+\\w+)\\s+)?" +
                    "(TABLE|VIEW|PROCEDURE|PROC|FUNCTION|TRIGGER)\\s+" +
                    "(?:IF\\s+NOT\\s+EXISTS\\s+)?" +
                    "(" + IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?)");
    private final Path directory;
    private final DatabaseEngine engine;
    private final Map<SchemaObject.Key, SchemaObject> objects = new TreeMap<>();
    private final Map<SchemaObject.Key, Path> objectFiles = new TreeMap<>();
    private boolean loaded;

    SqlSnapshotSource(Path directory, DatabaseEngine engine) {
        this.directory = directory.toAbsolutePath().normalize();
        this.engine = engine;
    }

    @Override public List<SchemaObject> listObjects() throws SQLException {
        readIfNeeded();
        return List.copyOf(objects.values());
    }

    @Override public SchemaObject loadObject(SchemaObject.Key key) throws SQLException {
        readIfNeeded();
        SchemaObject object = objects.get(key);
        if (object == null) throw new SQLException("El snapshot no contiene " + key + ".");
        return object;
    }

    private void readIfNeeded() throws SQLException {
        if (loaded) return;
        if (!Files.isDirectory(directory)) throw new SQLException("La ruta elegida no es una carpeta: " + directory);
        List<Path> scripts;
        try (Stream<Path> paths = Files.walk(directory)) {
            scripts = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".sql"))
                    .sorted(Comparator.comparing(path -> directory.relativize(path).toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException ex) {
            throw new SQLException("No se pudo recorrer la carpeta de snapshot.", ex);
        }
        if (scripts.isEmpty()) throw new SQLException("No se encontraron archivos .sql en la carpeta seleccionada.");
        for (Path script : scripts) {
            try {
                String text = Files.readString(script, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
                SchemaObject object = parse(script, text);
                if (object == null) continue;
                Path previousFile = objectFiles.putIfAbsent(object.key(), script);
                if (previousFile != null) {
                    String first = directory.relativize(previousFile).toString();
                    String second = directory.relativize(script).toString();
                    String detail = isFlywayMigration(previousFile) || isFlywayMigration(script)
                            ? " Estos nombres parecen migraciones Flyway incrementales; Comparar snapshot requiere una sola definición CREATE completa por objeto y no reproduce migraciones."
                            : " Dejá una sola definición CREATE completa por objeto.";
                    throw new SQLException("El objeto " + object.key().qualifiedName() + " aparece en más de un archivo del snapshot: "
                            + first + " y " + second + "." + detail);
                }
                objects.put(object.key(), object);
            } catch (IOException ex) {
                throw new SQLException("No se pudo leer " + directory.relativize(script) + ".", ex);
            }
        }
        if (objects.isEmpty()) throw new SQLException("No se encontraron definiciones CREATE de tablas, vistas, rutinas o triggers.");
        loaded = true;
    }

    private static boolean isFlywayMigration(Path path) {
        return path.getFileName().toString().matches("(?i)(?:V\\d+(?:[._]\\d+)*|U\\d+(?:[._]\\d+)*|R)__.*\\.sql");
    }

    private SchemaObject parse(Path path, String text) throws SQLException {
        Matcher header = CREATE_HEADER.matcher(text);
        if (!header.find()) return null;
        String objectType = header.group(1).toUpperCase();
        String qualifiedName = header.group(2);
        String definition = text.substring(header.start()).strip();
        // Remove client batch/directive wrappers while retaining the object DDL itself.
        definition = definition.replaceAll("(?im)^[ \\t]*GO[ \\t]*$", "")
                .replaceAll("(?im)^[ \\t]*DELIMITER[ \\t]+[^\\r\\n]+$", "").strip();
        SchemaObject.Kind kind = switch (objectType) {
            case "TABLE" -> SchemaObject.Kind.TABLE;
            case "VIEW" -> SchemaObject.Kind.VIEW;
            case "PROCEDURE", "PROC" -> SchemaObject.Kind.PROCEDURE;
            case "FUNCTION" -> SchemaObject.Kind.FUNCTION;
            case "TRIGGER" -> SchemaObject.Kind.TRIGGER;
            default -> throw new IllegalStateException("Unexpected SQL object type: " + objectType);
        };
        String[] parts = splitQualifiedName(qualifiedName);
        String schema = engine == DatabaseEngine.SQLSERVER
                ? parts.length == 2 ? unquote(parts[0]) : "dbo"
                : ""; // MySQL database qualifiers are not part of connector object keys.
        String name = unquote(parts.length == 2 ? parts[1] : parts[0]);
        SchemaObject.Key key = new SchemaObject.Key(kind, name, schema);
        SqlDefinitionCanonicalizer.Dialect dialect = engine == DatabaseEngine.SQLSERVER
                ? SqlDefinitionCanonicalizer.Dialect.SQL_SERVER : SqlDefinitionCanonicalizer.Dialect.MYSQL;
        String signature = SqlDefinitionCanonicalizer.signature(definition, dialect, kind == SchemaObject.Kind.TABLE);
        return new SchemaObject(key, definition, signature);
    }

    private static String unquote(String value) {
        String name = value.strip();
        if (name.length() >= 2 && name.startsWith("`") && name.endsWith("`")) return name.substring(1, name.length() - 1).replace("``", "`");
        if (name.length() >= 2 && name.startsWith("[") && name.endsWith("]")) return name.substring(1, name.length() - 1).replace("]]", "]");
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) return name.substring(1, name.length() - 1).replace("\"\"", "\"");
        return name;
    }

    /** Splits schema and object identifiers without treating dots inside quotes as separators. */
    private static String[] splitQualifiedName(String qualifiedName) {
        char quote = 0;
        for (int index = 0; index < qualifiedName.length(); index++) {
            char current = qualifiedName.charAt(index);
            if (quote != 0) {
                if (quote == '[' && current == ']') {
                    if (index + 1 < qualifiedName.length() && qualifiedName.charAt(index + 1) == ']') index++;
                    else quote = 0;
                } else if (current == quote) {
                    if (index + 1 < qualifiedName.length() && qualifiedName.charAt(index + 1) == quote) index++;
                    else quote = 0;
                }
            } else if (current == '`' || current == '[' || current == '"') quote = current;
            else if (current == '.') return new String[]{qualifiedName.substring(0, index).strip(), qualifiedName.substring(index + 1).strip()};
        }
        return new String[]{qualifiedName.strip()};
    }
}
