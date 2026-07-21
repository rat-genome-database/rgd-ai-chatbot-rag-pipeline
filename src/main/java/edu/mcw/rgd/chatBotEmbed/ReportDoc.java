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

    /** RGD ID this report is for; used in the on-disk file name. */
    public final int rgdId;

    /** Human-readable name stored in {@code document_embeddings.file_name} and shown as the citation. */
    public final String displayName;

    /** Filename-safe object symbol used to build the file name. */
    public final String safeSymbol;

    /** Full markdown body, starting at the {@code # Title} heading (no file_name comment). */
    public final String markdown;

    public ReportDoc(int rgdId, String displayName, String safeSymbol, String markdown) {
        this.rgdId = rgdId;
        this.displayName = displayName;
        this.safeSymbol = safeSymbol;
        this.markdown = markdown;
    }
}
