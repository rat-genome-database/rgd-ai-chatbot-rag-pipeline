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

    /** A markdown link with the display text escaped for table-cell safety. */
    public static String link(String text, String url) {
        return "[" + cell(text) + "](" + url + ")";
    }
}
