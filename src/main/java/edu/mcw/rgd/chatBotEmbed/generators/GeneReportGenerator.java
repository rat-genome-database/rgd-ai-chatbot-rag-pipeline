package edu.mcw.rgd.chatBotEmbed.generators;

import edu.mcw.rgd.chatBotEmbed.DAO;
import edu.mcw.rgd.chatBotEmbed.Md;
import edu.mcw.rgd.chatBotEmbed.MarkdownWriter;
import edu.mcw.rgd.chatBotEmbed.ReportDoc;
import edu.mcw.rgd.chatBotEmbed.ReportGenerator;
import edu.mcw.rgd.datamodel.Alias;
import edu.mcw.rgd.datamodel.Gene;
import edu.mcw.rgd.datamodel.Map;
import edu.mcw.rgd.datamodel.MapData;
import edu.mcw.rgd.datamodel.MappedQTL;
import edu.mcw.rgd.datamodel.NomenclatureEvent;
import edu.mcw.rgd.datamodel.QTL;
import edu.mcw.rgd.datamodel.Reference;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.Strain;
import edu.mcw.rgd.datamodel.Variant;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a gene report as markdown, structured for the chatbot's section-aware
 * chunker: real ATX headings (so every chunk carries a heading breadcrumb) and
 * GitHub tables with separator rows (so split tables keep their header).
 *
 * <p>Sections: Summary, Genomic Position (one row per assembly), Aliases,
 * Orthologs, and an Annotation block with one sub-section per ontology.</p>
 */
public class GeneReportGenerator implements ReportGenerator {

    private static final String GENE_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/gene/main.html?id=";
    private static final String REFERENCE_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/reference/main.html?id=";
    private static final String STRAIN_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/strain/main.html?id=";
    private static final String QTL_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/qtl/main.html?id=";
    private static final String VARIANT_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/rsId/main.html?geneId=";
    private static final String ONTOLOGY_TERM_URL = "https://rgd.mcw.edu/rgdweb/ontology/annot.html?acc_id=";

    /**
     * Ontology term-accession prefix -> annotation sub-section title, in display order.
     * Disease (DOID) is handled separately (manual vs imported) — see appendDiseaseAnnotations.
     */
    private static final LinkedHashMap<String, String> ONTOLOGY_SECTIONS = new LinkedHashMap<>();
    static {
        ONTOLOGY_SECTIONS.put("MP",  "Phenotype Annotations");
        ONTOLOGY_SECTIONS.put("PW",  "Pathway Annotations");
        ONTOLOGY_SECTIONS.put("GO",  "Gene Ontology Annotations");
    }

    /**
     * Evidence codes to skip entirely: computational / cross-species inference
     * (ISO = orthology, ISS = similarity, IEA = electronic, IBA = biological ancestor).
     */
    private static final Set<String> EXCLUDED_EVIDENCE = Set.of("ISO", "ISS", "IEA", "IBA");

    /** Only apply the evidence filter to annotation sets larger than this. */
    private static final int EVIDENCE_FILTER_THRESHOLD = 100;

    private final DAO dao;

    /** mapKey -> assembly, cached across genes/threads to avoid repeat lookups. */
    private final java.util.Map<Integer, Map> assemblyCache = new ConcurrentHashMap<>();

    public GeneReportGenerator(DAO dao) {
        this.dao = dao;
    }

    @Override
    public String getReportType() {
        return "gene";
    }

    @Override
    public List<Integer> getRgdIds(int speciesTypeKey) throws Exception {
        List<Gene> genes = dao.getActiveGenes(speciesTypeKey);
        List<Integer> ids = new ArrayList<>(genes.size());
        for (Gene g : genes) {
            ids.add(g.getRgdId());
        }
        return ids;
    }

    @Override
    public ReportDoc build(int rgdId, List<Map> assemblies) throws Exception {
        Gene gene = dao.getGene(rgdId);
        if (gene == null) {
            return null;
        }
        RgdId id = dao.getRgdId(rgdId);
        if (id == null || !"ACTIVE".equals(id.getObjectStatus())) {
            return null;   // withdrawn / retired — don't embed it
        }

        String symbol = Utils.defaultString(gene.getSymbol());
        String name = Utils.defaultString(gene.getName());
        String species = SpeciesType.getCommonName(gene.getSpeciesTypeKey());

        StringBuilder md = new StringBuilder(4096);

        // Title — becomes the root of every chunk's heading breadcrumb.
        md.append("# Gene: ").append(symbol);
        if (!name.isEmpty()) {
            md.append(" (").append(name).append(")");
        }
        md.append("\n\n");
        md.append("> **Source:** ")
          .append(Md.link("Rat Genome Database (RGD)", GENE_REPORT_URL + rgdId))
          .append("\n\n---\n\n");

        appendSummary(md, gene, rgdId, species);
        appendPositions(md, gene, assemblies);
        appendAliases(md, rgdId);
        appendOrthologs(md, rgdId);
        appendAnnotations(md, rgdId);
        appendVariantCount(md, gene);
        appendDamagingVariants(md, rgdId);
        appendReferences(md, rgdId);
        appendQtlsInRegion(md, gene);
        appendNucleotideSequences(md, gene);
        appendProteinSequences(md, gene);
        appendExternalDbLinks(md, gene);
        appendNomenclatureHistory(md, rgdId);

        md.append("\n---\n\n*This report was extracted from the ")
          .append(Md.link("Rat Genome Database (RGD)", "https://rgd.mcw.edu"))
          .append(", Medical College of Wisconsin.*\n");

        String displayName = "RGD Gene Report - " + symbol + " (" + rgdId + ")";
        return new ReportDoc(rgdId, displayName, MarkdownWriter.safeSymbol(symbol), md.toString());
    }

    private void appendSummary(StringBuilder md, Gene gene, int rgdId, String species) {
        md.append(Md.heading(2, "Summary"));
        md.append("- **Symbol:** ").append(Utils.defaultString(gene.getSymbol())).append("\n");
        if (!Utils.isStringEmpty(gene.getName())) {
            md.append("- **Name:** ").append(gene.getName()).append("\n");
        }
        md.append("- **RGD ID:** ").append(rgdId).append("\n");
        md.append("- **Species:** ").append(species).append("\n");
        if (!Utils.isStringEmpty(gene.getType())) {
            md.append("- **Gene Type:** ").append(gene.getType()).append("\n");
        }
        md.append("\n");

        String description = firstNonEmpty(gene.getMergedDescription(), gene.getAgrDescription(), gene.getDescription());
        if (!Utils.isStringEmpty(description)) {
            md.append(description.trim()).append("\n\n");
        }
    }

    /**
     * Genomic Position section.
     *
     * <p>When assemblies are configured (via {@code --mapKeys}) the gene's positions on
     * exactly those assemblies are shown. When none are configured, every position the gene
     * has — on genome assemblies of its own species — is shown instead, so the section is
     * populated rather than omitted.</p>
     */
    private void appendPositions(StringBuilder md, Gene gene, List<Map> assemblies) throws Exception {
        int rgdId = gene.getRgdId();
        List<List<String>> rows = new ArrayList<>();

        if (assemblies != null && !assemblies.isEmpty()) {
            for (Map assembly : assemblies) {
                for (MapData d : dao.getMapData(rgdId, assembly.getKey())) {
                    rows.add(positionRow(assembly.getName(), d));
                }
            }
        } else {
            int species = gene.getSpeciesTypeKey();
            List<Placement> placements = new ArrayList<>();
            for (MapData d : dao.getAllMapData(rgdId)) {
                Map assembly = resolveAssembly(d.getMapKey());
                if (assembly == null) continue;
                if (assembly.getSpeciesTypeKey() != species) continue;   // "in that species"
                if (!isGenomeAssembly(assembly)) continue;               // skip cM / genetic maps
                placements.add(new Placement(assembly, d));
            }
            // Primary/most-recent assemblies first (lower rank), then by name.
            placements.sort(Comparator.<Placement>comparingInt(p -> p.assembly.getRank())
                    .thenComparing(p -> Utils.defaultString(p.assembly.getName())));
            for (Placement p : placements) {
                rows.add(positionRow(p.assembly.getName(), p.data));
            }
        }

        if (rows.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Genomic Position"));
        md.append(Md.table(List.of("Assembly", "Chromosome", "Start", "Stop", "Strand"), rows));
    }

    private static List<String> positionRow(String assemblyName, MapData d) {
        return List.of(
                Md.cell(assemblyName),
                Md.cell(d.getChromosome()),
                d.getStartPos() == null ? "" : String.valueOf(d.getStartPos()),
                d.getStopPos() == null ? "" : String.valueOf(d.getStopPos()),
                Md.cell(Utils.defaultString(d.getStrand()))
        );
    }

    /** Resolve (and cache) an assembly by map key. */
    private Map resolveAssembly(Integer mapKey) throws Exception {
        if (mapKey == null) {
            return null;
        }
        Map cached = assemblyCache.get(mapKey);
        if (cached != null) {
            return cached;
        }
        Map assembly = dao.getAssembly(mapKey);
        if (assembly != null) {
            assemblyCache.put(mapKey, assembly);
        }
        return assembly;
    }

    /** A base-pair genome assembly, as opposed to a cM genetic / RH map. */
    private static boolean isGenomeAssembly(Map assembly) {
        String unit = assembly.getUnit();
        return unit == null || unit.isBlank() || unit.equalsIgnoreCase("bp");
    }

    /** Pairs a resolved assembly with one of the gene's positions on it, for sorting. */
    private record Placement(Map assembly, MapData data) {}

    /**
     * QTLs in Region section: QTLs whose mapped region overlaps the gene's position on its
     * species' primary reference assembly (e.g. GRCr8 for rat). The gene may be placed more
     * than once on that assembly; each placement's region is queried and the QTLs are merged,
     * deduplicated by QTL RGD ID.
     */
    private void appendQtlsInRegion(StringBuilder md, Gene gene) throws Exception {
        Map primary = dao.getPrimaryRefAssembly(gene.getSpeciesTypeKey());
        if (primary == null) {
            return;
        }
        List<MapData> placements = dao.getMapData(gene.getRgdId(), primary.getKey());
        if (placements == null || placements.isEmpty()) {
            return;
        }

        // Merge QTLs across every placement of the gene, keeping one row per QTL RGD ID.
        LinkedHashMap<Integer, MappedQTL> unique = new LinkedHashMap<>();
        for (MapData d : placements) {
            if (d.getChromosome() == null || d.getStartPos() == null || d.getStopPos() == null) {
                continue;
            }
            for (MappedQTL mq : dao.getQtlsInRegion(d.getChromosome(), d.getStartPos(), d.getStopPos(), primary.getKey())) {
                if (mq.getQTL() != null) {
                    unique.putIfAbsent(mq.getQTL().getRgdId(), mq);
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
        md.append("*QTLs whose mapped region overlaps this gene's position on "
                + primary.getName() + ".*\n\n");
        md.append(Md.table(List.of("Symbol", "Name", "LOD", "P-value", "Trait", "Sub-trait"), rows));
    }

    /** LOD score, falling back to the {@code qtl_statistics} note (matching the RGD QTL report). */
    private String lodValue(QTL qtl) throws Exception {
        if (qtl.getLod() != null) {
            return String.valueOf(qtl.getLod());
        }
        return dao.getQtlNote(qtl.getRgdId(), "qtl_statistics");
    }

    /**
     * P-value display: the stored p-value, or — when it is null/zero but a -log10(p) is
     * recorded — the value reconstructed from that mantissa/exponent, as the RGD QTL report does.
     */
    private static String pValue(QTL qtl) {
        Double p = qtl.getPValue();
        if ((p == null || p == 0) && qtl.getpValueMlog() != null) {
            double w = qtl.getpValueMlog();
            int x = (int) Math.ceil(w);
            int z = (int) Math.round(Math.pow(10, x - w));
            return z + "E-" + x;
        }
        return p == null ? "" : String.valueOf(p);
    }

    private void appendAliases(StringBuilder md, int rgdId) throws Exception {
        List<Alias> aliases = dao.getAliases(rgdId);
        if (aliases == null || aliases.isEmpty()) {
            return;
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Alias a : aliases) {
            if (!Utils.isStringEmpty(a.getValue())) {
                values.add(a.getValue().trim());
            }
        }
        if (values.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Aliases"));
        md.append(String.join(", ", values)).append("\n\n");
    }

    private void appendOrthologs(StringBuilder md, int rgdId) throws Exception {
        List<Gene> orthologs = dao.getActiveOrthologs(rgdId);
        if (orthologs == null || orthologs.isEmpty()) {
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (Gene o : orthologs) {
            rows.add(List.of(
                    Md.cell(SpeciesType.getCommonName(o.getSpeciesTypeKey())),
                    Md.cell(Utils.defaultString(o.getSymbol())),
                    Md.cell(Utils.defaultString(o.getName())),
                    Md.link("RGD:" + o.getRgdId(), GENE_REPORT_URL + o.getRgdId())
            ));
        }
        md.append(Md.heading(2, "Orthologs"));
        md.append(Md.table(List.of("Species", "Symbol", "Name", "RGD ID"), rows));
    }

    /**
     * Nucleotide Sequences section: the sequence accessions associated with the gene, grouped
     * into RefSeq transcripts and GenBank entries. Only the accession name is listed — the
     * report's FASTA / NCBI Sequence Viewer / Search-GEO links are intentionally omitted.
     */
    private void appendNucleotideSequences(StringBuilder md, Gene gene) throws Exception {
        List<XdbId> seqs = dao.getNucleotideSequences(gene.getRgdId(), gene.getSpeciesTypeKey());
        if (seqs == null || seqs.isEmpty()) {
            return;
        }
        List<String> refseq = new ArrayList<>();
        List<String> genbank = new ArrayList<>();
        for (XdbId x : seqs) {
            String name = firstNonEmpty(x.getLinkText(), x.getAccId()).trim();
            if (name.isEmpty()) {
                continue;
            }
            (isRefSeq(x.getAccId()) ? refseq : genbank).add(name);
        }
        refseq.sort(String.CASE_INSENSITIVE_ORDER);
        genbank.sort(String.CASE_INSENSITIVE_ORDER);
        if (refseq.isEmpty() && genbank.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Nucleotide Sequences"));
        if (!refseq.isEmpty()) {
            md.append("**RefSeq Transcripts:** ").append(String.join(", ", refseq)).append("\n\n");
        }
        if (!genbank.isEmpty()) {
            md.append("**GenBank Nucleotide:** ").append(String.join(", ", genbank)).append("\n\n");
        }
    }

    /**
     * Protein Sequences section: the protein accessions associated with the gene, grouped into
     * RefSeq proteins, GenBank proteins, and Ensembl proteins. As with nucleotide sequences,
     * only the accession name is listed — no FASTA / NCBI Sequence Viewer links.
     */
    private void appendProteinSequences(StringBuilder md, Gene gene) throws Exception {
        List<XdbId> seqs = dao.getProteinSequences(gene.getRgdId(), gene.getSpeciesTypeKey());
        if (seqs == null || seqs.isEmpty()) {
            return;
        }
        List<String> refseq = new ArrayList<>();
        List<String> genbank = new ArrayList<>();
        List<String> ensembl = new ArrayList<>();
        for (XdbId x : seqs) {
            String name = firstNonEmpty(x.getLinkText(), x.getAccId()).trim();
            if (name.isEmpty()) {
                continue;
            }
            if (isRefSeq(x.getAccId())) {
                refseq.add(name);
            } else if (x.getXdbKey() == XdbId.XDB_KEY_ENSEMBL_PROTEIN) {
                ensembl.add(name);
            } else {
                genbank.add(name);
            }
        }
        refseq.sort(String.CASE_INSENSITIVE_ORDER);
        genbank.sort(String.CASE_INSENSITIVE_ORDER);
        ensembl.sort(String.CASE_INSENSITIVE_ORDER);
        if (refseq.isEmpty() && genbank.isEmpty() && ensembl.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Protein Sequences"));
        if (!refseq.isEmpty()) {
            md.append("**Protein RefSeqs:** ").append(String.join(", ", refseq)).append("\n\n");
        }
        if (!genbank.isEmpty()) {
            md.append("**GenBank Protein:** ").append(String.join(", ", genbank)).append("\n\n");
        }
        if (!ensembl.isEmpty()) {
            md.append("**Ensembl Protein:** ").append(String.join(", ", ensembl)).append("\n\n");
        }
    }

    /** A RefSeq accession has an underscore as its third character (NM_, XM_, NR_, XR_, ...). */
    private static boolean isRefSeq(String accId) {
        return accId != null && accId.length() > 3 && accId.charAt(2) == '_';
    }

    /**
     * External Database Links section: the gene's cross-references to other databases, one row
     * per database/accession with the source pipeline(s) that supplied it. Sequence and
     * literature cross-references are excluded (they have their own sections); see
     * {@link DAO#getExternalDbLinks}.
     */
    private void appendExternalDbLinks(StringBuilder md, Gene gene) throws Exception {
        List<XdbId> links = dao.getExternalDbLinks(gene.getRgdId(), gene.getSpeciesTypeKey());
        if (links == null || links.isEmpty()) {
            return;
        }
        int species = gene.getSpeciesTypeKey();

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
            e.url = buildXdbUrl(xdb, species, e.accId);
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

    /**
     * Nomenclature History section: the record of symbol/name changes for the gene, with the
     * reference behind each change and its curation status.
     */
    private void appendNomenclatureHistory(StringBuilder md, int rgdId) throws Exception {
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
    private static String date(Date d) {
        if (d == null) {
            return "";
        }
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        return String.format("%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /**
     * Emit the annotation block. Each ontology becomes a level-3 sub-section under a
     * single level-2 "Annotation" heading, so the chunker's breadcrumb reads
     * {@code # Gene: A2m > ## Annotation > ### Disease Annotations}.
     */
    private void appendAnnotations(StringBuilder md, int rgdId) throws Exception {
        StringBuilder buf = new StringBuilder();
        appendDiseaseAnnotations(buf, rgdId);
        for (var section : ONTOLOGY_SECTIONS.entrySet()) {
            appendAnnotationSection(buf, rgdId, section.getKey(), section.getValue());
        }
        if (buf.length() > 0) {
            md.append(Md.heading(2, "Annotation"));
            md.append(buf);
        }
    }

    /**
     * References section: the PubMed literature associated with the gene, newest first.
     * References without a PubMed ID (internal data-load/curation entries) are omitted.
     */
    private void appendReferences(StringBuilder md, int rgdId) throws Exception {
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

    private static String year(Date date) {
        if (date == null) {
            return "";
        }
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        return String.valueOf(c.get(Calendar.YEAR));
    }

    /**
     * A one-line count of the gene's variants, linking to the full rsId variant report. The
     * count is the number of variants in the gene's region on the variant-reporting assembly
     * (GRCr8 for rat), matching what that report shows. Rendered just above Damaging Variants.
     */
    private void appendVariantCount(StringBuilder md, Gene gene) throws Exception {
        int mapKey = dao.getVariantReportMapKey(gene.getSpeciesTypeKey());
        if (mapKey <= 0) {
            return;
        }
        MapData pos = null;
        for (MapData d : dao.getMapData(gene.getRgdId(), mapKey)) {
            if (d.getChromosome() != null && d.getStartPos() != null && d.getStopPos() != null) {
                pos = d;
                break;
            }
        }
        if (pos == null) {
            return;
        }
        int count = dao.getVariantCountForGeneRegion(mapKey, pos.getChromosome(), pos.getStartPos(), pos.getStopPos());
        if (count <= 0) {
            return;
        }
        md.append(Md.heading(2, "Variants"));
        md.append("**Total variants:** ")
          .append(Md.link(String.format("%,d", count), VARIANT_REPORT_URL + gene.getRgdId()))
          .append("\n\n");
    }

    /**
     * Damaging Variants section: variants predicted damaging by PolyPhen, grouped by
     * assembly. Sourced from the CarpeNovo variant database via {@link DAO}. The same variant
     * can appear across many samples, so variants are deduplicated by position and change.
     */
    private void appendDamagingVariants(StringBuilder md, int rgdId) throws Exception {
        List<String> assemblyKeys = dao.getDamagingVariantAssemblies(rgdId);
        if (assemblyKeys == null || assemblyKeys.isEmpty()) {
            return;
        }

        StringBuilder buf = new StringBuilder();
        for (String mapKeyStr : assemblyKeys) {
            List<Variant> variants = dao.getDamagingVariantsForGene(rgdId, mapKeyStr);
            if (variants == null || variants.isEmpty()) {
                continue;
            }

            // Collapse the same variant seen across multiple samples.
            LinkedHashMap<String, Variant> unique = new LinkedHashMap<>();
            for (Variant v : variants) {
                String key = v.getChromosome() + ":" + v.getStartPos() + "-" + v.getEndPos()
                        + ":" + Utils.defaultString(v.getReferenceNucleotide())
                        + ">" + Utils.defaultString(v.getVariantNucleotide());
                unique.putIfAbsent(key, v);
            }

            List<List<String>> rows = new ArrayList<>();
            for (Variant v : unique.values()) {
                String position;
                if ("snv".equalsIgnoreCase(v.getVariantType()) || v.getStartPos() == v.getEndPos()) {
                    position = String.valueOf(v.getStartPos());
                } else {
                    position = v.getStartPos() + "-" + v.getEndPos();
                }
                String rsId = Utils.defaultString(v.getRsId()).trim();
                boolean hasRs = !rsId.isEmpty() && !rsId.equals(".");   // "." means no dbSNP ID
                String rsCell = hasRs ? Md.link(rsId, "https://www.ncbi.nlm.nih.gov/snp/" + rsId) : "";
                rows.add(List.of(
                        Md.cell(v.getChromosome()),
                        position,
                        Md.cell(Utils.defaultString(v.getReferenceNucleotide())),
                        Md.cell(Utils.defaultString(v.getVariantNucleotide())),
                        Md.cell(Utils.defaultString(v.getVariantType())),
                        rsCell
                ));
            }

            buf.append(Md.heading(3, assemblyName(mapKeyStr)));
            buf.append(Md.table(List.of("Chromosome", "Position", "Ref", "Var", "Type", "rsID"), rows));
            appendDamagingVariantStrains(buf, rgdId, mapKeyStr);
        }

        if (buf.length() > 0) {
            md.append(Md.heading(2, "Damaging Variants"));
            md.append(buf);
        }
    }

    /** List the strains that carry the gene's damaging variants on one assembly. */
    private void appendDamagingVariantStrains(StringBuilder md, int rgdId, String mapKeyStr) throws Exception {
        List<Strain> strains = dao.getDamagingVariantStrains(rgdId, mapKeyStr);
        if (strains == null || strains.isEmpty()) {
            return;
        }
        strains.sort(Comparator.comparing(s -> Utils.defaultString(s.getSymbol()).toLowerCase()));
        List<String> links = new ArrayList<>();
        for (Strain s : strains) {
            links.add(Md.link(Utils.defaultString(s.getSymbol()), STRAIN_REPORT_URL + s.getRgdId()));
        }
        md.append("**Strains with damaging variants (").append(strains.size()).append("):** ")
          .append(String.join(", ", links)).append("\n\n");
    }

    /** Assembly display name from a map-key string, falling back to the raw key. */
    private String assemblyName(String mapKeyStr) {
        try {
            Map asm = resolveAssembly(Integer.parseInt(mapKeyStr.trim()));
            if (asm != null && !Utils.isStringEmpty(asm.getName())) {
                return asm.getName();
            }
        } catch (Exception ignore) {
            // fall through to the raw key
        }
        return mapKeyStr;
    }

    /**
     * Disease (DOID) annotations, split the way RGD splits them: manually curated
     * annotations (data source "RGD") get the full layout; annotations imported from other
     * databases get a compact overview — one row per distinct disease with its sources,
     * without the per-annotation evidence/reference detail.
     */
    private void appendDiseaseAnnotations(StringBuilder md, int rgdId) throws Exception {
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

    private void appendAnnotationSection(StringBuilder md, int rgdId, String prefix, String title) throws Exception {
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
     * full — the filter exists to trim heavily-annotated genes, not sparse ones.
     */
    private static List<Annotation> filterEvidence(List<Annotation> annotations) {
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
    private List<List<String>> annotationRows(List<Annotation> annotations) {
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

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (!Utils.isStringEmpty(v)) {
                return v;
            }
        }
        return "";
    }
}
