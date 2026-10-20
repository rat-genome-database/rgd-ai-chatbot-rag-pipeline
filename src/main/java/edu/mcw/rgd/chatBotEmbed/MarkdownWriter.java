package edu.mcw.rgd.chatBotEmbed;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

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
 *   <li>One file per report. The embed step embeds every file it finds, so a file left behind
 *       when a report's symbol changes, or its object is withdrawn, would keep a stale copy in
 *       the index. Writing a report therefore removes that id's files under any other name, and
 *       {@link #prune} removes the files a full run did not write.</li>
 * </ul>
 */
public class MarkdownWriter {

    private static final Logger log = LogManager.getLogger("status");

    /**
     * A prune that would delete more than this share of a directory's reports is refused unless
     * forced: that many disappearing at once means the enumeration went wrong, not that so many
     * objects were withdrawn.
     */
    private static final double MAX_PRUNE_SHARE = 0.10;

    /** Below this many deletions the share check does not apply (small directories). */
    private static final int PRUNE_SHARE_MIN_FILES = 50;

    private final Path outputDir;

    /** Files written by this writer during the run. */
    private final Set<Path> written = ConcurrentHashMap.newKeySet();

    /**
     * Per directory, the report files present when it was first written to, keyed by the token
     * after the file name's last underscore (the id, or the numeric tail of an accession id).
     * Built once per directory so a write does not list a directory of tens of thousands of files.
     */
    private final Map<Path, Map<String, Set<Path>>> existing = new ConcurrentHashMap<>();

    public MarkdownWriter(String outputDir) {
        this.outputDir = Paths.get(outputDir);
    }

    /**
     * Write {@code <outputDir>/<reportType>/<species>/<reportType>_<safeSymbol>_<id>.md}, removing
     * any other file in that directory for the same id (the report under its previous name).
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
        written.add(file);
        removeOtherNames(dir, reportType, doc.id, file);
        return file;
    }

    /** Delete the files in {@code dir} for report {@code id} other than {@code keep}. */
    private void removeOtherNames(Path dir, String reportType, String id, Path keep) throws IOException {
        Map<String, Set<Path>> index = existing.computeIfAbsent(dir, d -> indexDirectory(d, reportType));
        Set<Path> candidates = index.get(idToken(id));
        if (candidates == null) {
            return;
        }
        String suffix = "_" + id + ".md";
        for (Path p : candidates) {
            if (!p.equals(keep) && p.getFileName().toString().endsWith(suffix) && Files.deleteIfExists(p)) {
                log.info("Removed {} (report {} is now {})", p.getFileName(), id, keep.getFileName());
            }
        }
    }

    private static Map<String, Set<Path>> indexDirectory(Path dir, String reportType) {
        Map<String, Set<Path>> index = new ConcurrentHashMap<>();
        for (Path p : reportFiles(List.of(dir), reportType, false)) {
            String base = p.getFileName().toString();
            base = base.substring(0, base.length() - 3);
            index.computeIfAbsent(idToken(base), k -> ConcurrentHashMap.newKeySet()).add(p);
        }
        return index;
    }

    /** The text after the last underscore: the whole id for RGD ids, the numeric tail for accessions. */
    private static String idToken(String s) {
        int u = s.lastIndexOf('_');
        return u >= 0 ? s.substring(u + 1) : s;
    }

    /**
     * After a complete run, delete the report files in {@code dirs} that this run did not write:
     * reports of withdrawn objects, terms no longer present, pages taken down. Files for the ids in
     * {@code keepIds} — those that failed to generate this run — are kept, since a failure says
     * nothing about whether the report should exist.
     *
     * <p>Only call this for a run that enumerated every object of the type (no {@code --rgdId},
     * {@code --ontAcc}, {@code --limit} or ontology filter): otherwise every report outside the
     * selection would look obsolete.</p>
     *
     * @param recursive also prune sub-directories (accession-keyed types write one per ontology)
     * @param force     prune even when more than {@link #MAX_PRUNE_SHARE} of the files would go
     * @return number of files deleted
     */
    public int prune(String reportType, Collection<Path> dirs, boolean recursive, Set<String> keepIds,
                     boolean force) throws IOException {
        List<Path> files = reportFiles(dirs, reportType, recursive);
        List<Path> obsolete = new ArrayList<>();
        for (Path p : files) {
            if (!written.contains(p) && !isKept(p, keepIds)) {
                obsolete.add(p);
            }
        }
        if (obsolete.isEmpty()) {
            return 0;
        }
        if (!force && obsolete.size() >= PRUNE_SHARE_MIN_FILES
                && obsolete.size() > MAX_PRUNE_SHARE * files.size()) {
            log.warn("NOT pruning {} of {} '{}' files under {}: more than {}% of them would be deleted, "
                            + "which suggests an incomplete run. Check the run, then rerun with --forcePrune "
                            + "to delete them.",
                    obsolete.size(), files.size(), reportType, dirs, (int) (MAX_PRUNE_SHARE * 100));
            return 0;
        }
        int deleted = 0;
        for (Path p : obsolete) {
            if (Files.deleteIfExists(p)) {
                deleted++;
                log.info("Pruned obsolete report {}", p.getFileName());
            }
        }
        log.info("Pruned {} obsolete '{}' file(s) under {} (their chunks stay in the index until removed there)",
                deleted, reportType, dirs);
        return deleted;
    }

    private static boolean isKept(Path p, Set<String> keepIds) {
        String name = p.getFileName().toString();
        for (String id : keepIds) {
            if (name.endsWith("_" + id + ".md")) {
                return true;
            }
        }
        return false;
    }

    /** The {@code <reportType>_*.md} files in the given directories; other files are never touched. */
    private static List<Path> reportFiles(Collection<Path> dirs, String reportType, boolean recursive) {
        List<Path> files = new ArrayList<>();
        String prefix = reportType + "_";
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = recursive ? Files.walk(dir) : Files.list(dir)) {
                s.filter(Files::isRegularFile)
                 .filter(p -> {
                     String n = p.getFileName().toString();
                     return n.startsWith(prefix) && n.endsWith(".md");
                 })
                 .forEach(files::add);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return files;
    }

    /** The directory a report type's files are written under. */
    public Path typeDir(String reportType) {
        return outputDir.resolve(reportType);
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
