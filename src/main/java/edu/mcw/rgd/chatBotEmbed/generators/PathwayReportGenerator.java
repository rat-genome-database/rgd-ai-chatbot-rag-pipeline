package edu.mcw.rgd.chatBotEmbed.generators;

import edu.mcw.rgd.chatBotEmbed.DAO;
import edu.mcw.rgd.chatBotEmbed.MarkdownWriter;
import edu.mcw.rgd.chatBotEmbed.Md;
import edu.mcw.rgd.chatBotEmbed.ReportDoc;
import edu.mcw.rgd.chatBotEmbed.TermReportGenerator;
import edu.mcw.rgd.datamodel.Pathway;
import edu.mcw.rgd.datamodel.PathwayObject;
import edu.mcw.rgd.datamodel.Reference;
import edu.mcw.rgd.process.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Builds a document for each curated pathway diagram page ({@code pathway/pathwayRecord.html}):
 * the curator-written pathway description, its categories, the objects drawn in the diagram and
 * the references behind it.
 *
 * <p>These descriptions are some of the longest curated prose RGD has and exist nowhere else in
 * the index: the ontology term report for the same PW term carries only the term definition.</p>
 *
 * <p>Keyed by pathway-ontology accession (e.g. {@code PW:0000559}), so it runs on the term path
 * of {@code Manager} and writes under {@code pathway/pw/}. Display name:
 * {@code RGD Pathway Report - <name> (<PW accession>)}; "Report" is what marks a file as an RGD
 * report to the chunker, and the accession (not a number) keeps it out of {@code report_object}.</p>
 */
public class PathwayReportGenerator implements TermReportGenerator {

    static final String PATHWAY_PAGE_URL = "https://rgd.mcw.edu/rgdweb/pathway/pathwayRecord.html?acc_id=";
    private static final String REFERENCE_REPORT_URL = "https://rgd.mcw.edu/rgdweb/report/reference/main.html?id=";
    private static final String ONTOLOGY_TERM_URL = "https://rgd.mcw.edu/rgdweb/ontology/annot.html?acc_id=";

    private final DAO dao;

    public PathwayReportGenerator(DAO dao) {
        this.dao = dao;
    }

    @Override
    public String getReportType() {
        return "pathway";
    }

    /** Every pathway with a diagram page; the ontology filter does not apply (all are PW). */
    @Override
    public List<String> getAccessions(String ontologyIdFilter) throws Exception {
        return dao.getPathwayAccIds();
    }

    /** Species and object filters do not apply: a pathway diagram page is not species-specific. */
    @Override
    public ReportDoc build(String accId, int speciesTypeKey, int objectKey) throws Exception {
        Pathway p = dao.getPathwayInfo(accId);
        if (p == null || Utils.isStringEmpty(p.getDescription())) {
            return null;   // no curated page content to embed
        }
        String name = Utils.defaultString(p.getName()).trim();

        StringBuilder md = new StringBuilder(8192);
        md.append("# Pathway Diagram: ").append(accId).append(" ").append(name).append("\n\n");
        md.append("> **Source:** ")
          .append(Md.link("Rat Genome Database (RGD) pathway diagram", PATHWAY_PAGE_URL + accId))
          .append("\n\n---\n\n");

        md.append(Md.heading(2, "Summary"));
        md.append("- **Pathway:** ").append(name).append("\n");
        md.append("- **Pathway Ontology ID:** ").append(Md.link(accId, ONTOLOGY_TERM_URL + accId)).append("\n");
        if (p.getPathwayCategories() != null && !p.getPathwayCategories().isEmpty()) {
            md.append("- **Categories:** ").append(String.join(", ", p.getPathwayCategories())).append("\n");
        }
        if ("Y".equalsIgnoreCase(p.getHasAlteredPath())) {
            md.append("- **Has Altered Pathway:** yes\n");
        }
        md.append("\n");

        md.append(Md.heading(2, "Description"));
        md.append(Md.plainText(p.getDescription())).append("\n\n");

        appendObjects(md, p.getObjectList());
        appendReferences(md, p.getReferenceList());

        md.append("\n---\n\n*This pathway description was extracted from the ")
          .append(Md.link("Rat Genome Database (RGD)", "https://rgd.mcw.edu"))
          .append(", Medical College of Wisconsin.*\n");

        String displayName = "RGD Pathway Report - " + name + " (" + accId + ")";
        return new ReportDoc(MarkdownWriter.safeSymbol(accId), displayName, MarkdownWriter.safeSymbol(name), md.toString());
    }

    /** Objects drawn in the diagram (genes, small molecules, other pathways), grouped by type. */
    private void appendObjects(StringBuilder md, List<PathwayObject> objects) {
        if (objects == null || objects.isEmpty()) {
            return;
        }
        LinkedHashMap<String, PathwayObject> unique = new LinkedHashMap<>();
        for (PathwayObject o : objects) {
            unique.putIfAbsent(Utils.defaultString(o.getTypeName()) + "|" + Utils.defaultString(o.getObjName())
                    + "|" + Utils.defaultString(o.getAccId()), o);
        }
        List<List<String>> rows = new ArrayList<>();
        for (PathwayObject o : unique.values()) {
            String nameCell = Utils.isStringEmpty(o.getUrl())
                    ? Md.cell(o.getObjName())
                    : Md.link(Utils.defaultString(o.getObjName()), o.getUrl());
            rows.add(List.of(
                    nameCell,
                    Md.cell(Utils.defaultString(o.getTypeName())),
                    Md.cell(Utils.defaultString(o.getAccId())),
                    Md.cell(Md.plainText(o.getObjDesc()))
            ));
        }
        md.append(Md.heading(2, "Pathway Objects"));
        md.append("*Additional elements shown in the pathway diagram.*\n\n");
        md.append(Md.table(List.of("Name", "Type", "Accession", "Description"), rows));
    }

    private void appendReferences(StringBuilder md, List<Reference> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (Reference r : references) {
            String title = firstNonEmpty(r.getTitle(), r.getCitation());
            rows.add(List.of(
                    Md.link(title.isEmpty() ? "RGD:" + r.getRgdId() : title, REFERENCE_REPORT_URL + r.getRgdId()),
                    Md.cell(Utils.defaultString(r.getCitation()))
            ));
        }
        md.append(Md.heading(2, "References"));
        md.append(Md.table(List.of("Title", "Citation"), rows));
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (!Utils.isStringEmpty(v)) {
                return v.trim();
            }
        }
        return "";
    }
}
