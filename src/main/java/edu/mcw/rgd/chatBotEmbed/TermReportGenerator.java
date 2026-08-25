package edu.mcw.rgd.chatBotEmbed;

import java.util.List;

/**
 * Builds markdown for an accession-keyed RGD object type — currently ontology terms, whose
 * identity is a term accession ({@code MP:0001900}) rather than an integer RGD ID.
 *
 * <p>This mirrors {@link ReportGenerator} but keys on {@code String} accessions instead of
 * {@code int} RGD IDs, and is not species-scoped (a term is shared across species; only its
 * annotation counts break down by species). {@link Manager} runs these on a separate code path
 * from the RGD-ID-keyed generators.</p>
 */
public interface TermReportGenerator {

    /** Report type directory name and file-name prefix, e.g. {@code "ontology"}. */
    String getReportType();

    /**
     * All term accessions to generate reports for.
     *
     * @param ontologyIdFilter a single ontology id/prefix to restrict to (e.g. {@code "MP"}),
     *                         or null/blank to enumerate every public ontology
     */
    List<String> getAccessions(String ontologyIdFilter) throws Exception;

    /**
     * Build the document for one term accession, or {@code null} to skip it.
     *
     * @param speciesTypeKey restrict annotation counts/detail to this species, or {@code 0} for all
     * @param objectKey      restrict annotation counts/detail to this RGD object type
     *                       (see {@code RgdId.OBJECT_KEY_*}), or {@code 0} for all object types
     */
    ReportDoc build(String accId, int speciesTypeKey, int objectKey) throws Exception;
}
