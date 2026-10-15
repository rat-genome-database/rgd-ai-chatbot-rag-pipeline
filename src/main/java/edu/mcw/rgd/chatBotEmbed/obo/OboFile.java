package edu.mcw.rgd.chatBotEmbed.obo;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The {@code [Term]} stanzas of one or more OBO 1.2 files, keyed by term accession.
 *
 * <p>Loaded once and then read-only, so a single instance is shared by all generator threads.
 * {@link #load(String)} reads every {@code *.obo} file under a directory, which is how new
 * ontologies are added: drop the file in, no code change.</p>
 *
 * <p>Two things are derived at load time because an OBO file cannot answer them directly:
 * the <em>inverted</em> {@code is_a} graph (a stanza records only its parents, so child terms
 * have to be accumulated across the whole file), and each ontology's {@code data-version}
 * header, which the reports cite so a stale index is recognisable.</p>
 *
 * <p>Unsupported-by-design: {@code [Typedef]} and {@code [Instance]} stanzas are skipped, as are
 * tags no report section uses. Obsolete terms are parsed but flagged, and the generator skips
 * them — matching the DB-driven path, which enumerates only active terms.</p>
 */
public class OboFile {

    private static final Logger LOG = LogManager.getLogger("obo");

    /** One {@code synonym:} entry: the text and its OBO scope (EXACT, BROAD, NARROW, RELATED). */
    public static class Synonym {
        public final String text;
        public final String scope;

        Synonym(String text, String scope) {
            this.text = text;
            this.scope = scope;
        }
    }

    /** A pointer at another term: {@code is_a: DOID:1115 ! sarcoma} or one side of a relationship. */
    public static class TermRef {
        /** Accession of the referenced term. */
        public final String id;
        /** The {@code ! name} trailing comment, or null when the file omits it. */
        public final String name;
        /** Relationship type for {@code relationship:} lines; null for plain {@code is_a:}. */
        public final String type;

        TermRef(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }

        /** The referenced term's name when the file supplied one, else its accession. */
        public String label() {
            return name != null && !name.isBlank() ? name : id;
        }
    }

    /** One {@code [Term]} stanza. Fields are empty rather than null for the list-valued tags. */
    public static class Term {
        public String id;
        public String name;
        public String def;
        public String comment;
        public String createdBy;
        public String creationDate;
        public boolean obsolete;
        /** Which file this term came from, used to resolve its {@code data-version}. */
        public String sourceOntology;

        public final List<String> defSources = new ArrayList<>();
        public final List<String> altIds = new ArrayList<>();
        public final List<String> xrefs = new ArrayList<>();
        public final List<String> subsets = new ArrayList<>();
        public final List<Synonym> synonyms = new ArrayList<>();
        public final List<TermRef> parents = new ArrayList<>();
        public final List<TermRef> relationships = new ArrayList<>();

        /** Accession prefix, e.g. {@code DOID} for {@code DOID:0001816}; "" when unprefixed. */
        public String prefix() {
            int colon = id == null ? -1 : id.indexOf(':');
            return colon > 0 ? id.substring(0, colon) : "";
        }
    }

    private final Map<String, Term> terms = new LinkedHashMap<>();
    private final Map<String, List<TermRef>> childrenByParent = new HashMap<>();
    private final Map<String, String> dataVersions = new LinkedHashMap<>();

    /**
     * Parse every {@code *.obo} file under {@code oboDir} (non-recursive).
     *
     * @throws IllegalStateException when the directory is missing or holds no .obo file, so a
     *         mis-set path fails loudly instead of silently generating nothing
     */
    public static OboFile load(String oboDir) throws IOException {
        Path root = Paths.get(oboDir);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("OBO directory not found: " + root.toAbsolutePath());
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".obo"))
             .sorted()
             .forEach(files::add);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("no *.obo files in " + root.toAbsolutePath());
        }

        OboFile obo = new OboFile();
        for (Path f : files) {
            obo.parse(f);
        }
        obo.indexChildren();
        LOG.info("Loaded {} term(s) from {} OBO file(s) in {}", obo.terms.size(), files.size(), root);
        return obo;
    }

    /** Parse one file into {@link #terms}, recording its {@code data-version} header. */
    private void parse(Path file) throws IOException {
        String ontology = stripExtension(file.getFileName().toString());
        int before = terms.size();
        int skippedObsolete = 0;

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Term current = null;
        boolean inTerm = false;

        for (String raw : lines) {
            // Files are written with CRLF; readAllLines keeps a stray \r on some platforms.
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;

            if (line.startsWith("[")) {
                current = commit(current);
                // Only [Term] stanzas carry report content; [Typedef]/[Instance] are skipped.
                inTerm = line.equals("[Term]");
                if (inTerm) {
                    current = new Term();
                    current.sourceOntology = ontology;
                }
                continue;
            }
            if (line.isEmpty()) {
                continue;
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String tag = line.substring(0, colon);
            String value = line.substring(colon + 1).trim();

            if (!inTerm) {
                // Header: the one tag the reports cite.
                if (tag.equals("data-version")) {
                    dataVersions.put(ontology, value);
                }
                continue;
            }
            applyTag(current, tag, value);
        }
        commit(current);

        for (Term t : terms.values()) {
            if (ontology.equals(t.sourceOntology) && t.obsolete) {
                skippedObsolete++;
            }
        }
        LOG.info("  {}: {} term(s){}", file.getFileName(), terms.size() - before,
                skippedObsolete > 0 ? " (" + skippedObsolete + " obsolete)" : "");
    }

    /** Store a finished stanza. Later files win on a duplicate accession, which is also logged. */
    private Term commit(Term t) {
        if (t != null && t.id != null && !t.id.isBlank()) {
            Term prior = terms.put(t.id, t);
            if (prior != null && !prior.sourceOntology.equals(t.sourceOntology)) {
                LOG.warn("term {} appears in both {} and {} — keeping {}",
                        t.id, prior.sourceOntology, t.sourceOntology, t.sourceOntology);
            }
        }
        return null;
    }

    private void applyTag(Term t, String tag, String value) {
        switch (tag) {
            case "id":            t.id = value; break;
            case "name":          t.name = value; break;
            case "comment":       t.comment = value; break;
            case "created_by":    t.createdBy = value; break;
            case "creation_date": t.creationDate = value; break;
            case "alt_id":        t.altIds.add(value); break;
            case "xref":          t.xrefs.add(value); break;
            case "subset":        t.subsets.add(value); break;
            case "is_obsolete":   t.obsolete = value.equalsIgnoreCase("true"); break;

            case "def": {
                // def: "the definition text" [source, source "DO", ...]
                String quoted = quotedPart(value);
                if (quoted != null) {
                    t.def = quoted;
                }
                t.defSources.addAll(bracketList(value));
                break;
            }
            case "synonym": {
                // synonym: "the text" EXACT []   (an optional type name may precede the brackets)
                String quoted = quotedPart(value);
                if (quoted == null) {
                    break;
                }
                int closeQuote = value.indexOf('"', 1);
                String tail = closeQuote < 0 ? "" : value.substring(closeQuote + 1).trim();
                int bracket = tail.indexOf('[');
                if (bracket >= 0) {
                    tail = tail.substring(0, bracket).trim();
                }
                String scope = tail.isEmpty() ? "" : tail.split("\\s+")[0];
                t.synonyms.add(new Synonym(quoted, scope));
                break;
            }
            case "is_a":
                t.parents.add(parseRef(value, null));
                break;
            case "relationship": {
                // relationship: has_component DOID:0050852 ! limb ischemia
                int sp = value.indexOf(' ');
                if (sp > 0) {
                    t.relationships.add(parseRef(value.substring(sp + 1).trim(), value.substring(0, sp)));
                }
                break;
            }
            default:
                // Every other tag (property_value, intersection_of, ...) has no report section.
                break;
        }
    }

    /** Parse {@code DOID:1115 ! sarcoma} into its accession and trailing name. */
    private static TermRef parseRef(String value, String type) {
        int bang = value.indexOf('!');
        if (bang < 0) {
            return new TermRef(value.trim(), null, type);
        }
        return new TermRef(value.substring(0, bang).trim(), value.substring(bang + 1).trim(), type);
    }

    /** The first double-quoted run of a value, or null when it is not quoted. */
    private static String quotedPart(String value) {
        if (value.isEmpty() || value.charAt(0) != '"') {
            return null;
        }
        int close = value.indexOf('"', 1);
        return close < 0 ? null : value.substring(1, close);
    }

    /**
     * The comma-separated contents of the trailing {@code [...]} list, with each entry's
     * {@code "DO"}-style quoted annotation dropped. Returns empty for {@code []}.
     */
    private static List<String> bracketList(String value) {
        int open = value.lastIndexOf('[');
        int close = value.lastIndexOf(']');
        if (open < 0 || close <= open + 1) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String part : value.substring(open + 1, close).split(",")) {
            String entry = part.trim();
            int quote = entry.indexOf('"');
            if (quote >= 0) {
                entry = entry.substring(0, quote).trim();
            }
            if (!entry.isEmpty()) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * Invert the {@code is_a} graph. A stanza lists only its parents, so children can only be
     * known once every stanza has been read. Obsolete terms are left out so they do not surface
     * as children of a live term.
     */
    private void indexChildren() {
        for (Term t : terms.values()) {
            if (t.obsolete) {
                continue;
            }
            for (TermRef parent : t.parents) {
                childrenByParent
                        .computeIfAbsent(parent.id, k -> new ArrayList<>())
                        .add(new TermRef(t.id, t.name, null));
            }
        }
        Comparator<TermRef> byLabel = Comparator.comparing(r -> r.label().toLowerCase());
        for (List<TermRef> kids : childrenByParent.values()) {
            kids.sort(byLabel);
        }
    }

    // ---- read side -------------------------------------------------------------

    public Term term(String accId) {
        return terms.get(accId);
    }

    /** Child terms of {@code accId}, name-sorted; empty when it is a leaf. */
    public List<TermRef> children(String accId) {
        return childrenByParent.getOrDefault(accId, Collections.emptyList());
    }

    /** The {@code data-version} of the file a term came from, or null when the header had none. */
    public String dataVersion(Term t) {
        return t == null ? null : dataVersions.get(t.sourceOntology);
    }

    /**
     * Accessions of all non-obsolete terms, in file order.
     *
     * @param prefixFilter an accession prefix such as {@code "DOID"} to restrict to, or
     *                     null/blank for every term in every loaded file
     */
    public List<String> accessions(String prefixFilter) {
        boolean all = prefixFilter == null || prefixFilter.isBlank();
        String want = all ? null : prefixFilter.trim();
        List<String> out = new ArrayList<>();
        for (Term t : terms.values()) {
            if (t.obsolete) {
                continue;
            }
            if (all || t.prefix().equalsIgnoreCase(want)) {
                out.add(t.id);
            }
        }
        return out;
    }

    public int size() {
        return terms.size();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
