package edu.mcw.rgd.chatBotEmbed.generators;

import edu.mcw.rgd.chatBotEmbed.DAO;
import edu.mcw.rgd.chatBotEmbed.Md;
import edu.mcw.rgd.chatBotEmbed.MarkdownWriter;
import edu.mcw.rgd.chatBotEmbed.ReportDoc;
import edu.mcw.rgd.datamodel.Alias;
import edu.mcw.rgd.datamodel.GenomicElement;
import edu.mcw.rgd.datamodel.Map;
import edu.mcw.rgd.datamodel.QTL;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.Sample;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.Strain;
import edu.mcw.rgd.datamodel.Strain2MarkerAssociation;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.datamodel.ontologyx.TermWithStats;
import edu.mcw.rgd.process.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds a strain report as markdown, structured for the chatbot's section-aware chunker
 * (real ATX headings, so every chunk carries a heading breadcrumb).
 *
 * <p>The Summary mirrors the informative fields of the RGD strain report page's
 * {@code report/strain/info.jsp}: symbol, strain/substrain, full name, RGD ID, citation (RRID)
 * and ontology IDs, aliases, type, source, origination, genetic markers/status, coat color,
 * inbred generations, last known status, research usage and description. Page-only elements
 * (the ChatGPT explainer link, photo, file-download links, HRDP portal link and the genomic
 * position table) are intentionally omitted — they carry nothing for retrieval.</p>
 *
 * <p>The display name is {@code RGD Strain Report - <symbol> (<rgdId>)}, matching the name the
 * chatbot's earlier ingest used, so re-embedding replaces those rows rather than duplicating
 * them.</p>
 */
public class StrainReportGenerator extends AbstractReportGenerator {

    private static final String STRAIN_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/strain/main.html?id=";
    private static final String ONTOLOGY_VIEW_URL = "https://rgd.mcw.edu/rgdweb/ontology/view.html?acc_id=";
    private static final String CELLLINE_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/cellline/main.html?id=";

    /** RRRC (Rat Resource &amp; Research Center) xdb key — used to build the RRID citation. */
    private static final int XDB_KEY_RRRC = 141;

    /** Strain-ontology (RS) accessions distinguishing congenic and mutant strains. */
    private static final String RS_CONGENIC = "RS:0000459";
    private static final String RS_MUTANT = "RS:0000461";

    /**
     * Ontology term-accession prefix -> annotation sub-section title, in display order. These are
     * the ontologies the strain report's generic Annotation block carries (matching the old
     * scraped report). Quantitative PhenoMiner measurement ontologies (CMO/MMO/XCO) belong to the
     * separate "Related Phenotype Data" section, not here. Disease (DOID) is handled separately
     * (manual vs imported) by {@link AbstractReportGenerator#appendAnnotations}.
     */
    private static final LinkedHashMap<String, String> ONTOLOGY_SECTIONS = new LinkedHashMap<>();
    static {
        ONTOLOGY_SECTIONS.put("MP", "Mammalian Phenotype Annotations");
        ONTOLOGY_SECTIONS.put("VT", "Vertebrate Trait Annotations");
        ONTOLOGY_SECTIONS.put("RS", "Rat Strain Annotations");
    }

    public StrainReportGenerator(DAO dao) {
        super(dao);
    }

    @Override
    public String getReportType() {
        return "strain";
    }

    @Override
    public List<Integer> getRgdIds(int speciesTypeKey) throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (Strain s : dao.getActiveStrains()) {
            if (s.getSpeciesTypeKey() == speciesTypeKey) {
                ids.add(s.getRgdId());
            }
        }
        return ids;
    }

    @Override
    public ReportDoc build(int rgdId, List<Map> assemblies) throws Exception {
        Strain strain = dao.getStrain(rgdId);
        if (strain == null) {
            return null;
        }
        RgdId id = dao.getRgdId(rgdId);
        if (id == null || !"ACTIVE".equals(id.getObjectStatus())) {
            return null;   // withdrawn / retired — don't embed it
        }

        String symbol = Utils.defaultString(strain.getSymbol());

        StringBuilder md = new StringBuilder(2048);

        // Title — becomes the root of every chunk's heading breadcrumb.
        md.append("# Strain: ").append(symbol).append("\n\n");
        md.append("> **Source:** ")
          .append(Md.link("Rat Genome Database (RGD)", STRAIN_REPORT_URL + rgdId))
          .append("\n\n---\n\n");

        appendSummary(md, strain, rgdId);
        appendHighlights(md, strain);
        appendAnnotations(md, rgdId, ONTOLOGY_SECTIONS);
        appendReferences(md, rgdId);
        appendRegion(md, strain);
        appendExternalDbLinks(md, rgdId, strain.getSpeciesTypeKey());
        appendNomenclatureHistory(md, rgdId);

        md.append("\n---\n\n*This report was extracted from the ")
          .append(Md.link("Rat Genome Database (RGD)", "https://rgd.mcw.edu"))
          .append(", Medical College of Wisconsin.*\n");

        String displayName = "RGD Strain Report - " + symbol + " (" + rgdId + ")";
        return new ReportDoc(rgdId, displayName, MarkdownWriter.safeSymbol(symbol), md.toString());
    }

    /**
     * Summary section — the strain report page's info panel, as a bullet list. Optional fields
     * are emitted only when populated, so the section stays free of "N/A" filler that would
     * otherwise be embedded as if it were data. The free-text description follows the bullets
     * as its own paragraph, like the gene report's description.
     */
    private void appendSummary(StringBuilder md, Strain strain, int rgdId) throws Exception {
        md.append(Md.heading(2, "Summary"));
        md.append("- **Symbol:** ").append(Utils.defaultString(strain.getSymbol())).append("\n");
        appendLine(md, "Strain", strain.getStrain());
        appendLine(md, "Substrain", strain.getSubstrain());
        appendLine(md, "Full Name", strain.getName());
        md.append("- **RGD ID:** ").append(rgdId).append("\n");
        md.append("- **Species:** ").append(SpeciesType.getCommonName(strain.getSpeciesTypeKey())).append("\n");
        appendLine(md, "Citation ID", citationId(rgdId));
        appendLine(md, "Ontology ID", dao.getStrainOntId(rgdId));
        appendLine(md, "Also Known As", alsoKnownAs(strain));
        appendLine(md, "Type", strain.getStrainTypeName());
        appendLine(md, "Available Source", strain.getSource());
        appendLine(md, "Origination", strain.getOrigination());
        appendLine(md, "Genetic Markers", strain.getGenetics());
        appendLine(md, "Genetic Status", strain.getGeneticStatus());
        appendLine(md, "Coat Color", strain.getColor());
        appendLine(md, "Inbred Generations", strain.getInbredGen());
        appendLine(md, "Last Known Status", strain.getLastStatus());
        appendLine(md, "Research Usage", strain.getResearchUse());
        md.append("\n");

        if (!Utils.isStringEmpty(strain.getDescription())) {
            md.append(strain.getDescription().trim()).append("\n\n");
        }
    }

    /**
     * Highlights section: the strain's substrains, congenic strains and mutant strains, each as
     * a comma-separated list of linked names. Substrains come from the strain-symbol match;
     * congenics and mutants are the strain-ontology child terms under the congenic (RS:0000459)
     * and mutant (RS:0000461) branches, linking to the strain report where the term maps to a
     * strain object and to the ontology term otherwise. The whole section (and each sub-section)
     * is emitted only when it has content.
     */
    private void appendHighlights(StringBuilder md, Strain strain) throws Exception {
        String substrains = substrainList(strain.getSymbol());

        String congenics = "";
        String mutants = "";
        String ontId = dao.getStrainOntId(strain.getRgdId());
        if (!Utils.isStringEmpty(ontId)) {
            List<TermWithStats> children = dao.getActiveStrainOntChildren(ontId, strain.getSpeciesTypeKey());
            congenics = ontologyStrainList(children, RS_CONGENIC);
            mutants = ontologyStrainList(children, RS_MUTANT);
        }

        if (substrains.isEmpty() && congenics.isEmpty() && mutants.isEmpty()) {
            return;
        }

        md.append(Md.heading(2, "Highlights"));
        if (!substrains.isEmpty()) {
            md.append(Md.heading(3, "Substrains")).append(substrains).append("\n\n");
        }
        if (!congenics.isEmpty()) {
            md.append(Md.heading(3, "Congenic Strains")).append(congenics).append("\n\n");
        }
        if (!mutants.isEmpty()) {
            md.append(Md.heading(3, "Mutant Strains")).append(mutants).append("\n\n");
        }
    }

    /** Comma-separated list of a strain's substrains, each a link to its strain report. */
    private String substrainList(String symbol) throws Exception {
        List<String> links = new ArrayList<>();
        for (Strain s : dao.getSubStrains(symbol)) {
            links.add(Md.link(Utils.defaultString(s.getSymbol()), STRAIN_REPORT_URL + s.getRgdId()));
        }
        return String.join(", ", links);
    }

    /**
     * Comma-separated list of the strain-ontology child terms that descend from {@code branchAcc}
     * (the congenic or mutant branch). Each term links to its strain report when it maps to a
     * strain object, or to the ontology term view when it does not.
     */
    private String ontologyStrainList(List<TermWithStats> children, String branchAcc) throws Exception {
        List<String> links = new ArrayList<>();
        for (TermWithStats term : children) {
            if (!dao.isDescendantOf(term.getAccId(), branchAcc)) {
                continue;
            }
            int strainRgdId = dao.getRgdIdForStrainOntId(term.getAccId());
            String url = (strainRgdId != 0)
                    ? STRAIN_REPORT_URL + strainRgdId
                    : ONTOLOGY_VIEW_URL + term.getAccId();
            links.add(Md.link(Utils.defaultString(term.getTerm()), url));
        }
        return String.join(", ", links);
    }

    /**
     * Region section: the strain's cell lines, position markers, QTL associations and the samples
     * carrying damaging variants. Each is a level-3 sub-section under one level-2 "Region" heading,
     * so the breadcrumb reads {@code # Strain: X > ## Region > ### Strain QTL Data}. Genomic
     * positions of the individual markers are intentionally omitted — only identity is reported.
     */
    private void appendRegion(StringBuilder md, Strain strain) throws Exception {
        StringBuilder buf = new StringBuilder();
        appendCellLines(buf, strain);
        appendPositionMarkers(buf, strain);
        appendStrainQtlData(buf, strain);
        appendStrainDamagingVariants(buf, strain);
        if (buf.length() > 0) {
            md.append(Md.heading(2, "Region"));
            md.append(buf);
        }
    }

    /** Cell Lines sub-section: cell lines derived from the strain, as a list of linked symbols. */
    private void appendCellLines(StringBuilder md, Strain strain) throws Exception {
        List<GenomicElement> cellLines = dao.getStrainCellLines(strain.getRgdId());
        if (cellLines == null || cellLines.isEmpty()) {
            return;
        }
        cellLines.sort((a, b) -> Utils.defaultString(a.getSymbol()).compareToIgnoreCase(Utils.defaultString(b.getSymbol())));
        List<String> links = new ArrayList<>();
        for (GenomicElement ge : cellLines) {
            links.add(Md.link(Utils.defaultString(ge.getSymbol()), CELLLINE_REPORT_URL + ge.getRgdId()));
        }
        md.append(Md.heading(3, "Cell Lines")).append(String.join(", ", links)).append("\n\n");
    }

    /**
     * Position Markers sub-section: the strain's associated markers (SSLP, gene, strain, variant),
     * excluding allele associations. Reports the association type, region name and marker identity
     * — the report page's per-marker position table is omitted.
     */
    private void appendPositionMarkers(StringBuilder md, Strain strain) throws Exception {
        List<Strain2MarkerAssociation> assocs = dao.getStrainMarkerAssociations(strain.getRgdId());
        if (assocs == null || assocs.isEmpty()) {
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (Strain2MarkerAssociation sa : assocs) {
            String type = sa.getMarkerType();
            if (Utils.isStringEmpty(type) || "allele".equalsIgnoreCase(type.trim())) {
                continue;   // drop alleles (many associated genes are alleles)
            }
            String marker = Utils.isStringEmpty(sa.getMarkerSymbol())
                    ? "RGD:" + sa.getMarkerRgdId() : sa.getMarkerSymbol();
            rows.add(List.of(
                    Md.cell(capitalize(type)),
                    Md.cell(Utils.defaultString(sa.getRegionName())),
                    Md.cell(marker),
                    Md.cell("RGD:" + sa.getMarkerRgdId())
            ));
        }
        if (rows.isEmpty()) {
            return;
        }
        md.append(Md.heading(3, "Position Markers"));
        md.append(Md.table(List.of("Type", "Region", "Marker", "RGD ID"), rows));
    }

    /**
     * Strain QTL Data sub-section: QTLs associated with the strain, sorted by symbol. Trait is the
     * QTL's Vertebrate Trait annotation (with the {@code qtl_trait} note fallback), the same
     * derivation the QTL report and gene "QTLs in Region" use.
     */
    private void appendStrainQtlData(StringBuilder md, Strain strain) throws Exception {
        List<QTL> qtls = dao.getQtlAssociationsForStrain(strain.getRgdId());
        if (qtls == null || qtls.isEmpty()) {
            return;
        }
        qtls.sort((a, b) -> Utils.defaultString(a.getSymbol()).compareToIgnoreCase(Utils.defaultString(b.getSymbol())));
        List<List<String>> rows = new ArrayList<>();
        for (QTL qtl : qtls) {
            rows.add(List.of(
                    Md.link(Utils.defaultString(qtl.getSymbol()), QTL_REPORT_URL + qtl.getRgdId()),
                    Md.cell(Utils.defaultString(qtl.getName())),
                    Md.cell(dao.getQtlTrait(qtl.getRgdId()))
            ));
        }
        md.append(Md.heading(3, "Strain QTL Data"));
        md.append(Md.table(List.of("Symbol", "Name", "Trait"), rows));
    }

    /**
     * Damaging Variants sub-section: the strain's variant samples that carry PolyPhen-damaging
     * variants, by assembly. Mirrors the report page's "Strain Samples in RGD with Damaging
     * Variants (PolyPhen)" — sample identity only, not the individual variants.
     */
    private void appendStrainDamagingVariants(StringBuilder md, Strain strain) throws Exception {
        List<String> assemblies = dao.getStrainDamagingVariantAssemblies(strain.getRgdId());
        if (assemblies == null || assemblies.isEmpty()) {
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (Sample s : dao.getStrainSamples(strain.getRgdId())) {
            if (!dao.sampleHasDamagingVariants(s.getId(), s.getMapKey())) {
                continue;
            }
            rows.add(List.of(
                    Md.cell(assemblyName(s.getMapKey())),
                    Md.cell(Utils.defaultString(s.getAnalysisName()))
            ));
        }
        if (rows.isEmpty()) {
            return;
        }
        md.append(Md.heading(3, "Strain Samples with Damaging Variants (PolyPhen)"));
        md.append(Md.table(List.of("Assembly", "Sample"), rows));
    }

    /** Reference-assembly name for a map key, falling back to the raw key. */
    private String assemblyName(int mapKey) throws Exception {
        Map asm = dao.getAssembly(mapKey);
        if (asm != null) {
            String name = !Utils.isStringEmpty(asm.getRefSeqAssemblyName()) ? asm.getRefSeqAssemblyName() : asm.getName();
            if (!Utils.isStringEmpty(name)) {
                return name;
            }
        }
        return String.valueOf(mapKey);
    }

    /** Capitalize the first character (e.g. "sslp" -> "Sslp"), leaving the rest unchanged. */
    private static String capitalize(String s) {
        String t = s.trim();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Emit "- **Label:** value" when the value is non-blank; skip the field entirely otherwise. */
    private static void appendLine(StringBuilder md, String label, String value) {
        if (!Utils.isStringEmpty(value)) {
            md.append("- **").append(label).append(":** ").append(value.trim()).append("\n");
        }
    }

    /**
     * The strain's RRID citation, as the report page derives it: {@code RRID:RRRC_0nnnn} (or
     * {@code RRID:RRRC_nnnnn}) when the strain has an RRRC accession, otherwise
     * {@code RRID:RGD_<rgdId>}.
     */
    private String citationId(int rgdId) throws Exception {
        List<XdbId> rrrc = dao.getXdbIdsByKey(XDB_KEY_RRRC, rgdId);
        if (rrrc != null && !rrrc.isEmpty() && !Utils.isStringEmpty(rrrc.get(0).getAccId())) {
            String acc = rrrc.get(0).getAccId().trim();
            return acc.length() == 4 ? "RRID:RRRC_0" + acc : "RRID:RRRC_" + acc;
        }
        return "RRID:RGD_" + rgdId;
    }

    /**
     * The strain's aliases, joined with "; ", including the tagless strain symbol as a
     * pseudo-alias when it differs from the symbol — matching the report page's "Also Known As".
     */
    private String alsoKnownAs(Strain strain) throws Exception {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Alias a : dao.getAliases(strain.getRgdId())) {
            if (!Utils.isStringEmpty(a.getValue())) {
                values.add(a.getValue().trim());
            }
        }
        String tagless = strain.getTaglessStrainSymbol();
        if (!Utils.isStringEmpty(tagless) && !tagless.trim().equalsIgnoreCase(Utils.defaultString(strain.getSymbol()))) {
            values.add(tagless.trim());
        }
        return String.join("; ", values);
    }
}
