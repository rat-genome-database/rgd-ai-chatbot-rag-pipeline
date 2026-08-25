package edu.mcw.rgd.chatBotEmbed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Owns the on-disk contract the chatbot's ingest depends on. Everything that can
 * silently break embedding quality if it drifts lives here, in one place:
 *
 * <ul>
 *   <li>Line 1 is always {@code <!-- file_name: <displayName> -->}. The chatbot reads
 *       this as the {@code document_embeddings.file_name} value, and
 *       {@code ReportMarkdownChunker.isRgdReport()} only selects the section-aware
 *       chunker when line 1 starts with {@code <!-- file_name:} and contains "Report".</li>
 *   <li>Files are named {@code <reportType>_<safeSymbol>_<id>.md} and written under
 *       {@code <outputDir>/<reportType>/<species>/}. The chatbot's ingest walks the report-type
 *       directory recursively, so the per-species sub-directory is transparent to embedding.</li>
 *   <li>UTF-8, {@code \n} line endings, trailing newline.</li>
 * </ul>
 */
public class MarkdownWriter {

    private final Path outputDir;

    public MarkdownWriter(String outputDir) {
        this.outputDir = Paths.get(outputDir);
    }

    /**
     * Write {@code <outputDir>/<reportType>/<species>/<reportType>_<safeSymbol>_<id>.md}.
     *
     * @param species filename-safe species directory segment (e.g. {@code "rat"})
     * @return the path written
     */
    public Path write(String reportType, String species, ReportDoc doc) throws IOException {
        Path dir = outputDir.resolve(reportType).resolve(species);
        Files.createDirectories(dir);

        String fileName = reportType + "_" + doc.safeSymbol + "_" + doc.id + ".md";
        Path file = dir.resolve(fileName);

        StringBuilder sb = new StringBuilder(doc.markdown.length() + 64);
        sb.append("<!-- file_name: ").append(doc.displayName).append(" -->\n");
        sb.append(doc.markdown);
        if (sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }

        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Turn an object symbol into a filename-safe token: strip path separators,
     * shell wildcards, quotes and whitespace, collapsing runs to a single underscore.
     */
    public static String safeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "unknown";
        }
        return symbol.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }
}
