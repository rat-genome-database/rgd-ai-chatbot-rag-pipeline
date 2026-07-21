package edu.mcw.rgd.chatBotEmbed;

import edu.mcw.rgd.dao.AbstractDAO;
import edu.mcw.rgd.dao.DataSourceFactory;
import edu.mcw.rgd.dao.impl.AliasDAO;
import edu.mcw.rgd.dao.impl.AnnotationDAO;
import edu.mcw.rgd.dao.impl.GeneDAO;
import edu.mcw.rgd.dao.impl.MapDAO;
import edu.mcw.rgd.dao.impl.NomenclatureDAO;
import edu.mcw.rgd.dao.impl.NotesDAO;
import edu.mcw.rgd.dao.impl.QTLDAO;
import edu.mcw.rgd.dao.impl.RGDManagementDAO;
import edu.mcw.rgd.dao.impl.ReferenceDAO;
import edu.mcw.rgd.dao.impl.StrainDAO;
import edu.mcw.rgd.dao.impl.VariantDAO;
import edu.mcw.rgd.dao.impl.XdbIdDAO;
import edu.mcw.rgd.dao.spring.StringMapQuery;
import edu.mcw.rgd.datamodel.Alias;
import edu.mcw.rgd.datamodel.Gene;
import edu.mcw.rgd.datamodel.Map;
import edu.mcw.rgd.datamodel.MapData;
import edu.mcw.rgd.datamodel.MappedQTL;
import edu.mcw.rgd.datamodel.NomenclatureEvent;
import edu.mcw.rgd.datamodel.Note;
import edu.mcw.rgd.datamodel.Reference;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.Strain;
import edu.mcw.rgd.datamodel.Variant;
import edu.mcw.rgd.datamodel.Xdb;
import edu.mcw.rgd.datamodel.XDBIndex;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.mapping.MapManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Thin facade over the rgdcore DAOs the generators need. All Oracle access goes
 * through here. Database credentials are never held here — the rgdcore DAOs obtain
 * their connection from {@code DataSourceFactory}, which resolves the {@code dataSource}
 * bean configured in {@code properties/AppConfigure.xml} at deploy time.
 */
public class DAO {

    private final GeneDAO geneDAO = new GeneDAO();
    private final MapDAO mapDAO = new MapDAO();
    private final AliasDAO aliasDAO = new AliasDAO();
    private final AnnotationDAO annotationDAO = new AnnotationDAO();
    private final RGDManagementDAO rgdManagementDAO = new RGDManagementDAO();
    private final ReferenceDAO referenceDAO = new ReferenceDAO();
    private final QTLDAO qtlDAO = new QTLDAO();
    private final NotesDAO notesDAO = new NotesDAO();
    private final NomenclatureDAO nomenclatureDAO = new NomenclatureDAO();
    private final XdbIdDAO xdbIdDAO = new XdbIdDAO();

    /**
     * Xdb keys the report's External Database Links section leaves out: literature (PubMed),
     * pathways (KEGG), and the sequence keys shown in their own Sequence subsections
     * (GenBank nucleotide/protein, Ensembl protein), plus PID and TransposAgen.
     */
    private static final Set<Integer> EXCLUDED_XDB_KEYS = Set.of(
            XdbId.XDB_KEY_PUBMED, XdbId.XDB_KEY_KEGGPATHWAY, XdbId.XDB_KEY_PID,
            XdbId.XDB_KEY_GENEBANKNU, XdbId.XDB_KEY_GENEBANKPROT,
            XdbId.XDB_KEY_ENSEMBL_PROTEIN, XdbId.XDB_KEY_TRANSPOSAGEN);
    private final VariantDAO variantDAO = new VariantDAO();   // uses the CarpeNovo (RATCNDEV) datasource
    private final StrainDAO strainDAO = new StrainDAO();      // main RGD datasource (strain symbols)
    // Variant-region counts (rsId report); self-resolves to the CarpeNovo datasource.
    private final edu.mcw.rgd.dao.impl.variants.VariantDAO variantsDAO = new edu.mcw.rgd.dao.impl.variants.VariantDAO();

    /** Human-readable description of the active connection, for a startup log line. */
    public String getConnectionInfo() {
        return geneDAO.getConnectionInfo();
    }

    // ---- Gene ----------------------------------------------------------------

    public Gene getGene(int rgdId) throws Exception {
        return geneDAO.getGene(rgdId);
    }

    public List<Gene> getActiveGenes(int speciesTypeKey) throws Exception {
        return geneDAO.getActiveGenes(speciesTypeKey);
    }

    /** Active homologs/orthologs of a gene, returned as their own gene objects. */
    public List<Gene> getActiveOrthologs(int rgdId) throws Exception {
        return geneDAO.getActiveOrthologs(rgdId);
    }

    // ---- RGD ID / status -----------------------------------------------------

    public RgdId getRgdId(int rgdId) throws Exception {
        return rgdManagementDAO.getRgdId(rgdId);
    }

    // ---- Maps / positions ----------------------------------------------------

    /** Resolve an assembly by its map key (e.g. GRCr8, mRatBN7.2). */
    public Map getAssembly(int mapKey) throws Exception {
        return mapDAO.getMapByKey(mapKey);
    }

    /** Genomic positions of an object on one assembly. */
    public List<MapData> getMapData(int rgdId, int mapKey) throws Exception {
        return mapDAO.getMapData(rgdId, mapKey);
    }

    /** All genomic positions of an object, across every map it is placed on. */
    public List<MapData> getAllMapData(int rgdId) throws Exception {
        return mapDAO.getMapData(rgdId);
    }

    /** The primary reference assembly for a species (e.g. GRCr8 for rat), or null. */
    public Map getPrimaryRefAssembly(int speciesTypeKey) throws Exception {
        return mapDAO.getPrimaryRefAssembly(speciesTypeKey);
    }

    // ---- QTLs -----------------------------------------------------------------

    /** Active QTLs whose mapped region overlaps [start, stop] on the given chromosome/assembly. */
    public List<MappedQTL> getQtlsInRegion(String chromosome, long start, long stop, int mapKey) throws Exception {
        return qtlDAO.getActiveMappedQTLs(chromosome, start, stop, mapKey);
    }

    /**
     * The QTL's trait — the Vertebrate Trait Ontology (VT) terms annotated to it, joined
     * with "; ". Falls back to the {@code qtl_trait} note when there is no VT annotation.
     * Mirrors how the RGD QTL report derives the Trait field.
     */
    public String getQtlTrait(int qtlRgdId) throws Exception {
        List<String> terms = annotationTerms(qtlRgdId, "V");   // 'V' = VT aspect
        if (!terms.isEmpty()) {
            return String.join("; ", terms);
        }
        return firstNote(qtlRgdId, "qtl_trait");
    }

    /**
     * The QTL's sub-trait / measurement — the Clinical Measurement Ontology (CMO) terms
     * annotated to it. When several CMO terms are present and the QTL names a most-significant
     * one, that single term is used; otherwise all are joined. Falls back to the
     * {@code qtl_subtrait} note when there is no CMO annotation.
     */
    public String getQtlSubtrait(int qtlRgdId, String mostSignificantCmoTermAcc) throws Exception {
        List<StringMapQuery.MapPair> pairs = annotationDAO.getAnnotationTermAccIds(qtlRgdId, "L"); // 'L' = CMO aspect
        if (pairs == null || pairs.isEmpty()) {
            return firstNote(qtlRgdId, "qtl_subtrait");
        }
        if (pairs.size() > 1 && mostSignificantCmoTermAcc != null && !mostSignificantCmoTermAcc.isBlank()) {
            for (StringMapQuery.MapPair p : pairs) {
                if (mostSignificantCmoTermAcc.equals(p.keyValue) && p.stringValue != null && !p.stringValue.isBlank()) {
                    return formatTerm(p.stringValue, p.keyValue);
                }
            }
        }
        List<String> terms = new ArrayList<>();
        for (StringMapQuery.MapPair p : pairs) {
            if (p.stringValue != null && !p.stringValue.isBlank()) {
                terms.add(formatTerm(p.stringValue, p.keyValue));
            }
        }
        terms.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join("; ", terms);
    }

    /** Text of a QTL note of the given type (e.g. {@code qtl_statistics} for LOD), or "". */
    public String getQtlNote(int qtlRgdId, String noteType) throws Exception {
        return firstNote(qtlRgdId, noteType);
    }

    /**
     * Distinct, alphabetically-sorted annotation terms for an object within an ontology aspect,
     * each formatted as {@code term name (ACC)}.
     */
    private List<String> annotationTerms(int rgdId, String aspect) throws Exception {
        List<String> terms = new ArrayList<>();
        for (StringMapQuery.MapPair p : annotationDAO.getAnnotationTermAccIds(rgdId, aspect)) {
            if (p.stringValue != null && !p.stringValue.isBlank()) {
                String term = formatTerm(p.stringValue, p.keyValue);
                if (!terms.contains(term)) {
                    terms.add(term);
                }
            }
        }
        terms.sort(String.CASE_INSENSITIVE_ORDER);
        return terms;
    }

    /** Format an ontology term as {@code term name (ACC)}, or just the name when the accession is blank. */
    private static String formatTerm(String name, String acc) {
        String trimmed = name.trim();
        return (acc == null || acc.isBlank()) ? trimmed : trimmed + " (" + acc.trim() + ")";
    }

    /** First note of a type for an object, trimmed, or "". */
    private String firstNote(int rgdId, String noteType) throws Exception {
        List<Note> notes = notesDAO.getNotes(rgdId, noteType);
        if (notes != null && !notes.isEmpty() && notes.get(0).getNotes() != null) {
            return notes.get(0).getNotes().trim();
        }
        return "";
    }

    // ---- Aliases -------------------------------------------------------------

    public List<Alias> getAliases(int rgdId) throws Exception {
        return aliasDAO.getAliases(rgdId);
    }

    // ---- Annotations ---------------------------------------------------------

    /**
     * Annotations for an object within one ontology, selected by term-accession
     * prefix (DOID=disease, MP=phenotype, PW=pathway, GO=gene ontology, ...).
     */
    public List<Annotation> getAnnotationsForOntology(int rgdId, String ontologyPrefix) throws Exception {
        return annotationDAO.getAnnotationsForOntology(rgdId, ontologyPrefix);
    }

    // ---- References -----------------------------------------------------------

    /** References (literature and curation) associated with an object. */
    public List<Reference> getReferencesForObject(int rgdId) throws Exception {
        return referenceDAO.getReferencesForObject(rgdId);
    }

    // ---- Sequences ------------------------------------------------------------

    /**
     * Nucleotide sequence cross-references for a gene — the accessions under the GenBank
     * Nucleotide xdb key, which also holds RefSeq transcript accessions (NM_/XM_/NR_/XR_).
     * This is the same set the report's Sequence &gt; Nucleotide Sequences subsection shows.
     * Duplicate accessions are collapsed; the species filter keeps only the gene's own species.
     */
    public List<XdbId> getNucleotideSequences(int rgdId, int speciesTypeKey) throws Exception {
        XdbId filter = new XdbId();
        filter.setRgdId(rgdId);
        filter.setXdbKey(XdbId.XDB_KEY_GENEBANKNU);
        LinkedHashMap<String, XdbId> unique = new LinkedHashMap<>();
        for (XdbId x : xdbIdDAO.getXdbIds(filter, speciesTypeKey)) {
            if (x.getAccId() != null && !x.getAccId().isBlank()) {
                unique.putIfAbsent(x.getAccId(), x);
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * Protein sequence cross-references for a gene — GenBank Protein accessions (which also hold
     * RefSeq protein accessions, NP_/XP_) plus Ensembl Protein accessions. This is the same set
     * the report's Sequence &gt; Protein Sequences subsection shows. Duplicate accessions are
     * collapsed; the species filter keeps only the gene's own species.
     */
    public List<XdbId> getProteinSequences(int rgdId, int speciesTypeKey) throws Exception {
        LinkedHashMap<String, XdbId> unique = new LinkedHashMap<>();
        for (int xdbKey : new int[] { XdbId.XDB_KEY_GENEBANKPROT, XdbId.XDB_KEY_ENSEMBL_PROTEIN }) {
            XdbId filter = new XdbId();
            filter.setRgdId(rgdId);
            filter.setXdbKey(xdbKey);
            for (XdbId x : xdbIdDAO.getXdbIds(filter, speciesTypeKey)) {
                if (x.getAccId() != null && !x.getAccId().isBlank()) {
                    unique.putIfAbsent(x.getAccId(), x);
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    // ---- External database links ----------------------------------------------

    /**
     * External database cross-references for a gene, excluding the literature/pathway and
     * sequence keys the report's External Database Links section omits (see
     * {@link #EXCLUDED_XDB_KEYS}). Blank accessions are dropped.
     */
    public List<XdbId> getExternalDbLinks(int rgdId, int speciesTypeKey) throws Exception {
        XdbId filter = new XdbId();
        filter.setRgdId(rgdId);
        List<XdbId> result = new ArrayList<>();
        for (XdbId x : xdbIdDAO.getXdbIds(filter, speciesTypeKey)) {
            if (!EXCLUDED_XDB_KEYS.contains(x.getXdbKey())
                    && x.getAccId() != null && !x.getAccId().isBlank()) {
                result.add(x);
            }
        }
        return result;
    }

    /** The external-database definition (name + URL template) for an xdb key, or null. */
    public Xdb getXdb(int xdbKey) throws Exception {
        return XDBIndex.getInstance().getXDB(xdbKey);
    }

    // ---- Nomenclature ---------------------------------------------------------

    /** Nomenclature-history events for an object. */
    public List<NomenclatureEvent> getNomenclatureEvents(int rgdId) throws Exception {
        return nomenclatureDAO.getNomenclatureEvents(rgdId);
    }

    /** RGD ID of the reference behind a nomenclature event's reference key, or 0 if none. */
    public int getReferenceRgdIdByKey(int refKey) throws Exception {
        return referenceDAO.getReferenceRgdIdByKey(refKey);
    }

    /** PubMed IDs attached to a reference's RGD ID (XDB_KEY = PubMed). */
    public List<String> getPubmedIdsForReference(int refRgdId) throws Exception {
        List<String> pmids = new ArrayList<>();
        for (XdbId x : xdbIdDAO.getPubmedIdsByRefRgdId(refRgdId)) {
            if (x.getAccId() != null && !x.getAccId().isBlank()) {
                pmids.add(x.getAccId());
            }
        }
        return pmids;
    }

    // ---- Variant counts (CarpeNovo / RATCNDEV datasource) ---------------------

    /**
     * Map key of the assembly the rsId variant report uses for a species: GRCr8 (380) for rat,
     * otherwise the species' reference assembly. Returns 0 when none can be resolved.
     */
    public int getVariantReportMapKey(int speciesTypeKey) throws Exception {
        if (speciesTypeKey == SpeciesType.RAT) {
            return 380;   // rat variants are reported on GRCr8, as the rsId report hardcodes
        }
        Map ref = MapManager.getInstance().getReferenceAssembly(speciesTypeKey);
        return ref == null ? 0 : ref.getKey();
    }

    /**
     * Number of active variants located within a gene's region on an assembly — the total the
     * rsId report ({@code rsId/main.html?geneId=...}) shows. Runs on the CarpeNovo datasource.
     */
    public int getVariantCountForGeneRegion(int mapKey, String chromosome, int start, int stop) throws Exception {
        Integer count = variantsDAO.getVariantsCountWithGeneLocation(mapKey, chromosome, start, stop);
        return count == null ? 0 : count;
    }

    // ---- Damaging variants (CarpeNovo / RATCNDEV datasource) ------------------

    /** Assembly map keys (as strings) that have PolyPhen-damaging variants for this gene. */
    public List<String> getDamagingVariantAssemblies(int geneRgdId) throws Exception {
        variantDAO.setDataSource(DataSourceFactory.getInstance().getCarpeNovoDataSource());
        return variantDAO.getGeneAssemblyOfDamagingVariants(geneRgdId);
    }

    /** PolyPhen-damaging variants for this gene on one assembly (map key as string). */
    public List<Variant> getDamagingVariantsForGene(int geneRgdId, String mapKey) throws Exception {
        variantDAO.setDataSource(DataSourceFactory.getInstance().getCarpeNovoDataSource());
        return variantDAO.getDamagingVariantsForGeneByAssembly(geneRgdId, mapKey);
    }

    /**
     * Distinct strains (as their own Strain objects) that carry PolyPhen-damaging variants for
     * this gene on one assembly. The strain association is resolved on the CarpeNovo datasource
     * (sample.strain_rgd_id via variant_sample_detail), then the strain symbols are looked up on
     * the main RGD datasource. Samples with no strain (e.g. aggregate EVA releases) are excluded.
     */
    public List<Strain> getDamagingVariantStrains(int geneRgdId, String mapKey) throws Exception {
        String sql =
                "SELECT DISTINCT s.strain_rgd_id " +
                "FROM sample s " +
                "JOIN variant_sample_detail vsd ON vsd.sample_id = s.sample_id " +
                "JOIN polyphen p ON p.variant_rgd_id = vsd.rgd_id AND p.prediction LIKE '%damaging' " +
                "JOIN variant_transcript vt ON vt.variant_rgd_id = p.variant_rgd_id " +
                "JOIN transcripts t ON t.transcript_rgd_id = vt.transcript_rgd_id " +
                "WHERE t.gene_rgd_id = ? AND s.map_key = ? AND s.strain_rgd_id IS NOT NULL";

        List<Integer> strainRgdIds = new ArrayList<>();
        try (Connection con = DataSourceFactory.getInstance().getCarpeNovoDataSource().getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, geneRgdId);
            ps.setInt(2, Integer.parseInt(mapKey.trim()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    strainRgdIds.add(rs.getInt(1));
                }
            }
        }
        if (strainRgdIds.isEmpty()) {
            return new ArrayList<>();
        }
        return strainDAO.getStrains(strainRgdIds);
    }
}
