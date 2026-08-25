package edu.mcw.rgd.chatBotEmbed.chunker;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Section-aware markdown chunker for RGD report files.
 *
 * <p>Ported verbatim from the rgd-ai-chatbot's {@code edu.mcw.rgdai.service.ReportMarkdownChunker}
 * so that markdown embedded by this pipeline is chunked identically to markdown embedded
 * by the chatbot's own Server Files flow. Keep the two in sync when either changes.</p>
 *
 * Instead of blindly splitting at token boundaries (which breaks tables mid-row
 * and loses section context), this chunker:
 *
 * 1. Parses headings to build a breadcrumb (e.g., "## Annotation > ### Disease Annotations")
 * 2. Accumulates content between headings into logical sections
 * 3. Detects table headers (rows above |---|) and prepends them to split table chunks
 * 4. Splits at line boundaries — never mid-line
 * 5. Prepends the heading breadcrumb to every chunk for retrieval context
 * 6. Handles oversized lines (>MAX_TOKENS) by splitting at link boundaries
 */
public class ReportMarkdownChunker {

    private static final Logger LOG = LogManager.getLogger(ReportMarkdownChunker.class);
    private static final int MAX_TOKENS = 800;
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    private final Encoding encoding;

    public ReportMarkdownChunker() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        encoding = registry.getEncoding(EncodingType.CL100K_BASE);
        LOG.info("ReportMarkdownChunker initialized (cl100k_base, max {} tokens)", MAX_TOKENS);
    }

    /**
     * Check if content is an RGD report (vs normal markdown).
     * Reports have an HTML comment on the first line: <!-- file_name: RGD ... Report ... -->
     */
    public static boolean isRgdReport(String content) {
        if (content == null) return false;
        String firstLine = content.split("\n", 2)[0].trim();
        return firstLine.startsWith("<!-- file_name:") && firstLine.contains("Report");
    }

    /**
     * Chunk an RGD report markdown into section-aware chunks.
     * Each chunk includes a heading breadcrumb for context, and table headers
     * are preserved when a table is split across multiple chunks.
     */
    public List<String> chunk(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> chunks = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);

        // Detect strain report from <!-- file_name: --> comment
        boolean isStrainReport = false;
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("<!-- file_name:") && t.endsWith("-->")) {
                isStrainReport = t.toLowerCase().contains("strain");
                break;
            }
        }

        // Strain reports: restructure Related Phenotype Data rows into sub-sections
        if (isStrainReport) {
            lines = restructurePhenotypeData(lines);
        }

        // Heading breadcrumb: level -> heading text (sorted by level)
        TreeMap<Integer, String> headingStack = new TreeMap<>();

        // Current section state
        List<String> sectionLines = new ArrayList<>();
        String tableHeader = null;
        List<String> pendingHeaderLines = new ArrayList<>();
        boolean seenSeparator = false;

        // Column stripping for strain reports (Reference Nucleotide column)
        int stripColIndex = -1;

        for (String line : lines) {
            // Skip HTML comments (the <!-- file_name: ... --> line)
            String trimmed = line.trim();
            if (trimmed.startsWith("<!--") && trimmed.endsWith("-->")) {
                continue;
            }

            // Skip sequence rows (gene Reference Sequences table)
            // Nucleotide (| Sequence:) and protein (| - Sequence:)
            if (trimmed.startsWith("| Sequence:") || trimmed.startsWith("| - Sequence:")) {
                LOG.debug("Skipping sequence row ({} chars)", trimmed.length());
                continue;
            }

            Matcher m = HEADING.matcher(trimmed);

            if (m.matches()) {
                // New heading — flush the current section first
                flushSection(chunks, sectionLines, headingStack, tableHeader);

                // Reset section state
                sectionLines.clear();
                tableHeader = null;
                pendingHeaderLines.clear();
                seenSeparator = false;
                stripColIndex = -1;

                // Update heading stack: clear this level and all deeper levels
                int level = m.group(1).length();
                String text = m.group(2).trim();
                headingStack.tailMap(level).clear();
                headingStack.put(level, text);
            } else {
                // Strain reports: detect and strip Reference Nucleotide column
                if (isStrainReport && isTableRow(trimmed)) {
                    // Detect column index from header row (before separator)
                    if (!seenSeparator && stripColIndex == -1 && trimmed.contains("Reference Nucleotide")) {
                        String[] cols = trimmed.split("\\|", -1);
                        for (int ci = 0; ci < cols.length; ci++) {
                            if (cols[ci].contains("Reference Nucleotide")) {
                                stripColIndex = ci;
                                LOG.info("Stripping Reference Nucleotide column (index {}) from strain report", ci);
                                break;
                            }
                        }
                    }
                    // Strip the column from this row
                    if (stripColIndex >= 0) {
                        line = removeColumn(line, stripColIndex);
                        trimmed = line.trim();
                    }
                }

                // Content line — add to current section
                sectionLines.add(line);

                // Table header detection. Two shapes are supported:
                //   1. GitHub tables with a |---| separator — header = the row(s) above it.
                //   2. Separator-less tables (what this pipeline emits) — the first row of the
                //      table run is the header.
                // Either way tableHeader is re-prepended to every fragment when a large table
                // is split across chunks (see splitSection), so split tables keep their columns.
                if (isTableRow(trimmed)) {
                    if (!seenSeparator && isTableSeparator(trimmed)) {
                        // Found the separator — everything before this is the header
                        seenSeparator = true;
                        StringBuilder hdr = new StringBuilder();
                        for (String hl : pendingHeaderLines) {
                            hdr.append(hl).append("\n");
                        }
                        hdr.append(line);
                        tableHeader = hdr.toString();
                        pendingHeaderLines.clear();
                    } else if (!seenSeparator) {
                        // First row of a separator-less table — treat it as the header.
                        // If a separator does follow, the branch above overrides this.
                        if (tableHeader == null && pendingHeaderLines.isEmpty()) {
                            tableHeader = line;
                        }
                        pendingHeaderLines.add(line);
                    }
                } else {
                    // Non-table line — reset pending header tracking
                    if (!seenSeparator) {
                        pendingHeaderLines.clear();
                    }
                }
            }
        }

        // Flush final section
        flushSection(chunks, sectionLines, headingStack, tableHeader);

        // DEBUG, not INFO: this fires once per file, so at INFO it floods the embed run with
        // one line per report. Kept at DEBUG for troubleshooting a single report's chunking.
        LOG.debug("Report chunked into {} chunks (max {} tokens/chunk)", chunks.size(), MAX_TOKENS);
        return chunks;
    }

    /**
     * Flush accumulated section lines as one or more chunks.
     * Empty sections (heading followed by heading with no content) produce no chunks.
     * Intro sections (under # title only, just source attribution) are skipped —
     * the # title is preserved in every other chunk's breadcrumb.
     */
    private void flushSection(List<String> chunks, List<String> sectionLines,
                              TreeMap<Integer, String> headingStack, String tableHeader) {
        if (sectionLines.isEmpty()) return;

        // Skip sections with no actual content (only whitespace/blank lines)
        boolean hasContent = sectionLines.stream().anyMatch(l -> !l.trim().isEmpty());
        if (!hasContent) return;

        String breadcrumb = buildBreadcrumb(headingStack);
        String content = String.join("\n", sectionLines).trim();
        if (content.isEmpty()) return;

        // Skip intro boilerplate: section under # title only (no ## or deeper),
        // containing just "> **Source:**..." and "---". The # title is already
        // in every other chunk's breadcrumb, so no info is lost.
        if (headingStack.size() == 1 && headingStack.firstKey() == 1) {
            String meaningful = content.replace("---", "").trim();
            if (meaningful.length() < 200) {
                LOG.debug("Skipping intro section ({} chars meaningful)", meaningful.length());
                return;
            }
        }

        // Try fitting everything in a single chunk
        String fullChunk = breadcrumb.isEmpty() ? content : breadcrumb + "\n" + content;

        if (countTokens(fullChunk) <= MAX_TOKENS) {
            chunks.add(fullChunk);
        } else {
            // Too large — split at line boundaries
            splitSection(chunks, sectionLines, breadcrumb, tableHeader);
        }
    }

    /**
     * Split a large section into multiple chunks at line boundaries.
     * Each chunk gets the breadcrumb prepended. If the section contains a table
     * (detected by |---| separator), the table header is also prepended.
     * Oversized lines (>available tokens) are further split at link boundaries.
     */
    private void splitSection(List<String> chunks, List<String> sectionLines,
                              String breadcrumb, String tableHeader) {
        // Build prefix: breadcrumb + table header (if present)
        String prefix;
        if (tableHeader != null && !tableHeader.isEmpty()) {
            prefix = breadcrumb.isEmpty() ? tableHeader : breadcrumb + "\n" + tableHeader;
        } else {
            prefix = breadcrumb;
        }

        int prefixTokens = prefix.isEmpty() ? 0 : countTokens(prefix + "\n");

        // Safety: if prefix alone is too large (>400 tokens), drop table header
        if (prefixTokens > 400 && tableHeader != null) {
            prefix = breadcrumb;
            prefixTokens = prefix.isEmpty() ? 0 : countTokens(prefix + "\n");
            LOG.warn("Table header too large, using breadcrumb only: {}", breadcrumb);
        }

        int available = MAX_TOKENS - prefixTokens;

        StringBuilder buf = new StringBuilder();
        int bufTokens = 0;

        for (String line : sectionLines) {
            int lineTokens = countTokens(line + "\n");

            // If adding this line exceeds the limit and we have buffered content, flush
            if (bufTokens + lineTokens > available && buf.length() > 0) {
                addChunk(chunks, prefix, buf.toString().trim());
                buf = new StringBuilder();
                bufTokens = 0;
            }

            // If this single line exceeds available tokens, split it at link boundaries
            if (lineTokens > available) {
                // Flush any buffered content first
                if (buf.length() > 0) {
                    addChunk(chunks, prefix, buf.toString().trim());
                    buf = new StringBuilder();
                    bufTokens = 0;
                }

                // Split the oversized line and add each part as its own chunk
                List<String> parts = splitLongLine(line, available);
                for (String part : parts) {
                    addChunk(chunks, prefix, part.trim());
                }
                LOG.info("Split oversized line ({} tokens) into {} parts", lineTokens, parts.size());
                continue; // don't add original line to buffer
            }

            buf.append(line).append("\n");
            bufTokens += lineTokens;
        }

        // Flush remaining content
        if (buf.length() > 0) {
            addChunk(chunks, prefix, buf.toString().trim());
        }
    }

    /**
     * Split an oversized line at logical boundaries so each part fits within maxTokens.
     * Tries ")[" first (consecutive markdown links like ](url)[next),
     * then "), [" (comma-separated links), then " | " (table cells).
     */
    private List<String> splitLongLine(String line, int maxTokens) {
        // Find break points: after ")[" — consecutive markdown links with no separator
        // e.g., [strain1](url1)[strain2](url2) → break between ")" and "["
        List<Integer> breaks = findBreakPoints(line, ")[", 1);

        // Fallback: try "), [" (comma-separated markdown links)
        if (breaks.isEmpty()) {
            breaks = findBreakPoints(line, "), [", 2);
        }

        // Fallback: try " | " (table cell boundaries)
        if (breaks.isEmpty()) {
            breaks = findBreakPoints(line, " | ", 3);
        }

        // No break points found — return as-is
        if (breaks.isEmpty()) {
            LOG.warn("No break points found in {}-char line, keeping as single chunk", line.length());
            return List.of(line);
        }

        // Add end of line as final break
        breaks.add(line.length());

        List<String> parts = new ArrayList<>();
        int start = 0;
        StringBuilder buf = new StringBuilder();
        int bufTokens = 0;

        for (int bp : breaks) {
            String segment = line.substring(start, bp);
            int segTokens = countTokens(segment);

            if (bufTokens + segTokens > maxTokens && buf.length() > 0) {
                parts.add(buf.toString());
                buf = new StringBuilder();
                bufTokens = 0;
            }

            buf.append(segment);
            bufTokens += segTokens;
            start = bp;
        }

        if (buf.length() > 0) {
            parts.add(buf.toString());
        }

        return parts;
    }

    /**
     * Find positions in text where we can break, returning the index AFTER the delimiter.
     * @param splitOffset how many chars into the delimiter to place the break
     *                    (e.g., 2 for "), [" breaks after "), " before "[")
     */
    private List<Integer> findBreakPoints(String text, String delimiter, int splitOffset) {
        List<Integer> points = new ArrayList<>();
        int idx = 0;
        while ((idx = text.indexOf(delimiter, idx)) != -1) {
            points.add(idx + splitOffset);
            idx += delimiter.length();
        }
        return points;
    }

    private void addChunk(List<String> chunks, String prefix, String content) {
        if (content.isEmpty()) return;
        String chunk = prefix.isEmpty() ? content : prefix + "\n" + content;
        chunks.add(chunk);
    }

    /**
     * Build breadcrumb from heading stack.
     * Example: "# Gene: A2m ... > ## Annotation > ### Disease Annotations"
     */
    private String buildBreadcrumb(TreeMap<Integer, String> headingStack) {
        if (headingStack.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : headingStack.entrySet()) {
            if (sb.length() > 0) sb.append(" > ");
            sb.append("#".repeat(e.getKey())).append(" ").append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Remove a column from a markdown table row by index.
     * Splits on "|", removes the column at the given index, and rejoins.
     */
    private String removeColumn(String line, int colIndex) {
        String[] cols = line.split("\\|", -1);
        if (colIndex < 0 || colIndex >= cols.length) return line;
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < cols.length; i++) {
            if (i != colIndex) kept.add(cols[i]);
        }
        return String.join("|", kept);
    }

    /**
     * Restructure Related Phenotype Data rows in strain reports.
     * Converts a single oversized pipe-delimited row like:
     *   | Rat Strains: * [link1] * [link2] | Clinical Measurements: * [link3] ... |
     * Into sub-sections:
     *   #### Rat Strains
     *   * [link1]
     *   * [link2]
     *   #### Clinical Measurements
     *   * [link3]
     *   ...
     */
    private String[] restructurePhenotypeData(String[] lines) {
        List<String> result = new ArrayList<>();
        boolean inPhenoSection = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Track when we enter/leave a Related Phenotype Data section
            if (trimmed.startsWith("#")) {
                inPhenoSection = trimmed.contains("Related Phenotype Data");
                result.add(line);
                continue;
            }

            // Restructure the phenotype data row
            if (inPhenoSection && trimmed.startsWith("| Rat Strains:")) {
                String[] cols = trimmed.split(" \\| ");
                for (String col : cols) {
                    // Clean leading/trailing pipe chars
                    String cell = col.trim();
                    if (cell.startsWith("|")) cell = cell.substring(1).trim();
                    if (cell.endsWith("|")) cell = cell.substring(0, cell.length() - 1).trim();
                    if (cell.isEmpty()) continue;

                    // Split "Label: * [item1] * [item2]" into label + items
                    int colonIdx = cell.indexOf(':');
                    if (colonIdx < 0) continue;

                    String label = cell.substring(0, colonIdx).trim();
                    String items = cell.substring(colonIdx + 1).trim();

                    result.add("#### " + label);
                    // Split items at "* [" boundaries
                    String[] parts = items.split("(?=\\* \\[)");
                    for (String part : parts) {
                        String item = part.trim();
                        if (!item.isEmpty()) {
                            result.add(item);
                        }
                    }
                    result.add("");  // blank line between sub-sections
                }
                inPhenoSection = false;  // only one data row per section
                LOG.info("Restructured Related Phenotype Data row ({} chars) into sub-sections", trimmed.length());
                continue;
            }

            result.add(line);
        }

        return result.toArray(new String[0]);
    }

    /** Check if a line is a markdown table row (starts and ends with |). */
    private boolean isTableRow(String line) {
        return line.startsWith("|") && line.endsWith("|") && line.length() > 2;
    }

    /**
     * Check if a line is a table separator (e.g., |---|---|---|).
     * Cells must contain only dashes with optional colons for alignment.
     */
    private boolean isTableSeparator(String line) {
        if (!isTableRow(line)) return false;
        String inner = line.substring(1, line.length() - 1);
        String[] cells = inner.split("\\|");
        if (cells.length == 0) return false;
        for (String cell : cells) {
            String c = cell.trim();
            if (c.isEmpty()) continue;
            if (!c.matches(":?-{1,}:?")) return false;
        }
        return true;
    }

    /** Count tokens using cl100k_base encoding (same as text-embedding-3-large). */
    private int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return encoding.countTokens(text);
    }
}
