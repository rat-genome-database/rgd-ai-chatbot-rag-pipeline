package edu.mcw.rgd.chatBotEmbed.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import edu.mcw.rgd.dao.impl.DocumentEmbeddingDAO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code --mode batch-embed}: the embed step, with vectors bought through the OpenAI Batch API
 * (asynchronous, within 24 hours, at a discount) instead of live requests.
 *
 * <p>The run has three parts, and only the middle one differs from {@code --mode embed}:</p>
 * <ol>
 *   <li><b>Plan.</b> Each report is chunked and compared with what is stored, exactly as the
 *       embed step does, to find the chunk texts that need a vector. Reports that need none
 *       (unchanged, or with only deletions) are handled immediately by the ordinary embed
 *       logic.</li>
 *   <li><b>Batch.</b> The texts that need vectors are packed into batches of at most
 *       {@code batchMaxInputs} (OpenAI allows 50,000 embedding inputs per batch), each line a
 *       normal embeddings request of up to 2,048 inputs and {@code batchMaxRequestTokens}
 *       tokens. A report is never split across batches. At most {@code batchMaxInFlight}
 *       batches, and {@code batchMaxQueuedTokens} tokens, are queued at once, so the run stays
 *       inside the organization's batch queue limit.</li>
 *   <li><b>Write.</b> When a batch finishes, every report in it goes through the ordinary embed
 *       logic again with the batch's vectors in place of live calls, so change detection,
 *       partial updates, stale-name cleanup and the object tables behave exactly as in
 *       {@code --mode embed}. A report whose file changed after submission, or whose request
 *       failed, finds a vector missing and is reported as failed; the next run picks it up.</li>
 * </ol>
 *
 * <p>Each queued batch is recorded under {@code batchDir} (its request file and a manifest).
 * A run that stops for any reason resumes those batches on the next start instead of paying
 * for them again.</p>
 */
class BatchEmbedRun {

    private static final Logger LOG = LogManager.getLogger("embed");
    private static final int MAX_INPUTS_PER_REQUEST = 2048;
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final EmbedService service;
    private final DocumentEmbeddingDAO dao;
    private final EmbeddingWriter writer;
    private final OpenAiBatchClient client;
    private final Path stateDir;
    private final boolean force;
    private final ObjectMapper json = new ObjectMapper();
    private final Encoding tokens = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private final List<Slice> inFlight = new ArrayList<>();
    private long lastPoll = 0;
    private int sliceSeq = 0;

    // run totals
    private final AtomicInteger written = new AtomicInteger();
    private final AtomicInteger unchanged = new AtomicInteger();
    private final AtomicInteger changed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger chunks = new AtomicInteger();
    private final AtomicLong billedTokens = new AtomicLong();
    private final List<String> failedFiles = Collections.synchronizedList(new ArrayList<>());

    BatchEmbedRun(EmbedService service, DocumentEmbeddingDAO dao, EmbeddingWriter writer,
                  OpenAiBatchClient client, boolean force) {
        this.service = service;
        this.dao = dao;
        this.writer = writer;
        this.client = client;
        this.stateDir = Paths.get(service.getBatchDir());
        this.force = force;
    }

    void run(List<Path> files) throws Exception {
        Files.createDirectories(stateDir);
        Set<String> resumedDocs = resume();

        LOG.info("Batch embed: planning {} markdown file(s) ({} already queued from an earlier run); "
                        + "at most {} batch(es) / {} tokens queued, {} inputs per batch",
                files.size(), resumedDocs.size(), service.getBatchMaxInFlight(),
                service.getBatchMaxQueuedTokens() > 0 ? String.format("%,d", service.getBatchMaxQueuedTokens()) : "unlimited",
                service.getBatchMaxInputs());

        Slice slice = new Slice();
        int planned = 0;
        for (Path file : files) {
            pollIfDue();
            String key = file.toAbsolutePath().toString();
            if (resumedDocs.contains(key)) {
                continue;   // its vectors are already being bought by a resumed batch
            }
            try {
                Plan plan = plan(file);
                if (plan == null) {
                    continue;
                }
                if (plan.needed.isEmpty()) {
                    // Unchanged, or only deletions: the ordinary logic needs no vectors.
                    record(service.processDocument(plan.rawName, plan.content, NO_VECTORS, dao, writer,
                            force, unchanged, changed));
                    continue;
                }
                if (!slice.fits(plan)) {
                    submit(slice);
                    slice = new Slice();
                }
                slice.add(plan, key);
            } catch (Exception e) {
                fail(file.getFileName().toString(), e);
            }
            if (++planned % 10_000 == 0) {
                LOG.info("  ... {} files planned ({} batch(es) queued, {} written so far)",
                        planned, inFlight.size(), written.get());
            }
        }
        if (!slice.docs.isEmpty()) {
            submit(slice);
        }

        while (!inFlight.isEmpty()) {
            sleepPoll();
            poll();
        }

        String summary = String.format("Batch embed done. written=%d (changed=%d) unchanged=%d failed=%d chunks=%d billed_tokens=%,d",
                written.get(), changed.get(), unchanged.get(), failed.get(), chunks.get(), billedTokens.get());
        LOG.info(summary);
        System.out.println(summary);
        if (!failedFiles.isEmpty()) {
            LOG.error("{} file(s) failed (rerun to retry them):", failedFiles.size());
            for (String f : failedFiles) {
                LOG.error("  FAILED: {}", f);
            }
        }
    }

    // ---- planning --------------------------------------------------------------------------

    /** What one report needs: its chunks, and those whose text has no stored vector. */
    private record Plan(String rawName, String content, List<String> needed, int neededTokens, long neededBytes) {}

    private Plan plan(Path file) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String rawName = file.getFileName().toString();
        if (!edu.mcw.rgd.chatBotEmbed.chunker.ReportMarkdownChunker.isRgdReport(content)) {
            return null;
        }
        String displayName = EmbedService.resolveDisplayName(content, rawName);
        List<String> all = service.chunksFor(displayName, rawName, content);
        if (all.isEmpty()) {
            return null;
        }

        List<String> needed;
        List<String> stored = force ? List.of() : dao.getChunksByFileName(displayName);
        if (force || stored.isEmpty()) {
            needed = all;
        } else if (all.equals(stored)) {
            needed = List.of();
        } else {
            // Same arithmetic as the partial update: a stored copy covers one occurrence.
            Map<String, Integer> remaining = new HashMap<>();
            for (String s : stored) {
                remaining.merge(s, 1, Integer::sum);
            }
            needed = new ArrayList<>();
            for (String c : all) {
                int left = remaining.getOrDefault(c, 0);
                if (left > 0) {
                    remaining.put(c, left - 1);
                } else {
                    needed.add(c);
                }
            }
        }
        int tok = 0;
        long bytes = 0;
        for (String c : needed) {
            tok += tokens.countTokens(c);
            bytes += c.getBytes(StandardCharsets.UTF_8).length;
        }
        return new Plan(rawName, content, needed, tok, bytes);
    }

    // ---- slices ----------------------------------------------------------------------------

    /** The reports and distinct texts going into one batch. */
    private class Slice {
        String id;
        final List<String> docs = new ArrayList<>();
        final LinkedHashMap<String, Integer> texts = new LinkedHashMap<>();   // text -> tokens
        long tokens;
        long bytes;
        // set once submitted
        Path jsonl;
        String inputFileId;
        String batchId;
        int attempts;

        boolean fits(Plan p) {
            if (docs.isEmpty()) {
                return true;   // a report larger than the limits still goes, alone
            }
            int newTexts = 0;
            for (String t : p.needed) {
                if (!texts.containsKey(t)) newTexts++;
            }
            long maxQueued = service.getBatchMaxQueuedTokens();
            return texts.size() + newTexts <= service.getBatchMaxInputs()
                    && bytes + p.neededBytes <= service.getBatchMaxFileBytes()
                    && (maxQueued <= 0 || tokens + p.neededTokens <= maxQueued);
        }

        void add(Plan p, String docKey) {
            docs.add(docKey);
            for (String t : p.needed) {
                if (!texts.containsKey(t)) {
                    int n = BatchEmbedRun.this.tokens.countTokens(t);
                    texts.put(t, n);
                    tokens += n;
                    bytes += t.getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }
    }

    /** Write the slice's request file, wait for queue room, upload and create the batch. */
    private void submit(Slice s) throws Exception {
        s.id = LocalDateTime.now().format(ID_TIME) + "-" + (++sliceSeq);
        s.jsonl = stateDir.resolve(s.id + ".jsonl");
        writeRequests(s);
        waitForRoom(s.tokens);
        s.inputFileId = client.uploadBatchFile(s.jsonl);
        create(s);
        saveManifest(s);
        inFlight.add(s);
        LOG.info("Queued batch {} ({}): {} report(s), {} input(s), {} tokens",
                s.batchId, s.id, s.docs.size(), s.texts.size(), String.format("%,d", s.tokens));
    }

    private void create(Slice s) throws Exception {
        s.attempts++;
        JsonNode batch = client.createBatch(s.inputFileId, "rgd-ai-chatbot-rag-pipeline " + s.id);
        s.batchId = batch.path("id").asText();
    }

    /** One line per request; each request carries up to 2,048 inputs within the token cap. */
    private void writeRequests(Slice s) throws Exception {
        try (BufferedWriter w = Files.newBufferedWriter(s.jsonl, StandardCharsets.UTF_8)) {
            List<String> batch = new ArrayList<>();
            long batchTokens = 0;
            int req = 0;
            for (Map.Entry<String, Integer> e : s.texts.entrySet()) {
                if (!batch.isEmpty() && (batch.size() >= MAX_INPUTS_PER_REQUEST
                        || batchTokens + e.getValue() > service.getBatchMaxRequestTokens())) {
                    writeRequest(w, "r" + (req++), batch);
                    batch = new ArrayList<>();
                    batchTokens = 0;
                }
                batch.add(e.getKey());
                batchTokens += e.getValue();
            }
            if (!batch.isEmpty()) {
                writeRequest(w, "r" + req, batch);
            }
        }
    }

    private void writeRequest(BufferedWriter w, String customId, List<String> inputs) throws Exception {
        ObjectNode line = json.createObjectNode();
        line.put("custom_id", customId);
        line.put("method", "POST");
        line.put("url", "/v1/embeddings");
        ObjectNode body = line.putObject("body");
        body.put("model", service.getModel());
        ArrayNode in = body.putArray("input");
        inputs.forEach(in::add);
        if (service.getDimensions() > 0) {
            body.put("dimensions", service.getDimensions());
        }
        body.put("encoding_format", "base64");   // a quarter of the size of JSON floats to download
        w.write(json.writeValueAsString(line));
        w.write('\n');
    }

    private void waitForRoom(long newTokens) throws Exception {
        while (!hasRoom(newTokens)) {
            sleepPoll();
            poll();
        }
    }

    private boolean hasRoom(long newTokens) {
        if (inFlight.size() >= service.getBatchMaxInFlight()) {
            return false;
        }
        long maxQueued = service.getBatchMaxQueuedTokens();
        if (maxQueued <= 0 || inFlight.isEmpty()) {
            return true;   // a lone batch is always tried; OpenAI rejects it if it is too big
        }
        long queued = 0;
        for (Slice s : inFlight) {
            queued += s.tokens;
        }
        return queued + newTokens <= maxQueued;
    }

    // ---- polling and collection ----------------------------------------------------------

    private void pollIfDue() throws Exception {
        if (!inFlight.isEmpty() && System.currentTimeMillis() - lastPoll >= service.getBatchPollSeconds() * 1000L) {
            poll();
        }
    }

    private void sleepPoll() throws InterruptedException {
        long wait = service.getBatchPollSeconds() * 1000L - (System.currentTimeMillis() - lastPoll);
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }

    private void poll() throws Exception {
        lastPoll = System.currentTimeMillis();
        for (Slice s : new ArrayList<>(inFlight)) {
            JsonNode batch;
            try {
                batch = client.getBatch(s.batchId);
            } catch (Exception e) {
                LOG.warn("Could not check batch {}: {}", s.batchId, e.getMessage());
                continue;
            }
            String status = batch.path("status").asText();
            if (!OpenAiBatchClient.isFinal(status)) {
                continue;
            }
            if ("failed".equals(status)) {
                handleFailed(s, batch);
                continue;
            }
            inFlight.remove(s);
            collect(s, batch);   // completed, or expired/cancelled with partial results
        }
    }

    /**
     * A batch that failed validation bought nothing. The usual cause on a large run is the
     * queue limit, which frees up as other batches finish, so it is retried later (same uploaded
     * file, no new charge); after three attempts its reports are left for the next run.
     */
    private void handleFailed(Slice s, JsonNode batch) throws Exception {
        String errors = batch.path("errors").path("data").toString();
        if (s.attempts < 3 && inFlight.size() > 1) {
            LOG.warn("Batch {} ({}) failed validation, will retry when other batches finish: {}", s.batchId, s.id, errors);
            inFlight.remove(s);
            waitForRoomExcluding(s);
            create(s);
            saveManifest(s);
            inFlight.add(s);
            LOG.info("Re-queued {} as batch {} (attempt {})", s.id, s.batchId, s.attempts);
            return;
        }
        inFlight.remove(s);
        LOG.error("Batch {} ({}) failed and will not be retried: {}", s.batchId, s.id, errors);
        for (String doc : s.docs) {
            fail(Paths.get(doc).getFileName().toString(), new IllegalStateException("batch " + s.batchId + " failed"));
        }
        cleanup(s, batch);
    }

    private void waitForRoomExcluding(Slice s) throws Exception {
        // wait until at least one other batch has finished
        int before = inFlight.size();
        while (inFlight.size() >= before && !inFlight.isEmpty()) {
            sleepPoll();
            poll();
        }
    }

    /** Download a finished batch's vectors and write every report in it. */
    private void collect(Slice s, JsonNode batch) throws Exception {
        JsonNode counts = batch.path("request_counts");
        long used = batch.path("usage").path("input_tokens").asLong(0);
        billedTokens.addAndGet(used);
        LOG.info("Batch {} ({}) {}: {}/{} request(s) completed, {} failed{}",
                s.batchId, s.id, batch.path("status").asText(), counts.path("completed").asInt(),
                counts.path("total").asInt(), counts.path("failed").asInt(),
                used > 0 ? String.format(", %,d tokens", used) : "");

        Map<String, List<String>> requestInputs = readRequests(s.jsonl);
        Map<String, float[]> vectors = new HashMap<>(s.texts.isEmpty() ? 16 : s.texts.size() * 2);

        String outputId = batch.path("output_file_id").asText(null);
        if (outputId != null && !outputId.isEmpty() && !"null".equals(outputId)) {
            Path out = stateDir.resolve(s.id + ".out.jsonl");
            client.download(outputId, out);
            readVectors(out, requestInputs, vectors);
            Files.deleteIfExists(out);
        }
        String errorId = batch.path("error_file_id").asText(null);
        if (errorId != null && !errorId.isEmpty() && !"null".equals(errorId)) {
            Path err = stateDir.resolve(s.id + ".err.jsonl");
            client.download(errorId, err);
            logErrors(err);
            Files.deleteIfExists(err);
        }

        Embedder fromBatch = texts -> {
            List<float[]> out = new ArrayList<>(texts.size());
            for (String t : texts) {
                float[] v = vectors.get(t);
                if (v == null) {
                    throw new IllegalStateException("no vector from batch " + s.batchId + " for a chunk of this "
                            + "report (the file changed after submission, or its request failed)");
                }
                out.add(v);
            }
            return out;
        };

        ExecutorService pool = Executors.newFixedThreadPool(service.getThreadCount());
        List<Future<?>> futures = new ArrayList<>(s.docs.size());
        for (String doc : s.docs) {
            futures.add(pool.submit(() -> {
                Path file = Paths.get(doc);
                try {
                    if (!Files.exists(file)) {
                        return;   // removed since submission (e.g. pruned by a generate run)
                    }
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    record(service.processDocument(file.getFileName().toString(), content, fromBatch,
                            dao, writer, force, unchanged, changed));
                } catch (Exception e) {
                    fail(file.getFileName().toString(), e);
                }
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignore) { /* logged per report */ }
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);

        cleanup(s, batch);
        LOG.info("  ... {} report(s) written so far, {} chunk(s), {} batch(es) still queued",
                written.get(), chunks.get(), inFlight.size());
    }

    private Map<String, List<String>> readRequests(Path jsonl) throws Exception {
        Map<String, List<String>> out = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n = json.readTree(line);
                List<String> inputs = new ArrayList<>();
                n.path("body").path("input").forEach(x -> inputs.add(x.asText()));
                out.put(n.path("custom_id").asText(), inputs);
            }
        }
        return out;
    }

    private void readVectors(Path out, Map<String, List<String>> requestInputs, Map<String, float[]> vectors)
            throws Exception {
        try (BufferedReader r = Files.newBufferedReader(out, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n = json.readTree(line);
                List<String> inputs = requestInputs.get(n.path("custom_id").asText());
                JsonNode resp = n.path("response");
                if (inputs == null || resp.path("status_code").asInt() != 200) {
                    continue;
                }
                for (JsonNode d : resp.path("body").path("data")) {
                    int idx = d.path("index").asInt(-1);
                    if (idx < 0 || idx >= inputs.size()) continue;
                    float[] v = decode(d.path("embedding"));
                    if (service.getDimensions() > 0 && v.length != service.getDimensions()) {
                        throw new IllegalStateException("batch returned " + v.length + "-dim vectors, expected "
                                + service.getDimensions());
                    }
                    vectors.put(inputs.get(idx), v);
                }
            }
        }
    }

    /** A vector sent as base64 little-endian float32, or as a JSON array. */
    private static float[] decode(JsonNode e) {
        if (e.isTextual()) {
            FloatBuffer fb = ByteBuffer.wrap(Base64.getDecoder().decode(e.asText()))
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[] v = new float[fb.remaining()];
            fb.get(v);
            return v;
        }
        float[] v = new float[e.size()];
        for (int i = 0; i < v.length; i++) v[i] = (float) e.get(i).asDouble();
        return v;
    }

    private void logErrors(Path err) throws Exception {
        int n = 0;
        try (BufferedReader r = Files.newBufferedReader(err, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                if (n++ < 5) {
                    JsonNode e = json.readTree(line);
                    JsonNode body = e.path("response").path("body").path("error");
                    LOG.warn("  request {} failed: {}", e.path("custom_id").asText(),
                            e.path("error").isNull() || e.path("error").isMissingNode() ? body.toString() : e.path("error").toString());
                }
            }
        }
        if (n > 0) {
            LOG.warn("  {} request(s) in the error file; their reports will be retried by the next run", n);
        }
    }

    private void cleanup(Slice s, JsonNode batch) throws Exception {
        Files.deleteIfExists(s.jsonl);
        Files.deleteIfExists(manifestPath(s));
        client.deleteFile(s.inputFileId);
        client.deleteFile(batch.path("output_file_id").asText(null));
        client.deleteFile(batch.path("error_file_id").asText(null));
    }

    // ---- state on disk ---------------------------------------------------------------------

    private Path manifestPath(Slice s) {
        return stateDir.resolve(s.id + ".json");
    }

    private void saveManifest(Slice s) throws Exception {
        ObjectNode m = json.createObjectNode();
        m.put("id", s.id);
        m.put("batchId", s.batchId);
        m.put("inputFileId", s.inputFileId);
        m.put("jsonl", s.jsonl.toString());
        m.put("tokens", s.tokens);
        m.put("attempts", s.attempts);
        m.put("force", force);
        ArrayNode docs = m.putArray("docs");
        s.docs.forEach(docs::add);
        Path tmp = stateDir.resolve(s.id + ".json.tmp");
        Files.writeString(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsString(m), StandardCharsets.UTF_8);
        Files.move(tmp, manifestPath(s), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** Pick up batches queued by an earlier run; returns the reports they cover. */
    private Set<String> resume() throws Exception {
        Set<String> docs = new HashSet<>();
        List<Path> manifests;
        try (var s = Files.list(stateDir)) {
            manifests = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        for (Path p : manifests) {
            JsonNode m = json.readTree(Files.readString(p, StandardCharsets.UTF_8));
            Slice s = new Slice();
            s.id = m.path("id").asText();
            s.batchId = m.path("batchId").asText();
            s.inputFileId = m.path("inputFileId").asText();
            s.jsonl = Paths.get(m.path("jsonl").asText());
            s.tokens = m.path("tokens").asLong();
            s.attempts = m.path("attempts").asInt(1);
            m.path("docs").forEach(d -> s.docs.add(d.asText()));
            if (m.path("force").asBoolean(false) != force) {
                LOG.warn("Resumed batch {} was queued with force={}; writing it with force={}",
                        s.batchId, m.path("force").asBoolean(false), force);
            }
            if (!Files.exists(s.jsonl)) {
                LOG.error("Resumed batch {} has lost its request file {}; dropping it (its reports will be re-planned)",
                        s.batchId, s.jsonl);
                Files.deleteIfExists(p);
                continue;
            }
            inFlight.add(s);
            docs.addAll(s.docs);
            LOG.info("Resuming batch {} ({}) with {} report(s)", s.batchId, s.id, s.docs.size());
        }
        return docs;
    }

    // ---- bookkeeping -----------------------------------------------------------------------

    private void record(int n) {
        if (n >= 0) {
            written.incrementAndGet();
            chunks.addAndGet(n);
        }
    }

    private void fail(String name, Exception e) {
        failed.incrementAndGet();
        failedFiles.add(name + " — " + e.getMessage());
        LOG.error("Batch embed failed for {}: {}", name, e.getMessage());
        LOG.debug("Batch embed failure detail for {}", name, e);
    }

    /** For reports that need no new vectors; asking it for one is a bug. */
    private static final Embedder NO_VECTORS = texts -> {
        if (!texts.isEmpty()) {
            throw new IllegalStateException("planned as needing no vectors, but " + texts.size() + " were asked for");
        }
        return List.of();
    };
}
