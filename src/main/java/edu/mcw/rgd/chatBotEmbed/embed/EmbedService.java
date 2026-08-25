package edu.mcw.rgd.chatBotEmbed.embed;

import edu.mcw.rgd.chatBotEmbed.chunker.ReportMarkdownChunker;
import edu.mcw.rgd.dao.impl.DocumentEmbeddingDAO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The pipeline's {@code --mode embed} step: read the markdown files this pipeline
 * generated, chunk them exactly as the chatbot would, embed each chunk, and write the
 * vectors to Postgres/pgvector.
 *
 * <p>This is deliberately a <em>separate run</em> from generation. It reads the same
 * output directory from disk, so you can regenerate markdown without re-embedding, and
 * re-embed without re-querying Oracle.</p>
 *
 * <p>Idempotent per file: existing chunks for a display name are deleted before its new
 * chunks are written. Without {@code --force}, the file's markdown is chunked and compared
 * to the chunks already stored for that display name — files whose chunks are unchanged are
 * skipped, and only new or <em>changed</em> files are re-embedded. {@code --force} re-embeds
 * every file unconditionally (no comparison).</p>
 */
public class EmbedService {

    // Embed run logs to its own file (embedRun.log), separate from the generate run's summary.log.
    // Per-file diagnostics are logged at DEBUG so they only reach embedDetail.log; embedRun.log
    // (INFO-only, emailed) stays small.
    private static final Logger LOG = LogManager.getLogger("embed");
    private static final int MIN_CHUNK_CHARS = 50;

    // Spring-injected configuration (see AppConfigure.xml). Model + dimensions MUST match
    // the chatbot's embedding model, or the stored vectors won't be comparable to queries.
    private String provider = "openai";
    private String baseUrl = "https://api.openai.com/v1";
    private String model = "text-embedding-3-small";
    private int dimensions = 1536;
    private String apiKeyEnv = "OPENAI_API_KEY";
    private String apiKeyFile = "";   // path to a file containing just the API key (takes precedence over the env var)
    private int threadCount = 2;

    private final ReportMarkdownChunker chunker = new ReportMarkdownChunker();

    /**
     * Embed all {@code .md} files under {@code outputDir[/subPath]}.
     *
     * @param subPath    optional report-type subdirectory (e.g. "gene"); null/blank = everything
     * @param speciesDir optional species sub-directory name (e.g. "human"); null/blank = every
     *                   species. Reports live under {@code <type>/<species>/}, so this keeps only
     *                   files with a matching species path segment.
     * @param force      re-embed every file unconditionally; when false, only new or changed
     *                   files (chunks differ from what's stored) are embedded
     */
    public void run(String outputDir, String subPath, String speciesDir, boolean force) throws Exception {
        Path root = Paths.get(outputDir);
        if (subPath != null && !subPath.isBlank()) {
            root = root.resolve(subPath);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("not a directory: " + root);
        }
        String species = (speciesDir != null && !speciesDir.isBlank()) ? speciesDir.trim() : null;

        String apiKey = null;
        if ("openai".equalsIgnoreCase(provider)) {
            apiKey = resolveApiKey();
        }

        EmbeddingClient client = new EmbeddingClient(provider, baseUrl, model, dimensions, apiKey);
        DocumentEmbeddingDAO embeddingDAO = new DocumentEmbeddingDAO();
        EmbeddingWriter writer = new EmbeddingWriter(embeddingDAO.getDataSource());
//        System.out.println("Total Chuck Count: " +embeddingDAO.getTotalChunkCount());
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.toString().endsWith(".md"))
             .filter(p -> species == null || hasPathSegment(p, species))
             .forEach(files::add);
        }

        LOG.info("Embedding {} markdown files under {} (species={}, provider={}, model={}, dims={}, threads={})",
                files.size(), root, species == null ? "all" : species, provider, model, dimensions, threadCount);

        AtomicInteger embedded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger changed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger chunksWritten = new AtomicInteger();
        // Names of files that failed to embed, collected so embedRun.log can list them at the end.
        List<String> failedFiles = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>(files.size());
        for (Path file : files) {
            futures.add(pool.submit(() -> {
                try {
                    int n = processFile(file, client, embeddingDAO, writer, force, skipped, changed);
                    if (n >= 0) {
                        int done = embedded.incrementAndGet();
                        chunksWritten.addAndGet(n);
                        if (done % 1000 == 0) {
                            LOG.info("  ... {} files embedded ({} chunks so far)", done, chunksWritten.get());
                        }
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    failedFiles.add(file.getFileName() + " — " + e.getMessage());
                    // Concise line in embedRun.log; full stack trace at DEBUG (embedDetail.log only).
                    LOG.error("Embed failed for {}: {}", file.getFileName(), e.getMessage());
                    LOG.debug("Embed failure detail for {}", file.getFileName(), e);
                }
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignore) { /* per-file errors already logged */ }
        }
        pool.shutdown();
        pool.awaitTermination(12, TimeUnit.HOURS);

        String summary = String.format("Embed done. files=%d (new=%d changed=%d) skipped=%d failed=%d chunks=%d",
                embedded.get(), embedded.get() - changed.get(), changed.get(), skipped.get(), failed.get(), chunksWritten.get());
        LOG.info(summary);
        System.out.println(summary);

        // List the files that failed so the emailed embedRun.log names them, not just a count.
        if (!failedFiles.isEmpty()) {
            LOG.error("{} file(s) failed to embed:", failedFiles.size());
            for (String f : failedFiles) {
                LOG.error("  FAILED: {}", f);
            }
        }
    }

    /** True when {@code path} has a directory/file segment equal (case-insensitively) to {@code segment}. */
    private static boolean hasPathSegment(Path path, String segment) {
        for (Path part : path) {
            if (part.toString().equalsIgnoreCase(segment)) {
                return true;
            }
        }
        return false;
    }

    /** @return chunks written for this file, or -1 if the file was skipped */
    private int processFile(Path file, EmbeddingClient client, DocumentEmbeddingDAO dao,
                            EmbeddingWriter writer, boolean force,
                            AtomicInteger skipped, AtomicInteger changed) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String rawName = file.getFileName().toString();
        String displayName = resolveDisplayName(content, rawName);

        if (!ReportMarkdownChunker.isRgdReport(content)) {
            LOG.debug("Not an RGD report (missing file_name/Report header), skipping: {}", rawName);
            skipped.incrementAndGet();
            return -1;
        }

        // Fold the RGD ID into the H1 title (e.g. "# Gene: A2m ..." -> "# Gene: RGD:2004 A2m ..."),
        // so it rides along in the heading breadcrumb the chunker prepends to every chunk — each
        // chunk then carries the identity of the object it came from. Done on the markdown before
        // chunking so the breadcrumb is formed with it in place.
        String rgdId = resolveRgdId(displayName, rawName);
        if (rgdId != null) {
            content = injectRgdIdIntoTitle(content, rgdId);
        } else {
            LOG.debug("Could not resolve RGD ID for {} — embedding chunks without an RGD id in the title", rawName);
        }

        // Chunk exactly as the chatbot would. Done up front (chunking is local/cheap) so the
        // result can be compared to what's already stored before spending any embedding calls.
        List<String> chunks = new ArrayList<>();
        for (String c : chunker.chunk(content)) {
            if (c != null && c.trim().length() >= MIN_CHUNK_CHARS) {
                chunks.add(c);
            }
        }
        if (chunks.isEmpty()) {
            LOG.debug("No usable chunks produced: {}", rawName);
            return 0;
        }

        // Change detection: without --force, re-embed only if this file's chunks differ from the
        // chunks already stored for it. getChunksByFileName returns them in insert order (ORDER BY
        // id), which is the order we wrote them, so an order-sensitive list equality is exact.
        // An empty stored list (never embedded) counts as changed, so new files still embed.
        if (!force) {
            List<String> stored = dao.getChunksByFileName(displayName);
            if (chunks.equals(stored)) {
                skipped.incrementAndGet();
                return -1;
            }
            if (!stored.isEmpty()) {
                changed.incrementAndGet();   // present but different -> genuinely changed
            }
        }

        // Clean slate so a re-embed replaces rather than duplicates.
        dao.deleteByFileName(displayName);
        if (!displayName.equals(rawName)) {
            dao.deleteByFileName(rawName);
        }

        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (String c : chunks) {
            vectors.add(client.embed(c));
        }

        writer.insert(displayName, chunks, vectors);
        return chunks.size();
    }

    /**
     * Resolve the OpenAI API key. If {@code apiKeyFile} is configured, read the key from
     * that file (which should contain only the key); otherwise fall back to the environment
     * variable named by {@code apiKeyEnv}. The key is never stored in the pipeline config.
     */
    private String resolveApiKey() throws Exception {
        if (apiKeyFile != null && !apiKeyFile.isBlank()) {
            Path keyPath = Paths.get(apiKeyFile.trim());
            if (!Files.isRegularFile(keyPath)) {
                throw new IllegalStateException("API key file not found: " + apiKeyFile);
            }
            String key = Files.readString(keyPath, StandardCharsets.UTF_8).trim();
            if (key.isEmpty()) {
                throw new IllegalStateException("API key file is empty: " + apiKeyFile);
            }
            return key;
        }
        String key = System.getenv(apiKeyEnv);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "no API key: set apiKeyFile in AppConfigure.xml, or the " + apiKeyEnv + " environment variable");
        }
        return key;
    }

    /** The RGD ID in a report's file_name comment, e.g. the {@code 2004} in "... A2m (2004)". */
    private static final Pattern RGD_ID_IN_PARENS = Pattern.compile("\\((\\d+)\\)");

    /**
     * The H1 report title's leading {@code "# <Type>: "} (e.g. {@code "# Gene: "}, {@code "# QTL: "}).
     * Multiline so {@code ^} anchors to the title line; a single {@code #} plus whitespace keeps it
     * to level-1 headings, and {@code [^:\n]+:} stops at the type label's colon. The RGD ID is
     * inserted right after this prefix.
     */
    private static final Pattern REPORT_H1_PREFIX = Pattern.compile("(?m)^(#[ \\t]+[^:\\n]+:[ \\t]+)");

    /**
     * An identity token already sitting right after the {@code "# <Type>: "} prefix — an
     * accession-style {@code PREFIX:digits} (e.g. {@code RGD:2004}, {@code MP:0001900},
     * {@code DOID:14330}). When the generator has already written one into the title, we must not
     * inject a second, so this guards {@link #injectRgdIdIntoTitle} against double-injection.
     */
    private static final Pattern TITLE_HAS_IDENTITY = Pattern.compile("^[A-Za-z][A-Za-z0-9]*:\\d");

    /**
     * Insert {@code "RGD:<id> "} into the report's H1 title, just after its {@code "# <Type>: "}
     * prefix — turning {@code "# Gene: A2m (alpha-2-macroglobulin)"} into
     * {@code "# Gene: RGD:2004 A2m (alpha-2-macroglobulin)"}. Only the first match (the title) is
     * touched; if no recognizable title is found the content is returned unchanged.
     *
     * <p>Idempotent: when an identity token already follows the prefix (the generator emits
     * {@code RGD:<id>} for gene/QTL/strain, and an ontology accession like {@code MP:0001900} for
     * ontology terms), the title is left as-is — otherwise it would gain a duplicate {@code RGD:}.</p>
     */
    private static String injectRgdIdIntoTitle(String content, String rgdId) {
        Matcher m = REPORT_H1_PREFIX.matcher(content);
        if (m.find()) {
            String rest = content.substring(m.end());
            if (TITLE_HAS_IDENTITY.matcher(rest).find()) {
                return content;   // identity already present — don't double-inject
            }
            return content.substring(0, m.end()) + "RGD:" + rgdId + " " + rest;
        }
        return content;
    }

    /**
     * Resolve the object's RGD ID for a report. Every RGD report's file_name comment ends with
     * the id in parentheses ("RGD Gene Report - A2m (2004)"), so that is the primary source; the
     * trailing number of the file name ("gene_A2m_2004.md") is the fallback. Returns null when
     * neither yields a number.
     */
    private static String resolveRgdId(String displayName, String rawName) {
        if (displayName != null) {
            Matcher m = RGD_ID_IN_PARENS.matcher(displayName);
            String last = null;
            while (m.find()) {
                last = m.group(1);   // last parenthesised number wins (the id trails the name)
            }
            if (last != null) {
                return last;
            }
        }
        if (rawName != null) {
            String base = rawName.endsWith(".md") ? rawName.substring(0, rawName.length() - 3) : rawName;
            int u = base.lastIndexOf('_');
            if (u >= 0 && u < base.length() - 1) {
                String tail = base.substring(u + 1);
                if (!tail.isEmpty() && tail.chars().allMatch(Character::isDigit)) {
                    return tail;
                }
            }
        }
        return null;
    }

    /** Pull the display name from the leading {@code <!-- file_name: ... -->} comment. */
    private static String resolveDisplayName(String content, String fallback) {
        if (content.startsWith("<!-- file_name:")) {
            int end = content.indexOf("-->");
            if (end > 0) {
                String name = content.substring("<!-- file_name:".length(), end).trim();
                if (!name.isEmpty()) {
                    return name;
                }
            }
        }
        return fallback;
    }

    // ---- Spring-injected properties ----

    public void setProvider(String provider) { this.provider = provider; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setModel(String model) { this.model = model; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }
    public void setApiKeyFile(String apiKeyFile) { this.apiKeyFile = apiKeyFile; }
    public void setThreadCount(int threadCount) { this.threadCount = threadCount; }
}
