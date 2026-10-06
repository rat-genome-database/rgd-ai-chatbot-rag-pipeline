-- Step 2 of 2: HNSW index for the qwen index, matching public's
-- idx_document_embeddings_vector. Run after the first full embed into qwen.document_embeddings.
--
-- CONCURRENTLY keeps the table writable while it builds; it cannot run inside a transaction.
-- More maintenance_work_mem makes the build much faster: the 1536-dim vectors alone are ~7GB,
-- and pgvector falls back to a slow on-disk build once the graph outgrows this. hoshi runs
-- with small settings (64MB maintenance_work_mem, 4GB effective_cache_size), so 2GB is a
-- cautious bump; raise it only after confirming the server's free RAM.

SET maintenance_work_mem = '2GB';

CREATE INDEX CONCURRENTLY idx_qwen_document_embeddings_vector
    ON qwen.document_embeddings USING hnsw (embedding vector_cosine_ops);
