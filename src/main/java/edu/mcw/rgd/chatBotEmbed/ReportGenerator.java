package edu.mcw.rgd.chatBotEmbed;

import edu.mcw.rgd.datamodel.Map;

import java.util.List;

/**
 * Builds the markdown for one RGD object type (gene, qtl, strain, ...).
 *
 * Implementations decide <em>what data</em> goes into <em>which section</em>. The
 * on-disk contract (file_name comment, file naming, encoding) is owned by
 * {@link MarkdownWriter}, and orchestration/threading by {@link Manager}, so a
 * generator only has to answer two questions: which RGD IDs, and what markdown.
 */
public interface ReportGenerator {

    /** Report type directory name and file-name prefix, e.g. {@code "gene"}. */
    String getReportType();

    /**
     * Whether reports of this type belong to one species. Species-scoped reports are written
     * under the run's species directory (e.g. {@code gene/rat/}); the others, such as references,
     * under {@code <type>/all/}, whichever species the run names.
     */
    default boolean isSpeciesScoped() {
        return true;
    }

    /** All RGD IDs to generate reports for, for the given species. */
    List<Integer> getRgdIds(int speciesTypeKey) throws Exception;

    /**
     * Build the document for one RGD ID.
     *
     * @param assemblies assemblies whose genomic positions should be included
     *                   (empty for non-assembly-specific types, or when none configured)
     * @return the document, or {@code null} to skip this RGD ID (e.g. withdrawn)
     */
    ReportDoc build(int rgdId, List<Map> assemblies) throws Exception;
}
