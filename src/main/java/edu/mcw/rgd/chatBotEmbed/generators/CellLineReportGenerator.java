package edu.mcw.rgd.chatBotEmbed.generators;

import edu.mcw.rgd.chatBotEmbed.DAO;
import edu.mcw.rgd.chatBotEmbed.MarkdownWriter;
import edu.mcw.rgd.chatBotEmbed.Md;
import edu.mcw.rgd.chatBotEmbed.ReportDoc;
import edu.mcw.rgd.datamodel.CellLine;
import edu.mcw.rgd.datamodel.Map;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.process.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Builds a cell line report as markdown, mirroring the RGD cell line report page
 * ({@code report/cellline/main.html}): origin, characteristics, phenotype, research use and
 * availability, then disease annotations, curator notes, references and external links.
 *
 * <p>Rat cell lines are mostly RGD-curated embryonic stem cell lines; the other species' come
 * from Cellosaurus. Display name: {@code RGD Cellline Report - <symbol> (<species>) (<rgdId>)},
 * which the chatbot links to {@code report/cellline/main.html}.</p>
 */
public class CellLineReportGenerator extends AbstractReportGenerator {

    private static final String CELL_LINE_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/cellline/main.html?id=";

    /** Cell lines carry only disease annotations, which appendAnnotations always handles. */
    private static final LinkedHashMap<String, String> ONTOLOGY_SECTIONS = new LinkedHashMap<>();

    public CellLineReportGenerator(DAO dao) {
        super(dao);
    }

    @Override
    public String getReportType() {
        return "cellline";
    }

    @Override
    public List<Integer> getRgdIds(int speciesTypeKey) throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (CellLine c : dao.getActiveCellLines()) {
            if (c.getSpeciesTypeKey() == speciesTypeKey) {
                ids.add(c.getRgdId());
            }
        }
        return ids;
    }

    @Override
    public ReportDoc build(int rgdId, List<Map> assemblies) throws Exception {
        CellLine c = dao.getCellLine(rgdId);
        if (c == null) {
            return null;
        }
        RgdId id = dao.getRgdId(rgdId);
        if (id == null || !"ACTIVE".equals(id.getObjectStatus())) {
            return null;
        }

        String symbol = Utils.defaultString(c.getSymbol()).trim();
        String name = Utils.defaultString(c.getName()).trim();
        String species = SpeciesType.getCommonName(c.getSpeciesTypeKey());

        StringBuilder md = new StringBuilder(2048);
        md.append("# Cell Line: RGD:").append(rgdId).append(" ").append(symbol);
        if (!name.isEmpty() && !name.equals(symbol)) {
            md.append(" (").append(name).append(")");
        }
        md.append("\n\n");
        md.append("> **Source:** ")
          .append(Md.link("Rat Genome Database (RGD)", CELL_LINE_REPORT_URL + rgdId))
          .append("\n\n---\n\n");

        md.append(Md.heading(2, "Summary"));
        md.append("- **Symbol:** ").append(symbol).append("\n");
        line(md, "Name", name);
        md.append("- **RGD ID:** ").append(rgdId).append("\n");
        md.append("- **Species:** ").append(species).append("\n");
        line(md, "Type", c.getObjectType());
        line(md, "Sex", c.getGender());
        line(md, "Germline Competent", c.getGermlineCompetent());
        line(md, "Availability", c.getAvailability());
        line(md, "Research Use", c.getResearchUse());
        line(md, "Groups", c.getGroups());
        line(md, "Citation ID", c.getCitationId());
        line(md, "Caution", c.getCaution());
        line(md, "Data Source", c.getSrcPipeline());
        md.append("\n");

        paragraph(md, "Origin", c.getOrigin());
        paragraph(md, "Characteristics", c.getCharacteristics());
        paragraph(md, "Phenotype", c.getPhenotype());
        paragraph(md, "Description", c.getDescription());

        appendAnnotations(md, rgdId, ONTOLOGY_SECTIONS);
        appendCuratorNotes(md, rgdId, Set.of());
        appendReferences(md, rgdId);
        appendExternalDbLinks(md, rgdId, c.getSpeciesTypeKey());
        appendNomenclatureHistory(md, rgdId);

        md.append("\n---\n\n*This report was extracted from the ")
          .append(Md.link("Rat Genome Database (RGD)", "https://rgd.mcw.edu"))
          .append(", Medical College of Wisconsin.*\n");

        String displayName = "RGD Cellline Report - " + symbol + " (" + species + ") (" + rgdId + ")";
        return new ReportDoc(rgdId, displayName, MarkdownWriter.safeSymbol(symbol), md.toString());
    }

    private static void line(StringBuilder md, String label, String value) {
        if (!Utils.isStringEmpty(value)) {
            md.append("- **").append(label).append(":** ").append(Md.plainText(value)).append("\n");
        }
    }

    /** A labelled paragraph of curated prose, or nothing when the field is empty. */
    private static void paragraph(StringBuilder md, String label, String value) {
        String text = Md.plainText(value);
        if (!text.isEmpty()) {
            md.append("**").append(label).append(":** ").append(text).append("\n\n");
        }
    }
}
