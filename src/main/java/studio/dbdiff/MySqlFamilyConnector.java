package studio.dbdiff;

import java.sql.*;
import java.util.*;

/** Shared read-only connector for MySQL-family engines. */
abstract class MySqlFamilyConnector implements DatabaseConnector {
    private final DatabaseEngine engine;
    private Connection connection;
    private String database;

    MySqlFamilyConnector(DatabaseEngine engine) { this.engine = engine; }

    @Override public void connect(Endpoint endpoint) throws SQLException {
        close();
        database = endpoint.database();
        String scheme = engine == DatabaseEngine.MYSQL ? "jdbc:mysql://" : "jdbc:mariadb://";
        String url = scheme + endpoint.host() + ":" + endpoint.port() + "/" + encodePath(database) +
                (engine == DatabaseEngine.MYSQL
                        ? "?connectTimeout=8000&socketTimeout=15000&useServerPrepStmts=true&useCursorFetch=true&sslMode=" + (endpoint.useTls() ? "VERIFY_IDENTITY" : "DISABLED")
                        : "?connectTimeout=8000&socketTimeout=15000&useServerPrepStmts=true&useCursorFetch=true" + (endpoint.useTls() ? "&sslMode=verify-full" : ""));
        Properties properties = new Properties();
        properties.setProperty("user", endpoint.user());
        char[] secret = endpoint.password();
        try {
            properties.setProperty("password", new String(secret));
            connection = DriverManager.getConnection(url, properties);
        } finally {
            Arrays.fill(secret, '\0');
            properties.remove("password");
        }
    }

    @Override public List<String> listDatabases() throws SQLException {
        requireConnection();
        List<String> databases = new ArrayList<>();
        String sql = "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA " +
                "WHERE SCHEMA_NAME NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys') " +
                "ORDER BY SCHEMA_NAME";
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) databases.add(rs.getString(1));
        }
        return List.copyOf(databases);
    }

    @Override public List<SchemaObject> listObjects() throws SQLException {
        requireConnection();
        List<SchemaObject> result = new ArrayList<>();
        String sql = "SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString(2);
                    SchemaObject.Kind kind = "VIEW".equalsIgnoreCase(type) ? SchemaObject.Kind.VIEW : SchemaObject.Kind.TABLE;
                    result.add(new SchemaObject(new SchemaObject.Key(kind, rs.getString(1)), "", ""));
                }
            }
        }
        String routines = "SELECT ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=? ORDER BY ROUTINE_NAME";
        try (PreparedStatement ps = connection.prepareStatement(routines)) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SchemaObject.Kind kind = "FUNCTION".equalsIgnoreCase(rs.getString(2)) ? SchemaObject.Kind.FUNCTION : SchemaObject.Kind.PROCEDURE;
                    result.add(new SchemaObject(new SchemaObject.Key(kind, rs.getString(1)), "", ""));
                }
            }
        }
        String triggers = "SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? ORDER BY TRIGGER_NAME";
        try (PreparedStatement ps = connection.prepareStatement(triggers)) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new SchemaObject(new SchemaObject.Key(SchemaObject.Kind.TRIGGER, rs.getString(1)), "", ""));
            }
        }
        result.sort(Comparator.comparing(SchemaObject::key));
        return result;
    }

    @Override public SchemaObject loadObject(SchemaObject.Key key) throws SQLException {
        requireConnection();
        return switch (key.kind()) {
            case TABLE -> loadTable(key);
            case VIEW -> loadView(key);
            case PROCEDURE, FUNCTION -> loadRoutine(key);
            case TRIGGER -> loadTrigger(key);
        };
    }

    /** Collects catalog-known dependencies; unsupported metadata views are safely skipped. */
    @Override public Map<SchemaObject.Key, Set<SchemaObject.Key>> dependencies() throws SQLException {
        return dependencyReport().dependencies();
    }

    /** Reports metadata gaps explicitly instead of presenting partial catalog edges as complete. */
    @Override public DependencyReport dependencyReport() throws SQLException {
        requireConnection();
        Map<SchemaObject.Key, Set<SchemaObject.Key>> result = new HashMap<>();
        List<DependencyReport.Coverage> coverage = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        collectDependencies(result, coverage, limitations, "claves foráneas",
                "SELECT TABLE_NAME, REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE " +
                        "WHERE TABLE_SCHEMA=? AND REFERENCED_TABLE_SCHEMA=? AND REFERENCED_TABLE_NAME IS NOT NULL",
                SchemaObject.Kind.TABLE, true, false);
        collectDependencies(result, coverage, limitations, "vistas",
                "SELECT usage.VIEW_NAME, usage.TABLE_NAME, objects.TABLE_TYPE FROM information_schema.VIEW_TABLE_USAGE usage " +
                        "LEFT JOIN information_schema.TABLES objects ON objects.TABLE_SCHEMA=usage.TABLE_SCHEMA AND objects.TABLE_NAME=usage.TABLE_NAME " +
                        "WHERE usage.VIEW_SCHEMA=? AND usage.TABLE_SCHEMA=?",
                SchemaObject.Kind.VIEW, true, true);
        collectExternalViewDependencies(coverage, limitations);
        collectDependencies(result, coverage, limitations, "triggers",
                "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=?",
                SchemaObject.Kind.TRIGGER, false, false);
        limitations.add("Las referencias dentro de procedimientos y funciones no se extraen de forma fiable desde los catálogos MySQL/MariaDB.");
        limitations.add("Las dependencias de vistas visibles requieren privilegios sobre la vista y los objetos referenciados. El recuento puede ser parcial para usuarios con permisos limitados.");
        limitations.add("Las referencias entre bases se cuentan aparte cuando VIEW_TABLE_USAGE las expone; no se incorporan al orden porque el modelo de exportación trabaja dentro de una sola base.");
        limitations.add("No se extraen dependencias de procedimientos ni funciones; cero relaciones no demuestra que no existan dependencias.");
        Map<SchemaObject.Key, Set<SchemaObject.Key>> immutable = new HashMap<>();
        result.forEach((key, value) -> immutable.put(key, Set.copyOf(value)));
        return new DependencyReport(immutable, coverage, limitations);
    }

    private void collectDependencies(Map<SchemaObject.Key, Set<SchemaObject.Key>> result,
                                     List<DependencyReport.Coverage> coverage, List<String> limitations,
                                     String source, String sql, SchemaObject.Kind dependentKind,
                                     boolean twoParameters, boolean includesDependencyType) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, database);
            if (twoParameters) ps.setString(2, database);
            int edges = 0;
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    String dependentName = rows.getString(1), prerequisiteName = rows.getString(2);
                    if (dependentName == null || prerequisiteName == null) continue;
                    SchemaObject.Key dependent = new SchemaObject.Key(dependentKind, dependentName);
                    SchemaObject.Kind prerequisiteKind = includesDependencyType && "VIEW".equalsIgnoreCase(rows.getString(3))
                            ? SchemaObject.Kind.VIEW : SchemaObject.Kind.TABLE;
                    SchemaObject.Key dependency = new SchemaObject.Key(prerequisiteKind, prerequisiteName);
                    if (result.computeIfAbsent(dependent, ignored -> new TreeSet<>()).add(dependency)) edges++;
                }
            }
            coverage.add(new DependencyReport.Coverage(source, edges, ""));
        } catch (SQLException ex) {
            limitations.add("No se pudieron leer las dependencias de " + source + ": " + safeMessage(ex));
            coverage.add(new DependencyReport.Coverage(source, 0, "No disponible"));
        }
    }

    /** Counts cross-database view references without pretending the exporter can order them. */
    private void collectExternalViewDependencies(List<DependencyReport.Coverage> coverage,
                                                List<String> limitations) {
        String sql = "SELECT COUNT(*) FROM information_schema.VIEW_TABLE_USAGE "
                + "WHERE VIEW_SCHEMA=? AND TABLE_SCHEMA<>?";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, database);
            query.setString(2, database);
            try (ResultSet rows = query.executeQuery()) {
                int count = rows.next() ? rows.getInt(1) : 0;
                coverage.add(new DependencyReport.Coverage("referencias de vistas a otras bases", count,
                        count == 0 ? "" : "No se usan para ordenar"));
                if (count > 0) limitations.add("Se detectaron " + count + " referencias de vistas a otras bases; no se usan para ordenar la exportación.");
            }
        } catch (SQLException ex) {
            coverage.add(new DependencyReport.Coverage("referencias de vistas a otras bases", 0, "No disponible"));
            limitations.add("No se pudieron contar las referencias de vistas a otras bases: " + safeMessage(ex));
        }
    }

    private SchemaObject loadTable(SchemaObject.Key key) throws SQLException {
        StringBuilder text = new StringBuilder("TABLE ").append(quote(key.name())).append("\nCOLUMNS\n");
        String columns = "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_KEY FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = connection.prepareStatement(columns)) {
            ps.setString(1, database); ps.setString(2, key.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    text.append("  ").append(quote(rs.getString("COLUMN_NAME"))).append(' ').append(rs.getString("COLUMN_TYPE"));
                    text.append(" NULLABLE=").append(rs.getString("IS_NULLABLE"));
                    String def = rs.getString("COLUMN_DEFAULT"); if (def != null) text.append(" DEFAULT=").append(def);
                    String extra = rs.getString("EXTRA"); if (extra != null && !extra.isBlank()) text.append(' ').append(extra.toUpperCase(Locale.ROOT));
                    String columnKey = rs.getString("COLUMN_KEY"); if (!"".equals(columnKey)) text.append(" KEY=").append(columnKey);
                    text.append('\n');
                }
            }
        }
        text.append("INDEXES\n");
        String indexes = "SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, INDEX_TYPE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY INDEX_NAME, SEQ_IN_INDEX";
        try (PreparedStatement ps = connection.prepareStatement(indexes)) {
            ps.setString(1, database); ps.setString(2, key.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) text.append("  ").append(quote(rs.getString("INDEX_NAME"))).append(" ")
                        .append(rs.getInt("NON_UNIQUE") == 0 ? "UNIQUE " : "").append(rs.getString("INDEX_TYPE"))
                        .append(" ").append(quote(rs.getString("COLUMN_NAME"))).append(" POSITION=").append(rs.getInt("SEQ_IN_INDEX")).append('\n');
            }
        }
        String summary = text.toString();
        try {
            String ddl = showCreate(key, 2);
            return new SchemaObject(key, ddl, signature(ddl, true));
        } catch (SQLException ex) {
            String definition = "-- " + AppInfo.NAME + ": SHOW CREATE TABLE unavailable (" + safeMessage(ex) + ")\n-- Schema summary for review only; no executable DDL follows.\n" + summary;
            return new SchemaObject(key, definition, signature(definition, false));
        }
    }

    @Override public RowDiff.Result compareTableRows(DatabaseConnector peerConnector, SchemaObject.Key key, int maxChanges) throws SQLException {
        requireConnection();
        if (!(peerConnector instanceof MySqlFamilyConnector peer) || peer.engine != engine) throw new SQLException("Row comparison requires matching database engines.");
        peer.requireConnection();
        if (key.kind() != SchemaObject.Kind.TABLE) throw new SQLException("Row comparison is available for tables only.");
        if (maxChanges < 1) throw new IllegalArgumentException("maxChanges must be positive");
        List<RowDiff.Column> columns = readColumns(key.name());
        List<RowDiff.Column> primaryKey = readPrimaryKey(key.name());
        List<RowDiff.Column> peerColumns = peer.readColumns(key.name());
        List<RowDiff.Column> peerPrimaryKey = peer.readPrimaryKey(key.name());
        if (columns.isEmpty()) throw new SQLException("Table has no visible columns or SELECT permission is missing.");
        if (!columns.equals(peerColumns) || !primaryKey.equals(peerPrimaryKey)) throw new SQLException("Skipped: table columns or primary key differ between endpoints. Compare table schema first.");
        if (primaryKey.isEmpty()) throw new SQLException("Skipped: table has no primary key; rows cannot be matched safely.");
        for (RowDiff.Column column : columns) if (!safeComparable(column.type()))
            throw new SQLException("Skipped: column " + column.name() + " has unsupported comparison type " + column.type() + ".");
        for (RowDiff.Column column : primaryKey) if (!safeKey(column.type()))
            throw new SQLException("Skipped: primary key column " + column.name() + " has unsupported ordering type " + column.type() + ".");

        String columnsSql = columns.stream().map(c -> quote(c.name())).collect(java.util.stream.Collectors.joining(", "));
        String order = primaryKey.stream().map(c -> isText(c.type()) ? "CAST(" + quote(c.name()) + " AS BINARY)" : quote(c.name())).collect(java.util.stream.Collectors.joining(", "));
        String query = "SELECT " + columnsSql + " FROM " + quote(database) + "." + quote(key.name()) + " ORDER BY " + order;
        String peerQuery = "SELECT " + columnsSql + " FROM " + quote(peer.database) + "." + quote(key.name()) + " ORDER BY " + order;
        List<RowDiff.RowChange> changes = new ArrayList<>();
        // Índices: 0 solo origen, 1 solo destino, 2 distintas, 3 claves emparejadas.
        long[] counts = new long[4]; boolean truncated = false;
        try (PreparedStatement left = connection.prepareStatement(query, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement right = peer.connection.prepareStatement(peerQuery, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            left.setFetchSize(500); right.setFetchSize(500);
            try (ResultSet a = left.executeQuery(); ResultSet b = right.executeQuery()) {
                // Ambos cursores llegan ordenados por clave. Avanzarlos como una
                // fusión evita cargar las tablas completas en memoria.
                boolean hasA = a.next(), hasB = b.next();
                while (hasA || hasB) {
                    Map<String, Object> rowA = hasA ? readRow(a, columns) : null;
                    Map<String, Object> rowB = hasB ? readRow(b, columns) : null;
                    int comparison = !hasA ? 1 : !hasB ? -1 : compareKeys(rowA, rowB, primaryKey);
                    if (comparison < 0) {
                        counts[0]++; addChange(changes, maxChanges, new RowDiff.RowChange(RowDiff.Status.SOURCE_ONLY, keyOf(rowA, primaryKey), rowA, null)); hasA = a.next();
                    } else if (comparison > 0) {
                        counts[1]++; addChange(changes, maxChanges, new RowDiff.RowChange(RowDiff.Status.TARGET_ONLY, keyOf(rowB, primaryKey), null, rowB)); hasB = b.next();
                    } else {
                        counts[3]++;
                        if (!rowsEqual(rowA, rowB, columns)) {
                            counts[2]++; addChange(changes, maxChanges, new RowDiff.RowChange(RowDiff.Status.DIFFERENT, keyOf(rowA, primaryKey), rowA, rowB));
                        }
                        hasA = a.next(); hasB = b.next();
                    }
                }
            }
        }
        truncated = counts[0] + counts[1] + counts[2] > changes.size();
        return new RowDiff.Result(peer.database, key, columns, primaryKey, changes, counts[0], counts[1], counts[2], counts[3], truncated,
                truncated ? "Only the first " + maxChanges + " row changes are retained; export is limited to those rows." : "");
    }

    private List<RowDiff.Column> readColumns(String table) throws SQLException {
        List<RowDiff.Column> columns = new ArrayList<>();
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, CHARACTER_SET_NAME, COLLATION_NAME, DATETIME_PRECISION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, database); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) columns.add(new RowDiff.Column(rs.getString("COLUMN_NAME"), rs.getString("DATA_TYPE").toLowerCase(Locale.ROOT), columnSignature(rs), "YES".equals(rs.getString("IS_NULLABLE"))));
            }
        }
        return columns;
    }

    private List<RowDiff.Column> readPrimaryKey(String table) throws SQLException {
        Map<Integer, RowDiff.Column> ordered = new TreeMap<>();
        String sql = "SELECT c.COLUMN_NAME, c.DATA_TYPE, c.COLUMN_TYPE, c.IS_NULLABLE, c.CHARACTER_SET_NAME, c.COLLATION_NAME, c.DATETIME_PRECISION, s.SEQ_IN_INDEX FROM information_schema.STATISTICS s JOIN information_schema.COLUMNS c ON c.TABLE_SCHEMA=s.TABLE_SCHEMA AND c.TABLE_NAME=s.TABLE_NAME AND c.COLUMN_NAME=s.COLUMN_NAME WHERE s.TABLE_SCHEMA=? AND s.TABLE_NAME=? AND s.INDEX_NAME='PRIMARY' ORDER BY s.SEQ_IN_INDEX";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, database); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ordered.put(rs.getInt("SEQ_IN_INDEX"), new RowDiff.Column(rs.getString("COLUMN_NAME"), rs.getString("DATA_TYPE").toLowerCase(Locale.ROOT), columnSignature(rs), "YES".equals(rs.getString("IS_NULLABLE"))));
            }
        }
        return List.copyOf(ordered.values());
    }

    private static String columnSignature(ResultSet rs) throws SQLException {
        return rs.getString("COLUMN_TYPE") + "|charset=" + String.valueOf(rs.getString("CHARACTER_SET_NAME"))
                + "|collation=" + String.valueOf(rs.getString("COLLATION_NAME"))
                + "|datetime_precision=" + String.valueOf(rs.getString("DATETIME_PRECISION"));
    }

    private static void addChange(List<RowDiff.RowChange> changes, int limit, RowDiff.RowChange change) { if (changes.size() < limit) changes.add(change); }
    private static boolean safeComparable(String type) { return safeKey(type) || Set.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint", "decimal", "numeric", "float", "double", "real", "char", "varchar", "tinytext", "text", "mediumtext", "longtext", "date", "datetime", "timestamp", "time", "year", "enum", "set").contains(type); }
    private static boolean safeKey(String type) { return Set.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint", "decimal", "numeric", "char", "varchar", "date", "datetime", "timestamp", "time", "year").contains(type); }
    private static boolean isText(String type) { return Set.of("char", "varchar").contains(type); }
    private static Map<String, Object> readRow(ResultSet rs, List<RowDiff.Column> columns) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>(); for (int i = 0; i < columns.size(); i++) values.put(columns.get(i).name(), rs.getObject(i + 1)); return values;
    }
    private static Map<String, Object> keyOf(Map<String, Object> row, List<RowDiff.Column> keys) { Map<String, Object> result = new LinkedHashMap<>(); for (RowDiff.Column key : keys) result.put(key.name(), row.get(key.name())); return result; }
    private static int compareKeys(Map<String, Object> a, Map<String, Object> b, List<RowDiff.Column> keys) throws SQLException {
        // La clave compuesta, en el orden de la metadata, determina qué cursor avanza.
        for (RowDiff.Column key : keys) { int c = compareValue(a.get(key.name()), b.get(key.name()), key.type()); if (c != 0) return c; } return 0;
    }
    private static int compareValue(Object a, Object b, String type) throws SQLException {
        if (a == null || b == null) { if (a == b) return 0; throw new SQLException("Primary key unexpectedly contains NULL."); }
        if (Set.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint", "decimal", "numeric").contains(type)) return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()));
        // Para texto, el ORDER BY SQL usa CAST(... AS BINARY); comparar bytes
        // UTF-8 acá mantiene el mismo orden sin depender de la collation.
        if (isText(type)) return compareUtf8(a.toString(), b.toString());
        return canonical(a, type).compareTo(canonical(b, type));
    }
    private static int compareUtf8(String a, String b) {
        byte[] left = a.getBytes(java.nio.charset.StandardCharsets.UTF_8), right = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < Math.min(left.length, right.length); i++) { int c = Integer.compare(Byte.toUnsignedInt(left[i]), Byte.toUnsignedInt(right[i])); if (c != 0) return c; }
        return Integer.compare(left.length, right.length);
    }
    private static String canonical(Object value, String type) { return value instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes) : value.toString(); }
    private static boolean rowsEqual(Map<String, Object> a, Map<String, Object> b, List<RowDiff.Column> columns) {
        // Normalizar valores JDBC evita falsos cambios entre tipos equivalentes
        // como BigDecimal y permite comparar valores binarios.
        for (RowDiff.Column column : columns) if (!Objects.deepEquals(canonicalObject(a.get(column.name())), canonicalObject(b.get(column.name())))) return false; return true;
    }
    private static Object canonicalObject(Object value) {
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof java.math.BigDecimal decimal) return decimal.stripTrailingZeros();
        return value == null ? null : value.toString();
    }

    private SchemaObject loadView(SchemaObject.Key key) throws SQLException {
        try { String definition = showCreate(key, 2); return new SchemaObject(key, definition, signature(definition, false)); }
        catch (SQLException ex) { String definition = "-- " + AppInfo.NAME + ": SHOW CREATE VIEW unavailable (" + safeMessage(ex) + ")"; return new SchemaObject(key, definition, signature(definition, false)); }
    }

    private SchemaObject loadRoutine(SchemaObject.Key key) throws SQLException {
        try { String definition = showCreate(key, 3); return new SchemaObject(key, definition, signature(definition, false)); }
        catch (SQLException ex) { String definition = "-- " + AppInfo.NAME + ": SHOW CREATE " + key.kind() + " unavailable (" + safeMessage(ex) + ")"; return new SchemaObject(key, definition, signature(definition, false)); }
    }

    private SchemaObject loadTrigger(SchemaObject.Key key) throws SQLException {
        try {
            String definition = showCreate(key, 3);
            return new SchemaObject(key, definition, signature(definition, false));
        } catch (SQLException ex) {
            String definition = "-- " + AppInfo.NAME + ": SHOW CREATE TRIGGER unavailable (" + safeMessage(ex) + ")";
            return new SchemaObject(key, definition, signature(definition, false));
        }
    }

    private String showCreate(SchemaObject.Key key, int ddlColumn) throws SQLException {
        String command = switch (key.kind()) {
            case TABLE -> "TABLE"; case VIEW -> "VIEW"; case PROCEDURE -> "PROCEDURE"; case FUNCTION -> "FUNCTION"; case TRIGGER -> "TRIGGER";
        };
        String sql = "SHOW CREATE " + command + " " + quote(database) + "." + quote(key.name());
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) throw new SQLException("No CREATE statement returned; check object visibility and privileges.");
            String ddl = rs.getString(ddlColumn);
            if (ddl == null || ddl.isBlank()) throw new SQLException("Server returned an empty CREATE statement; check privileges and server version.");
            return normalize(ddl);
        }
    }

    @Override public void close() {
        if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
        connection = null; database = null;
    }
    private void requireConnection() throws SQLException { if (connection == null || connection.isClosed()) throw new SQLException("Not connected"); }
    private static String quote(String value) { return "`" + value.replace("`", "``") + "`"; }
    private static String normalize(String value) {
        if (value == null) return "<definition unavailable: insufficient privileges or unsupported object>";
        return value.replace("\r\n", "\n").replace('\r', '\n').lines().map(String::stripTrailing).toList().stream().collect(java.util.stream.Collectors.joining("\n")).strip();
    }

    /**
     * Canonicalizes formatting for equality checks while leaving the displayed
     * SHOW CREATE text intact. Whitespace inside string and quoted identifiers
     * remains significant; table AUTO_INCREMENT counters are volatile metadata.
     */
    private static String signature(String ddl, boolean table) {
        return SqlDefinitionCanonicalizer.signature(ddl, SqlDefinitionCanonicalizer.Dialect.MYSQL, table);
    }
    private static String safeMessage(SQLException ex) { return ex.getMessage() == null ? ex.getSQLState() : ex.getMessage().replaceAll("[\\r\\n]+", " "); }
    private static String encodePath(String path) { return path.replace("%", "%25").replace("/", "%2F").replace("?", "%3F").replace("#", "%23"); }
}
