package edu.mcw.rgd.chatBotEmbed.generators;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.mcw.rgd.chatBotEmbed.HtmlMarkdown;
import edu.mcw.rgd.chatBotEmbed.MarkdownWriter;
import edu.mcw.rgd.chatBotEmbed.Md;
import edu.mcw.rgd.chatBotEmbed.ReportDoc;
import edu.mcw.rgd.chatBotEmbed.ReportGenerator;
import edu.mcw.rgd.datamodel.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Builds a document for each page of RGD's WordPress site ({@code rgd.mcw.edu/wg/}): help and
 * tutorial pages, tool and portal descriptions, data release announcements, nomenclature
 * guidelines. These answer "how do I ..." and "what is ..." questions about RGD itself, which
 * no database report covers.
 *
 * <p>Pages are read from the WordPress REST API ({@code wp-json/wp/v2/pages}) once per run.
 * Pages with fewer than {@link #setMinWords minWords} words of text, and pages whose slug or
 * title matches one of the {@link #setExcludePatterns exclude patterns} (calendar promotions,
 * meeting logistics, test pages), are skipped.</p>
 *
 * <p>Not species-scoped: written under {@code website/all/} as {@code website_<slug>_WP<page id>.md}.
 * Display name: {@code RGD Website Report - <title> (WP:<page id>)}; the chatbot links it to
 * {@code rgd.mcw.edu/wg/?page_id=<page id>}, which WordPress redirects to the page.</p>
 */
public class WebsitePageGenerator implements ReportGenerator {

    private static final Logger log = LogManager.getLogger("status");

    private String apiUrl = "https://rgd.mcw.edu/wg/wp-json/wp/v2/pages";
    private int minWords = 100;
    private List<Pattern> excludePatterns = new ArrayList<>();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper json = new ObjectMapper();

    /** Pages read by {@link #getRgdIds}, keyed by WordPress page id, for {@link #build}. */
    private final java.util.Map<Integer, JsonNode> pages = new ConcurrentHashMap<>();

    @Override
    public String getReportType() {
        return "website";
    }

    @Override
    public boolean isSpeciesScoped() {
        return false;
    }

    /** WordPress page ids of every published page worth embedding; the species does not apply. */
    @Override
    public List<Integer> getRgdIds(int speciesTypeKey) throws Exception {
        List<Integer> ids = new ArrayList<>();
        int skippedShort = 0, skippedExcluded = 0;
        for (int page = 1; ; page++) {
            HttpResponse<String> resp = get(apiUrl + "?per_page=100&status=publish&page=" + page
                    + "&_fields=id,link,slug,title,content,modified");
            if (resp.statusCode() == 400) {
                break;   // WordPress answers 400 for a page number past the last one
            }
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("WordPress API returned " + resp.statusCode() + " for page " + page);
            }
            JsonNode arr = json.readTree(resp.body());
            if (!arr.isArray() || arr.isEmpty()) {
                break;
            }
            for (JsonNode p : arr) {
                int id = p.path("id").asInt();
                String title = title(p);
                String slug = p.path("slug").asText("");
                if (isExcluded(slug, title)) {
                    skippedExcluded++;
                    continue;
                }
                if (title.isEmpty() || HtmlMarkdown.wordCount(p.path("content").path("rendered").asText("")) < minWords) {
                    skippedShort++;
                    continue;
                }
                pages.put(id, p);
                ids.add(id);
            }
            int totalPages = Integer.parseInt(resp.headers().firstValue("X-WP-TotalPages").orElse(String.valueOf(page)));
            if (page >= totalPages) {
                break;
            }
        }
        log.info("Website pages: {} to generate, {} excluded by pattern, {} under {} words or untitled",
                ids.size(), skippedExcluded, skippedShort, minWords);
        return ids;
    }

    @Override
    public ReportDoc build(int pageId, List<Map> assemblies) throws Exception {
        JsonNode p = pages.get(pageId);
        if (p == null) {
            // Asked for by --rgdId rather than enumerated: fetch just this page.
            HttpResponse<String> resp = get(apiUrl + "/" + pageId + "?_fields=id,link,slug,title,content,modified");
            if (resp.statusCode() != 200) {
                return null;
            }
            p = json.readTree(resp.body());
        }
        String title = title(p);
        String link = p.path("link").asText("");
        String body = HtmlMarkdown.convert(p.path("content").path("rendered").asText(""), link);
        if (title.isEmpty() || body.isBlank()) {
            return null;
        }

        StringBuilder md = new StringBuilder(body.length() + 512);
        md.append("# RGD Website: ").append(title).append("\n\n");
        md.append("> **Source:** ").append(Md.link("Rat Genome Database (RGD) website", link)).append("\n");
        String modified = p.path("modified").asText("");
        if (modified.length() >= 10) {
            md.append("> **Last updated:** ").append(modified, 0, 10).append("\n");
        }
        md.append("\n---\n\n");
        md.append(body);

        // "WP:<id>", never a bare number: the embed step reads a trailing "(<number>)" as an RGD
        // ID, and a WordPress page id would then be filed under an unrelated RGD object.
        // Parentheses in the title become brackets too, so a title like "Workshop (2025)" is not
        // read as RGD ID 2025.
        String displayName = "RGD Website Report - " + title.replace('(', '[').replace(')', ']')
                + " (WP:" + pageId + ")";
        return new ReportDoc("WP" + pageId, displayName, MarkdownWriter.safeSymbol(p.path("slug").asText(title)), md.toString());
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** The page title with HTML entities decoded (WordPress renders "&#8211;" for a dash). */
    private static String title(JsonNode p) {
        return Jsoup.parse(p.path("title").path("rendered").asText("")).text().trim();
    }

    private boolean isExcluded(String slug, String title) {
        for (Pattern pattern : excludePatterns) {
            if (pattern.matcher(slug).find() || pattern.matcher(title).find()) {
                return true;
            }
        }
        return false;
    }

    // ---- Spring-injected properties ----

    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public void setMinWords(int minWords) { this.minWords = minWords; }

    /** Case-insensitive regular expressions matched against each page's slug and title. */
    public void setExcludePatterns(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String s : patterns) {
            compiled.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE));
        }
        this.excludePatterns = compiled;
    }
}
