package edu.mcw.rgd.chatBotEmbed;

import java.util.List;

/**
 * Small helpers for emitting markdown that the chatbot's section-aware chunker
 * can parse: ATX headings ({@code #}, {@code ##}, ...) and GitHub-style tables
 * with a {@code |---|} separator row. Cell content is escaped so a stray pipe or
 * newline can't break table structure.
 */
public final class Md {

    private Md() {}

    public static String heading(int level, String text) {
        return "#".repeat(level) + " " + text.trim() + "\n\n";
    }

    /**
     * A pipe-delimited table: a header row followed by data rows, with no
     * {@code |---|} separator row (those are intentionally omitted from the output).
     */
    public static String table(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        for (List<String> row : rows) {
            sb.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /** Escape a value for use inside a table cell (no pipes, no newlines). */
    public static String cell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\r", " ").replace("\n", " ").replace("|", "\\|").trim();
    }

    /**
     * Curated free text as plain markdown paragraphs: any HTML markup is dropped (keeping its
     * text), hard-wrapped lines are re-joined, and blank lines between paragraphs are kept.
     */
    public static String plainText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String s = text.replace("\r\n", "\n").replace('\r', '\n');
        if (s.indexOf('<') >= 0) {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(s);
            doc.select("br").after("\n");
            doc.select("p, div, li, h1, h2, h3, h4, h5, h6, tr").after("\n\n");
            s = doc.body().wholeText();
        }
        StringBuilder out = new StringBuilder();
        for (String para : s.split("\n\\s*\n")) {
            String joined = para.replaceAll("\\s+", " ").trim();
            if (!joined.isEmpty()) {
                if (out.length() > 0) {
                    out.append("\n\n");
                }
                out.append(joined);
            }
        }
        return out.toString();
    }

    /** A markdown link with the display text escaped for table-cell safety. */
    public static String link(String text, String url) {
        return "[" + cell(text) + "](" + url + ")";
    }
}
