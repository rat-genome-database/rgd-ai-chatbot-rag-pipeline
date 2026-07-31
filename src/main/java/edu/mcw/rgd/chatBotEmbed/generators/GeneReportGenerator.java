package edu.mcw.rgd.chatBotEmbed.generators;

import edu.mcw.rgd.chatBotEmbed.DAO;
import edu.mcw.rgd.chatBotEmbed.Md;
import edu.mcw.rgd.chatBotEmbed.MarkdownWriter;
import edu.mcw.rgd.chatBotEmbed.ReportDoc;
import edu.mcw.rgd.datamodel.Alias;
import edu.mcw.rgd.datamodel.Gene;
import edu.mcw.rgd.datamodel.Map;
import edu.mcw.rgd.datamodel.MapData;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.Strain;
import edu.mcw.rgd.datamodel.Variant;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.Utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a gene report as markdown, structured for the chatbot's section-aware
 * chunker: real ATX headings (so every chunk carries a heading breadcrumb) and
 * GitHub tables with separator rows (so split tables keep their header).
 *
 * <p>Sections: Summary, Genomic Position (one row per assembly), Aliases,
 * Orthologs, and an Annotation block with one sub-section per ontology.</p>
 */
public class GeneReportGenerator extends AbstractReportGenerator {

    private static final String GENE_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/gene/main.html?id=";
    private static final String STRAIN_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/strain/main.html?id=";
    private static final String VARIANT_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/rsId/main.html?geneId=";

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

    /** mapKey -> assembly, cached across genes/threads to avoid repeat lookups. */
    private final java.util.Map<Integer, Map> assemblyCache = new ConcurrentHashMap<>();

    public GeneReportGenerator(DAO dao) {
        super(dao);
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
        appendAnnotations(md, rgdId, ONTOLOGY_SECTIONS);
        appendVariantCount(md, gene);
        appendDamagingVariants(md, rgdId);
        appendReferences(md, rgdId);
        appendQtlsInRegion(md, gene.getRgdId(), gene.getSpeciesTypeKey());
        appendNucleotideSequences(md, gene);
        appendProteinSequences(md, gene);
        appendExternalDbLinks(md, gene.getRgdId(), gene.getSpeciesTypeKey());
        appendNomenclatureHistory(md, rgdId);

        md.append("\n---\n\n*This report was extracted from the ")
          .append(Md.link("Rat Genome Database (RGD)", "https://rgd.mcw.edu"))
          .append(", Medical College of Wisconsin.*\n");

        String displayName = "RGD Gene Report - " + symbol + " (" + species + ") (" + rgdId + ")";
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
}
