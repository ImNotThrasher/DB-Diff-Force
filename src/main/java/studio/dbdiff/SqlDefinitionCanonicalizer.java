package studio.dbdiff;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Creates comparison-only SQL signatures while keeping source DDL untouched. */
final class SqlDefinitionCanonicalizer {
    enum Dialect { MYSQL, SQL_SERVER }

    private static final List<String> OPERATORS = List.of(
            "->>", "<=>", "!<", "!>", "::", ":=", "->", "<=", ">=", "<>", "!=", "||", "&&",
            "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^="
    );
    private static final Set<String> MYSQL_KEYWORDS = Set.of(
            "add", "after", "algorithm", "all", "alter", "and", "as", "asc", "auto_increment", "begin", "between",
            "bigint", "binary", "bit", "blob", "bool", "boolean", "by", "call", "case", "cascade", "char", "character",
            "charset", "check", "collate", "column", "comment", "condition", "constraint", "contains", "continue", "create",
            "cross", "current_date", "current_time", "current_timestamp", "data", "date", "datetime", "decimal", "declare",
            "default", "definer", "delete", "delimiter", "desc", "deterministic", "distinct", "do", "double", "drop", "else",
            "elseif", "end", "engine", "enum", "escape", "exists", "false", "float", "for", "foreign", "from", "fulltext",
            "function", "generated", "group", "having", "if", "in", "index", "inner", "insert", "int", "integer", "intersect",
            "into", "is", "iterate", "join", "json", "key", "language", "leave", "left", "like", "limit", "loop", "longblob",
            "longtext", "match", "mediumblob", "mediumint", "mediumtext", "modifies", "not", "null", "numeric", "on", "or", "order",
            "out", "outer", "primary", "procedure", "range", "read", "reads", "real", "references", "release", "rename", "repeat",
            "replace", "returns", "right", "rollback", "row_format", "schema", "security", "select", "set", "signal", "smallint",
            "spatial", "sql", "sqlstate", "sqlwarning", "start", "stored", "table", "then", "time", "timestamp", "tinyblob",
            "tinyint", "tinytext", "true", "truncate", "union", "unique", "unsigned", "update", "use", "using", "values", "varbinary",
            "varchar", "view", "virtual", "when", "where", "while", "with", "year", "zerofill", "innodb", "myisam", "aria", "memory"
    );
    private static final Set<String> SQL_SERVER_KEYWORDS = Set.of(
            "create", "alter", "procedure", "proc", "function", "view", "table", "as", "begin", "end", "return", "returns",
            "select", "from", "where", "join", "inner", "left", "right", "full", "outer", "on", "and", "or", "not", "null",
            "is", "in", "exists", "with", "distinct", "top", "order", "by", "group", "having", "union", "all", "case", "when",
            "then", "else", "declare", "set", "insert", "into", "values", "update", "delete", "exec", "execute", "transaction",
            "commit", "rollback", "primary", "key", "foreign", "references", "constraint", "unique", "default", "identity",
            "clustered", "nonclustered", "index", "include", "check", "collate", "asc", "desc", "go", "varchar", "nvarchar",
            "char", "nchar", "int", "bigint", "smallint", "tinyint", "decimal", "numeric", "datetime", "datetime2", "bit", "float",
            "real", "xml", "text", "ntext", "image", "max", "nulls", "offset", "fetch", "next", "throw", "try", "catch", "if",
            "while", "cursor", "open", "close", "deallocate", "nocount"
    );

    private SqlDefinitionCanonicalizer() { }

    /** Ignores SQL formatting and keyword case, preserving identifiers, literals, and comments. */
    static String signature(String sql, Dialect dialect, boolean tableDefinition) {
        if (sql == null || sql.isEmpty()) return "";
        Set<String> keywords = dialect == Dialect.MYSQL ? MYSQL_KEYWORDS : SQL_SERVER_KEYWORDS;
        StringBuilder result = new StringBuilder(sql.length());
        for (int cursor = 0; cursor < sql.length();) {
            char current = sql.charAt(cursor);
            if (Character.isWhitespace(current)) {
                cursor++;
                continue;
            }

            int end = commentEnd(sql, cursor, dialect);
            if (end > cursor) {
                appendToken(result, 'C', sql.substring(cursor, end).replace("\r\n", "\n").replace('\r', '\n'));
                cursor = end;
                continue;
            }

            if (isQuoted(current, dialect)) {
                end = quotedEnd(sql, cursor, current, dialect);
                appendToken(result, current == '\'' ? 'S' : 'Q', sql.substring(cursor, end));
                cursor = end;
                continue;
            }

            if (Character.isDigit(current)) {
                end = numberEnd(sql, cursor);
                appendToken(result, 'N', sql.substring(cursor, end));
                cursor = end;
                continue;
            }

            if (isWordStart(current)) {
                end = cursor + 1;
                while (end < sql.length() && isWordPart(sql.charAt(end))) end++;
                String word = sql.substring(cursor, end);
                int prefixedStringEnd = prefixedStringEnd(sql, end, word, dialect);
                if (prefixedStringEnd > end) {
                    // N'...' and MySQL character-set/hex introducers are part of the literal token.
                    appendToken(result, 'S', word.toUpperCase(Locale.ROOT) + sql.substring(end, prefixedStringEnd));
                    cursor = prefixedStringEnd;
                    continue;
                }
                if (tableDefinition && word.equalsIgnoreCase("AUTO_INCREMENT")) {
                    int metadataEnd = autoIncrementEnd(sql, end);
                    if (metadataEnd > end) {
                        appendToken(result, 'M', "AUTO_INCREMENT=<value>");
                        cursor = metadataEnd;
                        continue;
                    }
                }
                String lower = word.toLowerCase(Locale.ROOT);
                boolean keyword = keywords.contains(lower);
                appendToken(result, keyword ? 'K' : 'W', keyword ? word.toUpperCase(Locale.ROOT) : word);
                cursor = end;
                continue;
            }

            String operator = operatorAt(sql, cursor);
            appendToken(result, 'O', operator);
            cursor += operator.length();
        }
        return result.toString();
    }

    // Typed, length-prefixed tokens keep boundaries unambiguous after whitespace is discarded.
    private static void appendToken(StringBuilder output, char type, String value) {
        output.append(type).append(value.length()).append(':').append(value).append(';');
    }

    private static int commentEnd(String sql, int start, Dialect dialect) {
        if (sql.startsWith("/*", start)) {
            int depth = 1;
            int cursor = start + 2;
            while (cursor < sql.length()) {
                if (dialect == Dialect.SQL_SERVER && sql.startsWith("/*", cursor)) {
                    depth++;
                    cursor += 2;
                } else if (sql.startsWith("*/", cursor)) {
                    cursor += 2;
                    if (--depth == 0) return cursor;
                } else cursor++;
            }
            return sql.length();
        }
        if (sql.startsWith("--", start) && (dialect == Dialect.SQL_SERVER
                || start + 2 == sql.length() || Character.isWhitespace(sql.charAt(start + 2))
                || Character.isISOControl(sql.charAt(start + 2))))
            return lineCommentEnd(sql, start + 2);
        if (dialect == Dialect.MYSQL && sql.charAt(start) == '#') return lineCommentEnd(sql, start + 1);
        return start;
    }

    private static int lineCommentEnd(String sql, int cursor) {
        while (cursor < sql.length() && sql.charAt(cursor) != '\r' && sql.charAt(cursor) != '\n') cursor++;
        return cursor;
    }

    private static int quotedEnd(String sql, int start, char quote, Dialect dialect) {
        for (int cursor = start + 1; cursor < sql.length();) {
            char current = sql.charAt(cursor);
            if (dialect == Dialect.MYSQL && current == '\\' && cursor + 1 < sql.length()) cursor += 2;
            else if (current == quote) {
                if (cursor + 1 < sql.length() && sql.charAt(cursor + 1) == quote) cursor += 2;
                else return cursor + 1;
            } else if (quote == '[' && current == ']') {
                if (cursor + 1 < sql.length() && sql.charAt(cursor + 1) == ']') cursor += 2;
                else return cursor + 1;
            } else cursor++;
        }
        return sql.length();
    }

    private static int prefixedStringEnd(String sql, int wordEnd, String prefix, Dialect dialect) {
        if (wordEnd >= sql.length() || sql.charAt(wordEnd) != '\'') return wordEnd;
        boolean supportedPrefix = dialect == Dialect.SQL_SERVER
                ? prefix.equalsIgnoreCase("N")
                : prefix.equalsIgnoreCase("N") || prefix.equalsIgnoreCase("X") || prefix.equalsIgnoreCase("B")
                    || prefix.startsWith("_");
        if (!supportedPrefix) return wordEnd;
        return quotedEnd(sql, wordEnd, '\'', dialect);
    }

    private static boolean isQuoted(char value, Dialect dialect) {
        return value == '\'' || value == '"' || (value == '`' && dialect == Dialect.MYSQL)
                || (value == '[' && dialect == Dialect.SQL_SERVER);
    }

    private static boolean isWordStart(char value) {
        return Character.isLetter(value) || value == '_' || value == '$' || value == '#' || value == '@';
    }

    private static boolean isWordPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$' || value == '#' || value == '@';
    }

    private static int numberEnd(String sql, int start) {
        int cursor = start;
        if (cursor + 2 < sql.length() && sql.charAt(cursor) == '0'
                && (sql.charAt(cursor + 1) == 'x' || sql.charAt(cursor + 1) == 'X')) {
            cursor += 2;
            while (cursor < sql.length() && isHexDigit(sql.charAt(cursor))) cursor++;
            return cursor;
        }
        if (cursor + 2 < sql.length() && sql.charAt(cursor) == '0'
                && (sql.charAt(cursor + 1) == 'b' || sql.charAt(cursor + 1) == 'B')) {
            cursor += 2;
            while (cursor < sql.length() && (sql.charAt(cursor) == '0' || sql.charAt(cursor) == '1')) cursor++;
            return cursor;
        }

        while (cursor < sql.length() && Character.isDigit(sql.charAt(cursor))) cursor++;
        if (cursor + 1 < sql.length() && sql.charAt(cursor) == '.' && Character.isDigit(sql.charAt(cursor + 1))) {
            cursor++;
            while (cursor < sql.length() && Character.isDigit(sql.charAt(cursor))) cursor++;
        }
        if (cursor < sql.length() && (sql.charAt(cursor) == 'e' || sql.charAt(cursor) == 'E')) {
            int exponent = cursor + 1;
            if (exponent < sql.length() && (sql.charAt(exponent) == '+' || sql.charAt(exponent) == '-')) exponent++;
            int firstExponentDigit = exponent;
            while (exponent < sql.length() && Character.isDigit(sql.charAt(exponent))) exponent++;
            if (exponent > firstExponentDigit) cursor = exponent;
        }
        return cursor;
    }

    private static boolean isHexDigit(char value) {
        return Character.digit(value, 16) >= 0;
    }

    private static int autoIncrementEnd(String sql, int cursor) {
        int position = skipWhitespace(sql, cursor);
        if (position >= sql.length() || sql.charAt(position++) != '=') return cursor;
        position = skipWhitespace(sql, position);
        int firstDigit = position;
        while (position < sql.length() && Character.isDigit(sql.charAt(position))) position++;
        return position == firstDigit ? cursor : position;
    }

    private static int skipWhitespace(String value, int cursor) {
        while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) cursor++;
        return cursor;
    }

    private static String operatorAt(String sql, int cursor) {
        for (String operator : OPERATORS) if (sql.startsWith(operator, cursor)) return operator;
        return sql.substring(cursor, cursor + 1);
    }
}
