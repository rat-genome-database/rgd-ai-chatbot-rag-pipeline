package edu.mcw.rgd.chatBotEmbed.generators;

import edu.mcw.rgd.chatBotEmbed.DAO;
import edu.mcw.rgd.chatBotEmbed.MarkdownWriter;
import edu.mcw.rgd.chatBotEmbed.Md;
import edu.mcw.rgd.chatBotEmbed.ReportDoc;
import edu.mcw.rgd.chatBotEmbed.TermReportGenerator;
import edu.mcw.rgd.datamodel.phenominerExpectedRange.PhenominerExpectedRange;
import edu.mcw.rgd.process.Utils;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds one document per clinical measurement with PhenoMiner expected ranges: for each
 * strain group, sex and age span, the expected mean, its standard deviation and the range.
 * These answer "what is the normal systolic blood pressure of a WKY rat" — a question the
 * chatbot cannot answer from PhenoMiner record counts on strain reports.
 *
 * <p>Keyed by clinical measurement ontology accession (e.g. {@code CMO:0000004}), so it runs on
 * the term path of {@code Manager} and writes under {@code expectedrange/cmo/}. Display name:
 * {@code RGD Expected Range Report - <measurement> (<CMO accession>)}.</p>
 */
public class ExpectedRangeReportGenerator implements TermReportGenerator {

    static final String EXPECTED_RANGES_URL = "https://rgd.mcw.edu/rgdweb/phenominer/phenominerExpectedRanges/views/home.html";
    private static final String ONTOLOGY_TERM_URL = "https://rgd.mcw.edu/rgdweb/ontology/annot.html?acc_id=";

    private final DAO dao;

    public ExpectedRangeReportGenerator(DAO dao) {
        this.dao = dao;
    }

    @Override
    public String getReportType() {
        return "expectedrange";
    }

    /** Every clinical measurement with expected ranges; the ontology filter does not apply (all are CMO). */
    @Override
    public List<String> getAccessions(String ontologyIdFilter) throws Exception {
        return dao.getExpectedRangeMeasurements();
    }

    /** Species and object filters do not apply: expected ranges are rat-only and strain-based. */
    @Override
    public ReportDoc build(String accId, int speciesTypeKey, int objectKey) throws Exception {
        List<PhenominerExpectedRange> ranges = dao.getExpectedRanges(accId);
        if (ranges == null || ranges.isEmpty()) {
            return null;
        }
        PhenominerExpectedRange first = ranges.get(0);
        String measurement = firstNonEmpty(first.getClinicalMeasurement(), accId);

        LinkedHashSet<String> traits = new LinkedHashSet<>();
        LinkedHashSet<String> units = new LinkedHashSet<>();
        for (PhenominerExpectedRange r : ranges) {
            if (!Utils.isStringEmpty(r.getTrait())) traits.add(r.getTrait().trim());
            if (!Utils.isStringEmpty(r.getUnits())) units.add(r.getUnits().trim());
        }

        StringBuilder md = new StringBuilder(8192);
        md.append("# Expected Ranges: ").append(accId).append(" ").append(measurement).append("\n\n");
        md.append("> **Source:** ")
          .append(Md.link("Rat Genome Database (RGD) PhenoMiner Expected Ranges", EXPECTED_RANGES_URL))
          .append("\n\n---\n\n");

        md.append(Md.heading(2, "Summary"));
        md.append("- **Clinical Measurement:** ").append(Md.link(measurement, ONTOLOGY_TERM_URL + accId))
          .append(" (").append(accId).append(")\n");
        if (!traits.isEmpty()) {
            md.append("- **Trait:** ").append(String.join(", ", traits)).append("\n");
        }
        if (!units.isEmpty()) {
            md.append("- **Units:** ").append(String.join(", ", units)).append("\n");
        }
        md.append("- **Species:** Rat\n");
        md.append("- **Expected ranges:** ").append(ranges.size()).append("\n\n");
        md.append("*Expected ranges are calculated by RGD from PhenoMiner experimental data by meta-analysis: "
                + "for each strain group, sex and age span, the expected mean value with its standard "
                + "deviation and the range in which values are expected to fall.*\n\n");

        List<List<String>> rows = new ArrayList<>();
        for (PhenominerExpectedRange r : ranges) {
            String group = firstNonEmpty(r.getStrainGroupName(), dao.getStrainGroupName(r.getStrainGroupId()));
            rows.add(List.of(
                    Md.cell(group),
                    Md.cell(Utils.defaultString(r.getSex())),
                    Md.cell(r.getAgeLowBound() + "-" + r.getAgeHighBound()),
                    Md.cell(number(r.getRangeValue())),
                    Md.cell(number(r.getRangeSD())),
                    Md.cell(number(r.getRangeLow()) + " - " + number(r.getRangeHigh())),
                    Md.cell(Utils.defaultString(r.getUnits())),
                    Md.cell(Utils.defaultString(r.getExpectedRangeName()))
            ));
        }
        md.append(Md.heading(2, "Expected Ranges by Strain"));
        md.append(Md.table(List.of("Strain Group", "Sex", "Age (days)", "Mean", "SD", "Range",
                "Units", "Expected Range Name"), rows));

        md.append("\n---\n\n*These expected ranges were extracted from the ")
          .append(Md.link("Rat Genome Database (RGD)", "https://rgd.mcw.edu"))
          .append(", Medical College of Wisconsin.*\n");

        String displayName = "RGD Expected Range Report - " + measurement + " (" + accId + ")";
        return new ReportDoc(MarkdownWriter.safeSymbol(accId), displayName, MarkdownWriter.safeSymbol(measurement), md.toString());
    }

    /** Up to four decimal places, without trailing zeros. */
    private static String number(double v) {
        DecimalFormat f = new DecimalFormat("0.####");
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(v);
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
