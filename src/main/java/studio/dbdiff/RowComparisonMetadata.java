package studio.dbdiff;

import java.util.List;

/** Column metadata used to choose a safe key for matching rows. */
public record RowComparisonMetadata(List<RowDiff.Column> columns, List<RowDiff.Column> primaryKey) {
    public RowComparisonMetadata {
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
    }
}
