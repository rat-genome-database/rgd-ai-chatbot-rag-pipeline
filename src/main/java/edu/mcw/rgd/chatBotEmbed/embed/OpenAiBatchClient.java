package edu.mcw.rgd.chatBotEmbed.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The OpenAI Batch API calls {@link EmbedService#runBatch} needs: upload a JSONL request file,
 * create a batch for {@code /v1/embeddings}, poll it, download its output and error files, and
 * delete files afterwards.
 *
 * <p>A batch is processed asynchronously within a 24-hour window at a discount over the live
 * endpoint. Each request line is an ordinary embeddings request, so the vectors match what
 * {@link EmbeddingClient} would get for the same text.</p>
 */
public class OpenAiBatchClient {

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiBatchClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    /** Upload a JSONL request file with purpose "batch"; returns the file id. */
    public String uploadBatchFile(Path jsonl) throws Exception {
        String boundary = "----rgd" + UUID.randomUUID().toString().replace("-", "");
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                + "batch\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + jsonl.getFileName() + "\"\r\n"
                + "Content-Type: application/jsonl\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/files"))
                .timeout(Duration.ofMinutes(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new SequenceInputStream(
                        new SequenceInputStream(stream(head), openQuietly(jsonl)), stream(tail))))
                .build();
        return send(req).path("id").asText();
    }

    /** Create an embeddings batch over an uploaded file; returns the batch object. */
    public JsonNode createBatch(String inputFileId, String description) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("input_file_id", inputFileId);
        body.put("endpoint", "/v1/embeddings");
        body.put("completion_window", "24h");
        body.putObject("metadata").put("description", description.length() > 500 ? description.substring(0, 500) : description);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/batches"))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return send(req);
    }

    /** The current batch object (status, output_file_id, error_file_id, request_counts, usage). */
    public JsonNode getBatch(String batchId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/batches/" + batchId))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + apiKey)
                .GET().build();
        return send(req);
    }

    /** Stream a file's content to disk. */
    public void download(String fileId, Path dest) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/files/" + fileId + "/content"))
                .timeout(Duration.ofMinutes(60))
                .header("Authorization", "Bearer " + apiKey)
                .GET().build();
        HttpResponse<Path> resp = withRetry(() -> http.send(req, HttpResponse.BodyHandlers.ofFile(dest)));
        if (resp.statusCode() / 100 != 2) {
            String body = Files.exists(dest) ? Files.readString(dest) : "";
            throw new IllegalStateException("download of " + fileId + " failed: HTTP " + resp.statusCode()
                    + " " + truncate(body));
        }
    }

    /** Delete a file from OpenAI storage; failures are ignored (files also expire on their own). */
    public void deleteFile(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/files/" + fileId))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + apiKey)
                    .DELETE().build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignore) {
            // best effort
        }
    }

    private JsonNode send(HttpRequest req) throws Exception {
        HttpResponse<String> resp = withRetry(() -> http.send(req, HttpResponse.BodyHandlers.ofString()));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException(req.method() + " " + req.uri().getPath() + " failed: HTTP "
                    + resp.statusCode() + " " + truncate(resp.body()));
        }
        return mapper.readTree(resp.body());
    }

    /** Retry transport errors, 429 and 5xx with backoff; other responses are returned as-is. */
    private <T> HttpResponse<T> withRetry(Call<T> call) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 6; attempt++) {
            try {
                HttpResponse<T> resp = call.run();
                int sc = resp.statusCode();
                if (sc != 429 && sc < 500) {
                    return resp;
                }
                last = new IllegalStateException("HTTP " + sc);
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(Math.min(120_000L, 2_000L * (1L << attempt)));
        }
        throw last;
    }

    private interface Call<T> {
        HttpResponse<T> run() throws Exception;
    }

    private static InputStream stream(String s) {
        return new java.io.ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream openQuietly(Path p) {
        try {
            return Files.newInputStream(p);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 500 ? s.substring(0, 500) + "..." : s);
    }

    /** Statuses after which a batch will not change again. */
    public static boolean isFinal(String status) {
        return List.of("completed", "failed", "expired", "cancelled").contains(status);
    }
}
