package edu.mcw.rgd.chatBotEmbed.embed;

import javax.sql.DataSource;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes embedding rows into the Postgres/pgvector {@code document_embeddings} table —
 * the same table the chatbot reads for retrieval. rgdcore's {@code DocumentEmbeddingDAO}
 * can delete and read these rows but not insert them, so this fills that one gap using
 * the same datasource.
 *
 * <p>Columns: {@code embedding} (vector), {@code chunk}, {@code file_name}, {@code created_at},
 * plus {@code rgd_id} and {@code section} so chunks can be filtered without parsing their text.
 * {@code id} is a sequence default; the vector is passed as a {@code [f1,f2,...]} literal
 * and cast with {@code ::vector}.</p>
 */
public class EmbeddingWriter {

    private static final String INSERT_SQL =
            "INSERT INTO document_embeddings (embedding, chunk, file_name, created_at, rgd_id, section) "
            + "VALUES (CAST(? AS vector), ?, ?, ?, ?, ?)";

    /** Row ids of one file's chunks, in the order they were written. */
    private static final String SELECT_IDS_SQL =
            "SELECT id FROM document_embeddings WHERE file_name = ? ORDER BY id";

    private static final String UPDATE_META_SQL =
            "UPDATE document_embeddings SET rgd_id = ?, section = ? WHERE id = ?";

    /** Cheap existence probe for a file with rows predating the metadata columns. */
    private static final String NEEDS_META_SQL =
            "SELECT 1 FROM document_embeddings WHERE file_name = ? AND rgd_id IS NULL LIMIT 1";

    private final DataSource dataSource;

    public EmbeddingWriter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Insert every chunk of one file in a single batch. {@code embeddings.get(i)} and
     * {@code sections.get(i)} both describe {@code chunks.get(i)}.
     *
     * @param rgdId    the object this file describes, or null when it has no numeric RGD ID
     * @param sections per-chunk section path; entries may be null
     * @return number of rows inserted
     */
    public int insert(String fileName, Long rgdId, List<String> chunks,
                      List<String> sections, List<float[]> embeddings) throws SQLException {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(INSERT_SQL)) {
            for (int i = 0; i < chunks.size(); i++) {
                ps.setString(1, toVectorLiteral(embeddings.get(i)));
                ps.setString(2, chunks.get(i));
                ps.setString(3, fileName);
                ps.setTimestamp(4, now);
                if (rgdId == null) {
                    ps.setNull(5, Types.BIGINT);
                } else {
                    ps.setLong(5, rgdId);
                }
                ps.setString(6, sections.get(i));
                ps.addBatch();
            }
            try {
                int[] result = ps.executeBatch();
                return result.length;
            } catch (SQLException e) {
                throw new SQLException("batch insert failed for '" + fileName + "': " + fullBatchMessage(e), e);
            }
        }
    }

    /**
     * Set {@code rgd_id} and {@code section} on a file's existing rows, without re-embedding.
     *
     * <p>Change detection skips a file whose chunk text is unchanged, so rows embedded before
     * these columns existed would never be visited again — and re-embedding the whole corpus
     * to populate two columns would mean hundreds of thousands of needless API calls. Rows
     * come back in insert order, which is the order the chunks were written, so the i-th row
     * belongs to the i-th chunk.</p>
     *
     * @return number of rows updated, or -1 when the stored row count does not match the
     *         chunk count and the alignment cannot be trusted
     */
    /**
     * Whether this file still has rows without metadata.
     *
     * <p>Guards the backfill so it costs one indexed lookup on an already-populated file
     * rather than a read of every row id plus a write of every row. Without it, every
     * incremental run would rewrite the whole corpus's metadata to the values it already
     * holds. A file with no numeric RGD ID never needs the backfill, so it short-circuits
     * without touching the database at all.</p>
     */
    public boolean needsMetadata(String fileName, Long rgdId) throws SQLException {
        if (rgdId == null) {
            return false;
        }
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(NEEDS_META_SQL)) {
            ps.setString(1, fileName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public int backfillMetadata(String fileName, Long rgdId, List<String> sections) throws SQLException {
        try (Connection con = dataSource.getConnection()) {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(SELECT_IDS_SQL)) {
                ps.setString(1, fileName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }

            if (ids.size() != sections.size()) {
                return -1;
            }

            try (PreparedStatement ps = con.prepareStatement(UPDATE_META_SQL)) {
                for (int i = 0; i < ids.size(); i++) {
                    if (rgdId == null) {
                        ps.setNull(1, Types.BIGINT);
                    } else {
                        ps.setLong(1, rgdId);
                    }
                    ps.setString(2, sections.get(i));
                    ps.setLong(3, ids.get(i));
                    ps.addBatch();
                }
                try {
                    return ps.executeBatch().length;
                } catch (SQLException e) {
                    throw new SQLException("metadata backfill failed for '" + fileName + "': "
                            + fullBatchMessage(e), e);
                }
            }
        }
    }

    /**
     * Batch failures wrap the real cause in a chained "next exception" that a plain
     * {@code getMessage()} never shows. Walk both the {@link BatchUpdateException} next-chain
     * and the normal cause chain so the underlying Postgres error (e.g. a failing trigger or a
     * missing column) is always in the log.
     */
    private static String fullBatchMessage(SQLException e) {
        StringBuilder sb = new StringBuilder(e.getMessage());
        SQLException next = (e instanceof BatchUpdateException) ? e.getNextException() : null;
        while (next != null) {
            sb.append(" | caused by: ").append(next.getMessage());
            next = next.getNextException();
        }
        return sb.toString();
    }

    /** Format a float vector as the pgvector text literal {@code [f1,f2,...]}. */
    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
