package edu.mcw.rgd.chatBotEmbed;

import edu.mcw.rgd.chatBotEmbed.embed.EmbedService;
import edu.mcw.rgd.datamodel.Map;
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
 *   run.sh --mode embed [--path gene] [--force] [--outDir path]
 * </pre>
 */
public class Manager {

    private String version;
    private String outputDir;
    private int threadCount = 5;
    private String defaultAssemblyMapKeys = "";   // comma-separated map keys
    private List<ReportGenerator> generators = new ArrayList<>();
    private EmbedService embedService;

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
        String mapKeysArg = null;
        List<Integer> rgdIdArg = new ArrayList<>();
        int limit = 0;
        String path = null;
        boolean force = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode":     mode = args[++i]; break;
                case "--type":     type = args[++i]; break;
                case "--species":  speciesTypeKey = Integer.parseInt(args[++i]); break;
                case "--mapKeys":  mapKeysArg = args[++i]; break;
                case "--rgdId":
                case "--rgdIds":   parseIntList(args[++i], rgdIdArg); break;
                case "--limit":    limit = Integer.parseInt(args[++i]); break;
                case "--path":     path = args[++i]; break;
                case "--force":    force = true; break;
                case "--outDir":   outputDir = args[++i]; break;
                default: throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        if (Utils.isStringEmpty(outputDir)) {
            throw new IllegalArgumentException("outputDir not set (configure it in AppConfigure.xml or pass --outDir)");
        }

        if ("embed".equalsIgnoreCase(mode)) {
            if (embedService == null) {
                throw new IllegalStateException("no embedService configured in AppConfigure.xml");
            }
            embedService.run(outputDir, path, force);
            return;
        }
        if (!"generate".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("unknown --mode '" + mode + "' (expected generate or embed)");
        }

        // Logged here (not before the mode dispatch) so an embed run never writes to the
        // generate summary logger — that keeps run.log holding the last generation summary.
        log.info("{}", getVersion());
        runGenerate(type, speciesTypeKey, mapKeysArg, rgdIdArg, limit);
    }

    private void runGenerate(String type, int speciesTypeKey, String mapKeysArg,
                             List<Integer> rgdIdArg, int limit) throws Exception {
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
        String speciesDir = MarkdownWriter.safeSymbol(SpeciesType.getCommonName(speciesTypeKey)).toLowerCase();

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
                    log.error("Failed to generate rgdId={}: {}", id, e.getMessage());
                }
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignore) { /* per-task errors already logged */ }
        }
        pool.shutdown();
        pool.awaitTermination(6, TimeUnit.HOURS);

        String summary = String.format("Done. written=%d skipped=%d failed=%d", written.get(), skipped.get(), failed.get());
        log.info(summary);
        System.out.println(summary);
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

    // ---- Spring-injected properties ----

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    public void setThreadCount(int threadCount) { this.threadCount = threadCount; }
    public void setDefaultAssemblyMapKeys(String defaultAssemblyMapKeys) { this.defaultAssemblyMapKeys = defaultAssemblyMapKeys; }
    public void setGenerators(List<ReportGenerator> generators) { this.generators = generators; }
    public void setEmbedService(EmbedService embedService) { this.embedService = embedService; }
}
