package edu.mcw.rgd.chatBotEmbed.embed;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * Writes embedding rows into the Postgres/pgvector {@code document_embeddings} table —
 * the same table the chatbot reads for retrieval. rgdcore's {@code DocumentEmbeddingDAO}
 * can delete and read these rows but not insert them, so this fills that one gap using
 * the same datasource.
 *
 * <p>Columns: {@code embedding} (vector), {@code chunk}, {@code file_name}, {@code created_at}.
 * {@code id} is a sequence default; the vector is passed as a {@code [f1,f2,...]} literal
 * and cast with {@code ::vector}.</p>
 */
public class EmbeddingWriter {

    private static final String INSERT_SQL =
            "INSERT INTO document_embeddings (embedding, chunk, file_name, created_at) "
            + "VALUES (CAST(? AS vector), ?, ?, ?)";

    private final DataSource dataSource;

    public EmbeddingWriter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Insert every chunk of one file in a single batch. {@code embeddings.get(i)} is the
     * vector for {@code chunks.get(i)}.
     *
     * @return number of rows inserted
     */
    public int insert(String fileName, List<String> chunks, List<float[]> embeddings) throws SQLException {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(INSERT_SQL)) {
            for (int i = 0; i < chunks.size(); i++) {
                ps.setString(1, toVectorLiteral(embeddings.get(i)));
                ps.setString(2, chunks.get(i));
                ps.setString(3, fileName);
                ps.setTimestamp(4, now);
                ps.addBatch();
            }
            int[] result = ps.executeBatch();
            return result.length;
        }
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
