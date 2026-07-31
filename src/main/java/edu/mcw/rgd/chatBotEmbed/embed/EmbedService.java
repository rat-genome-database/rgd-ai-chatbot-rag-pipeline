package edu.mcw.rgd.chatBotEmbed.embed;

import edu.mcw.rgd.chatBotEmbed.chunker.ReportMarkdownChunker;
import edu.mcw.rgd.dao.impl.DocumentEmbeddingDAO;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * chunks are written. Without {@code --force}, files already present in the table are
 * skipped.</p>
 */
public class EmbedService {

    private static final Logger LOG = LogManager.getLogger("status");
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
     * @param subPath optional report-type subdirectory (e.g. "gene"); null/blank = everything
     * @param force   re-embed files already present in the table
     */
    public void run(String outputDir, String subPath, boolean force) throws Exception {
        Path root = Paths.get(outputDir);
        if (subPath != null && !subPath.isBlank()) {
            root = root.resolve(subPath);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("not a directory: " + root);
        }

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
             .forEach(files::add);
        }

        LOG.info("Embedding {} markdown files under {} (provider={}, model={}, dims={}, threads={})",
                files.size(), root, provider, model, dimensions, threadCount);

        AtomicInteger embedded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger chunksWritten = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>(files.size());
        for (Path file : files) {
            futures.add(pool.submit(() -> {
                try {
                    int n = processFile(file, client, embeddingDAO, writer, force, skipped);
                    if (n >= 0) {
                        int done = embedded.incrementAndGet();
                        chunksWritten.addAndGet(n);
                        if (done % 200 == 0) {
                            LOG.info("  ... {} files embedded ({} chunks so far)", done, chunksWritten.get());
                        }
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    LOG.error("Embed failed for {}: {}", file.getFileName(), e.getMessage());
                    Utils.printStackTrace(e,LOG);
                }
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignore) { /* per-file errors already logged */ }
        }
        pool.shutdown();
        pool.awaitTermination(12, TimeUnit.HOURS);

        String summary = String.format("Embed done. files=%d skipped=%d failed=%d chunks=%d",
                embedded.get(), skipped.get(), failed.get(), chunksWritten.get());
        LOG.info(summary);
        System.out.println(summary);
    }

    /** @return chunks written for this file, or -1 if the file was skipped */
    private int processFile(Path file, EmbeddingClient client, DocumentEmbeddingDAO dao,
                            EmbeddingWriter writer, boolean force, AtomicInteger skipped) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String rawName = file.getFileName().toString();
        String displayName = resolveDisplayName(content, rawName);

        if (!ReportMarkdownChunker.isRgdReport(content)) {
            LOG.warn("Not an RGD report (missing file_name/Report header), skipping: {}", rawName);
            skipped.incrementAndGet();
            return -1;
        }
        if (!force && dao.fileExists(displayName)) {
            skipped.incrementAndGet();
            return -1;
        }

        // Clean slate so a re-embed replaces rather than duplicates.
        dao.deleteByFileName(displayName);
        if (!displayName.equals(rawName)) {
            dao.deleteByFileName(rawName);
        }

        List<String> chunks = new ArrayList<>();
        for (String c : chunker.chunk(content)) {
            if (c != null && c.trim().length() >= MIN_CHUNK_CHARS) {
                chunks.add(c);
            }
        }
        if (chunks.isEmpty()) {
            LOG.warn("No usable chunks produced: {}", rawName);
            return 0;
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
