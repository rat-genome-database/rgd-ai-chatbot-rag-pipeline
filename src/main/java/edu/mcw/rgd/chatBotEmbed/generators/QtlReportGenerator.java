package edu.mcw.rgd.chatBotEmbed.generators;

import edu.mcw.rgd.chatBotEmbed.DAO;
import edu.mcw.rgd.chatBotEmbed.Md;
import edu.mcw.rgd.chatBotEmbed.MarkdownWriter;
import edu.mcw.rgd.chatBotEmbed.ReportDoc;
import edu.mcw.rgd.datamodel.Alias;
import edu.mcw.rgd.datamodel.GWASCatalog;
import edu.mcw.rgd.datamodel.Map;
import edu.mcw.rgd.datamodel.MapData;
import edu.mcw.rgd.datamodel.QTL;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.Strain;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.process.Utils;

import java.text.DecimalFormat;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a QTL report as markdown, structured for the chatbot's section-aware chunker
 * (real ATX headings, so every chunk carries a heading breadcrumb).
 *
 * <p>The Summary mirrors the fields of the RGD QTL report page's {@code report/qtl/info.jsp}:
 * symbol, name, RGD ID, previously-known-as, trait, measurement type, LOD, p-value, variance,
 * inheritance type, cross type, and strains crossed (rat) / population stats (human). The
 * page's JBrowse link is intentionally omitted — it carries no information for retrieval.</p>
 *
 * <p>The display name is {@code RGD Qtl Report - <symbol> (<species>) (<rgdId>)}; the species
 * common name disambiguates the same symbol across species in the {@code file_name} column and
 * in citations.</p>
 */
public class QtlReportGenerator extends AbstractReportGenerator {

    private static final String STRAIN_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/strain/main.html?id=";
    private static final String GENE_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/gene/main.html?id=";
    private static final String MARKER_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/marker/main.html?id=";
    private static final String RSID_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/rsId/main.html?id=";
    private static final String GWAS_STUDY_URL = "https://www.ebi.ac.uk/gwas/studies/";

    /**
     * Ontology term-accession prefix -> annotation sub-section title, in display order.
     * These are the experimental-data ontologies a QTL is annotated with (the RGD QTL report's
     * Experimental Data Annotations plus Mammalian Phenotype). Disease (DOID) is handled
     * separately (manual vs imported) by {@link AbstractReportGenerator#appendAnnotations}.
     */
    private static final LinkedHashMap<String, String> ONTOLOGY_SECTIONS = new LinkedHashMap<>();
    static {
        ONTOLOGY_SECTIONS.put("VT",  "Vertebrate Trait Annotations");
        ONTOLOGY_SECTIONS.put("CMO", "Clinical Measurement Annotations");
        ONTOLOGY_SECTIONS.put("MMO", "Measurement Method Annotations");
        ONTOLOGY_SECTIONS.put("XCO", "Experimental Condition Annotations");
        ONTOLOGY_SECTIONS.put("MP",  "Mammalian Phenotype Annotations");
    }

    /** mapKey -> assembly, cached across QTLs/threads to avoid repeat lookups. */
    private final java.util.Map<Integer, Map> assemblyCache = new ConcurrentHashMap<>();

    public QtlReportGenerator(DAO dao) {
        super(dao);
    }

    @Override
    public String getReportType() {
        return "qtl";
    }

    @Override
    public List<Integer> getRgdIds(int speciesTypeKey) throws Exception {
        List<QTL> qtls = dao.getActiveQtls(speciesTypeKey);
        List<Integer> ids = new ArrayList<>(qtls.size());
        for (QTL q : qtls) {
            ids.add(q.getRgdId());
        }
        return ids;
    }

    @Override
    public ReportDoc build(int rgdId, List<Map> assemblies) throws Exception {
        QTL qtl = dao.getQtl(rgdId);
        if (qtl == null) {
            return null;
        }
        RgdId id = dao.getRgdId(rgdId);
        if (id == null || !"ACTIVE".equals(id.getObjectStatus())) {
            return null;   // withdrawn / retired — don't embed it
        }

        String symbol = Utils.defaultString(qtl.getSymbol());
        String name = Utils.defaultString(qtl.getName());

        StringBuilder md = new StringBuilder(2048);

        // Title — becomes the root of every chunk's heading breadcrumb. The RGD ID precedes the
        // symbol so every chunk carries it. Otherwise matches the report page heading:
        // "QTL: <symbol> (<name>) <taxonomic name>".
        md.append("# QTL: RGD:").append(rgdId).append(" ").append(symbol);
        if (!name.isEmpty()) {
            md.append(" (").append(name).append(")");
        }
        String taxonomicName = SpeciesType.getTaxonomicName(qtl.getSpeciesTypeKey());
        if (!Utils.isStringEmpty(taxonomicName)) {
            md.append(" ").append(taxonomicName);
        }
        md.append("\n\n");
        md.append("> **Source:** ")
          .append(Md.link("Rat Genome Database (RGD)", QTL_REPORT_URL + rgdId))
          .append("\n\n---\n\n");

        appendSummary(md, qtl, rgdId);
        appendPositions(md, qtl, assemblies);
        appendAnnotations(md, rgdId, ONTOLOGY_SECTIONS);
        appendReferences(md, rgdId);
        appendRelatedQtls(md, qtl);
        appendGenesInRegion(md, qtl);
        appendMarkersInRegion(md, qtl);
        appendPositionMarkers(md, qtl);
        appendQtlsInRegion(md, rgdId, qtl.getSpeciesTypeKey());
        appendGwasQtlInfo(md, qtl);
        appendExternalDbLinks(md, rgdId, qtl.getSpeciesTypeKey());
        appendNomenclatureHistory(md, rgdId);

        md.append("\n---\n\n*This report was extracted from the ")
          .append(Md.link("Rat Genome Database (RGD)", "https://rgd.mcw.edu"))
          .append(", Medical College of Wisconsin.*\n");

        String species = SpeciesType.getCommonName(qtl.getSpeciesTypeKey());
        String displayName = "RGD Qtl Report - " + symbol + " (" + species + ") (" + rgdId + ")";
        return new ReportDoc(rgdId, displayName, MarkdownWriter.safeSymbol(symbol), md.toString());
    }

    /**
     * Summary section — the fields of the QTL report page's info panel. Each is emitted only
     * when it has a value, so the section stays free of "Not Available" filler that would
     * otherwise be embedded as if it were data.
     */
    private void appendSummary(StringBuilder md, QTL qtl, int rgdId) throws Exception {
        md.append(Md.heading(2, "Summary"));
        md.append("- **Symbol:** ").append(Utils.defaultString(qtl.getSymbol())).append("\n");
        if (!Utils.isStringEmpty(qtl.getName())) {
            md.append("- **Name:** ").append(qtl.getName()).append("\n");
        }
        md.append("- **RGD ID:** ").append(rgdId).append("\n");
        md.append("- **Species:** ").append(SpeciesType.getCommonName(qtl.getSpeciesTypeKey())).append("\n");

        appendLine(md, "Previously known as", aliases(rgdId));
        appendLine(md, "Trait", dao.getQtlTrait(rgdId));
        appendLine(md, "Measurement Type", dao.getQtlSubtrait(rgdId, qtl.getMostSignificantCmoTerm()));
        appendLine(md, "LOD Score", lodValue(qtl));
        appendLine(md, "P Value", pValue(qtl));
        appendLine(md, "Variance", qtl.getVariance() == null ? "" : String.valueOf(qtl.getVariance()));
        appendLine(md, "Inheritance Type", Utils.defaultString(qtl.getInheritanceType()));

        // Cross type is not recorded for human QTLs.
        if (qtl.getSpeciesTypeKey() != SpeciesType.HUMAN) {
            appendLine(md, "Cross Type", dao.getQtlNote(rgdId, "qtl_cross_type"));
        }

        // Rat QTLs record the strains crossed; human QTLs record a study population instead.
        if (qtl.getSpeciesTypeKey() == SpeciesType.HUMAN) {
            appendLine(md, "Population Stats", dao.getQtlNote(rgdId, "qtl_population"));
        } else {
            appendLine(md, "Strains Crossed", strainsCrossed(rgdId));
        }

        md.append("\n");
    }

    /** Emit "- **Label:** value" when the value is non-blank; skip the field entirely otherwise. */
    private static void appendLine(StringBuilder md, String label, String value) {
        if (!Utils.isStringEmpty(value)) {
            md.append("- **").append(label).append(":** ").append(value.trim()).append("\n");
        }
    }

    /** The QTL's aliases, joined with "; " as the report page does. */
    private String aliases(int rgdId) throws Exception {
        List<Alias> aliases = dao.getAliases(rgdId);
        if (aliases == null || aliases.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Alias a : aliases) {
            if (!Utils.isStringEmpty(a.getValue())) {
                values.add(a.getValue().trim());
            }
        }
        return String.join("; ", values);
    }

    /** The strains crossed to map this QTL, as links to their strain reports. */
    private String strainsCrossed(int rgdId) throws Exception {
        List<Strain> strains = dao.getStrainsCrossedForQtl(rgdId);
        if (strains == null || strains.isEmpty()) {
            return "";
        }
        strains.sort(Comparator.comparing(s -> Utils.defaultString(s.getSymbol()).toLowerCase()));
        List<String> links = new ArrayList<>(strains.size());
        for (Strain s : strains) {
            links.add(Md.link(Utils.defaultString(s.getSymbol()), STRAIN_REPORT_URL + s.getRgdId()));
        }
        return String.join(", ", links);
    }

    /**
     * Genomic Position section. When assemblies are configured (via {@code --mapKeys}) the
     * QTL's positions on exactly those assemblies are shown; otherwise every position it has
     * on genome assemblies of its own species is shown.
     */
    private void appendPositions(StringBuilder md, QTL qtl, List<Map> assemblies) throws Exception {
        int rgdId = qtl.getRgdId();
        List<List<String>> rows = new ArrayList<>();

        if (assemblies != null && !assemblies.isEmpty()) {
            for (Map assembly : assemblies) {
                for (MapData d : dao.getMapData(rgdId, assembly.getKey())) {
                    rows.add(positionRow(assembly.getName(), d));
                }
            }
        } else {
            int species = qtl.getSpeciesTypeKey();
            List<Placement> placements = new ArrayList<>();
            for (MapData d : dao.getAllMapData(rgdId)) {
                Map assembly = resolveAssembly(d.getMapKey());
                if (assembly == null) continue;
                if (assembly.getSpeciesTypeKey() != species) continue;
                if (!isGenomeAssembly(assembly)) continue;   // skip cM / genetic maps
                placements.add(new Placement(assembly, d));
            }
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
        md.append(Md.table(List.of("Assembly", "Chromosome", "Start", "Stop"), rows));
    }

    private static List<String> positionRow(String assemblyName, MapData d) {
        return List.of(
                Md.cell(assemblyName),
                Md.cell(d.getChromosome()),
                d.getStartPos() == null ? "" : String.valueOf(d.getStartPos()),
                d.getStopPos() == null ? "" : String.valueOf(d.getStopPos())
        );
    }

    /**
     * Related QTLs section: the QTL's curated associations to other QTLs. Each association's
     * value is a {@code ||}-delimited record
     * {@code speciesTypeKey||description||referenceRgdId||relatedQtlRgdId||relatedQtlSymbol};
     * the map key is the related QTL's RGD ID. Table columns match the report page: the related
     * QTL (linked), the relationship description, and the reference behind it.
     */
    private void appendRelatedQtls(StringBuilder md, QTL qtl) throws Exception {
        if (qtl.getKey() == null) {
            return;
        }
        java.util.Map<Integer, String> related = dao.getQtlToQtlAssociations(qtl.getKey());
        if (related == null || related.isEmpty()) {
            return;
        }

        List<List<String>> rows = new ArrayList<>();
        for (java.util.Map.Entry<Integer, String> e : related.entrySet()) {
            int relatedRgdId = e.getKey();
            String[] parts = e.getValue().split("\\|\\|", -1);
            String description = parts.length > 1 ? parts[1] : "";
            String refRgdId = parts.length > 2 ? parts[2] : "";
            String symbol = parts.length > 4 ? parts[4] : String.valueOf(relatedRgdId);

            String refCell = "";
            if (!Utils.isStringEmpty(refRgdId)) {
                try {
                    refCell = Md.link("RGD:" + Integer.parseInt(refRgdId.trim()),
                            REFERENCE_REPORT_URL + refRgdId.trim());
                } catch (NumberFormatException ignore) {
                    // non-numeric reference id — leave the reference cell blank
                }
            }
            rows.add(List.of(
                    Md.link(Utils.defaultString(symbol), QTL_REPORT_URL + relatedRgdId),
                    Md.cell(Utils.defaultString(description)),
                    refCell
            ));
        }

        // Stable, human-friendly order: by related QTL symbol.
        rows.sort(Comparator.comparing(r -> r.get(0).toLowerCase()));

        md.append(Md.heading(2, "Related QTLs"));
        md.append(Md.table(List.of("Related QTL", "Description", "Reference"), rows));
    }

    /**
     * Genes in Region section: the genes whose position on the QTL's primary reference assembly
     * (e.g. GRCr8 for rat) overlaps the QTL's region there. The QTL may be placed more than once
     * on that assembly; genes are merged across placements and deduplicated by gene RGD ID.
     *
     * <p>Only identity is reported — RGD ID, symbol, name — deliberately not each gene's own
     * position, which belongs on the gene's own report and only bloats this list.</p>
     */
    private void appendGenesInRegion(StringBuilder md, QTL qtl) throws Exception {
        Map primary = dao.getPrimaryRefAssembly(qtl.getSpeciesTypeKey());
        if (primary == null) {
            return;
        }
        List<MapData> placements = dao.getMapData(qtl.getRgdId(), primary.getKey());
        if (placements == null || placements.isEmpty()) {
            return;
        }

        // Merge genes across every placement of the QTL, keeping one row per gene RGD ID.
        java.util.LinkedHashMap<Integer, edu.mcw.rgd.datamodel.Gene> unique = new java.util.LinkedHashMap<>();
        for (MapData d : placements) {
            if (d.getChromosome() == null || d.getStartPos() == null || d.getStopPos() == null) {
                continue;
            }
            for (edu.mcw.rgd.datamodel.Gene g : dao.getGenesInRegion(
                    d.getChromosome(), d.getStartPos(), d.getStopPos(), primary.getKey())) {
                unique.putIfAbsent(g.getRgdId(), g);
            }
        }
        if (unique.isEmpty()) {
            return;
        }

        List<edu.mcw.rgd.datamodel.Gene> genes = new ArrayList<>(unique.values());
        genes.sort(Comparator.comparing(g -> Utils.defaultString(g.getSymbol()).toLowerCase()));

        List<List<String>> rows = new ArrayList<>();
        for (edu.mcw.rgd.datamodel.Gene g : genes) {
            rows.add(List.of(
                    Md.link("RGD:" + g.getRgdId(), GENE_REPORT_URL + g.getRgdId()),
                    Md.cell(Utils.defaultString(g.getSymbol())),
                    Md.cell(Utils.defaultString(g.getName()))
            ));
        }

        md.append(Md.heading(2, "Genes in Region (" + primary.getName() + ")"));
        md.append("*Genes whose position on " + primary.getName()
                + " overlaps this QTL's region.*\n\n");
        md.append(Md.table(List.of("RGD ID", "Symbol", "Name"), rows));
    }

    /**
     * Markers in Region section: the markers (SSLPs) whose position on the QTL's primary
     * reference assembly overlaps the QTL's region there, merged across the QTL's placements and
     * deduplicated by marker RGD ID. Only the marker's name and RGD ID are reported — the
     * marker's own position is intentionally omitted.
     */
    private void appendMarkersInRegion(StringBuilder md, QTL qtl) throws Exception {
        Map primary = dao.getPrimaryRefAssembly(qtl.getSpeciesTypeKey());
        if (primary == null) {
            return;
        }
        List<MapData> placements = dao.getMapData(qtl.getRgdId(), primary.getKey());
        if (placements == null || placements.isEmpty()) {
            return;
        }

        java.util.LinkedHashMap<Integer, edu.mcw.rgd.datamodel.SSLP> unique = new java.util.LinkedHashMap<>();
        for (MapData d : placements) {
            if (d.getChromosome() == null || d.getStartPos() == null || d.getStopPos() == null) {
                continue;
            }
            for (edu.mcw.rgd.datamodel.SSLP s : dao.getMarkersInRegion(
                    d.getChromosome(), d.getStartPos(), d.getStopPos(), primary.getKey())) {
                unique.putIfAbsent(s.getRgdId(), s);
            }
        }
        if (unique.isEmpty()) {
            return;
        }

        List<edu.mcw.rgd.datamodel.SSLP> markers = new ArrayList<>(unique.values());
        markers.sort(Comparator.comparing(s -> Utils.defaultString(s.getName()).toLowerCase()));

        List<List<String>> rows = new ArrayList<>();
        for (edu.mcw.rgd.datamodel.SSLP s : markers) {
            rows.add(List.of(
                    Md.link("RGD:" + s.getRgdId(), MARKER_REPORT_URL + s.getRgdId()),
                    Md.cell(Utils.defaultString(s.getName()))
            ));
        }

        md.append(Md.heading(2, "Markers in Region (" + primary.getName() + ")"));
        md.append("*Markers whose position on " + primary.getName()
                + " overlaps this QTL's region.*\n\n");
        md.append(Md.table(List.of("RGD ID", "Name"), rows));
    }

    /**
     * Position Markers section: the QTL's flanking and peak markers. Each role (Flank 1, Peak,
     * Flank 2) is resolved to its marker name and RGD ID — or, when the marker is a variant given
     * only by rs ID, to that rs ID. The report page's per-marker position table is intentionally
     * omitted; only the marker's identity is reported.
     */
    private void appendPositionMarkers(StringBuilder md, QTL qtl) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        addPositionMarkerRow(rows, "Flank 1", qtl.getFlank1RgdId(), qtl.getFlank1RsId());
        addPositionMarkerRow(rows, "Peak", qtl.getPeakRgdId(), qtl.getPeakRsId());
        addPositionMarkerRow(rows, "Flank 2", qtl.getFlank2RgdId(), qtl.getFlank2RsId());
        if (rows.isEmpty()) {
            return;
        }
        md.append(Md.heading(2, "Position Markers"));
        md.append(Md.table(List.of("Position", "Marker", "RGD ID"), rows));
    }

    /**
     * Add one Position Markers row for a role. Prefers an RGD-object marker (resolved to its
     * symbol or name); falls back to an rs-ID variant marker. Roles with neither are skipped.
     */
    private void addPositionMarkerRow(List<List<String>> rows, String role, Integer rgdId, String rsId)
            throws Exception {
        if (rgdId != null && rgdId > 0) {
            String name = "";
            Object obj = dao.getObject(rgdId);
            if (obj instanceof edu.mcw.rgd.datamodel.ObjectWithSymbol) {
                name = ((edu.mcw.rgd.datamodel.ObjectWithSymbol) obj).getSymbol();
            } else if (obj instanceof edu.mcw.rgd.datamodel.ObjectWithName) {
                name = ((edu.mcw.rgd.datamodel.ObjectWithName) obj).getName();
            }
            rows.add(List.of(
                    Md.cell(role),
                    Md.cell(Utils.defaultString(name)),
                    Md.cell("RGD:" + rgdId)
            ));
        } else if (!Utils.isStringEmpty(rsId)) {
            rows.add(List.of(
                    Md.cell(role),
                    Md.link(rsId, RSID_REPORT_URL + rsId),
                    Md.cell("")
            ));
        }
    }

    /**
     * GWAS QTLs Related by Peak Marker section — <b>human QTLs only</b>. Lists GWAS Catalog
     * entries that share this QTL's peak marker (rs ID), mirroring the report page's human GWAS
     * table. Rat GWAS uses a different layout on the page and is intentionally not produced here.
     */
    private void appendGwasQtlInfo(StringBuilder md, QTL qtl) throws Exception {
        if (qtl.getSpeciesTypeKey() != SpeciesType.HUMAN) {
            return;
        }
        String peakRsId = qtl.getPeakRsId();
        if (Utils.isStringEmpty(peakRsId)) {
            return;
        }
        List<GWASCatalog> gwasList = dao.getGwasByRsId(peakRsId);
        if (gwasList == null || gwasList.isEmpty()) {
            return;
        }

        DecimalFormat mlogFormat = new DecimalFormat("#.###");
        mlogFormat.setRoundingMode(RoundingMode.CEILING);

        List<List<String>> rows = new ArrayList<>();
        for (GWASCatalog g : gwasList) {
            rows.add(List.of(
                    gwasQtlCell(g.getQtlRgdId()),
                    Utils.isStringEmpty(g.getStudyAcc()) ? ""
                            : Md.link(g.getStudyAcc(), GWAS_STUDY_URL + g.getStudyAcc()),
                    Md.cell(Utils.defaultString(g.getDiseaseTrait())),
                    Md.cell(Utils.defaultString(g.getInitialSample())),
                    Md.cell(Utils.defaultString(g.getStrongSnpRiskallele())),
                    Md.cell(nvl(g.getRiskAlleleFreq())),
                    Md.cell(g.getpVal() == null ? "" : g.getpVal().toPlainString()),
                    Md.cell(g.getpValMlog() == null ? "" : mlogFormat.format(g.getpValMlog())),
                    Utils.isStringEmpty(g.getSnps()) ? ""
                            : Md.link(g.getSnps(), RSID_REPORT_URL + g.getSnps()),
                    Md.cell(nvl(g.getOrBeta())),
                    Md.cell(gwasOntologyTerms(g.getEfoId())),
                    pubmedCell(g.getPmid())
            ));
        }

        md.append(Md.heading(2, "GWAS QTLs Related by Peak Marker"));
        md.append(Md.table(List.of(
                "QTL", "GWAS Catalog Study", "Disease Trait", "Study Size", "Risk Allele",
                "Risk Allele Frequency", "P-value", "P-value MLOG", "Peak Marker",
                "Odds Ratio / Beta", "Ontology Terms", "PubMed"), rows));
    }

    /** The GWAS entry's QTL as a linked symbol, or "None Available" when absent/withdrawn. */
    private String gwasQtlCell(Integer qtlRgdId) throws Exception {
        if (qtlRgdId == null || qtlRgdId == 0) {
            return Md.cell("None Available");
        }
        QTL q = dao.getQtl(qtlRgdId);
        if (q == null) {
            return Md.cell("None Available");
        }
        return Md.link(Utils.defaultString(q.getSymbol()), QTL_REPORT_URL + q.getRgdId());
    }

    /**
     * The GWAS entry's EFO accessions rendered as "term name (ACC)" links, skipping Orphanet,
     * NCIT and MONDO terms exactly as the report page does. "N/A" when none resolve.
     */
    private String gwasOntologyTerms(String efoId) throws Exception {
        if (Utils.isStringEmpty(efoId)) {
            return "N/A";
        }
        List<String> terms = new ArrayList<>();
        for (String raw : efoId.replace('_', ':').split(",")) {
            String acc = raw.trim();
            if (acc.isEmpty() || acc.contains("Orphanet") || acc.contains("NCIT") || acc.contains("MONDO")) {
                continue;
            }
            Term term = dao.getOntologyTerm(acc);
            if (term != null && !Utils.isStringEmpty(term.getTerm())) {
                terms.add(Md.link(term.getTerm() + " (" + term.getAccId() + ")", ONTOLOGY_TERM_URL + term.getAccId()));
            }
        }
        return terms.isEmpty() ? "N/A" : String.join("; ", terms);
    }

    /** "N/A" for blank values, matching the report page's {@code Utils.NVL} default. */
    private static String nvl(String value) {
        return Utils.isStringEmpty(value) ? "N/A" : value.trim();
    }

    /** PubMed cell: the PMID (which may arrive as {@code PMID:12345} or bare) linked to PubMed. */
    private static String pubmedCell(String pmid) {
        if (Utils.isStringEmpty(pmid)) {
            return "";
        }
        String id = pmid.contains(":") ? pmid.substring(pmid.indexOf(':') + 1).trim() : pmid.trim();
        if (id.isEmpty()) {
            return "";
        }
        return Md.link("PMID:" + id, "https://pubmed.ncbi.nlm.nih.gov/" + id + "/");
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

    /** Pairs a resolved assembly with one of the QTL's positions on it, for sorting. */
    private record Placement(Map assembly, MapData data) {}
}
