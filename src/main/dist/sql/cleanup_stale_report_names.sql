-- Remove chunks stored under a report name that is no longer the object's current one.
--
-- When a report's display name changes (a renamed gene, a strain symbol written with different
-- markup), the old rows stay in document_embeddings under the old file_name and are retrieved
-- alongside the current report. The embed step now removes them as each report is embedded
-- (DocumentEmbeddingDAO.deleteChunksUnderOtherFileNames); this script clears the existing ones
-- in one pass instead of waiting for the next embed of every report.
--
-- The current name is the one report_object holds, which every embed run rewrites.
-- Run against the schema to clean (public for the OpenAI index). Review step 1 before step 2.

-- Step 1: what would be deleted, by report type.
SELECT split_part(e.file_name, ' ', 2) AS report_type,
       COUNT(DISTINCT e.rgd_id)        AS objects,
       COUNT(DISTINCT e.file_name)     AS stale_names,
       COUNT(*)                        AS chunks
FROM document_embeddings e
JOIN report_object o ON o.rgd_id = e.rgd_id
WHERE e.file_name <> o.file_name
GROUP BY 1
ORDER BY 4 DESC;

-- Step 1b: rows from before report names carried a species ("RGD Gene Report - A2m (2004)"),
-- written before the rgd_id column existed. They duplicate the current report for that object.
SELECT e.file_name, COUNT(*) AS chunks
FROM document_embeddings e
JOIN report_object o ON o.rgd_id = substring(e.file_name FROM '\((\d+)\)$')::bigint
WHERE e.rgd_id IS NULL
  AND e.file_name ~ '^RGD \S+ Report - .+ \(\d+\)$'
  AND e.file_name !~ '\([^()]+\) \(\d+\)$'
GROUP BY 1;

-- Step 2: delete them.
BEGIN;
DELETE FROM document_embeddings e
USING report_object o
WHERE o.rgd_id = e.rgd_id
  AND e.file_name <> o.file_name;
DELETE FROM document_embeddings e
USING report_object o
WHERE o.rgd_id = substring(e.file_name FROM '\((\d+)\)$')::bigint
  AND e.rgd_id IS NULL
  AND e.file_name ~ '^RGD \S+ Report - .+ \(\d+\)$'
  AND e.file_name !~ '\([^()]+\) \(\d+\)$';
-- Check the row count reported above, then COMMIT (or ROLLBACK).
-- COMMIT;
