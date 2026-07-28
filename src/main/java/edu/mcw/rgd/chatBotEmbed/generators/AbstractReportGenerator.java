package edu.mcw.rgd.chatBotEmbed.generators;

import edu.mcw.rgd.chatBotEmbed.DAO;
import edu.mcw.rgd.chatBotEmbed.Md;
import edu.mcw.rgd.chatBotEmbed.ReportGenerator;
import edu.mcw.rgd.datamodel.MapData;
import edu.mcw.rgd.datamodel.MappedQTL;
import edu.mcw.rgd.datamodel.NomenclatureEvent;
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
            md.append(Md.table(List.of("Term", "Qualifier", "Evidence", "Reference"), manualRows));
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
        md.append(Md.table(List.of("Term", "Qualifier", "Evidence", "Reference"), rows));
    }

    /**
     * Drop annotations whose evidence code is in {@link #EXCLUDED_EVIDENCE}, but only when the
     * set is large (more than {@link #EVIDENCE_FILTER_THRESHOLD}). Smaller sets are kept in
     * full — the filter exists to trim heavily-annotated objects, not sparse ones.
     */
    protected static List<Annotation> filterEvidence(List<Annotation> annotations) {
        if (annotations == null) {
            return new ArrayList<>();
        }
        if (annotations.size() <= EVIDENCE_FILTER_THRESHOLD) {
            return annotations;
        }
        List<Annotation> kept = new ArrayList<>();
        for (Annotation a : annotations) {
            String ev = a.getEvidence();
            if (ev != null && EXCLUDED_EVIDENCE.contains(ev.trim().toUpperCase())) {
                continue;
            }
            kept.add(a);
        }
        return kept;
    }

    /** Full annotation table rows (Term | Qualifier | Evidence | Reference), deduplicated. */
    protected List<List<String>> annotationRows(List<Annotation> annotations) {
        // Collapse duplicates that differ only by reference/source.
        LinkedHashMap<String, Annotation> unique = new LinkedHashMap<>();
        for (Annotation a : annotations) {
            String key = a.getTermAcc() + "|" + Utils.defaultString(a.getQualifier()) + "|" + Utils.defaultString(a.getEvidence());
            unique.putIfAbsent(key, a);
        }

        List<List<String>> rows = new ArrayList<>();
        for (Annotation a : unique.values()) {
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
                    reference
            ));
        }
        return rows;
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

        List<List<String>> rows = new ArrayList<>();
        for (Reference ref : references) {
            List<String> pmids = dao.getPubmedIdsForReference(ref.getRgdId());
            if (pmids.isEmpty()) {
                continue;   // keep only PubMed references
            }
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
     * QTLs in Region section: QTLs whose mapped region overlaps this object's position on its
     * species' primary reference assembly (e.g. GRCr8 for rat). The object may be placed more
     * than once on that assembly; each placement's region is queried and the QTLs are merged,
     * deduplicated by QTL RGD ID. The object itself is excluded, so a QTL report does not list
     * itself among the QTLs overlapping its own region.
     *
     * @param rgdId          the object (gene or QTL) whose region is queried
     * @param speciesTypeKey that object's species, used to pick the primary reference assembly
     */
    protected void appendQtlsInRegion(StringBuilder md, int rgdId, int speciesTypeKey) throws Exception {
        edu.mcw.rgd.datamodel.Map primary = dao.getPrimaryRefAssembly(speciesTypeKey);
        if (primary == null) {
            return;
        }
        List<MapData> placements = dao.getMapData(rgdId, primary.getKey());
        if (placements == null || placements.isEmpty()) {
            return;
        }

        // Merge QTLs across every placement, keeping one row per QTL RGD ID; exclude self.
        LinkedHashMap<Integer, MappedQTL> unique = new LinkedHashMap<>();
        for (MapData d : placements) {
            if (d.getChromosome() == null || d.getStartPos() == null || d.getStopPos() == null) {
                continue;
            }
            for (MappedQTL mq : dao.getQtlsInRegion(d.getChromosome(), d.getStartPos(), d.getStopPos(), primary.getKey())) {
                QTL q = mq.getQTL();
                if (q != null && q.getRgdId() != rgdId) {
                    unique.putIfAbsent(q.getRgdId(), mq);
                }
            }
        }
        if (unique.isEmpty()) {
            return;
        }

        List<MappedQTL> mapped = new ArrayList<>(unique.values());
        mapped.sort(Comparator.comparing(mq -> Utils.defaultString(mq.getQTL().getSymbol()).toLowerCase()));

        List<List<String>> rows = new ArrayList<>();
        for (MappedQTL mq : mapped) {
            QTL qtl = mq.getQTL();
            int qtlRgdId = qtl.getRgdId();
            rows.add(List.of(
                    Md.link(Utils.defaultString(qtl.getSymbol()), QTL_REPORT_URL + qtlRgdId),
                    Md.cell(Utils.defaultString(qtl.getName())),
                    Md.cell(lodValue(qtl)),
                    Md.cell(pValue(qtl)),
                    Md.cell(dao.getQtlTrait(qtlRgdId)),
                    Md.cell(dao.getQtlSubtrait(qtlRgdId, qtl.getMostSignificantCmoTerm()))
            ));
        }

        md.append(Md.heading(2, "QTLs in Region (" + primary.getName() + ")"));
        md.append("*QTLs whose mapped region overlaps this region on "
                + primary.getName() + ".*\n\n");
        md.append(Md.table(List.of("Symbol", "Name", "LOD", "P-value", "Trait", "Sub-trait"), rows));
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
        all.sort(Comparator.comparing((ExtLink e) -> e.database.toLowerCase())
                .thenComparing(e -> e.accId.toLowerCase()));

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
        final LinkedHashSet<String> sources = new LinkedHashSet<>();
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

    /** Four-digit publication year, or "" when the date is null. */
    protected static String year(Date date) {
        if (date == null) {
            return "";
        }
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        return String.valueOf(c.get(Calendar.YEAR));
    }

    /** Aggregates one imported disease term with the set of databases it came from. */
    private static final class ImportedDisease {
        final String term;
        final String termAcc;
        final LinkedHashSet<String> sources = new LinkedHashSet<>();
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
