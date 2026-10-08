package studio.dbdiff;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** JDBC connector for SQL Server catalogs, object definitions, and primary-key row diffs. */
public final class SqlServerConnector implements DatabaseConnector {
    private Connection connection;
    private String database;

    @Override public void connect(Endpoint endpoint) throws SQLException {
        close();
        database = endpoint.database().isBlank() ? "master" : endpoint.database();
        Properties properties = new Properties();
        properties.setProperty("databaseName", database);
        properties.setProperty("user", endpoint.user());
        properties.setProperty("encrypt", Boolean.toString(endpoint.useTls()));
        properties.setProperty("trustServerCertificate", "false");
        properties.setProperty("loginTimeout", "8");
        properties.setProperty("socketTimeout", "120000");
        char[] secret = endpoint.password();
        try {
            properties.setProperty("password", new String(secret));
            connection = DriverManager.getConnection("jdbc:sqlserver://" + endpoint.host() + ":" + endpoint.port() + ";", properties);
        } finally {
            java.util.Arrays.fill(secret, '\0');
            properties.remove("password");
        }
    }

    @Override public List<String> listDatabases() throws SQLException {
        requireConnection();
        List<String> result = new ArrayList<>();
        String sql = "SELECT name FROM sys.databases WHERE database_id > 4 AND state_desc='ONLINE' AND HAS_DBACCESS(name)=1 ORDER BY name";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return List.copyOf(result);
    }

    @Override public List<SchemaObject> listObjects() throws SQLException {
        requireConnection();
        List<SchemaObject> result = new ArrayList<>();
        String sql = "SELECT s.name, o.name, o.type FROM sys.objects o JOIN sys.schemas s ON s.schema_id=o.schema_id "
                + "WHERE o.is_ms_shipped=0 AND o.type IN ('U','V','P','FN','IF','TF','FS','FT','TR') ORDER BY s.name,o.name";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                SchemaObject.Kind kind = kind(rows.getString(3));
                if (kind != null) result.add(new SchemaObject(new SchemaObject.Key(kind, rows.getString(2), rows.getString(1)), "", ""));
            }
        }
        result.sort(Comparator.comparing(SchemaObject::key));
        return List.copyOf(result);
    }

    @Override public SchemaObject loadObject(SchemaObject.Key key) throws SQLException {
        requireConnection();
        return switch (key.kind()) {
            case TABLE -> loadTable(key);
            case VIEW, PROCEDURE, FUNCTION, TRIGGER -> loadDefinition(key);
        };
    }

    /** Reads foreign-key and SQL-expression references for dependency-aware exports. */
    @Override public Map<SchemaObject.Key, Set<SchemaObject.Key>> dependencies() throws SQLException {
        return dependencyReport().dependencies();
    }

    /** Makes SQL Server's catalog visibility and unresolved-reference limits visible to export review. */
    @Override public DependencyReport dependencyReport() throws SQLException {
        requireConnection();
        Map<SchemaObject.Key, Set<SchemaObject.Key>> result = new java.util.HashMap<>();
        List<DependencyReport.Coverage> coverage = new java.util.ArrayList<>();
        List<String> limitations = new java.util.ArrayList<>();
        String foreignKeys = "SELECT ps.name, pt.name, rs.name, rt.name FROM sys.foreign_keys fk " +
                "JOIN sys.tables pt ON pt.object_id=fk.parent_object_id JOIN sys.schemas ps ON ps.schema_id=pt.schema_id " +
                "JOIN sys.tables rt ON rt.object_id=fk.referenced_object_id JOIN sys.schemas rs ON rs.schema_id=rt.schema_id";
        int previousEdges = countEdges(result);
        try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery(foreignKeys)) {
            while (rows.next()) addDependency(result,
                    new SchemaObject.Key(SchemaObject.Kind.TABLE, rows.getString(2), rows.getString(1)),
                    new SchemaObject.Key(SchemaObject.Kind.TABLE, rows.getString(4), rows.getString(3)));
            coverage.add(new DependencyReport.Coverage("claves foráneas", countEdges(result) - previousEdges, ""));
        } catch (SQLException ex) {
            coverage.add(new DependencyReport.Coverage("claves foráneas", 0, "No disponible"));
            limitations.add("No se pudieron leer las claves foráneas: " + metadataError(ex));
        }
        String triggerParents = "SELECT ts.name, tr.name, ps.name, parent.name FROM sys.triggers tr " +
                "JOIN sys.objects parent ON parent.object_id=tr.parent_id JOIN sys.schemas ps ON ps.schema_id=parent.schema_id " +
                "JOIN sys.objects triggerObject ON triggerObject.object_id=tr.object_id JOIN sys.schemas ts ON ts.schema_id=triggerObject.schema_id " +
                "WHERE tr.parent_class=1 AND tr.is_ms_shipped=0";
        previousEdges = countEdges(result);
        try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery(triggerParents)) {
            while (rows.next()) addDependency(result,
                    new SchemaObject.Key(SchemaObject.Kind.TRIGGER, rows.getString(2), rows.getString(1)),
                    new SchemaObject.Key(SchemaObject.Kind.TABLE, rows.getString(4), rows.getString(3)));
            coverage.add(new DependencyReport.Coverage("triggers", countEdges(result) - previousEdges, ""));
        } catch (SQLException ex) {
            coverage.add(new DependencyReport.Coverage("triggers", 0, "No disponible"));
            limitations.add("No se pudieron leer las dependencias de triggers: " + metadataError(ex));
        }
        String references = "SELECT ds.name, d.name, d.type, rs.name, r.name, r.type, e.referenced_server_name, " +
                "e.referenced_database_name, e.referenced_schema_name, e.referenced_entity_name, e.referenced_id FROM sys.sql_expression_dependencies e " +
                "JOIN sys.objects d ON d.object_id=e.referencing_id JOIN sys.schemas ds ON ds.schema_id=d.schema_id " +
                "LEFT JOIN sys.objects r ON r.object_id=e.referenced_id LEFT JOIN sys.schemas rs ON rs.schema_id=r.schema_id " +
                "WHERE d.is_ms_shipped=0 AND (r.object_id IS NULL OR r.is_ms_shipped=0)";
        previousEdges = countEdges(result);
        try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery(references)) {
            int unresolved = 0, unsupported = 0;
            while (rows.next()) {
                SchemaObject.Kind dependentKind = kind(rows.getString(3));
                SchemaObject.Kind dependencyKind = kind(rows.getString(6));
                if (rows.getObject(11) == null) {
                    unresolved++;
                } else if (dependentKind == null || dependencyKind == null) {
                    unsupported++;
                } else {
                    addDependency(result,
                            new SchemaObject.Key(dependentKind, rows.getString(2), rows.getString(1)),
                            new SchemaObject.Key(dependencyKind, rows.getString(5), rows.getString(4)));
                }
            }
            coverage.add(new DependencyReport.Coverage("referencias SQL visibles", countEdges(result) - previousEdges, ""));
            if (unresolved > 0) {
                coverage.add(new DependencyReport.Coverage("referencias no resueltas localmente", unresolved, "No se usan para ordenar"));
                limitations.add("SQL Server registró " + unresolved + " referencias sin object_id local (por ejemplo, referencias entre bases o servidores); no se usan para ordenar la exportación.");
            }
            if (unsupported > 0) {
                coverage.add(new DependencyReport.Coverage("referencias a tipos no modelados", unsupported, "No se usan para ordenar"));
                limitations.add("Se omitieron " + unsupported + " referencias SQL cuyo tipo de objeto no está modelado por el exportador.");
            }
        } catch (SQLException ex) {
            coverage.add(new DependencyReport.Coverage("referencias SQL visibles", 0, "No disponible"));
            limitations.add("No se pudieron leer sys.sql_expression_dependencies: " + metadataError(ex));
        }
        limitations.add("SQL Server limita los metadatos a los objetos visibles para el usuario. Leer todas las dependencias requiere VIEW DEFINITION y SELECT sobre sys.sql_expression_dependencies; las referencias no resueltas se reportan aparte.");
        Map<SchemaObject.Key, Set<SchemaObject.Key>> immutable = new java.util.HashMap<>();
        result.forEach((key, value) -> immutable.put(key, Set.copyOf(value)));
        return new DependencyReport(immutable, coverage, limitations);
    }

    private static int countEdges(Map<SchemaObject.Key, Set<SchemaObject.Key>> dependencies) {
        return dependencies.values().stream().mapToInt(Set::size).sum();
    }

    private static String metadataError(SQLException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "error SQL " + error.getSQLState() : message.replaceAll("[\\r\\n]+", " ");
    }

    private static void addDependency(Map<SchemaObject.Key, Set<SchemaObject.Key>> dependencies,
                                      SchemaObject.Key object, SchemaObject.Key prerequisite) {
        dependencies.computeIfAbsent(object, ignored -> new java.util.TreeSet<>()).add(prerequisite);
    }

    private SchemaObject loadDefinition(SchemaObject.Key key) throws SQLException {
        String sql = "SELECT OBJECT_DEFINITION(o.object_id) FROM sys.objects o JOIN sys.schemas s ON s.schema_id=o.schema_id "
                + "WHERE s.name=? AND o.name=?";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, key.schema()); query.setString(2, key.name());
            try (ResultSet rows = query.executeQuery()) {
                String ddl = rows.next() ? rows.getString(1) : null;
                if (ddl == null || ddl.isBlank()) ddl = "-- " + AppInfo.NAME + ": SQL Server definition unavailable (object is encrypted, hidden, or permission is missing).";
                ddl = normalizeLines(ddl);
                return new SchemaObject(key, ddl, canonicalSql(ddl));
            }
        }
    }

    /** Build CREATE TABLE text from SQL Server catalog rows; unsupported index features remain explicit. */
    private SchemaObject loadTable(SchemaObject.Key key) throws SQLException {
        List<String> definitions = new ArrayList<>();
        List<String> columns = readTableColumns(key, definitions);
        if (columns.isEmpty()) return unavailableTable(key, "table is hidden or metadata permissions are missing");
        definitions.addAll(readKeyConstraints(key));
        definitions.addAll(readForeignKeys(key));
        List<String> checks = readCheckConstraints(key);
        boolean hasUnsupportedChecks = checks.stream().anyMatch(value -> value.startsWith("-- " + AppInfo.NAME + ":"));
        definitions.addAll(checks);
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(qualified(key)).append(" (\n  ")
                .append(String.join(",\n  ", definitions)).append("\n);\n");
        List<String> indexStatements = readIndexes(key);
        indexStatements.forEach(index -> ddl.append(index).append("\n"));
        List<String> unsupported = readUnsupportedIndexes(key);
        if (hasUnsupportedChecks) unsupported.add("one or more disabled or untrusted check constraints");
        if (!unsupported.isEmpty()) {
            ddl.insert(0, "-- " + AppInfo.NAME + ": SQL Server table definition needs manual review for advanced indexes.\n");
            unsupported.forEach(item -> ddl.append("-- REVIEW ONLY: ").append(item).append('\n'));
        }
        String text = normalizeLines(ddl.toString());
        return new SchemaObject(key, text, canonicalSql(text));
    }

    private List<String> readTableColumns(SchemaObject.Key key, List<String> definitions) throws SQLException {
        List<String> result = new ArrayList<>();
        String sql = "SELECT c.name,t.name AS type_name,SCHEMA_NAME(t.schema_id) AS type_schema,t.is_user_defined,"
                + "c.max_length,c.precision,c.scale,c.is_nullable,c.is_identity,CONVERT(varchar(100),ic.seed_value),"
                + "CONVERT(varchar(100),ic.increment_value),c.is_rowguidcol,c.is_sparse,c.collation_name,"
                + "dc.name,dc.definition,cc.definition,cc.is_persisted "
                + "FROM sys.tables tb JOIN sys.schemas s ON s.schema_id=tb.schema_id JOIN sys.columns c ON c.object_id=tb.object_id "
                + "JOIN sys.types t ON t.user_type_id=c.user_type_id LEFT JOIN sys.identity_columns ic ON ic.object_id=c.object_id AND ic.column_id=c.column_id "
                + "LEFT JOIN sys.default_constraints dc ON dc.parent_object_id=c.object_id AND dc.parent_column_id=c.column_id "
                + "LEFT JOIN sys.computed_columns cc ON cc.object_id=c.object_id AND cc.column_id=c.column_id "
                + "WHERE s.name=? AND tb.name=? ORDER BY c.column_id";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, key.schema()); query.setString(2, key.name());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1), type = rows.getString(2), typeSchema = rows.getString(3);
                    boolean userType = rows.getBoolean(4);
                    String declaration = typeDeclaration(type, rows.getInt(5), rows.getInt(6), rows.getInt(7), userType, typeSchema);
                    String computed = rows.getString(17);
                    StringBuilder column = new StringBuilder(quote(name)).append(' ');
                    if (computed != null) {
                        column.append("AS ").append(computed);
                        if (rows.getBoolean(18)) column.append(" PERSISTED");
                    } else {
                        column.append(declaration);
                        if (rows.getBoolean(8)) column.append(" NULL"); else column.append(" NOT NULL");
                        if (rows.getBoolean(9)) {
                            String seed = rows.getString(10), increment = rows.getString(11);
                            column.append(" IDENTITY(").append(seed == null ? "1" : seed).append(',').append(increment == null ? "1" : increment).append(')');
                        }
                        if (rows.getBoolean(12)) column.append(" ROWGUIDCOL");
                        if (rows.getBoolean(13)) column.append(" SPARSE");
                        String collation = rows.getString(14);
                        if (collation != null && isCharacterType(type)) column.append(" COLLATE ").append(quote(collation));
                        String defaultName = rows.getString(15), defaultDefinition = rows.getString(16);
                        if (defaultDefinition != null) column.append(" CONSTRAINT ").append(quote(defaultName)).append(" DEFAULT ").append(defaultDefinition);
                    }
                    result.add(column.toString());
                }
            }
        }
        return result;
    }

    private List<String> readKeyConstraints(SchemaObject.Key key) throws SQLException {
        Map<String, List<String>> columnsByKey = new LinkedHashMap<>();
        Map<String, String> clauses = new LinkedHashMap<>();
        String sql = "SELECT kc.name,kc.type,i.type_desc,c.name,ic.is_descending_key FROM sys.key_constraints kc "
                + "JOIN sys.indexes i ON i.object_id=kc.parent_object_id AND i.index_id=kc.unique_index_id "
                + "JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id AND ic.key_ordinal>0 "
                + "JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id "
                + "JOIN sys.tables t ON t.object_id=kc.parent_object_id JOIN sys.schemas s ON s.schema_id=t.schema_id "
                + "WHERE s.name=? AND t.name=? ORDER BY kc.name,ic.key_ordinal";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, key.schema()); query.setString(2, key.name());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1), constraintType = rows.getString(2), indexType = rows.getString(3);
                    String prefix = "CONSTRAINT " + quote(name) + ("PK".equals(constraintType) ? " PRIMARY KEY " : " UNIQUE ")
                            + ("CLUSTERED".equals(indexType) ? "CLUSTERED" : "NONCLUSTERED");
                    clauses.put(name, prefix);
                    columnsByKey.computeIfAbsent(name, ignored -> new ArrayList<>()).add(quote(rows.getString(4)) + (rows.getBoolean(5) ? " DESC" : " ASC"));
                }
            }
        }
        List<String> result = new ArrayList<>();
        for (String name : clauses.keySet()) result.add(clauses.get(name) + " (" + String.join(", ", columnsByKey.get(name)) + ")");
        return result;
    }

    private List<String> readForeignKeys(SchemaObject.Key key) throws SQLException {
        Map<String, ForeignKeyParts> constraints = new LinkedHashMap<>();
        String sql = "SELECT fk.name,pc.name,rs.name,rt.name,rc.name,fk.delete_referential_action_desc,fk.update_referential_action_desc "
                + "FROM sys.foreign_keys fk JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id=fk.object_id "
                + "JOIN sys.tables pt ON pt.object_id=fk.parent_object_id JOIN sys.schemas ps ON ps.schema_id=pt.schema_id "
                + "JOIN sys.columns pc ON pc.object_id=pt.object_id AND pc.column_id=fkc.parent_column_id "
                + "JOIN sys.tables rt ON rt.object_id=fk.referenced_object_id JOIN sys.schemas rs ON rs.schema_id=rt.schema_id "
                + "JOIN sys.columns rc ON rc.object_id=rt.object_id AND rc.column_id=fkc.referenced_column_id "
                + "WHERE ps.name=? AND pt.name=? ORDER BY fk.name,fkc.constraint_column_id";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, key.schema()); query.setString(2, key.name());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    ForeignKeyParts parts = constraints.get(name);
                    if (parts == null) {
                        parts = new ForeignKeyParts(rows.getString(3), rows.getString(4), rows.getString(6), rows.getString(7));
                        constraints.put(name, parts);
                    }
                    parts.source.add(quote(rows.getString(2)));
                    parts.target.add(quote(rows.getString(5)));
                }
            }
        }
        List<String> result = new ArrayList<>();
        constraints.forEach((name, fk) -> {
            String clause = "CONSTRAINT " + quote(name) + " FOREIGN KEY (" + String.join(", ", fk.source) + ") REFERENCES "
                    + qualified(fk.schema, fk.table) + " (" + String.join(", ", fk.target) + ")";
            if (!"NO_ACTION".equals(fk.deleteAction)) clause += " ON DELETE " + fk.deleteAction.replace('_', ' ');
            if (!"NO_ACTION".equals(fk.updateAction)) clause += " ON UPDATE " + fk.updateAction.replace('_', ' ');
            result.add(clause);
        });
        return result;
    }

    private List<String> readCheckConstraints(SchemaObject.Key key) throws SQLException {
        List<String> result = new ArrayList<>();
        String sql = "SELECT cc.name,cc.definition,cc.is_disabled,cc.is_not_trusted FROM sys.check_constraints cc "
                + "JOIN sys.tables t ON t.object_id=cc.parent_object_id JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=? AND t.name=? ORDER BY cc.name";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, key.schema()); query.setString(2, key.name());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    if (rows.getBoolean(3) || rows.getBoolean(4)) {
                        result.add("-- " + AppInfo.NAME + ": SQL Server table definition needs manual review (check constraint "
                                + quote(rows.getString(1)) + " is disabled or untrusted).");
                    } else result.add("CONSTRAINT " + quote(rows.getString(1)) + " CHECK " + rows.getString(2));
                }
            }
        }
        return result;
    }

    private List<String> readIndexes(SchemaObject.Key key) throws SQLException {
        Map<Integer, IndexParts> indexes = new LinkedHashMap<>();
        String sql = "SELECT i.index_id,i.name,i.is_unique,i.type_desc,i.filter_definition,ic.key_ordinal,ic.is_included_column,"
                + "c.name,ic.is_descending_key,i.is_disabled FROM sys.indexes i JOIN sys.tables t ON t.object_id=i.object_id "
                + "JOIN sys.schemas s ON s.schema_id=t.schema_id LEFT JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id "
                + "LEFT JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id "
                + "WHERE s.name=? AND t.name=? AND i.index_id>0 AND i.is_primary_key=0 AND i.is_unique_constraint=0 AND i.name IS NOT NULL "
                + "AND i.type IN (1,2) ORDER BY i.index_id,ic.is_included_column,ic.key_ordinal,ic.index_column_id";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, key.schema()); query.setString(2, key.name());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    int id = rows.getInt(1);
                    IndexParts index = indexes.get(id);
                    if (index == null) {
                        index = new IndexParts(rows.getString(2), rows.getBoolean(3), rows.getString(4), rows.getString(5), rows.getBoolean(10));
                        indexes.put(id, index);
                    }
                    String column = rows.getString(8);
                    if (column != null && rows.getBoolean(7)) index.include.add(quote(column));
                    else if (column != null && rows.getInt(6) > 0) index.keys.add(quote(column) + (rows.getBoolean(9) ? " DESC" : " ASC"));
                }
            }
        }
        List<String> result = new ArrayList<>();
        for (IndexParts index : indexes.values()) {
            if (index.disabled) continue;
            StringBuilder sqlIndex = new StringBuilder("CREATE ");
            if (index.unique) sqlIndex.append("UNIQUE ");
            sqlIndex.append("CLUSTERED".equals(index.type) ? "CLUSTERED " : "NONCLUSTERED ")
                    .append("INDEX ").append(quote(index.name)).append(" ON ").append(qualified(key))
                    .append(" (").append(String.join(", ", index.keys)).append(')');
            if (!index.include.isEmpty()) sqlIndex.append(" INCLUDE (").append(String.join(", ", index.include)).append(')');
            if (index.filter != null && !index.filter.isBlank()) sqlIndex.append(" WHERE ").append(index.filter);
            result.add(sqlIndex.append(';').toString());
        }
        return result;
    }

    private List<String> readUnsupportedIndexes(SchemaObject.Key key) throws SQLException {
        List<String> result = new ArrayList<>();
        String sql = "SELECT i.name,i.type_desc,i.is_disabled FROM sys.indexes i JOIN sys.tables t ON t.object_id=i.object_id "
                + "JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=? AND t.name=? AND i.index_id>0 "
                + "AND (i.type NOT IN (1,2) OR i.is_disabled=1) AND i.name IS NOT NULL ORDER BY i.name";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, key.schema()); query.setString(2, key.name());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add("index " + rows.getString(1) + " (" + rows.getString(2)
                        + (rows.getBoolean(3) ? ", disabled" : "") + ") cannot be scripted automatically");
            }
        }
        return result;
    }

    @Override public RowDiff.Result compareTableRows(DatabaseConnector peerConnector, SchemaObject.Key key, int maxChanges) throws SQLException {
        requireConnection();
        if (!(peerConnector instanceof SqlServerConnector peer)) throw new SQLException("Row comparison requires two SQL Server connections.");
        peer.requireConnection();
        if (key.kind() != SchemaObject.Kind.TABLE) throw new SQLException("Row comparison is available for tables only.");
        if (maxChanges < 1) throw new IllegalArgumentException("maxChanges must be positive");
        List<RowDiff.Column> columns = readColumns(key), peerColumns = peer.readColumns(key);
        List<RowDiff.Column> primaryKey = readPrimaryKey(key), peerPrimaryKey = peer.readPrimaryKey(key);
        if (columns.isEmpty()) throw new SQLException("Table has no visible columns or SELECT permission is missing.");
        if (!columns.equals(peerColumns) || !primaryKey.equals(peerPrimaryKey)) throw new SQLException("Skipped: table columns or primary key differ between endpoints. Compare table schema first.");
        if (primaryKey.isEmpty()) throw new SQLException("Skipped: table has no primary key; rows cannot be matched safely.");
        for (RowDiff.Column column : columns) if (!safeComparable(column.type()))
            throw new SQLException("Skipped: column " + column.name() + " has unsupported comparison type " + column.type() + ".");
        for (RowDiff.Column column : primaryKey) if (!safeKey(column.type()))
            throw new SQLException("Skipped: primary key column " + column.name() + " has unsupported ordering type " + column.type() + ".");

        String selectedColumns = columns.stream().map(column -> quote(column.name())).collect(java.util.stream.Collectors.joining(", "));
        String orderBy = primaryKey.stream().map(column -> isText(column.type())
                ? "CONVERT(nvarchar(4000)," + quote(column.name()) + ") COLLATE Latin1_General_100_BIN2" : quote(column.name()))
                .collect(java.util.stream.Collectors.joining(", "));
        String sourceQuery = "SELECT " + selectedColumns + " FROM " + qualified(database, key) + " ORDER BY " + orderBy;
        String targetQuery = "SELECT " + selectedColumns + " FROM " + qualified(peer.database, key) + " ORDER BY " + orderBy;
        List<RowDiff.RowChange> changes = new ArrayList<>(); long[] counts = new long[4];
        try (PreparedStatement left = connection.prepareStatement(sourceQuery, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement right = peer.connection.prepareStatement(targetQuery, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            left.setFetchSize(500); right.setFetchSize(500);
            try (ResultSet sourceRows = left.executeQuery(); ResultSet targetRows = right.executeQuery()) {
                boolean hasSource = sourceRows.next(), hasTarget = targetRows.next();
                while (hasSource || hasTarget) {
                    Map<String, Object> sourceRow = hasSource ? readRow(sourceRows, columns) : null;
                    Map<String, Object> targetRow = hasTarget ? readRow(targetRows, columns) : null;
                    int comparison = !hasSource ? 1 : !hasTarget ? -1 : compareKeys(sourceRow, targetRow, primaryKey);
                    if (comparison < 0) {
                        counts[0]++; retain(changes, maxChanges, new RowDiff.RowChange(RowDiff.Status.SOURCE_ONLY, keyOf(sourceRow, primaryKey), sourceRow, null)); hasSource = sourceRows.next();
                    } else if (comparison > 0) {
                        counts[1]++; retain(changes, maxChanges, new RowDiff.RowChange(RowDiff.Status.TARGET_ONLY, keyOf(targetRow, primaryKey), null, targetRow)); hasTarget = targetRows.next();
                    } else {
                        counts[3]++;
                        if (!rowsEqual(sourceRow, targetRow, columns)) {
                            counts[2]++; retain(changes, maxChanges, new RowDiff.RowChange(RowDiff.Status.DIFFERENT, keyOf(sourceRow, primaryKey), sourceRow, targetRow));
                        }
                        hasSource = sourceRows.next(); hasTarget = targetRows.next();
                    }
                }
            }
        }
        boolean truncated = counts[0] + counts[1] + counts[2] > changes.size();
        return new RowDiff.Result(peer.database, key, columns, primaryKey, changes, counts[0], counts[1], counts[2], counts[3], truncated,
                truncated ? "Only the first " + maxChanges + " row changes are retained; export is limited to those rows." : "");
    }

    private List<RowDiff.Column> readColumns(SchemaObject.Key key) throws SQLException {
        List<RowDiff.Column> result = new ArrayList<>();
        String sql = "SELECT c.name,t.name,c.max_length,c.precision,c.scale,c.is_nullable,t.is_user_defined,SCHEMA_NAME(t.schema_id),c.collation_name,c.is_identity,c.is_computed "
                + "FROM sys.tables tb JOIN sys.schemas s ON s.schema_id=tb.schema_id JOIN sys.columns c ON c.object_id=tb.object_id "
                + "JOIN sys.types t ON t.user_type_id=c.user_type_id WHERE s.name=? AND tb.name=? ORDER BY c.column_id";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, key.schema()); query.setString(2, key.name());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String type = rows.getString(2).toLowerCase(Locale.ROOT);
                    String signature = type + "|length=" + rows.getInt(3) + "|precision=" + rows.getInt(4) + "|scale=" + rows.getInt(5)
                            + "|userType=" + rows.getBoolean(7) + "|typeSchema=" + rows.getString(8) + "|collation=" + rows.getString(9)
                            + "|identity=" + rows.getBoolean(10) + "|computed=" + rows.getBoolean(11);
                    result.add(new RowDiff.Column(rows.getString(1), type, signature, rows.getBoolean(6)));
                }
            }
        }
        return List.copyOf(result);
    }

    private List<RowDiff.Column> readPrimaryKey(SchemaObject.Key key) throws SQLException {
        List<RowDiff.Column> result = new ArrayList<>();
        String sql = "SELECT c.name,t.name,c.max_length,c.precision,c.scale,c.is_nullable,t.is_user_defined,SCHEMA_NAME(t.schema_id),c.collation_name,c.is_identity,c.is_computed "
                + "FROM sys.indexes i JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id AND ic.key_ordinal>0 "
                + "JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id JOIN sys.types t ON t.user_type_id=c.user_type_id "
                + "JOIN sys.tables tb ON tb.object_id=i.object_id JOIN sys.schemas s ON s.schema_id=tb.schema_id "
                + "WHERE i.is_primary_key=1 AND s.name=? AND tb.name=? ORDER BY ic.key_ordinal";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, key.schema()); query.setString(2, key.name());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String type = rows.getString(2).toLowerCase(Locale.ROOT);
                    String signature = type + "|length=" + rows.getInt(3) + "|precision=" + rows.getInt(4) + "|scale=" + rows.getInt(5)
                            + "|userType=" + rows.getBoolean(7) + "|typeSchema=" + rows.getString(8) + "|collation=" + rows.getString(9)
                            + "|identity=" + rows.getBoolean(10) + "|computed=" + rows.getBoolean(11);
                    result.add(new RowDiff.Column(rows.getString(1), type, signature, rows.getBoolean(6)));
                }
            }
        }
        return List.copyOf(result);
    }

    private static int compareKeys(Map<String, Object> left, Map<String, Object> right, List<RowDiff.Column> keys) throws SQLException {
        for (RowDiff.Column key : keys) {
            Object a = left.get(key.name()), b = right.get(key.name());
            int comparison;
            if (a == null || b == null) { if (a == b) comparison = 0; else throw new SQLException("Primary key unexpectedly contains NULL."); }
            else if (isNumeric(key.type())) comparison = new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
            else if (isText(key.type())) comparison = a.toString().compareTo(b.toString());
            else comparison = a.toString().compareTo(b.toString());
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static boolean rowsEqual(Map<String, Object> left, Map<String, Object> right, List<RowDiff.Column> columns) {
        for (RowDiff.Column column : columns) if (!Objects.deepEquals(canonical(left.get(column.name())), canonical(right.get(column.name())))) return false;
        return true;
    }

    private static Object canonical(Object value) {
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros();
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> readRow(ResultSet rows, List<RowDiff.Column> columns) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) result.put(columns.get(i).name(), rows.getObject(i + 1));
        return result;
    }

    private static Map<String, Object> keyOf(Map<String, Object> row, List<RowDiff.Column> keys) {
        Map<String, Object> result = new LinkedHashMap<>(); keys.forEach(key -> result.put(key.name(), row.get(key.name()))); return result;
    }

    private static boolean safeComparable(String type) {
        return safeKey(type) || Set.of("bit", "money", "smallmoney", "float", "real", "uniqueidentifier", "binary", "varbinary").contains(type);
    }

    private static boolean safeKey(String type) {
        return isNumeric(type) || isText(type) || Set.of("date", "datetime", "datetime2", "smalldatetime", "time", "bit").contains(type);
    }

    private static boolean isNumeric(String type) {
        return Set.of("tinyint", "smallint", "int", "bigint", "decimal", "numeric", "money", "smallmoney", "float", "real").contains(type);
    }

    private static boolean isText(String type) { return Set.of("char", "varchar", "nchar", "nvarchar").contains(type); }

    private static void retain(List<RowDiff.RowChange> changes, int limit, RowDiff.RowChange change) { if (changes.size() < limit) changes.add(change); }

    private static String typeDeclaration(String type, int length, int precision, int scale, boolean userDefined, String schema) {
        if (userDefined) return quote(schema) + "." + quote(type);
        String lower = type.toLowerCase(Locale.ROOT);
        if (Set.of("varchar", "char", "varbinary", "binary").contains(lower)) return type + "(" + (length < 0 ? "max" : length) + ")";
        if (Set.of("nvarchar", "nchar").contains(lower)) return type + "(" + (length < 0 ? "max" : length / 2) + ")";
        if (Set.of("decimal", "numeric").contains(lower)) return type + "(" + precision + "," + scale + ")";
        if (Set.of("datetime2", "datetimeoffset", "time").contains(lower)) return type + "(" + scale + ")";
        if ("float".equals(lower)) return type + "(" + precision + ")";
        return type;
    }

    private static boolean isCharacterType(String type) { return Set.of("char", "varchar", "nchar", "nvarchar", "text", "ntext").contains(type.toLowerCase(Locale.ROOT)); }

    private static SchemaObject.Kind kind(String type) {
        // LEFT JOINs against sys.objects produce a null type for unresolved
        // references (for example, objects in another database/server).
        if (type == null) return null;
        return switch (type) { case "U" -> SchemaObject.Kind.TABLE; case "V" -> SchemaObject.Kind.VIEW; case "P" -> SchemaObject.Kind.PROCEDURE; case "FN", "IF", "TF", "FS", "FT" -> SchemaObject.Kind.FUNCTION; case "TR" -> SchemaObject.Kind.TRIGGER; default -> null; };
    }

    private SchemaObject unavailableTable(SchemaObject.Key key, String reason) {
        String message = "-- " + AppInfo.NAME + ": SQL Server table definition unavailable (" + reason + ")";
        return new SchemaObject(key, message, canonicalSql(message));
    }

    private static String normalizeLines(String text) { return text.replace("\r\n", "\n").replace('\r', '\n').lines().map(String::stripTrailing).collect(java.util.stream.Collectors.joining("\n")).strip(); }
    private static String canonicalSql(String text) {
        return SqlDefinitionCanonicalizer.signature(text, SqlDefinitionCanonicalizer.Dialect.SQL_SERVER, false);
    }
    private static String qualified(SchemaObject.Key key) { return qualified(key.schema(), key.name()); }
    private static String qualified(String schema, String name) { return quote(schema) + "." + quote(name); }
    private static String qualified(String database, SchemaObject.Key key) { return quote(database) + "." + qualified(key); }
    private static String quote(String identifier) { return "[" + identifier.replace("]", "]]" ) + "]"; }

    private static final class ForeignKeyParts {
        final String schema, table, deleteAction, updateAction;
        final List<String> source = new ArrayList<>(), target = new ArrayList<>();
        ForeignKeyParts(String schema, String table, String deleteAction, String updateAction) { this.schema = schema; this.table = table; this.deleteAction = deleteAction; this.updateAction = updateAction; }
    }

    private static final class IndexParts {
        final String name, type, filter; final boolean unique, disabled;
        final List<String> keys = new ArrayList<>(), include = new ArrayList<>();
        IndexParts(String name, boolean unique, String type, String filter, boolean disabled) { this.name = name; this.unique = unique; this.type = type; this.filter = filter; this.disabled = disabled; }
    }

    private void requireConnection() throws SQLException { if (connection == null || connection.isClosed()) throw new SQLException("Not connected"); }
    @Override public void close() { if (connection != null) try { connection.close(); } catch (SQLException ignored) { } connection = null; database = null; }
}
