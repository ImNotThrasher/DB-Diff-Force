package studio.dbdiff;

/** Shared Spanish messages for row-comparison limitations across JDBC connectors. */
final class RowComparisonMessages {
    private RowComparisonMessages() { }

    static String matchingEnginesRequired() {
        return "La comparación de filas requiere conexiones al mismo motor de base de datos.";
    }

    static String sqlServerConnectionsRequired() {
        return "La comparación de filas requiere dos conexiones a SQL Server.";
    }

    static String tablesOnly() {
        return "La comparación de filas solo está disponible para tablas.";
    }

    static String noVisibleColumns() {
        return "La tabla no tiene columnas visibles o falta el permiso SELECT.";
    }

    static String columnsOrPrimaryKeyDiffer() {
        return "Se omitió la comparación: las columnas o la clave primaria difieren entre las bases. Compará primero el esquema de la tabla.";
    }

    static String incompatibleColumns() {
        return "Se omitió la comparación: las columnas tienen nombres, orden o tipos de datos incompatibles entre las bases.";
    }

    static String incompatiblePrimaryKey() {
        return "Se omitió la comparación: las columnas de la clave primaria o sus tipos de orden difieren entre las bases.";
    }

    static String primaryKeyRequired() {
        return "Se omitió la comparación: la tabla no tiene clave primaria y no se pueden emparejar sus filas de forma segura.";
    }

    static String unsupportedColumnType(String column, String type) {
        return "Se omitió la comparación: la columna " + column + " tiene un tipo de datos no compatible: " + type + ".";
    }

    static String unsupportedPrimaryKeyType(String column, String type) {
        return "Se omitió la comparación: la columna " + column + " de la clave primaria tiene un tipo no compatible para ordenar: " + type + ".";
    }

    static String truncated(int maxChanges) {
        return "Solo se conservaron los primeros " + maxChanges + " cambios de fila; la exportación se limita a esos registros.";
    }

    static String rowCountUnavailable(String table) {
        return "No se pudo contar las filas de " + table + ".";
    }

    static String primaryKeyContainsNull() {
        return "La clave primaria contiene un valor NULL inesperado.";
    }

    static String notConnected() {
        return "No hay una conexión activa a la base de datos.";
    }
}
