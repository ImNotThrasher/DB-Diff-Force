package studio.dbdiff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SqlDefinitionCanonicalizerTest {
    @Test
    void mysqlFormattingAndKeywordCaseDoNotChangeSignature() {
        assertSameMysql("CREATE TABLE `items` (`id` INT NOT NULL DEFAULT 1) ENGINE=InnoDB",
                "create\ttable `items` (\n `id` int\t not\n null default 1\n) engine = innodb", true);
    }

    @Test
    void sqlServerFormattingAndKeywordCaseDoNotChangeSignature() {
        assertSameSqlServer("CREATE TABLE [dbo].[Thing] ([Id] INT NOT NULL)",
                "create\ntable [dbo].[Thing]\t( [Id] int\nnot null )");
    }

    @Test
    void identifiersAndLiteralValuesRemainCaseAndTextSensitive() {
        assertDifferentMysql("SELECT `CustomerId` FROM `orders`", "select `customerid` from `orders`", false);
        assertDifferentMysql("SELECT customerId FROM orders", "select customerid from orders", false);
        assertDifferentMysql("SELECT 'Ready' FROM `orders`", "select 'ready' from `orders`", false);
        assertDifferentMysql("SELECT 12", "SELECT 1 2", false);
        assertDifferentMysql("SELECT 0b101", "select 0 b101", false);
        assertDifferentSqlServer("SELECT [CustomerId] FROM [dbo].[Orders]", "select [customerid] from [dbo].[Orders]");
        assertDifferentSqlServer("SELECT 'Ready'", "select 'Ready '");
        assertDifferentSqlServer("SELECT N'Unicode'", "select N 'Unicode'");
    }

    @Test
    void commentsRemainSignificantAndSqlInsideThemIsNotCanonicalized() {
        assertDifferentMysql("SELECT 1 -- select rows\nFROM `orders`", "select 1 -- SELECT rows\nfrom `orders`", false);
        assertDifferentMysql("SELECT 1 /* keep this */", "select 1 /* keep that */", false);
        assertSameMysql("SELECT 1 /* same\r\ncomment */", "select 1 /* same\ncomment */", false);
        assertDifferentSqlServer("SELECT 1 /* select */", "select 1 /* SELECT */");
    }

    @Test
    void quotedIdentifiersAndCommentDelimitersAreParsedByDialect() {
        assertDifferentMysql("SELECT `select` FROM `t`", "select `SELECT` from `t`", false);
        assertDifferentSqlServer("SELECT [select] FROM [t]", "select [SELECT] from [t]");
        // In MySQL, two dashes only start a comment when followed by whitespace/control.
        assertDifferentMysql("SELECT 4--2", "select 4 -- 2", false);
    }

    @Test
    void mysqlAutoIncrementCounterIsVolatileOnlyForTableDefinitions() {
        assertSameMysql("CREATE TABLE `t` (`id` INT) AUTO_INCREMENT=12",
                "create table `t` (`id` int) auto_increment = 987", true);
        assertDifferentMysql("SELECT 'AUTO_INCREMENT=12'", "select 'AUTO_INCREMENT=987'", true);
    }

    @Test
    void diffStatusUsesCanonicalSignatureButKeepsOriginalDefinitions() {
        String original = "CREATE VIEW `v` AS SELECT 1";
        String formatted = "create\nview `v` as\nselect 1";
        SchemaObject.Key key = new SchemaObject.Key(SchemaObject.Kind.VIEW, "v");
        SchemaObject source = new SchemaObject(key, original,
                mysql(original, false));
        SchemaObject target = new SchemaObject(key, formatted,
                mysql(formatted, false));

        DiffEngine.Result result = new DiffEngine().compare(List.of(source), List.of(target),
                Set.of(SchemaObject.Kind.VIEW), Set.of()).getFirst();

        assertEquals(DiffEngine.Status.IDENTICAL, result.status());
        assertEquals(original, result.source().definition());
        assertEquals(formatted, result.target().definition());
    }

    private static void assertSameMysql(String left, String right, boolean table) {
        assertEquals(mysql(left, table), mysql(right, table));
    }

    private static void assertDifferentMysql(String left, String right, boolean table) {
        assertNotEquals(mysql(left, table), mysql(right, table));
    }

    private static String mysql(String sql, boolean table) {
        return SqlDefinitionCanonicalizer.signature(sql, SqlDefinitionCanonicalizer.Dialect.MYSQL, table);
    }

    private static void assertSameSqlServer(String left, String right) {
        assertEquals(sqlServer(left), sqlServer(right));
    }

    private static void assertDifferentSqlServer(String left, String right) {
        assertNotEquals(sqlServer(left), sqlServer(right));
    }

    private static String sqlServer(String sql) {
        return SqlDefinitionCanonicalizer.signature(sql, SqlDefinitionCanonicalizer.Dialect.SQL_SERVER, false);
    }
}
