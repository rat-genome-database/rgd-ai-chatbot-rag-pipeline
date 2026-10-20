package edu.mcw.rgd.chatBotEmbed.generators;

import edu.mcw.rgd.chatBotEmbed.DAO;
import edu.mcw.rgd.chatBotEmbed.MarkdownWriter;
import edu.mcw.rgd.chatBotEmbed.Md;
import edu.mcw.rgd.chatBotEmbed.ReportDoc;
import edu.mcw.rgd.datamodel.Author;
import edu.mcw.rgd.datamodel.Map;
import edu.mcw.rgd.datamodel.Reference;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds a reference report for each paper RGD curators have annotated from: the citation,
 * authors, abstract, and the genes, QTLs and strains annotated from it with their terms.
 *
 * <p>This answers the questions object reports cannot — "what did this paper show", "which
 * papers link gene X to disease Y" — because the object reports list a reference only by title.
 * Only references cited by at least one RGD-curated annotation are included; the remaining
 * references are bulk imports whose abstracts add volume rather than RGD knowledge.</p>
 *
 * <p>References are not species-specific, so they are generated once, under {@code reference/all/}.
 * Display name: {@code RGD Reference Report - <title> (<rgdId>)}.</p>
 */
public class ReferenceReportGenerator extends AbstractReportGenerator {

    /** Annotated objects listed per reference; a handful of large-scale papers annotate thousands. */
    private static final int MAX_OBJECTS = 200;

    /** Longest title kept in the display name, which is also the citation the chatbot shows. */
    private static final int MAX_TITLE_IN_NAME = 150;

    public ReferenceReportGenerator(DAO dao) {
        super(dao);
    }

    @Override
    public String getReportType() {
        return "reference";
    }

    @Override
    public boolean isSpeciesScoped() {
        return false;
    }

    /** Every curated reference, whatever species is asked for: references are not species-scoped. */
    @Override
    public List<Integer> getRgdIds(int speciesTypeKey) throws Exception {
        return dao.getCuratedReferenceRgdIds();
    }

    @Override
    public ReportDoc build(int rgdId, List<Map> assemblies) throws Exception {
        Reference ref = dao.getReference(rgdId);
        if (ref == null) {
            return null;
        }
        RgdId id = dao.getRgdId(rgdId);
        if (id == null || !"ACTIVE".equals(id.getObjectStatus())) {
            return null;
        }

        String title = Md.plainText(firstNonEmpty(ref.getTitle(), ref.getCitation(), "Reference " + rgdId));

        StringBuilder md = new StringBuilder(4096);
        md.append("# Reference: RGD:").append(rgdId).append(" ").append(title).append("\n\n");
        md.append("> **Source:** ")
          .append(Md.link("Rat Genome Database (RGD)", REFERENCE_REPORT_URL + rgdId))
          .append("\n\n---\n\n");

        appendSummary(md, ref, rgdId, title);

        String abstractText = Md.plainText(ref.getRefAbstract());
        if (!abstractText.isEmpty()) {
            md.append(Md.heading(2, "Abstract"));
            md.append(abstractText).append("\n\n");
        }

        appendAnnotatedObjects(md, rgdId);

        md.append("\n---\n\n*This report was extracted from the ")
          .append(Md.link("Rat Genome Database (RGD)", "https://rgd.mcw.edu"))
          .append(", Medical College of Wisconsin.*\n");

        String shortTitle = title.length() > MAX_TITLE_IN_NAME
                ? title.substring(0, MAX_TITLE_IN_NAME).trim() + "..."
                : title;
        // Parentheses in the title become brackets: the embed step reads "(<x>) (<id>)" at the end
        // of a display name as species and RGD ID, so a title ending in "(Review)" would otherwise
        // be stored as a reference of species "Review".
        String displayName = "RGD Reference Report - " + shortTitle.replace('(', '[').replace(')', ']')
                + " (" + rgdId + ")";
        return new ReportDoc(rgdId, displayName, "RGD", md.toString());
    }

    private void appendSummary(StringBuilder md, Reference ref, int rgdId, String title) throws Exception {
        md.append(Md.heading(2, "Summary"));
        md.append("- **Title:** ").append(title).append("\n");
        md.append("- **RGD ID:** ").append(rgdId).append("\n");

        List<String> names = new ArrayList<>();
        for (Author a : dao.getAuthors(ref.getKey())) {
            String n = (Utils.defaultString(a.getLastName()).trim() + " " + Utils.defaultString(a.getInitials()).trim()).trim();
            if (!n.isEmpty()) {
                names.add(n);
            }
        }
        if (!names.isEmpty()) {
            md.append("- **Authors:** ").append(String.join(", ", names)).append("\n");
        }
        line(md, "Citation", Md.plainText(ref.getCitation()));
        line(md, "Journal", ref.getPublication());
        line(md, "Year", year(ref.getPubDate()));
        line(md, "Reference Type", ref.getReferenceType());

        List<String> pmids = dao.getPubmedIdsForReference(rgdId);
        if (!pmids.isEmpty()) {
            List<String> links = new ArrayList<>();
            for (String pmid : pmids) {
                links.add(Md.link("PMID:" + pmid, "https://pubmed.ncbi.nlm.nih.gov/" + pmid + "/"));
            }
            md.append("- **PubMed:** ").append(String.join(" ", links)).append("\n");
        }
        if (!Utils.isStringEmpty(ref.getDoi())) {
            md.append("- **DOI:** ").append(Md.link(ref.getDoi().trim(), "https://doi.org/" + ref.getDoi().trim())).append("\n");
        }
        md.append("\n");
    }

    /**
     * Objects annotated from this reference by RGD curators, one row per object with the terms
     * the paper supports for it. Imported annotations that merely cite the paper are left out.
     */
    private void appendAnnotatedObjects(StringBuilder md, int refRgdId) throws Exception {
        LinkedHashMap<Integer, AnnotatedObject> byObject = new LinkedHashMap<>();
        for (Annotation a : dao.getAnnotationsByReference(refRgdId)) {
            if (!"RGD".equalsIgnoreCase(a.getDataSrc()) || a.getAnnotatedObjectRgdId() == null) {
                continue;
            }
            AnnotatedObject o = byObject.computeIfAbsent(a.getAnnotatedObjectRgdId(), k -> new AnnotatedObject(a));
            String term = Utils.defaultString(a.getTerm()).trim();
            if (!term.isEmpty()) {
                String qualifier = Utils.defaultString(a.getQualifier()).trim();
                o.terms.add(qualifier.isEmpty() ? term : term + " (" + qualifier + ")");
            }
        }
        if (byObject.isEmpty()) {
            return;
        }

        List<List<String>> rows = new ArrayList<>();
        for (AnnotatedObject o : byObject.values()) {
            if (rows.size() == MAX_OBJECTS) {
                break;
            }
            String url = objectUrl(o.objectKey, o.rgdId);
            String symbol = firstNonEmpty(o.symbol, "RGD:" + o.rgdId);
            rows.add(List.of(
                    url == null ? Md.cell(symbol) : Md.link(symbol, url),
                    Md.cell(objectType(o.objectKey)),
                    Md.cell(o.speciesTypeKey > 0 ? SpeciesType.getCommonName(o.speciesTypeKey) : ""),
                    Md.cell(String.join("; ", o.terms))
            ));
        }
        md.append(Md.heading(2, "Curated Annotations"));
        md.append("*Genes, QTLs and strains RGD curators annotated from this reference, with the terms it supports.");
        if (byObject.size() > MAX_OBJECTS) {
            md.append(" The first ").append(MAX_OBJECTS).append(" of ").append(byObject.size()).append(" objects are listed.");
        }
        md.append("*\n\n");
        md.append(Md.table(List.of("Object", "Type", "Species", "Terms"), rows));
    }

    private static String objectType(int objectKey) {
        switch (objectKey) {
            case RgdId.OBJECT_KEY_GENES:      return "Gene";
            case RgdId.OBJECT_KEY_QTLS:       return "QTL";
            case RgdId.OBJECT_KEY_STRAINS:    return "Strain";
            case RgdId.OBJECT_KEY_SSLPS:      return "Marker";
            case RgdId.OBJECT_KEY_VARIANTS:   return "Variant";
            case RgdId.OBJECT_KEY_CELL_LINES: return "Cell Line";
            default:                          return "";
        }
    }

    private static String objectUrl(int objectKey, int rgdId) {
        String type;
        switch (objectKey) {
            case RgdId.OBJECT_KEY_GENES:      type = "gene"; break;
            case RgdId.OBJECT_KEY_QTLS:       type = "qtl"; break;
            case RgdId.OBJECT_KEY_STRAINS:    type = "strain"; break;
            case RgdId.OBJECT_KEY_SSLPS:      type = "marker"; break;
            case RgdId.OBJECT_KEY_CELL_LINES: type = "cellline"; break;
            default: return null;
        }
        return "https://rgd.mcw.edu/rgdweb/report/" + type + "/main.html?id=" + rgdId;
    }

    private static void line(StringBuilder md, String label, String value) {
        if (!Utils.isStringEmpty(value)) {
            md.append("- **").append(label).append(":** ").append(value.trim()).append("\n");
        }
    }

    /** One object annotated from the reference, with its distinct terms in encounter order. */
    private static final class AnnotatedObject {
        final int rgdId;
        final int objectKey;
        final int speciesTypeKey;
        final String symbol;
        final LinkedHashSet<String> terms = new LinkedHashSet<>();
        AnnotatedObject(Annotation a) {
            this.rgdId = a.getAnnotatedObjectRgdId();
            this.objectKey = a.getRgdObjectKey() == null ? 0 : a.getRgdObjectKey();
            this.speciesTypeKey = a.getSpeciesTypeKey();
            this.symbol = Utils.defaultString(a.getObjectSymbol()).trim();
        }
    }
}
