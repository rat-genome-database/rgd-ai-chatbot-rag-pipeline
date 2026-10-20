package edu.mcw.rgd.chatBotEmbed;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Converts a web page's HTML body into markdown the chatbot's section-aware chunker can use:
 * headings become ATX headings, paragraphs and list items keep their text and links, and tables
 * become pipe tables. Scripts, styles, forms, images and embedded frames are dropped.
 *
 * <p>Page headings are shifted down one level ({@code h1}/{@code h2} -> {@code ##}, {@code h3} ->
 * {@code ###}, deeper -> {@code ####}) because the document's own {@code #} title is the page
 * title.</p>
 */
public final class HtmlMarkdown {

    private static final Set<String> DROPPED = Set.of(
            "script", "style", "form", "iframe", "img", "svg", "noscript", "button", "input", "select", "nav");

    private HtmlMarkdown() {}

    /**
     * @param html    the HTML fragment (e.g. a WordPress page's rendered content)
     * @param baseUrl URL that relative links resolve against
     */
    public static String convert(String html, String baseUrl) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Element body = Jsoup.parseBodyFragment(html, baseUrl).body();
        StringBuilder md = new StringBuilder(html.length());
        blocks(body, md);
        return md.toString().replaceAll("\n{3,}", "\n\n").trim() + "\n";
    }

    /** Number of words of visible text in an HTML fragment. */
    public static int wordCount(String html) {
        if (html == null || html.isBlank()) {
            return 0;
        }
        String text = Jsoup.parseBodyFragment(html).body().text().trim();
        return text.isEmpty() ? 0 : text.split("\\s+").length;
    }

    private static void blocks(Element parent, StringBuilder md) {
        StringBuilder loose = new StringBuilder();   // inline content directly inside a container
        for (Node n : parent.childNodes()) {
            if (n instanceof TextNode t) {
                loose.append(t.text());
                continue;
            }
            if (!(n instanceof Element e)) {
                continue;
            }
            String tag = e.normalName();
            if (DROPPED.contains(tag)) {
                continue;
            }
            if (isBlock(tag)) {
                flush(loose, md);
                block(e, tag, md);
            } else {
                loose.append(inline(e));
            }
        }
        flush(loose, md);
    }

    private static void block(Element e, String tag, StringBuilder md) {
        switch (tag) {
            case "h1": case "h2": heading(md, 2, e); break;
            case "h3":            heading(md, 3, e); break;
            case "h4": case "h5": case "h6": heading(md, 4, e); break;
            case "p": case "blockquote": case "figcaption": case "dt": case "dd":
                paragraph(md, inline(e));
                break;
            case "ul": case "ol":
                list(e, md, 0);
                md.append("\n");
                break;
            case "table":
                table(e, md);
                break;
            case "pre":
                paragraph(md, e.wholeText().trim());
                break;
            case "hr":
                break;
            default:   // div, section, article, figure, dl, ...
                blocks(e, md);
        }
    }

    private static void heading(StringBuilder md, int level, Element e) {
        String text = clean(e.text());
        if (!text.isEmpty()) {
            md.append("\n").append(Md.heading(level, text));
        }
    }

    private static void paragraph(StringBuilder md, String text) {
        String t = clean(text);
        if (!t.isEmpty()) {
            md.append(t).append("\n\n");
        }
    }

    private static void flush(StringBuilder loose, StringBuilder md) {
        paragraph(md, loose.toString());
        loose.setLength(0);
    }

    private static void list(Element listEl, StringBuilder md, int depth) {
        for (Element li : listEl.children()) {
            if (!li.normalName().equals("li")) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            List<Element> nested = new ArrayList<>();
            for (Node n : li.childNodes()) {
                if (n instanceof TextNode t) {
                    text.append(t.text());
                } else if (n instanceof Element c) {
                    String tag = c.normalName();
                    if (tag.equals("ul") || tag.equals("ol")) {
                        nested.add(c);
                    } else if (!DROPPED.contains(tag)) {
                        text.append(' ').append(inline(c)).append(' ');
                    }
                }
            }
            String t = clean(text.toString());
            if (!t.isEmpty()) {
                md.append("  ".repeat(depth)).append("- ").append(t).append("\n");
            }
            for (Element c : nested) {
                list(c, md, depth + 1);
            }
        }
    }

    private static void table(Element table, StringBuilder md) {
        List<List<String>> rows = new ArrayList<>();
        int width = 0;
        for (Element tr : table.select("tr")) {
            List<String> row = new ArrayList<>();
            for (Element cell : tr.children()) {
                if (cell.normalName().equals("td") || cell.normalName().equals("th")) {
                    row.add(Md.cell(clean(inline(cell))));
                }
            }
            if (row.stream().anyMatch(s -> !s.isEmpty())) {
                rows.add(row);
                width = Math.max(width, row.size());
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        // Drop columns that are empty in every row (left behind by removed images and videos).
        List<Integer> keep = new ArrayList<>();
        for (int col = 0; col < width; col++) {
            for (List<String> r : rows) {
                if (col < r.size() && !r.get(col).isEmpty()) {
                    keep.add(col);
                    break;
                }
            }
        }
        // A one-row or one-column table is page layout, not data: emit its cells as paragraphs.
        if (rows.size() == 1 || keep.size() == 1) {
            for (List<String> r : rows) {
                for (int col : keep) {
                    if (col < r.size()) {
                        paragraph(md, r.get(col));
                    }
                }
            }
            return;
        }
        List<List<String>> kept = new ArrayList<>();
        for (List<String> r : rows) {
            List<String> k = new ArrayList<>();
            for (int col : keep) {
                k.add(col < r.size() ? r.get(col) : "");
            }
            kept.add(k);
        }
        md.append(Md.table(kept.get(0), kept.subList(1, kept.size())));
    }

    /** Inline text of an element with links kept as markdown links. */
    private static String inline(Element e) {
        if (DROPPED.contains(e.normalName())) {
            return "";
        }
        if (e.normalName().equals("br")) {
            return " ";
        }
        StringBuilder sb = new StringBuilder();
        for (Node n : e.childNodes()) {
            if (n instanceof TextNode t) {
                sb.append(t.text());
            } else if (n instanceof Element c) {
                sb.append(inline(c));
            }
        }
        if (e.normalName().equals("a")) {
            String href = e.absUrl("href");
            String text = clean(sb.toString());
            if (!href.isEmpty() && !text.isEmpty() && (href.startsWith("http://") || href.startsWith("https://"))) {
                return "[" + text.replace("[", "(").replace("]", ")") + "](" + href.replace(" ", "%20") + ")";
            }
            return text;
        }
        return sb.toString();
    }

    private static boolean isBlock(String tag) {
        switch (tag) {
            case "p": case "div": case "section": case "article": case "header": case "footer": case "main":
            case "aside": case "figure": case "figcaption": case "blockquote": case "pre": case "hr":
            case "ul": case "ol": case "dl": case "dt": case "dd": case "table":
            case "h1": case "h2": case "h3": case "h4": case "h5": case "h6":
                return true;
            default:
                return false;
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }
}
