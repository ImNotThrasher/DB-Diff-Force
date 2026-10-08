package studio.dbdiff;

import java.util.Objects;

public record SchemaObject(Key key, String definition, String signature) {
    public enum Kind { TABLE, VIEW, PROCEDURE, FUNCTION }
    public record Key(Kind kind, String name) implements Comparable<Key> {
        public Key { Objects.requireNonNull(kind); Objects.requireNonNull(name); }
        @Override public int compareTo(Key other) {
            int typeOrder = kind.compareTo(other.kind);
            return typeOrder != 0 ? typeOrder : name.compareToIgnoreCase(other.name);
        }
        @Override public String toString() { return kind + " · " + name; }
    }
    public SchemaObject {
        Objects.requireNonNull(key);
        definition = definition == null ? "" : definition;
        signature = signature == null ? definition : signature;
    }
}
