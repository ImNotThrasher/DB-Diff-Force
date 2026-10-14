package studio.dbdiff;

/** Adds readable line breaks to one-line MySQL/MariaDB view definitions without rewriting SQL tokens. */
final class MySqlViewFormatter {
    private MySqlViewFormatter() { }

    static String format(String ddl) {
        if (ddl == null || ddl.isBlank()) return ddl;
        int queryStart = queryStart(ddl);
        if (queryStart < 0) return ddl;
        String query = ddl.substring(queryStart);
        if (query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0) return ddl;
        StringBuilder result = new StringBuilder(ddl.substring(0, queryStart).stripTrailing())
                .append("\n    ");
        int depth = 0;
        String previousWord = "";
        int cursor = queryStart;
        while (cursor < ddl.length()) {
            char current = ddl.charAt(cursor);
            if (Character.isWhitespace(current)) {
                int end = cursor + 1;
                while (end < ddl.length() && Character.isWhitespace(ddl.charAt(end))) end++;
                if (result.length() > 0 && !Character.isWhitespace(result.charAt(result.length() - 1))) result.append(' ');
                cursor = end;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                int end = quotedEnd(ddl, cursor, current);
                result.append(ddl, cursor, end);
                cursor = end;
                continue;
            }
            if (current == '/' && cursor + 1 < ddl.length() && ddl.charAt(cursor + 1) == '*') {
                int end = blockCommentEnd(ddl, cursor);
                result.append(ddl, cursor, end);
                cursor = end;
                continue;
            }
            if ((current == '-' && cursor + 1 < ddl.length() && ddl.charAt(cursor + 1) == '-') || current == '#') {
                int end = cursor;
                while (end < ddl.length() && ddl.charAt(end) != '\n' && ddl.charAt(end) != '\r') end++;
                result.append(ddl, cursor, end);
                cursor = end;
                continue;
            }
            if (isWordStart(current)) {
                int end = cursor + 1;
                while (end < ddl.length() && isWordPart(ddl.charAt(end))) end++;
                String word = ddl.substring(cursor, end).toUpperCase(java.util.Locale.ROOT);
                boolean joined = word.equals("JOIN") && !java.util.Set.of("LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "NATURAL").contains(previousWord);
                if (depth == 0 && (isClauseStart(word) || joined)) {
                    startNewLine(result, word.equals("ON") ? "        " : "    ");
                }
                result.append(ddl, cursor, end);
                previousWord = word;
                cursor = end;
                continue;
            }
            if (current == '(') depth++;
            else if (current == ')') depth = Math.max(0, depth - 1);
            result.append(current);
            cursor++;
            if (current == ',' && depth == 0) startNewLine(result, "        ");
        }
        return result.toString().stripTrailing();
    }

    private static int queryStart(String ddl) {
        boolean foundView = false;
        for (int cursor = 0; cursor < ddl.length();) {
            char current = ddl.charAt(cursor);
            if (current == '\'' || current == '"' || current == '`') {
                cursor = quotedEnd(ddl, cursor, current);
                continue;
            }
            if (current == '/' && cursor + 1 < ddl.length() && ddl.charAt(cursor + 1) == '*') {
                cursor = blockCommentEnd(ddl, cursor);
                continue;
            }
            if ((current == '-' && cursor + 1 < ddl.length() && ddl.charAt(cursor + 1) == '-') || current == '#') {
                while (cursor < ddl.length() && ddl.charAt(cursor) != '\n' && ddl.charAt(cursor) != '\r') cursor++;
                continue;
            }
            if (isWordStart(current)) {
                int end = cursor + 1;
                while (end < ddl.length() && isWordPart(ddl.charAt(end))) end++;
                String word = ddl.substring(cursor, end);
                if (foundView && word.equalsIgnoreCase("AS")) return end;
                if (word.equalsIgnoreCase("VIEW")) foundView = true;
                cursor = end;
                continue;
            }
            cursor++;
        }
        return -1;
    }

    private static boolean isClauseStart(String word) {
        return switch (word) {
            case "SELECT", "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "UNION",
                    "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "ON" -> true;
            default -> false;
        };
    }

    private static boolean isWordStart(char value) {
        return Character.isLetter(value) || value == '_' || value == '$';
    }

    private static boolean isWordPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private static int quotedEnd(String text, int start, char quote) {
        for (int cursor = start + 1; cursor < text.length(); cursor++) {
            if (text.charAt(cursor) == '\\' && cursor + 1 < text.length()) { cursor++; continue; }
            if (text.charAt(cursor) == quote) {
                if (cursor + 1 < text.length() && text.charAt(cursor + 1) == quote) { cursor++; continue; }
                return cursor + 1;
            }
        }
        return text.length();
    }

    private static int blockCommentEnd(String text, int start) {
        int close = text.indexOf("*/", start + 2);
        return close < 0 ? text.length() : close + 2;
    }

    private static void trimSpaces(StringBuilder text) {
        while (!text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1)))
            text.setLength(text.length() - 1);
    }

    private static void startNewLine(StringBuilder text, String indentation) {
        trimSpaces(text);
        if (text.isEmpty() || text.charAt(text.length() - 1) != '\n') text.append('\n');
        text.append(indentation);
    }
}
