package studio.dbdiff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComparisonReportGeneratorTest {
    @Test void includesEveryObjectStateAndEscapesDefinitionsAndWarnings() {
        SchemaObject same = object("same", "CREATE TABLE same (id int);");
        SchemaObject oldChanged = object("changed", "CREATE TABLE changed (value int);");
        SchemaObject newChanged = object("changed", "CREATE TABLE changed (value varchar(20));");
        SchemaObject sourceOnly = object("<script>alert(1)</script>", "CREATE TABLE source_only (value <script>);");
        SchemaObject targetOnly = object("target_only", "CREATE TABLE target_only (id int);");
        List<DiffEngine.Result> results = new DiffEngine().compare(
                List.of(same, oldChanged, sourceOnly), List.of(same, newChanged, targetOnly),
                Set.of(SchemaObject.Kind.TABLE), Set.of());

        String html = ComparisonReportGenerator.generate("Origen <A>", "Destino", DatabaseEngine.MARIADB,
                results, List.of("Advertencia <script>alert(1)</script>"));

        assertTrue(html.contains("Iguales: 1"));
        assertTrue(html.contains("Modificados: 1"));
        assertTrue(html.contains("Solo en origen: 1"));
        assertTrue(html.contains("Solo en destino: 1"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("value varchar(20)"));
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("<script src="));
    }

    private static SchemaObject object(String name, String definition) {
        return new SchemaObject(new SchemaObject.Key(SchemaObject.Kind.TABLE, name), definition, definition);
    }
}
