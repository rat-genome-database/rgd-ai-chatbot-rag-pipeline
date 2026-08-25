package edu.mcw.rgd.chatBotEmbed.generators;

import edu.mcw.rgd.chatBotEmbed.DAO;
import edu.mcw.rgd.chatBotEmbed.Md;
import edu.mcw.rgd.chatBotEmbed.MarkdownWriter;
import edu.mcw.rgd.chatBotEmbed.ReportDoc;
import edu.mcw.rgd.chatBotEmbed.TermReportGenerator;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Ontology;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.datamodel.ontologyx.TermWithStats;
import edu.mcw.rgd.datamodel.ontologyx.TermXRef;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds an ontology-term report as markdown, structured for the chatbot's section-aware chunker
 * (real ATX headings, so every chunk carries a heading breadcrumb).
 *
 * <p>Mirrors the informative content of the RGD ontology report page
 * ({@code ontology/annot.html?acc_id=...} and its {@code termInfoTable.jsp}/{@code annotTable.jsp}):
 * the term name and accession, definition, comment, synonyms (grouped by type), definition-source
 * cross-references, the term's parent and child terms, per-species/per-object annotation counts
 * (term + descendant terms), and the full annotation detail — one row per annotated object,
 * grouped by species and object type. As on the live page, a species/object list with more than
 * {@link #MAX_ANNOT_ROWS} annotated objects is summarised as a count rather than listed, so a
 * high-level term cannot produce an unbounded file.</p>
 *
 * <p>The title is {@code # Ontology Term: <ACC> <name>}; the accession is the term's identity (the
 * role {@code RGD:<id>} plays for gene/QTL/strain reports), so the embed step does not inject an
 * RGD id into it. The display name is {@code RGD Ontology Report - <name> (<ACC>)}.</p>
 */
public class OntologyReportGenerator implements TermReportGenerator {

    private static final Logger LOG = LogManager.getLogger("status");

    private static final String ONT_REPORT_URL = "https://rgd.mcw.edu/rgdweb/ontology/annot.html?acc_id=";
    private static final String GENE_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/gene/main.html?id=";
    private static final String QTL_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/qtl/main.html?id=";
    private static final String STRAIN_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/strain/main.html?id=";
    private static final String VARIANT_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/variant/main.html?id=";
    private static final String CELLLINE_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/cellline/main.html?id=";
    private static final String REFERENCE_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/reference/main.html?id=";

    /** Matches the ontology report page's cap ({@code OntAnnotBean.MAX_ANNOT_COUNT}). */
    private static final int MAX_ANNOT_ROWS = 10000;

    /** Longest term-name token kept in the on-disk file name (the accession makes it unique anyway). */
    private static final int MAX_SYMBOL_LEN = 60;

    /** Annotations include a descendant-term rollup, exactly like the page's default view. */
    private static final boolean WITH_CHILDREN = true;

    /**
     * Species shown on the report, in the ontology page's tab order. A species is emitted only when
     * the term (with descendants) has annotations for it, so this list never adds empty sections.
     */
    private static final int[] SPECIES = {
            SpeciesType.RAT, SpeciesType.MOUSE, SpeciesType.HUMAN,
            4 /* Chinchilla */, 5 /* Bonobo */, 6 /* Dog */, 7 /* Squirrel */,
            9 /* Pig */, 13 /* Green Monkey */, 14 /* Naked Mole-Rat */
    };

    /** The annotated object types the page breaks out, in display order. */
    private static final ObjType[] OBJECT_TYPES = {
            new ObjType(RgdId.OBJECT_KEY_GENES, "Genes", GENE_REPORT_URL),
            new ObjType(RgdId.OBJECT_KEY_QTLS, "QTLs", QTL_REPORT_URL),
            new ObjType(RgdId.OBJECT_KEY_STRAINS, "Strains", STRAIN_REPORT_URL),
            new ObjType(RgdId.OBJECT_KEY_VARIANTS, "Variants", VARIANT_REPORT_URL),
            new ObjType(RgdId.OBJECT_KEY_CELL_LINES, "Cell Lines", CELLLINE_REPORT_URL),
    };

    private final DAO dao;

    public OntologyReportGenerator(DAO dao) {
        this.dao = dao;
    }

    @Override
    public String getReportType() {
        return "ontology";
    }

    @Override
    public List<String> getAccessions(String ontologyIdFilter) throws Exception {
        List<String> accs = new ArrayList<>();
        if (!Utils.isStringEmpty(ontologyIdFilter)) {
            addOntologyTermAccs(accs, ontologyIdFilter.trim());
        } else {
            for (Ontology ont : dao.getPublicOntologies()) {
                if (!Utils.isStringEmpty(ont.getId())) {
                    addOntologyTermAccs(accs, ont.getId());
                }
            }
        }
        return accs;
    }

    private void addOntologyTermAccs(List<String> accs, String ontId) throws Exception {
        int before = accs.size();
        for (Term t : dao.getActiveTerms(ontId)) {
            if (!Utils.isStringEmpty(t.getAccId())) {
                accs.add(t.getAccId());
            }
        }
        LOG.info("  ontology {}: {} active terms", ontId, accs.size() - before);
    }

    @Override
    public ReportDoc build(String accId, int speciesTypeKey, int objectKey) throws Exception {
        TermWithStats term = dao.getTermWithStats(accId);
        if (term == null || term.isObsolete()) {
            return null;   // unknown or obsolete — don't embed it
        }

        // Resolve the requested filters to the species/object-type sets the annotation sections
        // iterate: a filter of 0 keeps the full default set.
        int[] speciesList = (speciesTypeKey > 0) ? new int[] { speciesTypeKey } : SPECIES;
        List<ObjType> objectTypes = objectTypesFor(objectKey);

        String name = Utils.defaultString(term.getTerm());

        StringBuilder md = new StringBuilder(4096);

        // Title — becomes the root of every chunk's heading breadcrumb. The accession precedes the
        // name and is the term's identity, so every chunk carries it.
        md.append("# Ontology Term: ").append(accId).append(" ").append(name).append("\n\n");
        md.append("> **Source:** ")
          .append(Md.link("Rat Genome Database (RGD)", ONT_REPORT_URL + accId))
          .append("\n\n---\n\n");

        appendSummary(md, term, accId, name);
        appendSynonyms(md, accId);
        appendDefinitionSources(md, accId);
        appendHierarchy(md, accId);
        appendAnnotationCounts(md, term, speciesList, objectTypes);
        appendAnnotations(md, term, accId, speciesList, objectTypes);

        md.append("\n---\n\n*This report was extracted from the ")
          .append(Md.link("Rat Genome Database (RGD)", "https://rgd.mcw.edu"))
          .append(", Medical College of Wisconsin.*\n");

        String displayName = "RGD Ontology Report - " + name + " (" + accId + ")";
        return new ReportDoc(fileId(accId), displayName, safeSymbol(name), md.toString());
    }

    /** Summary section — the term-info block of the report page. */
    private void appendSummary(StringBuilder md, TermWithStats term, String accId, String name) throws Exception {
        md.append(Md.heading(2, "Summary"));
        md.append("- **Term:** ").append(name).append("\n");
        md.append("- **Accession:** ").append(accId).append("\n");
        appendLine(md, "Ontology", ontologyLabel(term.getOntologyId()));
        if (term.isObsolete()) {
            md.append("- **Status:** Obsolete\n");
        }
        md.append("\n");

        if (!Utils.isStringEmpty(term.getDefinition())) {
            md.append(term.getDefinition().trim()).append("\n\n");
        }
        if (!Utils.isStringEmpty(term.getComment())) {
            md.append("**Comment:** ").append(term.getComment().trim()).append("\n\n");
        }
    }

    /**
     * Synonyms section: the term's synonyms grouped by their friendly type (Exact Synonym, Related
     * Synonym, Alt ID, ...), each type a comma-separated line. The internal {@code Not4Curation}
     * marker synonym is skipped.
     */
    private void appendSynonyms(StringBuilder md, String accId) throws Exception {
        List<TermSynonym> synonyms = dao.getTermSynonyms(accId);
        if (synonyms == null || synonyms.isEmpty()) {
            return;
        }
        LinkedHashMap<String, LinkedHashSet<String>> byType = new LinkedHashMap<>();
        for (TermSynonym s : synonyms) {
            if (Utils.isStringEmpty(s.getName()) || "Not4Curation".equalsIgnoreCase(s.getName())) {
                continue;
            }
            String type = Utils.defaultString(s.getFriendlyType());
            if (type.isEmpty()) {
                type = "Synonym";
            }
            byType.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(s.getName().trim());
        }
        if (byType.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Synonyms"));
        for (java.util.Map.Entry<String, LinkedHashSet<String>> e : byType.entrySet()) {
            md.append("- **").append(e.getKey()).append(":** ")
              .append(String.join(", ", e.getValue())).append("\n");
        }
        md.append("\n");
    }

    /** Definition Sources section: the term's definition cross-references (db-xrefs). */
    private void appendDefinitionSources(StringBuilder md, String accId) throws Exception {
        List<TermXRef> xrefs = dao.getTermXRefs(accId);
        if (xrefs == null || xrefs.isEmpty()) {
            return;
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (TermXRef x : xrefs) {
            if (Utils.isStringEmpty(x.getXrefValue())) {
                continue;
            }
            String v = x.getXrefValue().trim();
            if (!Utils.isStringEmpty(x.getXrefDescription())) {
                v += " (" + x.getXrefDescription().trim() + ")";
            }
            values.add(v);
        }
        if (values.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Definition Sources"));
        md.append(String.join(", ", values)).append("\n\n");
    }

    /**
     * Hierarchy section: the term's direct parent and child terms, each a comma-separated list of
     * linked term names. The whole section (and each sub-section) is emitted only when it has
     * content.
     */
    private void appendHierarchy(StringBuilder md, String accId) throws Exception {
        String parents = termLinks(dao.getParentTerms(accId));
        String children = termLinks(new ArrayList<>(dao.getChildTerms(accId)));
        if (parents.isEmpty() && children.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Hierarchy"));
        if (!parents.isEmpty()) {
            md.append(Md.heading(3, "Parent Terms")).append(parents).append("\n\n");
        }
        if (!children.isEmpty()) {
            md.append(Md.heading(3, "Child Terms")).append(children).append("\n\n");
        }
    }

    /** Comma-separated list of terms, each a link to its ontology report. */
    private String termLinks(List<? extends Term> terms) {
        if (terms == null || terms.isEmpty()) {
            return "";
        }
        List<String> links = new ArrayList<>();
        for (Term t : terms) {
            if (!Utils.isStringEmpty(t.getAccId())) {
                links.add(Md.link(Utils.defaultString(t.getTerm()) + " (" + t.getAccId() + ")",
                        ONT_REPORT_URL + t.getAccId()));
            }
        }
        return String.join(", ", links);
    }

    /**
     * Annotation Counts section: annotated-object counts per species and object type, for the term
     * and its descendant terms (the page's default "show annotations for term's descendants" view).
     * Only species with at least one annotation get a row.
     */
    private void appendAnnotationCounts(StringBuilder md, TermWithStats term,
                                        int[] speciesList, List<ObjType> objectTypes) {
        List<List<String>> rows = new ArrayList<>();
        for (int species : speciesList) {
            if (speciesTotal(term, species, objectTypes) <= 0) {
                continue;
            }
            List<String> row = new ArrayList<>();
            row.add(Md.cell(SpeciesType.getCommonName(species)));
            for (ObjType ot : objectTypes) {
                row.add(String.valueOf(objectCount(term, species, ot.key)));
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return;
        }
        List<String> headers = new ArrayList<>();
        headers.add("Species");
        for (ObjType ot : objectTypes) {
            headers.add(ot.label);
        }
        md.append(Md.heading(2, "Annotation Counts"));
        md.append("*Annotated objects for this term and its descendant terms.*\n\n");
        md.append(Md.table(headers, rows));
    }

    /**
     * Annotations section: the full annotation detail, one level-3 sub-section per species/object
     * type that has annotations. Each annotated object is one row (its per-term annotations merged),
     * mirroring the report page's grouped view. A list larger than {@link #MAX_ANNOT_ROWS} is
     * summarised as a count, matching the page's "too large to display" guard.
     */
    private void appendAnnotations(StringBuilder md, TermWithStats term, String accId,
                                   int[] speciesList, List<ObjType> objectTypes) throws Exception {
        StringBuilder buf = new StringBuilder();
        for (int species : speciesList) {
            if (speciesTotal(term, species, objectTypes) <= 0) {
                continue;
            }
            String speciesName = SpeciesType.getCommonName(species);
            for (ObjType ot : objectTypes) {
                int count = objectCount(term, species, ot.key);
                if (count <= 0) {
                    continue;
                }
                buf.append(Md.heading(3, speciesName + " - " + ot.label + " (" + count + ")"));
                if (count > MAX_ANNOT_ROWS) {
                    buf.append("*").append(count).append(" annotated ").append(ot.label.toLowerCase())
                       .append(" - too many to list individually.*\n\n");
                    continue;
                }
                appendObjectRows(buf, accId, species, ot);
            }
        }
        if (buf.length() > 0) {
            md.append(Md.heading(2, "Annotations"));
            md.append(buf);
        }
    }

    /** One table of annotated objects (of one type, one species), grouped/merged per object. */
    private void appendObjectRows(StringBuilder md, String accId, int species, ObjType ot) throws Exception {
        List<Annotation> annotations =
                dao.getTermAnnotations(accId, WITH_CHILDREN, species, MAX_ANNOT_ROWS, ot.key);
        if (annotations == null || annotations.isEmpty()) {
            return;
        }

        // Merge the annotations of one object (multiple terms/evidences/references) into one row.
        LinkedHashMap<Integer, ObjAnnot> byObject = new LinkedHashMap<>();
        for (Annotation a : annotations) {
            Integer rgdId = a.getAnnotatedObjectRgdId();
            if (rgdId == null) {
                continue;
            }
            ObjAnnot oa = byObject.computeIfAbsent(rgdId,
                    k -> new ObjAnnot(a.getObjectSymbol(), a.getObjectName()));
            addNonEmpty(oa.qualifiers, a.getQualifier());
            addNonEmpty(oa.evidences, a.getEvidence());
            if (a.getRefRgdId() != null && a.getRefRgdId() > 0) {
                oa.refRgdIds.add(a.getRefRgdId());
            }
        }
        if (byObject.isEmpty()) {
            return;
        }

        List<List<String>> rows = new ArrayList<>();
        for (java.util.Map.Entry<Integer, ObjAnnot> e : byObject.entrySet()) {
            int rgdId = e.getKey();
            ObjAnnot oa = e.getValue();
            List<String> refLinks = new ArrayList<>();
            for (int ref : oa.refRgdIds) {
                refLinks.add(Md.link("RGD:" + ref, REFERENCE_REPORT_URL + ref));
            }
            rows.add(List.of(
                    Md.link("RGD:" + rgdId, ot.url + rgdId),
                    Md.cell(Utils.defaultString(oa.symbol)),
                    Md.cell(Utils.defaultString(oa.name)),
                    Md.cell(String.join("; ", oa.qualifiers)),
                    Md.cell(String.join(", ", oa.evidences)),
                    String.join(" ", refLinks)
            ));
        }
        md.append(Md.table(List.of("RGD ID", "Symbol", "Name", "Qualifier", "Evidence", "Reference"), rows));
    }

    // ---- helpers --------------------------------------------------------------

    /**
     * The object types to report for the requested {@code --object} filter: the single matching
     * type when one is given, or all supported types when the filter is {@code 0} (all). An
     * unsupported key yields an empty list (no annotation content).
     */
    private static List<ObjType> objectTypesFor(int objectKey) {
        if (objectKey <= 0) {
            return java.util.Arrays.asList(OBJECT_TYPES);
        }
        for (ObjType ot : OBJECT_TYPES) {
            if (ot.key == objectKey) {
                return List.of(ot);
            }
        }
        return List.of();
    }

    /** Annotated-object count for a species summed over the requested object types, term + descendants. */
    private static int speciesTotal(TermWithStats term, int species, List<ObjType> objectTypes) {
        int total = 0;
        for (ObjType ot : objectTypes) {
            total += objectCount(term, species, ot.key);
        }
        return total;
    }

    /** Annotated-object count for a species and one object type, term + descendants. */
    private static int objectCount(TermWithStats term, int species, int objectKey) {
        return term.getStat("annotated_object_count", species, objectKey, 1);
    }

    /** Resolve an ontology id to a "Name (ID)" label, falling back to the bare id. */
    private String ontologyLabel(String ontId) throws Exception {
        if (Utils.isStringEmpty(ontId)) {
            return "";
        }
        Ontology ont = dao.getOntology(ontId);
        if (ont != null && !Utils.isStringEmpty(ont.getName())) {
            return ont.getName().trim() + " (" + ontId + ")";
        }
        return ontId;
    }

    /** Emit "- **Label:** value" when the value is non-blank; skip the field entirely otherwise. */
    private static void appendLine(StringBuilder md, String label, String value) {
        if (!Utils.isStringEmpty(value)) {
            md.append("- **").append(label).append(":** ").append(value.trim()).append("\n");
        }
    }

    private static void addNonEmpty(LinkedHashSet<String> set, String value) {
        if (!Utils.isStringEmpty(value)) {
            set.add(value.trim());
        }
    }

    /** File-name identity token: the accession with its colon made filename-safe (MP:0001900 -> MP_0001900). */
    private static String fileId(String accId) {
        return accId.replace(':', '_');
    }

    /** Filename-safe, length-capped term-name token. */
    private static String safeSymbol(String name) {
        String safe = MarkdownWriter.safeSymbol(name);
        return safe.length() > MAX_SYMBOL_LEN ? safe.substring(0, MAX_SYMBOL_LEN) : safe;
    }

    /** One annotated object's merged annotations. */
    private static final class ObjAnnot {
        final String symbol;
        final String name;
        final LinkedHashSet<String> qualifiers = new LinkedHashSet<>();
        final LinkedHashSet<String> evidences = new LinkedHashSet<>();
        final LinkedHashSet<Integer> refRgdIds = new LinkedHashSet<>();
        ObjAnnot(String symbol, String name) {
            this.symbol = symbol;
            this.name = name;
        }
    }

    /** An annotated object type: its RGD object key, display label and report URL. */
    private static final class ObjType {
        final int key;
        final String label;
        final String url;
        ObjType(int key, String label, String url) {
            this.key = key;
            this.label = label;
            this.url = url;
        }
    }
}
