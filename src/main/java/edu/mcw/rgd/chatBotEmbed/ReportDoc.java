package edu.mcw.rgd.chatBotEmbed;

/**
 * One generated markdown document, before it is written to disk.
 *
 * The {@link MarkdownWriter} turns this into a file on disk, prepending the
 * {@code <!-- file_name: ... -->} comment that the chatbot's ingest keys off of.
 * Generators therefore produce the body starting at the {@code # Title} line and
 * leave the header comment to the writer.
 */
public class ReportDoc {

    /**
     * Stable identifier this report is for, used as the trailing token of the on-disk file name.
     * For gene/QTL/strain reports this is the RGD ID (as a string); for ontology-term reports it is
     * the term accession with its colon made filename-safe (e.g. {@code MP_0001900}).
     */
    public final String id;

    /** Human-readable name stored in {@code document_embeddings.file_name} and shown as the citation. */
    public final String displayName;

    /** Filename-safe object symbol used to build the file name. */
    public final String safeSymbol;

    /** Full markdown body, starting at the {@code # Title} heading (no file_name comment). */
    public final String markdown;

    public ReportDoc(String id, String displayName, String safeSymbol, String markdown) {
        this.id = id;
        this.displayName = displayName;
        this.safeSymbol = safeSymbol;
        this.markdown = markdown;
    }

    /** Convenience for the RGD-ID-keyed report types (gene, QTL, strain). */
    public ReportDoc(int rgdId, String displayName, String safeSymbol, String markdown) {
        this(String.valueOf(rgdId), displayName, safeSymbol, markdown);
    }
}
