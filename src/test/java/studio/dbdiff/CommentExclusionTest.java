package studio.dbdiff;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CommentExclusionTest {
    private DiffEngine.Result compare(String left, String right, boolean ignore) {
        var key = new SchemaObject.Key(SchemaObject.Kind.PROCEDURE, "p");
        var dialect = SqlDefinitionCanonicalizer.Dialect.MYSQL;
        return new DiffEngine().compare(
                List.of(new SchemaObject(key, left, SqlDefinitionCanonicalizer.signature(left, dialect, false))),
                List.of(new SchemaObject(key, right, SqlDefinitionCanonicalizer.signature(right, dialect, false))),
                Set.of(key.kind()), Set.of(), false, ignore, DatabaseEngine.MARIADB).getFirst();
    }

    @Test void ignoresOnlyCommentAndKeepsOriginalSql() {
        String left = "SELECT @current_user,\nNOW()";
        String right = "SELECT @current_user, -- set this before deletion\nNOW()";
        assertEquals(DiffEngine.Status.CHANGED, compare(left, right, false).status());
        var result = compare(left, right, true);
        assertEquals(DiffEngine.Status.IDENTICAL, result.status());
        assertEquals(right, result.target().definition());
        assertEquals(DiffEngine.Status.CHANGED, compare(left.replace("@current_user", "@curr_user"), right, true).status());
        assertEquals(DiffEngine.Status.CHANGED, compare(left.replace(",", ""), right, true).status());
    }

    @Test void ignoresCommentHighlightEvenWithAnotherChange() {
        var result = compare("SELECT @current_user,\n1", "SELECT @current_user, -- note\n2", true);
        assertEquals(DiffEngine.Status.CHANGED, result.status());
        assertEquals(DiffEngine.LineType.CONTEXT, result.diffLines().getFirst().type());
    }

    @Test void preservesLiteralsExecutableCommentsAndTokenBoundaries() {
        assertEquals(DiffEngine.Status.CHANGED, compare("SELECT '-- a'", "SELECT '-- b'", true).status());
        assertEquals(DiffEngine.Status.CHANGED, compare("SELECT 1 /*! + 1 */", "SELECT 1 /*! + 2 */", true).status());
        assertEquals(DiffEngine.Status.CHANGED, compare("SELECT 1 /*M! + 1 */", "SELECT 1 /*M! + 2 */", true).status());
        assertEquals(DiffEngine.Status.CHANGED, compare("SELECT /*+ hint1 */ 1", "SELECT /*+ hint2 */ 1", true).status());
        assertEquals(DiffEngine.Status.IDENTICAL, compare("SELECT /* note */ 1 # note\n", "SELECT 1", true).status());
        assertEquals(DiffEngine.Status.CHANGED, compare("SELECT a/* note */b", "SELECT ab", true).status());
    }
}
