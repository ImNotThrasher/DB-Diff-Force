package studio.dbdiff;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds a self-contained, read-only HTML summary of the current schema comparison. */
final class ComparisonReportGenerator {
    private static final DateTimeFormatter GENERATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private ComparisonReportGenerator() { }

    static String generate(String sourceName, String targetName, DatabaseEngine engine,
                           List<DiffEngine.Result> results, List<String> warnings) {
        Map<DiffEngine.Status, Long> counts = results.stream().collect(Collectors.groupingBy(
                DiffEngine.Result::status, () -> new java.util.EnumMap<>(DiffEngine.Status.class), Collectors.counting()));
        StringBuilder html = new StringBuilder(32_768);
        html.append("<!doctype html>\n<html lang=\"es\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Informe de comparación · DB-Diff-Force</title><style>")
                .append("body{font:14px system-ui,sans-serif;line-height:1.5;margin:2rem auto;padding:0 1rem;max-width:1200px;color:#202124;background:#fff}")
                .append("h1,h2{line-height:1.2}header,.warning,.object{border:1px solid #d0d7de;border-radius:6px;padding:1rem;margin:1rem 0}")
                .append(".summary{display:flex;gap:.65rem;flex-wrap:wrap}.badge{border-radius:1rem;padding:.2rem .65rem;background:#eef2f6}")
                .append(".badge.changed{background:#fff0c2}.badge.missing{background:#ffebe9}.badge.same{background:#dafbe1}")
                .append(".warning{border-left:4px solid #4389D1;background:#f5f9ff}.object h3{margin:.25rem 0}")
                .append(".status{font-size:.85rem;color:#57606a}.diff{width:100%;border-collapse:collapse;font:12px ui-monospace,monospace}")
                .append(".diff td{padding:.1rem .45rem;vertical-align:top;white-space:pre-wrap;overflow-wrap:anywhere}")
                .append(".diff .numbers{color:#6e7781;text-align:right;white-space:nowrap;width:1%;border-right:1px solid #d0d7de}")
                .append(".diff tr.added{background:#dafbe1}.diff tr.removed{background:#ffebe9}.diff tr.context{background:#f6f8fa}")
                .append(".empty{color:#57606a}details{margin-top:.75rem}summary{cursor:pointer;font-weight:600}")
                .append(".object-name{font-family:ui-monospace,monospace}footer{color:#57606a;margin-top:2rem}</style></head><body>")
                .append("<header><h1>Informe de comparación de esquema</h1>")
                .append("<p><strong>Origen:</strong> ").append(escape(sourceName)).append("<br><strong>Destino:</strong> ")
                .append(escape(targetName)).append("<br><strong>Motor:</strong> ").append(escape(engine.toString()))
                .append("<br><strong>Generado:</strong> ").append(escape(GENERATED_AT.format(Instant.now()))).append("</p>")
                .append("<div class=\"summary\"><span class=\"badge same\">Iguales: ").append(counts.getOrDefault(DiffEngine.Status.IDENTICAL, 0L))
                .append("</span><span class=\"badge changed\">Modificados: ").append(counts.getOrDefault(DiffEngine.Status.CHANGED, 0L))
                .append("</span><span class=\"badge missing\">Solo en origen: ").append(counts.getOrDefault(DiffEngine.Status.SOURCE_ONLY, 0L))
                .append("</span><span class=\"badge missing\">Solo en destino: ").append(counts.getOrDefault(DiffEngine.Status.TARGET_ONLY, 0L))
                .append("</span><span class=\"badge\">Total: ").append(results.size()).append("</span></div>")
                .append("<p>Este informe contiene definiciones y diferencias del esquema. No incluye datos de filas ni ejecuta SQL.</p></header>");

        if (warnings == null || warnings.isEmpty()) html.append("<p class=\"empty\">No hay advertencias registradas.</p>");
        else {
            html.append("<section class=\"warning\"><h2>Advertencias y limitaciones</h2><ul>");
            for (String warning : warnings) html.append("<li>").append(escape(warning)).append("</li>");
            html.append("</ul></section>");
        }

        html.append("<section><h2>Objetos comparados</h2>");
        for (DiffEngine.Result result : results) appendObject(html, result);
        html.append("</section><footer>Generado por DB-Diff-Force · El SQL no se ejecuta.</footer></body></html>\n");
        return html.toString();
    }

    private static void appendObject(StringBuilder html, DiffEngine.Result result) {
        String status = statusName(result.status());
        String style = switch (result.status()) {
            case IDENTICAL -> "same";
            case CHANGED -> "changed";
            case SOURCE_ONLY, TARGET_ONLY -> "missing";
        };
        html.append("<article class=\"object\"><h3><span class=\"object-name\">")
                .append(escape(result.key().qualifiedName())).append("</span></h3><div class=\"status badge ")
                .append(style).append("\">").append(escape(CatalogDisplay.kindName(result.key().kind())))
                .append(" · ").append(escape(status)).append("</div>");
        if (result.status() == DiffEngine.Status.IDENTICAL) {
            html.append("<p class=\"empty\">Las definiciones son iguales.</p>");
        } else {
            html.append("<details open><summary>Ver diferencias de definición</summary><table class=\"diff\"><tbody>");
            for (DiffEngine.DiffLine line : result.diffLines()) {
                String rowClass = switch (line.type()) {
                    case ADDED -> "added";
                    case REMOVED -> "removed";
                    case CONTEXT -> "context";
                };
                html.append("<tr class=\"").append(rowClass).append("\"><td class=\"numbers\">")
                        .append(line.sourceLineNumber() == null ? "" : line.sourceLineNumber()).append("</td><td class=\"numbers\">")
                        .append(line.targetLineNumber() == null ? "" : line.targetLineNumber()).append("</td><td>")
                        .append(escape(line.type() == DiffEngine.LineType.ADDED ? "+ " : line.type() == DiffEngine.LineType.REMOVED ? "− " : "  "))
                        .append(escape(line.text())).append("</td></tr>");
            }
            html.append("</tbody></table></details>");
        }
        html.append("</article>");
    }

    private static String statusName(DiffEngine.Status status) {
        return switch (status) {
            case IDENTICAL -> "Igual";
            case CHANGED -> "Modificado";
            case SOURCE_ONLY -> "Solo en origen";
            case TARGET_ONLY -> "Solo en destino";
        };
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
