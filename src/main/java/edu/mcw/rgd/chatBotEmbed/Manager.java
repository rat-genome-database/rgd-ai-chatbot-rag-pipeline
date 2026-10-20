package edu.mcw.rgd.chatBotEmbed;

import edu.mcw.rgd.chatBotEmbed.embed.EmbedService;
import edu.mcw.rgd.datamodel.Map;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RGD chatbot RAG pipeline. Two independent run modes, selected with {@code --mode}:
 *
 * <ul>
 *   <li><b>generate</b> (default) — query Oracle and write markdown report files to
 *       {@code outputDir}. Replaces the chatbot's HTML-scraping ingest.</li>
 *   <li><b>embed</b> — read those markdown files, chunk + embed them, and write vectors
 *       to Postgres/pgvector. A separate run from generation, by design.</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 *   run.sh --mode generate --type gene [--species 3] [--mapKeys 380,372] [--limit N] [--rgdId N] [--outDir path]
 *   run.sh --mode generate --type ontology [--ontology MP | --ontAcc DOID:10763,DOID:2841] [--species 3] [--object gene] [--limit N] [--outDir path]
 *   run.sh --mode generate --type cellline [--species 3] [--limit N] [--rgdId N]
 *   run.sh --mode generate --type reference|website [--limit N] [--rgdId N]   (not species-scoped: written under &lt;type&gt;/all)
 *   run.sh --mode generate --type pathway|expectedrange [--ontAcc PW:0000559] [--limit N]
 *   run.sh --mode embed --path obo [--ontology DOID] [--force]
 *
 *   A generate run over every object of a type (no --rgdId/--ontAcc/--limit/--ontology) also
 *   deletes that type's files it did not write; it refuses when that is over 10% of them, unless
 *   --forcePrune is given.
 *   run.sh --mode embed [--index qwen] [--path gene] [--species 3] [--rgdId N,N] [--force] [--outDir path]
 *   run.sh --mode refresh-objects [--path strain] [--species 3] [--rgdId N,N] [--outDir path]
 * </pre>
 *
 * <p>{@code --index} picks one of the {@code embedServices} configured in AppConfigure.xml
 * (embedding model, datasource and target schema); without it the default
 * {@code embedService} is used.</p>
 *
 * <p>{@code --rgdId} works in both modes: in generate it limits which objects are built,
 * in embed it limits which of the already-generated files are read. Embedding matches on
 * the trailing id of the {@code <type>_<symbol>_<id>.md} file name.</p>
 */
public class Manager {

    private String version;
    private String outputDir;
    private int threadCount = 5;
    private String defaultAssemblyMapKeys = "";   // comma-separated map keys
    private List<ReportGenerator> generators = new ArrayList<>();
    private List<TermReportGenerator> termGenerators = new ArrayList<>();
    private EmbedService embedService;
    private java.util.Map<String, EmbedService> embedServices = new java.util.HashMap<>();
    /** Which embedServices entry a run uses when --index is not given. */
    private String defaultIndex = "openai";

    private final Logger log = LogManager.getLogger("status");

    private static Manager instance;
    public static Manager getInstance() { return instance; }

    public static void main(String[] args) throws Exception {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(bf).loadBeanDefinitions(
                new FileSystemResource("properties/AppConfigure.xml"));
        Manager manager = (Manager) bf.getBean("manager");
        instance = manager;

        System.out.println(manager.getVersion());
        try {
            manager.run(args);
        } catch (Exception e) {
            Utils.printStackTrace(e, LogManager.getLogger("status"));
            throw e;
        }
    }

    void run(String[] args) throws Exception {
        String mode = "generate";
        String type = null;
        int speciesTypeKey = SpeciesType.RAT;
        boolean speciesProvided = false;
        String mapKeysArg = null;
        String ontologyArg = null;
        String objectArg = null;
        List<String> ontAccArg = new ArrayList<>();
        List<Integer> rgdIdArg = new ArrayList<>();
        int limit = 0;
        String path = null;
        String index = null;
        boolean force = false;
        boolean forcePrune = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode":     mode = args[++i]; break;
                case "--type":     type = args[++i]; break;
                case "--species":  speciesTypeKey = Integer.parseInt(args[++i]); speciesProvided = true; break;
                case "--ontology": ontologyArg = args[++i]; break;
                case "--ontAcc":
                case "--ontAccs":  parseStringList(args[++i], ontAccArg); break;
                case "--object":   objectArg = args[++i]; break;
                case "--mapKeys":  mapKeysArg = args[++i]; break;
                case "--rgdId":
                case "--rgdIds":   parseIntList(args[++i], rgdIdArg); break;
                case "--limit":    limit = Integer.parseInt(args[++i]); break;
                case "--path":     path = args[++i]; break;
                case "--force":    force = true; break;
                case "--forcePrune": forcePrune = true; break;
                case "--index":    index = args[++i]; break;
                case "--outDir":   outputDir = args[++i]; break;
                default: throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        if (Utils.isStringEmpty(outputDir)) {
            throw new IllegalArgumentException("outputDir not set (configure it in AppConfigure.xml or pass --outDir)");
        }

        if ("embed".equalsIgnoreCase(mode)) {
            EmbedService service = selectEmbedService(index);
            // OBO files are produced by a separate pipeline, so there is nothing to generate and
            // no markdown on disk: build each term's report in memory and embed it directly.
            // Restrict to one ontology with --ontology (e.g. DOID).
            if ("obo".equalsIgnoreCase(path)) {
                service.runObo(ontologyArg, force);
                return;
            }
            // Optional species filter: reports are written under a per-species sub-directory named
            // by the species common name (see runGenerate's speciesDir). When --species is given,
            // embed only that species' sub-tree; when omitted, embed every species.
            String speciesDir = speciesProvided
                    ? MarkdownWriter.safeSymbol(SpeciesType.getCommonName(speciesTypeKey)).toLowerCase()
                    : null;
            service.run(outputDir, path, speciesDir, rgdIdArg, force);
            return;
        }
        if ("refresh-objects".equalsIgnoreCase(mode)) {
            // Resolved the same way as an embed run, so this keeps working when the only
            // configuration is the embedServices map. Which index is picked barely matters:
            // report_object and report_position are model-independent and every index's
            // datasource resolves them to the one shared copy in public.
            EmbedService service = selectEmbedService(index);
            String speciesDir = speciesProvided
                    ? MarkdownWriter.safeSymbol(SpeciesType.getCommonName(speciesTypeKey)).toLowerCase()
                    : null;
            service.refreshObjects(outputDir, path, speciesDir, rgdIdArg);
            return;
        }
        if (!"generate".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("unknown --mode '" + mode + "' (expected generate, embed or refresh-objects)");
        }

        // Logged here (not before the mode dispatch) so an embed run never writes to the
        // generate summary logger — that keeps run.log holding the last generation summary.
        log.info("{}", getVersion());

        // Accession-keyed types (ontology terms) run on their own path — not species-scoped and
        // enumerated by term accession rather than RGD ID.
        TermReportGenerator termGenerator = findTermGenerator(type);
        if (termGenerator != null) {
            int speciesFilter = speciesProvided ? speciesTypeKey : 0;   // 0 = all species
            int objectFilter = parseObjectKey(objectArg);               // 0 = all object types
            runGenerateTerms(termGenerator, ontologyArg, ontAccArg, speciesFilter, objectFilter, limit, forcePrune);
        } else {
            runGenerate(type, speciesTypeKey, mapKeysArg, rgdIdArg, limit, forcePrune);
        }
    }

    private void runGenerate(String type, int speciesTypeKey, String mapKeysArg,
                             List<Integer> rgdIdArg, int limit, boolean forcePrune) throws Exception {
        if (Utils.isStringEmpty(type)) {
            throw new IllegalArgumentException("--type is required in generate mode (e.g. --type gene)");
        }

        ReportGenerator generator = findGenerator(type);
        DAO dao = new DAO();
        log.info("Connected to: {}", dao.getConnectionInfo());

        List<Map> assemblies = resolveAssemblies(dao, mapKeysArg);
        MarkdownWriter writer = new MarkdownWriter(outputDir);

        // Per-species sub-directory (e.g. "rat"): reports are generated one species per run
        // (getRgdIds is species-scoped), so every file in this run lands under the same species.
        // Types that are not species-specific (references, website pages) go under "all".
        String speciesDir = generator.isSpeciesScoped()
                ? MarkdownWriter.safeSymbol(SpeciesType.getCommonName(speciesTypeKey)).toLowerCase()
                : "all";

        List<Integer> rgdIds;
        if (!rgdIdArg.isEmpty()) {
            rgdIds = rgdIdArg;   // explicit gene list — for testing, skips the full assembly scan
        } else {
            rgdIds = generator.getRgdIds(speciesTypeKey);
            if (limit > 0 && rgdIds.size() > limit) {
                rgdIds = rgdIds.subList(0, limit);
            }
        }

        log.info("Generating {} '{}' reports into {} (species={}, threads={}, assemblies={})",
                rgdIds.size(), type, outputDir, speciesTypeKey, threadCount, assemblies.size());

        AtomicInteger written = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Set<String> failedIds = ConcurrentHashMap.newKeySet();
        boolean completeRun = rgdIdArg.isEmpty() && limit <= 0;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>(rgdIds.size());
        for (int rgdId : rgdIds) {
            final int id = rgdId;
            futures.add(pool.submit(() -> {
                try {
                    ReportDoc doc = generator.build(id, assemblies);
                    if (doc == null) {
                        skipped.incrementAndGet();
                        return;
                    }
                    Path p = writer.write(generator.getReportType(), speciesDir, doc);
                    int n = written.incrementAndGet();
                    if (n % 500 == 0) {
                        log.info("  ... {} files written (last: {})", n, p.getFileName());
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    failedIds.add(String.valueOf(id));
                    log.error("Failed to generate rgdId={}: {}", id, e.getMessage());
                }
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignore) { /* per-task errors already logged */ }
        }
        pool.shutdown();
        pool.awaitTermination(6, TimeUnit.HOURS);

        // A run over every object of the species also removes the reports it no longer produces
        // (withdrawn objects), so the embed step does not keep re-reading them.
        int pruned = 0;
        if (completeRun) {
            pruned = writer.prune(generator.getReportType(),
                    List.of(writer.typeDir(generator.getReportType()).resolve(speciesDir)), false, failedIds, forcePrune);
        }

        String summary = String.format("Done. written=%d skipped=%d failed=%d pruned=%d",
                written.get(), skipped.get(), failed.get(), pruned);
        log.info(summary);
        System.out.println(summary);
    }

    /**
     * Generate accession-keyed (ontology-term) reports. Unlike {@link #runGenerate}, this is not
     * species-scoped — a term is shared across species — so it enumerates term accessions and
     * writes each report under a per-ontology sub-directory (e.g. {@code ontology/mp/}).
     */
    private void runGenerateTerms(TermReportGenerator generator, String ontologyFilter,
                                  List<String> explicitAccessions, int speciesFilter, int objectFilter,
                                  int limit, boolean forcePrune) throws Exception {
        DAO dao = new DAO();
        log.info("Connected to: {}", dao.getConnectionInfo());
        MarkdownWriter writer = new MarkdownWriter(outputDir);

        List<String> accessions;
        String scope;
        if (!explicitAccessions.isEmpty()) {
            accessions = explicitAccessions;   // explicit term list — for testing, skips enumeration
            scope = explicitAccessions.size() + " explicit";
        } else {
            accessions = generator.getAccessions(ontologyFilter);
            scope = Utils.isStringEmpty(ontologyFilter) ? "all public" : ontologyFilter;
        }
        if (limit > 0 && accessions.size() > limit) {
            accessions = accessions.subList(0, limit);
        }

        log.info("Generating {} '{}' reports into {} (ontology={}, species={}, object={}, threads={})",
                accessions.size(), generator.getReportType(), outputDir, scope,
                speciesFilter == 0 ? "all" : SpeciesType.getCommonName(speciesFilter),
                objectFilter == 0 ? "all" : objectFilter, threadCount);

        AtomicInteger written = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Set<String> failedIds = ConcurrentHashMap.newKeySet();
        boolean completeRun = explicitAccessions.isEmpty() && limit <= 0 && Utils.isStringEmpty(ontologyFilter);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>(accessions.size());
        for (String accId : accessions) {
            final String acc = accId;
            futures.add(pool.submit(() -> {
                try {
                    ReportDoc doc = generator.build(acc, speciesFilter, objectFilter);
                    if (doc == null) {
                        skipped.incrementAndGet();
                        return;
                    }
                    Path p = writer.write(generator.getReportType(), ontologySubDir(acc), doc);
                    int n = written.incrementAndGet();
                    if (n % 500 == 0) {
                        log.info("  ... {} files written (last: {})", n, p.getFileName());
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    failedIds.add(MarkdownWriter.safeSymbol(acc));
                    log.error("Failed to generate term={}: {}", acc, e.getMessage());
                }
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignore) { /* per-task errors already logged */ }
        }
        pool.shutdown();
        pool.awaitTermination(6, TimeUnit.HOURS);

        // Only a run over every accession can tell which reports are obsolete. Every sub-directory
        // of the type is pruned, but only <type>_*.md files: the OBO files under ontology/obo stay.
        int pruned = 0;
        if (completeRun) {
            pruned = writer.prune(generator.getReportType(),
                    List.of(writer.typeDir(generator.getReportType())), true, failedIds, forcePrune);
        }

        String summary = String.format("Done. written=%d skipped=%d failed=%d pruned=%d",
                written.get(), skipped.get(), failed.get(), pruned);
        log.info(summary);
        System.out.println(summary);
    }

    /**
     * Per-ontology sub-directory for a term accession — the accession's prefix, lowercased and
     * filename-safe (e.g. {@code MP:0001900} -> {@code "mp"}). Terms with no prefix land in
     * {@code "misc"}. This is the accession-keyed analogue of the per-species sub-directory.
     */
    private static String ontologySubDir(String accId) {
        int colon = accId.indexOf(':');
        String prefix = colon > 0 ? accId.substring(0, colon) : accId;
        String safe = MarkdownWriter.safeSymbol(prefix).toLowerCase();
        return safe.isEmpty() ? "misc" : safe;
    }

    /**
     * Resolve an {@code --object} argument to an RGD object key, accepting either a type name
     * ({@code gene}, {@code qtl}, {@code strain}, {@code variant}, {@code cellline}) or the numeric
     * key itself. Null/blank means "all object types" (returns 0).
     */
    private static int parseObjectKey(String arg) {
        if (Utils.isStringEmpty(arg)) {
            return 0;   // all object types
        }
        switch (arg.trim().toLowerCase()) {
            case "gene": case "genes":         return RgdId.OBJECT_KEY_GENES;
            case "qtl": case "qtls":           return RgdId.OBJECT_KEY_QTLS;
            case "strain": case "strains":     return RgdId.OBJECT_KEY_STRAINS;
            case "variant": case "variants":   return RgdId.OBJECT_KEY_VARIANTS;
            case "cellline": case "celllines":
            case "cell_line": case "cell-line": return RgdId.OBJECT_KEY_CELL_LINES;
            default:
                try {
                    return Integer.parseInt(arg.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("unknown --object '" + arg
                            + "' (expected gene, qtl, strain, variant, cellline, or an object key)");
                }
        }
    }

    /** Parse a comma-separated list of strings (e.g. "DOID:10763,DOID:2841") into the target list, trimming each. */
    private static void parseStringList(String csv, List<String> target) {
        for (String s : csv.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) {
                target.add(s);
            }
        }
    }

    /** Parse a comma-separated list of ints (e.g. "2004,2219,25736") into the target list. */
    private static void parseIntList(String csv, List<Integer> target) {
        for (String s : csv.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) {
                target.add(Integer.parseInt(s));
            }
        }
    }

    private ReportGenerator findGenerator(String type) {
        for (ReportGenerator g : generators) {
            if (g.getReportType().equalsIgnoreCase(type)) {
                return g;
            }
        }
        throw new IllegalArgumentException("no generator configured for type '" + type + "' (available: "
                + generators.stream().map(ReportGenerator::getReportType).toList() + ")");
    }

    /** The accession-keyed generator for a type (e.g. "ontology"), or null when the type is RGD-ID-keyed. */
    private TermReportGenerator findTermGenerator(String type) {
        if (Utils.isStringEmpty(type)) {
            return null;
        }
        for (TermReportGenerator g : termGenerators) {
            if (g.getReportType().equalsIgnoreCase(type)) {
                return g;
            }
        }
        return null;
    }

    private List<Map> resolveAssemblies(DAO dao, String mapKeysArg) throws Exception {
        String keys = !Utils.isStringEmpty(mapKeysArg) ? mapKeysArg : defaultAssemblyMapKeys;
        List<Map> result = new ArrayList<>();
        if (Utils.isStringEmpty(keys)) {
            log.info("No assembly map keys configured; showing all mapped positions per gene's species.");
            return result;
        }
        for (String k : keys.split(",")) {
            k = k.trim();
            if (k.isEmpty()) continue;
            int mapKey = Integer.parseInt(k);
            Map asm = dao.getAssembly(mapKey);
            if (asm == null) {
                log.warn("Unknown assembly map key {} — skipping", mapKey);
                continue;
            }
            result.add(asm);
            log.info("Including assembly: {} (mapKey={})", asm.getName(), mapKey);
        }
        return result;
    }

    /**
     * The embed service for a run: the named index, or the default when none was asked for.
     *
     * <p>Without {@code --index} the standalone {@code embedService} bean wins — that is the
     * OpenAI index, and an ordinary run must keep writing there. Only an explicit
     * {@code --index} sends vectors to another schema. The {@code defaultIndex} entry
     * ("openai") in {@code embedServices} is consulted only when no standalone bean exists,
     * so an installation that configures every index in the map still has a default.</p>
     *
     * <p>Deliberately no "if only one index is configured, use it" rule: with just a qwen
     * entry present that would quietly send a routine run into the qwen schema. Writing a
     * model's vectors into another model's index is both expensive to undo and hard to
     * notice, so an ambiguous default fails instead of guessing.</p>
     */
    private EmbedService selectEmbedService(String index) {
        if (index != null) {
            EmbedService service = embedServices.get(index);
            if (service == null) {
                throw new IllegalArgumentException("unknown --index '" + index + "' (configured: "
                        + embedServices.keySet() + ")");
            }
            return service;
        }

        if (embedService != null) {
            return embedService;
        }
        EmbedService service = embedServices.get(defaultIndex);
        if (service != null) {
            return service;
        }
        throw new IllegalStateException("no default embed service: AppConfigure.xml has no "
                + "standalone embedService bean and no '" + defaultIndex + "' entry in "
                + "embedServices (configured: " + embedServices.keySet()
                + ") - pass --index explicitly, or set defaultIndex");
    }

    // ---- Spring-injected properties ----

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    public void setThreadCount(int threadCount) { this.threadCount = threadCount; }
    public void setDefaultAssemblyMapKeys(String defaultAssemblyMapKeys) { this.defaultAssemblyMapKeys = defaultAssemblyMapKeys; }
    public void setGenerators(List<ReportGenerator> generators) { this.generators = generators; }
    public void setTermGenerators(List<TermReportGenerator> termGenerators) { this.termGenerators = termGenerators; }
    public void setEmbedService(EmbedService embedService) { this.embedService = embedService; }
    public void setEmbedServices(java.util.Map<String, EmbedService> embedServices) { this.embedServices = embedServices; }
    public void setDefaultIndex(String defaultIndex) { this.defaultIndex = defaultIndex; }
}
