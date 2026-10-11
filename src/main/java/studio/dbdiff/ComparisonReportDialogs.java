package studio.dbdiff;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Save-dialog workflow for sharing a schema comparison without exporting apply-SQL. */
final class ComparisonReportDialogs {
    private ComparisonReportDialogs() { }

    static void save(JFrame owner, String sourceName, String targetName, DatabaseEngine engine,
                     List<DiffEngine.Result> results, List<String> warnings) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Guardar informe de comparación");
        chooser.setSelectedFile(new java.io.File("db-diff-informe.html"));
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;

        Path output = withHtmlExtension(chooser.getSelectedFile().toPath());
        if (Files.exists(output) && JOptionPane.showConfirmDialog(owner,
                "El archivo ya existe. ¿Querés reemplazarlo?", "Confirmar reemplazo",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            Files.writeString(output, ComparisonReportGenerator.generate(sourceName, targetName, engine, results, warnings),
                    StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(owner,
                    "Se guardó el informe de comparación. No incluye datos de filas ni ejecuta SQL.\n" + output.toAbsolutePath(),
                    "Informe listo", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(owner, "No se pudo guardar el informe: " + AppUtils.errorMessage(ex),
                    "Error al guardar", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static Path withHtmlExtension(Path path) {
        String name = path.getFileName().toString();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".html") || name.toLowerCase(java.util.Locale.ROOT).endsWith(".htm")) return path;
        return path.resolveSibling(name + ".html");
    }
}
