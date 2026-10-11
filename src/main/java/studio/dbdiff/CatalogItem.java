package studio.dbdiff;

record CatalogItem(SchemaObject.Key key, boolean sourcePresent, boolean targetPresent) {
    @Override public String toString() {
        return key.qualifiedName() + " · " + CatalogDisplay.kindName(key.kind()) + " · sin comparar";
    }
}
