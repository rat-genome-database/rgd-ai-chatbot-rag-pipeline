package edu.mcw.rgd.chatBotEmbed.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns text into an embedding vector by calling an HTTP embeddings API. Supports
 * two providers so the pipeline can match whichever the chatbot is configured for:
 *
 * <ul>
 *   <li><b>openai</b> — {@code POST {baseUrl}/embeddings}, bearer auth, optional
 *       {@code dimensions}; reads {@code data[0].embedding}.</li>
 *   <li><b>ollama</b> — {@code POST {baseUrl}/api/embed} with a list of inputs and optional
 *       {@code dimensions}; reads {@code embeddings[]}. One call embeds up to {@code batchSize}
 *       chunks, which on a local GPU costs little more than embedding one.</li>
 * </ul>
 *
 * <p><b>The model and dimensions MUST match the chatbot's query-side embedding model</b>
 * (its {@code spring.ai.*.embedding} settings). Vectors from different models are not
 * comparable, so a mismatch silently wrecks retrieval rather than erroring.</p>
 */
public class EmbeddingClient {

    private final String provider;
    private final String baseUrl;
    private final String model;
    private final int dimensions;   // 0 = don't send a dimensions override
    private final String apiKey;    // null/empty allowed (e.g. local Ollama)
    private final int batchSize;    // inputs per Ollama request
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public EmbeddingClient(String provider, String baseUrl, String model, int dimensions, String apiKey) {
        this(provider, baseUrl, model, dimensions, apiKey, 1);
    }

    public EmbeddingClient(String provider, String baseUrl, String model, int dimensions, String apiKey,
                           int batchSize) {
        this.provider = provider == null ? "openai" : provider.trim().toLowerCase();
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model;
        this.dimensions = dimensions;
        this.apiKey = apiKey;
        this.batchSize = Math.max(1, batchSize);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public float[] embed(String text) throws Exception {
        return embedAll(List.of(text)).get(0);
    }

    /**
     * Embed every text, returning vectors in the same order. Ollama receives them in batches
     * of {@code batchSize}; OpenAI keeps its one-request-per-chunk behaviour.
     */
    public List<float[]> embedAll(List<String> texts) throws Exception {
        List<float[]> out = new ArrayList<>(texts.size());
        if ("ollama".equals(provider)) {
            for (int from = 0; from < texts.size(); from += batchSize) {
                out.addAll(embedOllama(texts.subList(from, Math.min(texts.size(), from + batchSize))));
            }
        } else {
            for (String text : texts) {
                out.add(embedOpenAi(text));
            }
        }
        return out;
    }

    private float[] embedOpenAi(String text) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", text);
        if (dimensions > 0) {
            body.put("dimensions", dimensions);
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/embeddings"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (apiKey != null && !apiKey.isEmpty()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }
        JsonNode root = send(rb.build());
        return checkDimensions(toFloatArray(root.path("data").path(0).path("embedding")));
    }

    private List<float[]> embedOllama(List<String> texts) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        texts.forEach(body.putArray("input")::add);
        if (dimensions > 0) {
            // Matryoshka models (qwen3-embedding) truncate and re-normalize server-side.
            body.put("dimensions", dimensions);
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embed"))
                // Generous: the first request after Ollama unloads the model pays to reload it.
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        JsonNode embeddings = send(req).path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != texts.size()) {
            throw new RuntimeException("expected " + texts.size() + " embeddings from Ollama, got "
                    + (embeddings.isArray() ? embeddings.size() : "none"));
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (JsonNode e : embeddings) {
            out.add(checkDimensions(toFloatArray(e)));
        }
        return out;
    }

    /**
     * Fail on a vector of the wrong size. An Ollama that ignores {@code dimensions} (too old to
     * support it) would otherwise surface only as a confusing pgvector cast error at insert time.
     */
    private float[] checkDimensions(float[] v) {
        if (dimensions > 0 && v.length != dimensions) {
            throw new RuntimeException("model " + model + " returned " + v.length
                    + "-dim vectors, expected " + dimensions);
        }
        return v;
    }

    /** Send with retry on 429 / 5xx (exponential backoff, capped). */
    private JsonNode send(HttpRequest req) throws Exception {
        int attempt = 0;
        while (true) {
            attempt++;
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc >= 200 && sc < 300) {
                return mapper.readTree(resp.body());
            }
            if ((sc == 429 || sc >= 500) && attempt < 5) {
                Thread.sleep(Math.min(30_000L, 1_000L * (1L << attempt)));
                continue;
            }
            throw new RuntimeException("embedding request failed: HTTP " + sc + " — " + truncate(resp.body(), 300));
        }
    }

    private float[] toFloatArray(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            throw new RuntimeException("no embedding array in response");
        }
        float[] out = new float[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) arr.get(i).asDouble();
        }
        return out;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
