package com.github.axiomate.agentic.ide.plugins;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.axiomate.agentic.ide.agent.memory.MemoryItem;
import com.github.axiomate.agentic.ide.agent.memory.MemoryType;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.mcp.McpServerConfig;
import com.github.axiomate.agentic.ide.plugins.PluginManifest.CommandContribution;
import com.github.axiomate.agentic.ide.plugins.PluginManifest.McpServerContribution;
import com.github.axiomate.agentic.ide.plugins.PluginManifest.MemoryContribution;
import com.github.axiomate.agentic.ide.plugins.PluginPackageReader.PluginPackage;
import com.github.axiomate.agentic.ide.plugins.PluginPackageReader.ResolvedUrl;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry.SlashCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Installs, enables, disables and uninstalls plugins. Plugins come from the built-in catalog, a local
 * folder, a ZIP archive or a URL (direct .zip or GitHub repository/folder), in Axiomate or Claude Code format.
 * <p>
 * Contributions are applied through a {@link PluginHost}: memories are tagged with the source
 * {@code plugin:<id>} so they can be removed cleanly, MCP servers are registered (stopped unless the user
 * opts in), and commands are added to the {@link SlashCommandRegistry}.
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);
    private static final String REGISTRY_FILE = "installed.json";
    private static final long MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024;
    private static PluginManager instance;

    /**
     * @param startMcpServers run the plugin's MCP servers immediately (otherwise they are registered disabled)
     * @param envValues       values for the servers' required environment variables (blank = inherit)
     * @param projectRoot     expands {@code ${PROJECT_ROOT}} in server arguments
     */
    public record InstallOptions(boolean startMcpServers, Map<String, String> envValues, Path projectRoot) {
        public InstallOptions {
            envValues = envValues != null ? Map.copyOf(envValues) : Map.of();
        }

        public static InstallOptions defaults(Path projectRoot) {
            return new InstallOptions(false, Map.of(), projectRoot);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path pluginsRoot;
    private final PluginHost host;
    private final Map<String, InstalledPlugin> installed = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static synchronized PluginManager getInstance() {
        if (instance == null) {
            instance = new PluginManager(ConfigManager.getAppDirectory().resolve("plugins"), new PluginHost.Default());
        }
        return instance;
    }

    public PluginManager(Path pluginsRoot, PluginHost host) {
        this.pluginsRoot = pluginsRoot;
        this.host = host;
        load();
        // Memories and MCP configs persist in their own stores; commands live in memory and are re-registered.
        for (InstalledPlugin p : installed.values()) {
            if (p.enabled()) registerCommands(p);
        }
    }

    public Path getPluginsRoot() {
        return pluginsRoot;
    }

    public static String sourceKey(String pluginId) {
        return "plugin:" + pluginId;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public List<PluginManifest> getCatalog() {
        return PluginCatalog.builtIn();
    }

    public synchronized List<InstalledPlugin> getInstalled() {
        return new ArrayList<>(installed.values());
    }

    public synchronized Optional<InstalledPlugin> getInstalled(String id) {
        return Optional.ofNullable(installed.get(id));
    }

    public synchronized boolean isInstalled(String id) {
        return installed.containsKey(id);
    }

    // ------------------------------------------------------------------
    // Install sources
    // ------------------------------------------------------------------

    /**
     * Installs a catalog (or programmatically built) manifest.
     */
    public InstalledPlugin install(PluginManifest manifest, InstallOptions opts) throws IOException {
        String id = PluginPackageReader.sanitizeId(manifest.id() != null ? manifest.id() : manifest.displayName());
        PluginManifest m = manifest.withId(id);
        Path dir = pluginsRoot.resolve(id);
        Files.createDirectories(dir);
        mapper.writeValue(dir.resolve(PluginManifest.FILE_NAME).toFile(), m);
        return register(m, "catalog", PluginPackageReader.FORMAT_AXIOMATE, dir, opts);
    }

    /**
     * Reads (but does not install) a plugin from a folder or .zip file, for previewing.
     */
    public PluginPackage readPackage(Path folderOrZip) throws IOException {
        if (Files.isRegularFile(folderOrZip) && folderOrZip.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            Path tmp = Files.createTempDirectory("axiomate-plugin-");
            try (InputStream in = Files.newInputStream(folderOrZip)) {
                PluginPackageReader.extractZip(in, tmp);
            }
            return PluginPackageReader.read(tmp);
        }
        return PluginPackageReader.read(folderOrZip);
    }

    /**
     * Downloads a plugin archive (direct .zip or GitHub repository/folder URL) and reads it for previewing.
     */
    public PluginPackage downloadPackage(String url) throws IOException, InterruptedException {
        ResolvedUrl resolved = PluginPackageReader.resolveUrl(url);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .proxy(ProxySelector.getDefault())
                .build();
        HttpRequest req = HttpRequest.newBuilder(resolved.archiveUri()).timeout(Duration.ofSeconds(120))
                .header("User-Agent", "Axiomate-IDE").GET().build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            resp.body().close();
            throw new IOException("Download failed: HTTP " + resp.statusCode() + " for " + resolved.archiveUri());
        }
        Path tmp = Files.createTempDirectory("axiomate-plugin-dl-");
        try {
            try (InputStream in = new LimitedInputStream(resp.body(), MAX_DOWNLOAD_BYTES)) {
                PluginPackageReader.extractZip(in, tmp);
            }
            Path root = tmp;
            if (resolved.subPath() != null) {
                Path top = singleChildDir(tmp);
                root = (top != null ? top : tmp).resolve(resolved.subPath()).normalize();
                if (!root.startsWith(tmp) || !Files.isDirectory(root)) {
                    throw new IOException("Folder '" + resolved.subPath() + "' not found in the downloaded archive");
                }
            }
            return PluginPackageReader.read(root);
        } catch (IOException | RuntimeException e) {
            deleteTree(tmp); // a failed download must not leave its files in the temp folder
            throw e;
        }
    }

    /**
     * Copies a previously read package into the plugins folder and registers it.
     */
    public InstalledPlugin installPackage(PluginPackage pkg, String source, InstallOptions opts) throws IOException {
        String id = PluginPackageReader.sanitizeId(pkg.manifest().id());
        Path root = pluginsRoot.toAbsolutePath().normalize();
        Path target = root.resolve(id).normalize();
        if (!target.getParent().equals(root)) {
            throw new IOException("Invalid plugin id: " + pkg.manifest().id());
        }
        pkg = new PluginPackage(pkg.manifest().withId(id), pkg.root(), pkg.format(), pkg.warnings());
        if (!pkg.root().toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            deleteTree(target);
            copyTree(pkg.root(), target);
            deleteExtractedTemp(pkg.root());
        }
        return register(pkg.manifest(), source, pkg.format(), target, opts);
    }

    /**
     * Removes the temporary folder a ZIP or URL package was extracted into.
     */
    private static void deleteExtractedTemp(Path packageRoot) {
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path p = packageRoot.toAbsolutePath().normalize();
        while (p.getParent() != null && !p.getParent().equals(tmpDir)) {
            p = p.getParent();
        }
        if (p.getParent() != null && p.getFileName().toString().startsWith("axiomate-plugin-")) {
            try {
                deleteTree(p);
            } catch (IOException e) {
                log.debug("Could not delete temporary plugin folder {}: {}", p, e.getMessage());
            }
        }
    }

    public InstalledPlugin installFromPath(Path folderOrZip, InstallOptions opts) throws IOException {
        return installPackage(readPackage(folderOrZip), folderOrZip.toAbsolutePath().toString(), opts);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private synchronized InstalledPlugin register(PluginManifest m, String source, String format, Path dir,
                                                  InstallOptions opts) throws IOException {
        InstalledPlugin previous = installed.get(m.id());
        if (previous != null) {
            retract(previous, true); // reinstall/upgrade replaces all prior contributions
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("PLUGIN_ROOT", dir.toAbsolutePath().toString());
        vars.put("CLAUDE_PLUGIN_ROOT", dir.toAbsolutePath().toString());
        vars.put("PROJECT_ROOT", opts.projectRoot() != null ? opts.projectRoot().toAbsolutePath().toString() : ".");

        List<String> serverNames = new ArrayList<>();
        for (McpServerContribution sc : m.mcpServers()) {
            String name = sc.name() != null && !sc.name().isBlank() ? sc.name() : m.id();
            if (host.hasMcpServer(name) && (previous == null || !previous.mcpServerNames().contains(name))) {
                name = m.id() + "-" + name; // avoid clobbering a server the user or another plugin owns
            }
            McpServerConfig cfg = sc.toConfig(name, vars, opts.envValues());
            cfg.setDescription("Plugin: " + m.displayName() + (cfg.getDescription().isBlank() ? "" : " — " + cfg.getDescription()));
            cfg.setEnabled(opts.startMcpServers());
            host.addMcpServer(cfg);
            serverNames.add(name);
        }

        InstalledPlugin plugin = new InstalledPlugin(m, true, opts.startMcpServers(), source, format,
                dir.toAbsolutePath().toString(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), serverNames);
        installed.put(m.id(), plugin);
        host.addMemories(buildMemories(m));
        registerCommands(plugin);
        save();
        log.info("Installed plugin '{}' v{} from {} ({})", m.displayName(), m.version(), source, m.contributionSummary());
        notifyListeners();
        return plugin;
    }

    public synchronized void setEnabled(String id, boolean enabled) {
        InstalledPlugin p = installed.get(id);
        if (p == null || p.enabled() == enabled) return;
        if (enabled) {
            host.addMemories(buildMemories(p.manifest()));
            registerCommands(p);
            for (String s : p.mcpServerNames()) host.setMcpServerEnabled(s, p.mcpEnabled());
        } else {
            retract(p, false);
        }
        installed.put(id, p.withEnabled(enabled));
        save();
        notifyListeners();
    }

    /**
     * Starts or stops the plugin's MCP servers (only effective while the plugin is enabled).
     */
    public synchronized void setMcpServersEnabled(String id, boolean run) {
        InstalledPlugin p = installed.get(id);
        if (p == null) return;
        if (p.enabled()) {
            for (String s : p.mcpServerNames()) host.setMcpServerEnabled(s, run);
        }
        installed.put(id, p.withMcpEnabled(run));
        save();
        notifyListeners();
    }

    public synchronized void uninstall(String id) throws IOException {
        InstalledPlugin p = installed.remove(id);
        if (p == null) return;
        retract(p, true);
        Path dir = Paths.get(p.installPath());
        if (dir.toAbsolutePath().normalize().startsWith(pluginsRoot.toAbsolutePath().normalize())) {
            deleteTree(dir);
        }
        save();
        log.info("Uninstalled plugin '{}'", p.manifest().displayName());
        notifyListeners();
    }

    private void retract(InstalledPlugin p, boolean removeServers) {
        host.removeMemoriesBySource(sourceKey(p.id()));
        host.commands().unregisterSource(sourceKey(p.id()));
        for (String s : p.mcpServerNames()) {
            if (removeServers) {
                host.removeMcpServer(s);
            } else {
                host.setMcpServerEnabled(s, false);
            }
        }
    }

    private void registerCommands(InstalledPlugin p) {
        for (CommandContribution c : p.manifest().commands()) {
            host.commands().register(new SlashCommand(c.name(), c.description() != null ? c.description() : "",
                    c.prompt(), sourceKey(p.id())));
        }
    }

    static List<MemoryItem> buildMemories(PluginManifest m) {
        List<MemoryItem> items = new ArrayList<>();
        int i = 0;
        for (MemoryContribution mc : m.memories()) {
            MemoryType type;
            try {
                type = mc.type() != null ? MemoryType.valueOf(mc.type().trim().toUpperCase(Locale.ROOT)) : MemoryType.LONG_TERM;
            } catch (IllegalArgumentException e) {
                type = MemoryType.LONG_TERM;
            }
            List<String> tags = new ArrayList<>(mc.tags());
            tags.add("plugin");
            tags.add(m.id());
            MemoryItem item = new MemoryItem(type, mc.title(), mc.content(), tags);
            item.setId("plugin-" + m.id() + "-" + (i++));
            item.setSource(sourceKey(m.id()));
            item.setImportance(type == MemoryType.PROJECT_RULE ? 0.7 : 0.6);
            items.add(item);
        }
        return items;
    }

    // ------------------------------------------------------------------
    // Persistence & listeners
    // ------------------------------------------------------------------

    private void load() {
        Path file = pluginsRoot.resolve(REGISTRY_FILE);
        if (!Files.exists(file)) return;
        try {
            List<InstalledPlugin> list = mapper.readValue(file.toFile(), new TypeReference<List<InstalledPlugin>>() {});
            for (InstalledPlugin p : list) {
                if (p != null && p.manifest() != null && p.id() != null) installed.put(p.id(), p);
            }
            log.info("Loaded {} installed plugin(s) from {}", installed.size(), file);
        } catch (Exception e) {
            log.error("Failed to read plugin registry {}", file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(pluginsRoot);
            mapper.writeValue(pluginsRoot.resolve(REGISTRY_FILE).toFile(), new ArrayList<>(installed.values()));
        } catch (IOException e) {
            log.error("Failed to save plugin registry", e);
        }
    }

    public void addChangeListener(Runnable r) {
        listeners.add(r);
    }

    public void removeChangeListener(Runnable r) {
        listeners.remove(r);
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Exception e) {
                log.warn("Plugin listener failed", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // File helpers
    // ------------------------------------------------------------------

    private static Path singleChildDir(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            List<Path> children = s.filter(Files::isDirectory).toList();
            return children.size() == 1 ? children.get(0) : null;
        }
    }

    static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName() != null && dir.getFileName().toString().equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Files.copy(file, dst.resolve(src.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Caps the number of bytes read from a download.
     */
    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private final long limit;
        private long count;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && ++count > limit) throw new IOException("Download exceeds " + limit / (1024 * 1024) + " MB");
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0 && (count += n) > limit) throw new IOException("Download exceeds " + limit / (1024 * 1024) + " MB");
            return n;
        }
    }
}
