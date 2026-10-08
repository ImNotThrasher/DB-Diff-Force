package studio.dbdiff;

import java.util.Objects;

public record SchemaObject(Key key, String definition, String signature) {
    public enum Kind { TABLE, VIEW, PROCEDURE, FUNCTION }
    public record Key(Kind kind, String name, String schema) implements Comparable<Key> {
        public Key(Kind kind, String name) { this(kind, name, ""); }
        public Key { Objects.requireNonNull(kind); Objects.requireNonNull(name); schema = schema == null ? "" : schema; }
        @Override public int compareTo(Key other) {
            int typeOrder = kind.compareTo(other.kind);
            if (typeOrder != 0) return typeOrder;
            int schemaOrder = schema.compareToIgnoreCase(other.schema);
            if (schemaOrder != 0) return schemaOrder;
            schemaOrder = schema.compareTo(other.schema);
            if (schemaOrder != 0) return schemaOrder;
            int insensitiveOrder = name.compareToIgnoreCase(other.name);
            return insensitiveOrder != 0 ? insensitiveOrder : name.compareTo(other.name);
        }
        public String qualifiedName() { return schema.isBlank() ? name : schema + "." + name; }
        @Override public String toString() { return kind + " · " + qualifiedName(); }
    }
    public SchemaObject {
        Objects.requireNonNull(key);
        definition = definition == null ? "" : definition;
        signature = signature == null ? definition : signature;
    }
}
