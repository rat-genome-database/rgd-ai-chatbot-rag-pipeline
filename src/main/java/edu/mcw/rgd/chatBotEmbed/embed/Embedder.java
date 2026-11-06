package edu.mcw.rgd.chatBotEmbed.embed;

import java.util.List;

/**
 * Where a report's chunk vectors come from. {@link EmbeddingClient} calls the embeddings API
 * live; {@link EmbedService#runBatch} supplies vectors already returned by an OpenAI batch, so
 * both paths share the same per-report logic (change detection, partial update, object tables).
 */
public interface Embedder {

    /** One vector per text, in the same order. */
    List<float[]> embedAll(List<String> texts) throws Exception;
}
