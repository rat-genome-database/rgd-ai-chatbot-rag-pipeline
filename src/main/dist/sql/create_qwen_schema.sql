-- Schema for the qwen3-embedding:8b index, alongside the OpenAI index in public.
--
-- Connections for this index use currentSchema=qwen,public, so unqualified names resolve to
-- the qwen copies of the embedding tables and fall through to the shared tables in public
-- (report_object, report_position, report_load_status) and the pgvector extension.
--
-- Step 1 of 2. Run create_qwen_vector_index.sql after the first full embed: building HNSW
-- once over 1.1M rows is far faster than maintaining it during the bulk insert.
--
-- Run as rgdragowner on hoshi.rgd.mcw.edu / rgd_rag.

BEGIN;

CREATE SCHEMA qwen AUTHORIZATION rgdragowner;

-- document_embeddings: same columns as public, including the generated tsv column.
-- Dimension stays 1536 (qwen3-embedding is asked for 1536 via the Ollama "dimensions" option).
CREATE TABLE qwen.document_embeddings (
    LIKE public.document_embeddings
    INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING CONSTRAINTS INCLUDING STORAGE INCLUDING COMMENTS
);

-- LIKE copies the id default as nextval() on public's sequence; give qwen its own.
CREATE SEQUENCE qwen.document_embeddings_id_seq OWNED BY qwen.document_embeddings.id;
ALTER TABLE qwen.document_embeddings
    ALTER COLUMN id SET DEFAULT nextval('qwen.document_embeddings_id_seq');
ALTER TABLE qwen.document_embeddings ADD PRIMARY KEY (id);

CREATE INDEX idx_qwen_de_rgd_id    ON qwen.document_embeddings USING btree (rgd_id);
CREATE INDEX idx_qwen_de_section   ON qwen.document_embeddings USING btree (section);
CREATE INDEX idx_qwen_de_file_name ON qwen.document_embeddings USING btree (file_name);
CREATE INDEX idx_qwen_de_tsv       ON qwen.document_embeddings USING gin (tsv);

-- embed_status: per-file embedding progress for the chatbot's bulk-embed page; model-specific.
CREATE TABLE qwen.embed_status (
    LIKE public.embed_status
    INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING STORAGE INCLUDING COMMENTS
);
CREATE SEQUENCE qwen.embed_status_id_seq OWNED BY qwen.embed_status.id;
ALTER TABLE qwen.embed_status
    ALTER COLUMN id SET DEFAULT nextval('qwen.embed_status_id_seq');
ALTER TABLE qwen.embed_status ADD PRIMARY KEY (id);

COMMIT;

-- Verify: both defaults should name qwen sequences, and tsv should be generated ('s').
SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod) AS type,
       pg_get_expr(d.adbin, d.adrelid) AS default_expr, a.attgenerated
FROM pg_attribute a
JOIN pg_class c ON c.oid = a.attrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
WHERE n.nspname = 'qwen' AND c.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped
ORDER BY c.relname, a.attnum;
