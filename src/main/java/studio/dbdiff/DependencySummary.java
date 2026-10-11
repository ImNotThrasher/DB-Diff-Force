package studio.dbdiff;

import java.util.List;
import java.util.ArrayList;

/** Shared explanation of dependency metadata coverage. */
final class DependencySummary {
    static List<String> notes(String side, DependencyReport report) {
        List<String> notes = new ArrayList<>();
        if (report.coverage().isEmpty()) notes.add(side + ": cobertura de dependencias no disponible.");
        else {
            String summary = report.coverage().stream()
                    .map(item -> item.source() + " " + item.edges() + (item.warning().isBlank() ? "" : " (" + item.warning() + ")"))
                    .collect(java.util.stream.Collectors.joining(", "));
            notes.add(side + " · relaciones encontradas en catálogos: " + summary + ".");
        }
        report.limitations().forEach(limitation -> notes.add(side + " · " + limitation));
        return notes;
    }
}
