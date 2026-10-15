package edu.mcw.rgd.chatBotEmbed.obo;

import edu.mcw.rgd.chatBotEmbed.Md;
import edu.mcw.rgd.chatBotEmbed.MarkdownWriter;
import edu.mcw.rgd.chatBotEmbed.ReportDoc;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an ontology-term report from an OBO file rather than from the RGD database.
 *
 * <p>OBO files are produced by a different pipeline and dropped into {@code oboDir}, so there is
 * no generate step here and nothing is written to disk: {@link EmbedService} asks this builder
 * for each term's markdown in memory and embeds it directly. {@link #fileBody} produces exactly
 * the bytes a generated {@code .md} file would have held, header comment included, so the embed
 * path treats an in-memory report and an on-disk one identically.</p>
 *
 * <p>This is the cheap counterpart to {@code OntologyReportGenerator}, whose cost is dominated by
 * its <strong>Annotations</strong> section — one row per annotated object rolled up over every
 * descendant term, 27.9&nbsp;M rows across the disease ontology, most of them under high-level
 * terms whose annotation tables answer no question a user asks. An OBO file carries no
 * annotations at all, so these reports hold only what the ontology itself defines: the term, its
 * definition, synonyms, cross-references and its place in the hierarchy. That is about a dollar
 * for all 20,251 disease terms against roughly $375, and it is what teaches the chatbot the
 * <em>vocabulary</em> — that "hemangiosarcoma" means angiosarcoma, that angiosarcoma is a kind of
 * sarcoma — which is what query rewriting and retrieval actually need.</p>
 *
 * <p>Child terms come from the inverted {@code is_a} graph {@link OboFile} builds at load time,
 * since an OBO stanza records only its parents.</p>
 */
public class OboReportBuilder {

    private static final Logger LOG = LogManager.getLogger("obo");

    private static final String ONT_REPORT_URL = "https://rgd.mcw.edu/rgdweb/ontology/annot.html?acc_id=";

    /** Longest term-name token kept in the on-disk file name (the accession makes it unique). */
    private static final int MAX_SYMBOL_LEN = 60;

    /**
     * Most child terms to list before summarising the rest as a count.
     *
     * <p>Nine disease-ontology terms are grouping nodes with hundreds to thousands of direct
     * children ({@code DOID:0050737} "autosomal recessive disease" has 1,846), which would make a
     * single 197&nbsp;KB line of links. 99.6% of terms have 50 or fewer, so this cap only ever
     * truncates the handful of reports where an exhaustive list has no retrieval value anyway.</p>
     */
    private static final int MAX_CHILD_LINKS = 100;

    /** Human-readable ontology names for the prefixes RGD loads; falls back to the prefix. */
    private static String ontologyLabel(String prefix) {
        switch (prefix.toUpperCase()) {
            case "DOID": return "RGD Disease Ontology (RDO)";
            case "MP":   return "Mammalian Phenotype Ontology";
            case "RS":   return "Rat Strain Ontology";
            case "CMO":  return "Clinical Measurement Ontology";
            case "MMO":  return "Measurement Method Ontology";
            case "XCO":  return "Experimental Condition Ontology";
            case "VT":   return "Vertebrate Trait Ontology";
            case "PW":   return "Pathway Ontology";
            case "CHEBI": return "Chemical Entities of Biological Interest";
            case "HP":   return "Human Phenotype Ontology";
            case "UBERON": return "Uberon Anatomy Ontology";
            case "CL":   return "Cell Ontology";
            case "SO":   return "Sequence Ontology";
            case "NBO":  return "Neuro Behavior Ontology";
            default:     return prefix;
        }
    }

    /** Directory holding the {@code *.obo} files; set from AppConfigure.xml. */
    private String oboDir = "data/markdown/ontology/obo";

    /** Parsed once, on first use, then read-only and shared across generator threads. */
    private volatile OboFile obo;

    public OboReportBuilder() {
    }

    public OboReportBuilder(String oboDir) {
        this.oboDir = oboDir;
    }

    /** Report type name, used as the {@code --path} segment of an embed run. */
    public String getReportType() {
        return "obo";
    }

    /** Build the report for one accession, or null when it is unknown or obsolete. */
    public ReportDoc build(String accId) {
        OboFile.Term term = obo().term(accId);
        if (term == null) {
            LOG.warn("term {} not found in any OBO file under {}", accId, oboDir);
            return null;
        }
        if (term.obsolete) {
            return null;
        }
        String name = term.name != null ? term.name : accId;

        StringBuilder md = new StringBuilder(2048);
        md.append("# Ontology Term: ").append(accId).append(" ").append(name).append("\n\n");
        md.append("Source: ")
          .append(Md.link("Rat Genome Database (RGD)", ONT_REPORT_URL + accId))
          .append("\n\n");

        appendSummary(md, term, accId, name);
        appendSynonyms(md, term);
        appendDefinitionSources(md, term);
        appendCrossReferences(md, term);
        appendHierarchy(md, term, accId);
        appendRelatedTerms(md, term);

        // Two constraints on this string. It must contain "Report", because
        // ReportMarkdownChunker.isRgdReport() requires it in the file_name header line and
        // silently skips anything else. And it must differ from OntologyReportGenerator's
        // "RGD Ontology Report - ..." so the two sources never collide on
        // document_embeddings.file_name and a citation says which one answered.
        String displayName = "RGD Ontology Term Report - " + name + " (" + accId + ")";
        return new ReportDoc(fileId(accId), displayName, safeSymbol(name), md.toString());
    }

    /**
     * The report for one accession as a complete file body — the {@code <!-- file_name: ... -->}
     * header line followed by the markdown, newline-terminated.
     *
     * <p>Byte-identical to what {@link MarkdownWriter#write} would have written, so the embed
     * path's report detection, display-name resolution and change detection behave exactly as
     * they do for a generated file. Returns null when the accession is unknown or obsolete.</p>
     */
    public String fileBody(String accId) {
        ReportDoc doc = build(accId);
        if (doc == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(doc.markdown.length() + 64);
        sb.append("<!-- file_name: ").append(doc.displayName).append(" -->\n");
        sb.append(doc.markdown);
        if (sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Accessions of every non-obsolete term, optionally restricted to one prefix (e.g. DOID). */
    public List<String> accessions(String prefixFilter) {
        return obo().accessions(prefixFilter);
    }

    /** Terms loaded from {@code oboDir}; triggers the parse on first call. */
    public int termCount() {
        return obo().size();
    }

    private void appendSummary(StringBuilder md, OboFile.Term term, String accId, String name) {
        md.append(Md.heading(2, "Summary"));
        md.append("- **Accession:** ").append(accId).append("\n");
        md.append("- **Name:** ").append(name).append("\n");
        appendLine(md, "Ontology", ontologyLabel(term.prefix()));
        appendLine(md, "Definition", term.def);
        appendLine(md, "Comment", term.comment);
        if (!term.altIds.isEmpty()) {
            appendLine(md, "Alternate IDs", String.join(", ", term.altIds));
        }
        if (!term.subsets.isEmpty()) {
            appendLine(md, "Subsets", String.join(", ", term.subsets));
        }
        appendLine(md, "Created by", term.createdBy);
        appendLine(md, "Creation date", term.creationDate);
        appendLine(md, "Ontology version", obo().dataVersion(term));
        md.append("\n");
    }

    private void appendSynonyms(StringBuilder md, OboFile.Term term) {
        if (term.synonyms.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Synonyms"));
        for (OboFile.Synonym s : term.synonyms) {
            md.append("- ").append(s.text);
            if (s.scope != null && !s.scope.isEmpty()) {
                md.append(" (").append(s.scope.toLowerCase()).append(")");
            }
            md.append("\n");
        }
        md.append("\n");
    }

    private void appendDefinitionSources(StringBuilder md, OboFile.Term term) {
        if (term.defSources.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Definition Sources"));
        for (String src : term.defSources) {
            md.append("- ").append(src).append("\n");
        }
        md.append("\n");
    }

    private void appendCrossReferences(StringBuilder md, OboFile.Term term) {
        if (term.xrefs.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Cross-References"));
        for (String xref : term.xrefs) {
            md.append("- ").append(xref).append("\n");
        }
        md.append("\n");
    }

    private void appendHierarchy(StringBuilder md, OboFile.Term term, String accId) {
        String parents = termLinks(term.parents);
        List<OboFile.TermRef> kids = obo().children(accId);
        if (parents.isEmpty() && kids.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Hierarchy"));
        if (!parents.isEmpty()) {
            md.append(Md.heading(3, "Parent Terms")).append(parents).append("\n\n");
        }
        if (!kids.isEmpty()) {
            // The count goes in the body, never the heading: ReportMetadata.sectionOf() stores the
            // heading breadcrumb in document_embeddings.section, and a count baked into the heading
            // would give every distinct child count its own section value — breaking the chatbot's
            // section filtering and its excludedSections list.
            md.append(Md.heading(3, "Child Terms"));
            boolean truncated = kids.size() > MAX_CHILD_LINKS;
            md.append("*").append(kids.size()).append(kids.size() == 1 ? " child term" : " child terms");
            if (truncated) {
                md.append(", showing the first ").append(MAX_CHILD_LINKS);
            }
            md.append(".*\n\n");
            md.append(termLinks(truncated ? kids.subList(0, MAX_CHILD_LINKS) : kids));
            md.append("\n\n");
        }
    }

    private void appendRelatedTerms(StringBuilder md, OboFile.Term term) {
        if (term.relationships.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Related Terms"));
        for (OboFile.TermRef r : term.relationships) {
            String type = r.type == null ? "related to" : r.type.replace('_', ' ');
            md.append("- **").append(type).append(":** ")
              .append(Md.link(r.label(), ONT_REPORT_URL + r.id))
              .append("\n");
        }
        md.append("\n");
    }

    /** Comma-separated list of terms, each a link to its ontology report. */
    private static String termLinks(List<OboFile.TermRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        List<String> links = new ArrayList<>(refs.size());
        for (OboFile.TermRef r : refs) {
            links.add(Md.link(r.label(), ONT_REPORT_URL + r.id));
        }
        return String.join(", ", links);
    }

    private static void appendLine(StringBuilder md, String label, String value) {
        if (value != null && !value.isBlank()) {
            md.append("- **").append(label).append(":** ").append(value.trim()).append("\n");
        }
    }

    /** Lazily parse the OBO directory; the first caller wins and the rest reuse it. */
    private OboFile obo() {
        OboFile local = obo;
        if (local == null) {
            synchronized (this) {
                local = obo;
                if (local == null) {
                    try {
                        local = OboFile.load(oboDir);
                    } catch (IOException e) {
                        throw new IllegalStateException("cannot read OBO files in " + oboDir, e);
                    }
                    obo = local;
                }
            }
        }
        return local;
    }

    /** {@code DOID:0001816} -> {@code DOID_0001816}, so the accession is filename-safe. */
    private static String fileId(String accId) {
        return accId.replace(':', '_');
    }

    private static String safeSymbol(String name) {
        String safe = MarkdownWriter.safeSymbol(name);
        return safe.length() > MAX_SYMBOL_LEN ? safe.substring(0, MAX_SYMBOL_LEN) : safe;
    }

    // ---- Spring-injected properties ----

    public void setOboDir(String oboDir) { this.oboDir = oboDir; }
}
