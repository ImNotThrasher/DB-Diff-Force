package studio.dbdiff;

final class CatalogDisplay {
    private CatalogDisplay() { }

    static String kindName(SchemaObject.Kind kind) {
        return switch (kind) {
            case TABLE -> "tabla";
            case VIEW -> "vista";
            case PROCEDURE -> "procedimiento";
            case FUNCTION -> "función";
            case TRIGGER -> "trigger";
        };
    }

    static SchemaObject.Key objectKey(Object item) {
        return item instanceof CatalogItem catalog ? catalog.key() : ((DiffEngine.Result) item).key();
    }

    static int statusRank(Object item) {
        if (item instanceof CatalogItem catalog) {
            if (!catalog.sourcePresent()) return 2;
            if (!catalog.targetPresent()) return 3;
            return 4; // Aún no comparado.
        }
        return switch (((DiffEngine.Result) item).status()) {
            case IDENTICAL -> 0;
            case CHANGED -> 1;
            case SOURCE_ONLY -> 2;
            case TARGET_ONLY -> 3;
        };
    }
}
