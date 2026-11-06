package edu.mcw.rgd.chatBotEmbed.generators;

import edu.mcw.rgd.chatBotEmbed.DAO;
import edu.mcw.rgd.chatBotEmbed.Md;
import edu.mcw.rgd.chatBotEmbed.ReportGenerator;
import edu.mcw.rgd.datamodel.MapData;
import edu.mcw.rgd.datamodel.NomenclatureEvent;
import edu.mcw.rgd.datamodel.Note;
import edu.mcw.rgd.datamodel.QTL;
import edu.mcw.rgd.datamodel.Reference;
import edu.mcw.rgd.datamodel.Xdb;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.Utils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sections shared by every report type. Annotations are structured identically for genes,
 * QTLs and strains — only the set of ontologies differs — so the layout, evidence filtering
 * and de-duplication live here once rather than being copied per generator.
 *
 * <p>Subclasses supply their own ontology map to {@link #appendAnnotations} and add whatever
 * type-specific sections they need.</p>
 */
public abstract class AbstractReportGenerator implements ReportGenerator {

    protected static final String REFERENCE_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/reference/main.html?id=";
    protected static final String ONTOLOGY_TERM_URL = "https://rgd.mcw.edu/rgdweb/ontology/annot.html?acc_id=";
    protected static final String QTL_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/qtl/main.html?id=";

    /**
     * Evidence codes to skip entirely: computational / cross-species inference
     * (ISO = orthology, ISS = similarity, IEA = electronic, IBA = biological ancestor).
     */
    private static final Set<String> EXCLUDED_EVIDENCE = Set.of("ISO", "ISS", "IEA", "IBA");

    /** Only apply the evidence filter to annotation sets larger than this. */
    private static final int EVIDENCE_FILTER_THRESHOLD = 100;

    /** Columns of every full annotation table; see {@link #annotationRows}. */
    protected static final List<String> ANNOTATION_HEADERS = List.of("Term", "Qualifier", "Evidence", "Reference", "Notes");

    /** Longest curator note kept in an annotation table cell. */
    private static final int MAX_ANNOTATION_NOTES = 300;

    /**
     * A total order on text: case-insensitive first, then exact, so two values that differ only in
     * case still sort the same way every run.
     *
     * <p>Every list a report prints is sorted on a complete key rather than left in database
     * order. Several queries have no ORDER BY, and Oracle returns their rows in whatever order the
     * plan produces, so an unsorted list can come out differently on two runs over the same data.
     * The embed step re-embeds any chunk whose text changed, so that churn would be paid for on
     * every refresh.</p>
     */
    protected static final Comparator<String> TEXT =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    /** Order for position rows: chromosome, start, stop, strand. */
    protected static final Comparator<MapData> MAP_DATA_ORDER =
            Comparator.comparing((MapData d) -> Utils.defaultString(d.getChromosome()), TEXT)
                    .thenComparing(d -> d.getStartPos() == null ? Integer.MIN_VALUE : d.getStartPos())
                    .thenComparing(d -> d.getStopPos() == null ? Integer.MIN_VALUE : d.getStopPos())
                    .thenComparing(d -> Utils.defaultString(d.getStrand()), TEXT);

    /** Distinct values in {@link #TEXT} order, joined with {@code separator}. */
    protected static String sortedJoin(java.util.Collection<String> values, String separator) {
        java.util.TreeSet<String> sorted = new java.util.TreeSet<>(TEXT);
        sorted.addAll(values);
        return String.join(separator, sorted);
    }

    /** Sort table rows cell by cell in {@link #TEXT} order. */
    protected static void sortRows(List<List<String>> rows) {
        rows.sort(ROW_ORDER);
    }

    /** Cell-by-cell {@link #TEXT} order on table rows. */
    protected static final Comparator<List<String>> ROW_ORDER = (a, b) -> {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = TEXT.compare(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    };

    /** Order for annotations: term name, accession, qualifier, evidence, reference, source, notes. */
    private static final Comparator<Annotation> ANNOTATION_ORDER =
            Comparator.comparing((Annotation a) -> Utils.defaultString(a.getTerm()), TEXT)
                    .thenComparing(a -> Utils.defaultString(a.getTermAcc()), TEXT)
                    .thenComparing(a -> Utils.defaultString(a.getQualifier()), TEXT)
                    .thenComparing(a -> Utils.defaultString(a.getEvidence()), TEXT)
                    .thenComparing(a -> a.getRefRgdId() == null ? 0 : a.getRefRgdId())
                    .thenComparing(a -> Utils.defaultString(a.getDataSrc()), TEXT)
                    .thenComparing(a -> Utils.defaultString(a.getNotes()), TEXT);

    protected final DAO dao;

    protected AbstractReportGenerator(DAO dao) {
        this.dao = dao;
    }

    /**
     * Emit the annotation block. Each ontology becomes a level-3 sub-section under a single
     * level-2 "Annotation" heading, so the chunker's breadcrumb reads
     * {@code # Gene: A2m > ## Annotation > ### Disease Annotations}.
     *
     * @param ontologySections term-accession prefix -> sub-section title, in display order.
     *                         Disease (DOID) is handled separately and must not be included.
     */
    protected void appendAnnotations(StringBuilder md, int rgdId,
                                     Map<String, String> ontologySections) throws Exception {
        StringBuilder buf = new StringBuilder();
        appendDiseaseAnnotations(buf, rgdId);
        for (Map.Entry<String, String> section : ontologySections.entrySet()) {
            appendAnnotationSection(buf, rgdId, section.getKey(), section.getValue());
        }
        if (buf.length() > 0) {
            md.append(Md.heading(2, "Annotation"));
            md.append(buf);
        }
    }

    /**
     * Disease (DOID) annotations, split the way RGD splits them: manually curated
     * annotations (data source "RGD") get the full layout; annotations imported from other
     * databases get a compact overview — one row per distinct disease with its sources,
     * without the per-annotation evidence/reference detail.
     */
    protected void appendDiseaseAnnotations(StringBuilder md, int rgdId) throws Exception {
        // RGD's disease ontology is the Disease Ontology — term accessions are DOID:xxxxx.
        List<Annotation> annotations = filterEvidence(dao.getAnnotationsForOntology(rgdId, "DOID"));
        if (annotations.isEmpty()) {
            return;
        }

        List<Annotation> manual = new ArrayList<>();
        List<Annotation> imported = new ArrayList<>();
        for (Annotation a : annotations) {
            if ("RGD".equalsIgnoreCase(a.getDataSrc())) {
                manual.add(a);
            } else {
                imported.add(a);
            }
        }

        // Manual — full layout.
        List<List<String>> manualRows = annotationRows(manual);
        if (!manualRows.isEmpty()) {
            md.append(Md.heading(3, "Manual Disease Annotations"));
            md.append(Md.table(ANNOTATION_HEADERS, manualRows));
        }

        // Imported — overview: distinct disease -> contributing sources.
        if (!imported.isEmpty()) {
            LinkedHashMap<String, ImportedDisease> byTerm = new LinkedHashMap<>();
            for (Annotation a : imported) {
                String key = !Utils.isStringEmpty(a.getTermAcc()) ? a.getTermAcc() : Utils.defaultString(a.getTerm());
                ImportedDisease d = byTerm.computeIfAbsent(key, k -> new ImportedDisease(a.getTerm(), a.getTermAcc()));
                if (!Utils.isStringEmpty(a.getDataSrc())) {
                    d.sources.add(a.getDataSrc());
                }
            }
            List<List<String>> rows = new ArrayList<>();
            for (ImportedDisease d : byTerm.values()) {
                String termCell = Utils.isStringEmpty(d.termAcc)
                        ? Md.cell(d.term)
                        : Md.link(Utils.defaultString(d.term), ONTOLOGY_TERM_URL + d.termAcc);
                rows.add(List.of(termCell, Md.cell(String.join(", ", d.sources))));
            }
            md.append(Md.heading(3, "Imported Disease Annotations"));
            md.append("*Overview of diseases imported from other databases; see the source for evidence detail.*\n\n");
            md.append(Md.table(List.of("Disease", "Source(s)"), rows));
        }
    }

    protected void appendAnnotationSection(StringBuilder md, int rgdId, String prefix, String title) throws Exception {
        List<Annotation> annotations = filterEvidence(dao.getAnnotationsForOntology(rgdId, prefix));
        List<List<String>> rows = annotationRows(annotations);
        if (rows.isEmpty()) {
            return;
        }
        md.append(Md.heading(3, title));
        md.append(Md.table(ANNOTATION_HEADERS, rows));
    }

    /**
     * Drop annotations whose evidence code is in {@link #EXCLUDED_EVIDENCE}, but only when the
     * set is large (more than {@link #EVIDENCE_FILTER_THRESHOLD}). Smaller sets are kept in
     * full — the filter exists to trim heavily-annotated objects, not sparse ones. The result is
     * a new list in {@link #ANNOTATION_ORDER}.
     */
    protected static List<Annotation> filterEvidence(List<Annotation> annotations) {
        if (annotations == null) {
            return new ArrayList<>();
        }
        List<Annotation> kept = new ArrayList<>();
        boolean filter = annotations.size() > EVIDENCE_FILTER_THRESHOLD;
        for (Annotation a : annotations) {
            String ev = a.getEvidence();
            if (filter && ev != null && EXCLUDED_EVIDENCE.contains(ev.trim().toUpperCase())) {
                continue;
            }
            kept.add(a);
        }
        // Sorted here, before any de-duplication, so the annotation kept for a row (and so the
        // reference it shows) does not depend on the order the database returned them in.
        kept.sort(ANNOTATION_ORDER);
        return kept;
    }

    /**
     * Full annotation table rows (Term | Qualifier | Evidence | Reference | Notes), deduplicated.
     *
     * <p>Notes are the curator's free text on the annotation — for disease annotations the
     * kind of evidence ("protein:increased expression:serum"), for GO the specifics. Only
     * RGD-curated notes are kept: imported annotations' notes are mostly the source's own
     * identifiers (e.g. {@code MGI:...}), which say nothing to a reader.</p>
     */
    protected List<List<String>> annotationRows(List<Annotation> annotations) {
        // Collapse duplicates that differ only by reference/source, pooling their notes.
        LinkedHashMap<String, Annotation> unique = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<String>> notes = new LinkedHashMap<>();
        for (Annotation a : annotations) {
            String key = a.getTermAcc() + "|" + Utils.defaultString(a.getQualifier()) + "|" + Utils.defaultString(a.getEvidence());
            unique.putIfAbsent(key, a);
            LinkedHashSet<String> rowNotes = notes.computeIfAbsent(key, k -> new LinkedHashSet<>());
            if ("RGD".equalsIgnoreCase(a.getDataSrc()) && !Utils.isStringEmpty(a.getNotes())) {
                rowNotes.add(a.getNotes().trim());
            }
        }

        List<List<String>> rows = new ArrayList<>();
        for (java.util.Map.Entry<String, Annotation> e : unique.entrySet()) {
            Annotation a = e.getValue();
            String rowNotes = String.join("; ", notes.get(e.getKey()));
            if (rowNotes.length() > MAX_ANNOTATION_NOTES) {
                rowNotes = rowNotes.substring(0, MAX_ANNOTATION_NOTES).trim() + "...";
            }
            String term = Utils.isStringEmpty(a.getTermAcc())
                    ? Md.cell(a.getTerm())
                    : Md.link(Utils.defaultString(a.getTerm()), ONTOLOGY_TERM_URL + a.getTermAcc());
            String reference = (a.getRefRgdId() != null && a.getRefRgdId() > 0)
                    ? Md.link("RGD:" + a.getRefRgdId(), REFERENCE_REPORT_URL + a.getRefRgdId())
                    : "";
            rows.add(List.of(
                    term,
                    Md.cell(Utils.defaultString(a.getQualifier())),
                    Md.cell(Utils.defaultString(a.getEvidence())),
                    reference,
                    Md.cell(rowNotes)
            ));
        }
        return rows;
    }

    /** Headings for note types whose abbreviated names would read poorly when title-cased. */
    private static final Map<String, String> NOTE_TYPE_LABELS = Map.of(
            "strain_phys_biochem", "Physiology and Biochemistry",
            "strain_drgs_chems", "Drugs and Chemicals",
            "strain_life_disease", "Life Span and Disease",
            "gene_mutations_overexpression", "Mutations and Overexpression",
            "qtl_cand_genes", "Candidate Genes",
            "qtl_trait_method", "Trait Method");

    /**
     * Curator Notes section: the object's public free-text notes, one sub-section per note type
     * (e.g. "Expression", "Function", "Strain Characteristics"). These carry curated findings
     * that appear nowhere else in the report.
     *
     * @param shownElsewhere note types the report already presents as fields (e.g. a QTL's
     *                       {@code qtl_cross_type}), left out so they are not embedded twice
     */
    protected void appendCuratorNotes(StringBuilder md, int rgdId, Set<String> shownElsewhere) throws Exception {
        java.util.TreeMap<String, java.util.TreeSet<String>> byType = new java.util.TreeMap<>(TEXT);
        for (Note n : dao.getPublicNotes(rgdId)) {
            String type = Utils.defaultString(n.getNotesTypeName()).trim().toLowerCase();
            if (shownElsewhere.contains(type)) {
                continue;
            }
            byType.computeIfAbsent(type, k -> new java.util.TreeSet<>(TEXT)).add(n.getNotes().trim());
        }
        if (byType.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Curator Notes"));
        for (Map.Entry<String, java.util.TreeSet<String>> e : byType.entrySet()) {
            md.append(Md.heading(3, noteTypeLabel(e.getKey())));
            for (String text : e.getValue()) {
                md.append("- ").append(text.replace("\r", " ").replace("\n", " ")).append("\n");
            }
            md.append("\n");
        }
    }

    /**
     * Readable heading for a note type: the object-type prefix dropped and the rest title-cased
     * ({@code gene_cellular_localization} -> "Cellular Localization").
     */
    static String noteTypeLabel(String type) {
        String named = NOTE_TYPE_LABELS.get(type);
        if (named != null) {
            return named;
        }
        String t = type.replaceFirst("^(gene|qtl|strain|sslps)_", "");
        if (t.isEmpty()) {
            return "General";
        }
        StringBuilder sb = new StringBuilder();
        for (String w : t.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /**
     * References section: the PubMed literature associated with the object, newest first.
     * References without a PubMed ID (internal data-load/curation entries) are omitted, so the
     * section carries only citable literature rather than pipeline bookkeeping.
     */
    protected void appendReferences(StringBuilder md, int rgdId) throws Exception {
        List<Reference> references = new ArrayList<>(dao.getReferencesForObject(rgdId));
        if (references.isEmpty()) {
            return;
        }
        // Newest first; references with no publication date sort last.
        references.sort((a, b) -> {
            Date da = a.getPubDate(), db = b.getPubDate();
            if (da == null && db == null) return 0;
            if (da == null) return 1;
            if (db == null) return -1;
            return db.compareTo(da);
        });
        // Same-date references (common: a curation batch shares one date) keep a fixed order.
        references.sort(Comparator.comparing(Reference::getPubDate,
                        Comparator.nullsLast(Comparator.<Date>reverseOrder()))
                .thenComparingInt(Reference::getRgdId));

        List<List<String>> rows = new ArrayList<>();
        for (Reference ref : references) {
            List<String> pmids = new ArrayList<>(dao.getPubmedIdsForReference(ref.getRgdId()));
            if (pmids.isEmpty()) {
                continue;   // keep only PubMed references
            }
            pmids.sort(TEXT);
            List<String> links = new ArrayList<>();
            for (String pmid : pmids) {
                links.add(Md.link("PMID:" + pmid, "https://pubmed.ncbi.nlm.nih.gov/" + pmid + "/"));
            }
            rows.add(List.of(
                    Md.cell(Utils.defaultString(ref.getTitle())),
                    year(ref.getPubDate()),
                    String.join(" ", links)
            ));
        }
        if (rows.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "References"));
        md.append(Md.table(List.of("Title", "Year", "PubMed"), rows));
    }

    /**
     * External Database Links section: the object's cross-references to other databases, one row
     * per database/accession with the source pipeline(s) that supplied it. Sequence and
     * literature cross-references are excluded (they have their own sections); see
     * {@link DAO#getExternalDbLinks}.
     */
    protected void appendExternalDbLinks(StringBuilder md, int rgdId, int speciesTypeKey) throws Exception {
        List<XdbId> links = dao.getExternalDbLinks(rgdId, speciesTypeKey);
        if (links == null || links.isEmpty()) {
            return;
        }

        // Collapse duplicate database+accession rows, aggregating their source pipelines.
        // Sorted first: when several rows share a database and accession, the link text shown is
        // the first one's, which must not depend on database order.
        links = new ArrayList<>(links);
        links.sort(Comparator.comparingInt(XdbId::getXdbKey)
                .thenComparing(x -> Utils.defaultString(x.getAccId()), TEXT)
                .thenComparing(x -> Utils.defaultString(x.getLinkText()), TEXT)
                .thenComparing(x -> Utils.defaultString(x.getSrcPipeline()), TEXT));
        LinkedHashMap<String, ExtLink> byKeyAcc = new LinkedHashMap<>();
        for (XdbId x : links) {
            ExtLink e = byKeyAcc.computeIfAbsent(
                    x.getXdbKey() + "|" + x.getAccId(),
                    k -> new ExtLink(x.getXdbKey(), x.getAccId(), firstNonEmpty(x.getLinkText(), x.getAccId())));
            if (!Utils.isStringEmpty(x.getSrcPipeline())) {
                e.sources.add(x.getSrcPipeline().trim());
            }
        }

        // Resolve database names/URLs, then sort by database then accession.
        List<ExtLink> all = new ArrayList<>(byKeyAcc.values());
        for (ExtLink e : all) {
            Xdb xdb = dao.getXdb(e.xdbKey);
            e.database = (xdb != null && !Utils.isStringEmpty(xdb.getName())) ? xdb.getName() : ("XDB " + e.xdbKey);
            e.url = buildXdbUrl(xdb, speciesTypeKey, e.accId);
        }
        all.sort(Comparator.comparing((ExtLink e) -> e.database, TEXT)
                .thenComparing(e -> e.accId, TEXT)
                .thenComparing(e -> e.linkText, TEXT));

        List<List<String>> rows = new ArrayList<>();
        for (ExtLink e : all) {
            String accCell = (e.url != null) ? Md.link(e.linkText, e.url) : Md.cell(e.linkText);
            rows.add(List.of(Md.cell(e.database), accCell, Md.cell(String.join(", ", e.sources))));
        }
        md.append(Md.heading(2, "External Database Links"));
        md.append(Md.table(List.of("Database", "Accession", "Source(s)"), rows));
    }

    /** Build an external accession URL from an xdb's URL template, honouring the [ID_HERE] placeholder. */
    private static String buildXdbUrl(Xdb xdb, int speciesTypeKey, String accId) {
        if (xdb == null) {
            return null;
        }
        String base = xdb.getUrl(speciesTypeKey);
        if (Utils.isStringEmpty(base)) {
            return null;
        }
        return base.contains("[ID_HERE]") ? base.replace("[ID_HERE]", accId) : base + accId;
    }

    /** One external-database cross-reference, aggregating the source pipelines that supplied it. */
    private static final class ExtLink {
        final int xdbKey;
        final String accId;
        final String linkText;
        final java.util.TreeSet<String> sources = new java.util.TreeSet<>(TEXT);
        String database;
        String url;
        ExtLink(int xdbKey, String accId, String linkText) {
            this.xdbKey = xdbKey;
            this.accId = accId;
            this.linkText = linkText;
        }
    }

    /** LOD score, falling back to the {@code qtl_statistics} note (matching the RGD QTL report). */
    protected String lodValue(QTL qtl) throws Exception {
        if (qtl.getLod() != null) {
            return String.valueOf(qtl.getLod());
        }
        return dao.getQtlNote(qtl.getRgdId(), "qtl_statistics");
    }

    /**
     * P-value display: the stored p-value, or — when it is null/zero but a -log10(p) is
     * recorded — the value reconstructed from that mantissa/exponent, as the RGD QTL report does.
     */
    protected static String pValue(QTL qtl) {
        Double p = qtl.getPValue();
        if ((p == null || p == 0) && qtl.getpValueMlog() != null) {
            double w = qtl.getpValueMlog();
            int x = (int) Math.ceil(w);
            int z = (int) Math.round(Math.pow(10, x - w));
            return z + "E-" + x;
        }
        return p == null ? "" : String.valueOf(p);
    }

    /**
     * Nomenclature History section: the record of symbol/name changes for the object, with the
     * reference behind each change and its curation status.
     */
    protected void appendNomenclatureHistory(StringBuilder md, int rgdId) throws Exception {
        List<NomenclatureEvent> events = dao.getNomenclatureEvents(rgdId);
        if (events == null || events.isEmpty()) {
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (NomenclatureEvent e : events) {
            String refCell = "";
            if (!Utils.isStringEmpty(e.getRefKey())) {
                try {
                    int refRgdId = dao.getReferenceRgdIdByKey(Integer.parseInt(e.getRefKey().trim()));
                    if (refRgdId > 0) {
                        refCell = Md.link("RGD:" + refRgdId, REFERENCE_REPORT_URL + refRgdId);
                    }
                } catch (NumberFormatException ignore) {
                    // non-numeric ref key — leave the reference cell blank
                }
            }
            rows.add(List.of(
                    date(e.getEventDate()),
                    Md.cell(Utils.defaultString(e.getSymbol())),
                    Md.cell(Utils.defaultString(e.getName())),
                    Md.cell(Utils.defaultString(e.getPreviousSymbol())),
                    Md.cell(Utils.defaultString(e.getPreviousName())),
                    Md.cell(Utils.defaultString(e.getDesc())),
                    refCell,
                    Md.cell(Utils.defaultString(e.getNomenStatusType()))
            ));
        }
        // Newest first; same-day events in a fixed order. Dates are ISO yyyy-MM-dd, so text order
        // is date order.
        rows.sort(Comparator.comparing((List<String> r) -> r.get(0), Comparator.reverseOrder())
                .thenComparing(ROW_ORDER));
        md.append(Md.heading(2, "Nomenclature History"));
        md.append(Md.table(
                List.of("Date", "Current Symbol", "Current Name", "Previous Symbol",
                        "Previous Name", "Description", "Reference", "Status"),
                rows));
    }

    /** ISO-style yyyy-MM-dd, or "" when the date is null. */
    protected static String date(Date d) {
        if (d == null) {
            return "";
        }
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        return String.format("%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /**
     * Four-digit publication year, or "" when the date is null or a placeholder: references with
     * no known date are stored with the year 1000 (or 1 BC), which must not reach the reader.
     */
    protected static String year(Date date) {
        if (date == null) {
            return "";
        }
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        if (c.get(Calendar.ERA) != java.util.GregorianCalendar.AD || c.get(Calendar.YEAR) < 1800) {
            return "";
        }
        return String.valueOf(c.get(Calendar.YEAR));
    }

    /** Aggregates one imported disease term with the set of databases it came from. */
    private static final class ImportedDisease {
        final String term;
        final String termAcc;
        final java.util.TreeSet<String> sources = new java.util.TreeSet<>(TEXT);
        ImportedDisease(String term, String termAcc) {
            this.term = term;
            this.termAcc = termAcc;
        }
    }

    protected static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (!Utils.isStringEmpty(v)) {
                return v;
            }
        }
        return "";
    }
}
