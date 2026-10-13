package studio.dbdiff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SqlDefinitionCanonicalizerTest {
    @Test
    void representativeServerDdlFixturesIgnoreKeywordCaseAndFormatting() throws IOException {
        String mariaTable = fixture("mariadb-show-create-table.sql");
        String mariaFormatted = mariaTable.replace("CREATE TABLE", "create\ttable")
                .replace("NOT NULL", "not\nnull").replace("AUTO_INCREMENT", "auto_increment")
                .replace("ENGINE=InnoDB", "engine = innodb").replace("DEFAULT CHARSET", "default charset");
        assertEquals(mysql(mariaTable, true), mysql(mariaFormatted, true), "MariaDB SHOW CREATE TABLE fixture");

        String mysqlView = fixture("mysql-show-create-view.sql");
        String mysqlFormatted = mysqlView.replace("CREATE ALGORITHM=UNDEFINED", "create algorithm = undefined")
                .replace("SQL SECURITY DEFINER VIEW", "sql security definer view")
                .replace(" AS\nselect", " as\nSELECT").replace("from ", "FROM ").replace(" where ", " WHERE ");
        assertEquals(mysql(mysqlView, false), mysql(mysqlFormatted, false), "MySQL SHOW CREATE VIEW fixture");

        String sqlServerProcedure = fixture("sqlserver-object-definition-procedure.sql");
        String sqlServerFormatted = sqlServerProcedure.replace("CREATE PROCEDURE", "create procedure")
                .replace("AS\nBEGIN", "as\nbegin").replace("SET NOCOUNT ON", "set nocount on")
                .replace("SELECT ", "select ").replace("FROM ", "from ").replace("WHERE ", "where ")
                .replace(" AND ", " and ");
        assertEquals(sqlServer(sqlServerProcedure), sqlServer(sqlServerFormatted), "SQL Server OBJECT_DEFINITION fixture");
    }

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

    @Test
    void canIgnoreMysqlViewDefinerWithoutChangingDisplayedDefinitions() {
        String source = "CREATE ALGORITHM=UNDEFINED DEFINER=`sistemausr_dev`@`%` SQL SECURITY DEFINER VIEW `vista_tecnica_seguimiento` AS SELECT 1";
        String target = source.replace("sistemausr_dev", "sistemausr_qa");
        SchemaObject.Key key = new SchemaObject.Key(SchemaObject.Kind.VIEW, "vista_tecnica_seguimiento", "");
        SchemaObject left = new SchemaObject(key, source, mysql(source, false));
        SchemaObject right = new SchemaObject(key, target, mysql(target, false));

        DiffEngine.Result defaultResult = new DiffEngine().compare(List.of(left), List.of(right),
                java.util.EnumSet.of(SchemaObject.Kind.VIEW), java.util.Set.of(key)).get(0);
        DiffEngine.Result ignoredResult = new DiffEngine().compare(List.of(left), List.of(right),
                java.util.EnumSet.of(SchemaObject.Kind.VIEW), java.util.Set.of(key), true).get(0);

        assertEquals(DiffEngine.Status.CHANGED, defaultResult.status());
        assertEquals(DiffEngine.Status.IDENTICAL, ignoredResult.status());
        assertEquals(source, ignoredResult.source().definition());
        assertEquals(target, ignoredResult.target().definition());
    }

    @Test
    void definerExclusionOnlyRemovesLeadingDefinerClause() {
        String ddl = "CREATE DEFINER=`user`@`%` VIEW v AS SELECT 'DEFINER=user'";
        assertEquals("CREATE VIEW v AS SELECT 'DEFINER=user'", SqlDefinitionCanonicalizer.withoutMySqlViewDefiner(ddl));
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

    private static String fixture(String name) throws IOException {
        try (InputStream input = SqlDefinitionCanonicalizerTest.class.getResourceAsStream("/ddl/" + name)) {
            if (input == null) throw new IOException("Missing DDL fixture: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
