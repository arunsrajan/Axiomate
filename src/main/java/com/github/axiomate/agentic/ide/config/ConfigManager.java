package com.github.axiomate.agentic.ide.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Manages loading and saving user configuration for Axiomate IDE.
 */
public class ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);

    public static final String APP_DIR_NAME = ".axiomate-ide";
    public static final String LEGACY_APP_DIR_NAME = ".agentforge-ide";
    public static final String ORIGINAL_LEGACY_APP_DIR_NAME = ".agentic-ide";
    public static final String CONFIG_FILE_NAME = "config.json";

    private static ConfigManager instance;

    private final ObjectMapper objectMapper;
    private final Path configPath;
    private IdeConfig config;
    private final List<Consumer<IdeConfig>> listeners = new ArrayList<>();

    public static Path getAppDirectory() {
        String userHome = System.getProperty("user.home", ".");
        Path appDir = Paths.get(userHome, APP_DIR_NAME);
        try {
            if (!Files.exists(appDir)) {
                Files.createDirectories(appDir);
                // Migrate any legacy config files if present from .agentforge-ide or .agentic-ide
                List<Path> potentialLegacyDirs = List.of(
                        Paths.get(userHome, LEGACY_APP_DIR_NAME),
                        Paths.get(userHome, ORIGINAL_LEGACY_APP_DIR_NAME)
                );
                for (Path legacyDir : potentialLegacyDirs) {
                    if (Files.exists(legacyDir)) {
                        log.info("Migrating configuration files from legacy {} to {}", legacyDir, appDir);
                        try (var stream = Files.list(legacyDir)) {
                            for (Path src : stream.toList()) {
                                Path dest = appDir.resolve(src.getFileName());
                                if (!Files.exists(dest)) {
                                    Files.copy(src, dest);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Error migrating legacy configs from {}", legacyDir, e);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not create app directory: {}", appDir, e);
        }
        return appDir;
    }

    private ConfigManager() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path appDir = getAppDirectory();
        this.configPath = appDir.resolve(CONFIG_FILE_NAME);
        this.config = loadConfig();
    }

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    public IdeConfig getConfig() {
        return config;
    }

    private IdeConfig loadConfig() {
        if (Files.exists(configPath)) {
            try {
                IdeConfig loaded = objectMapper.readValue(configPath.toFile(), IdeConfig.class);
                log.info("Loaded IDE config from {}", configPath);
                return loaded;
            } catch (Exception e) {
                log.error("Failed to parse config file, creating default config", e);
                Path backup = configPath.resolveSibling(CONFIG_FILE_NAME + ".bak");
                try {
                    Files.copy(configPath, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    log.warn("Backed up corrupted config file to {}", backup);
                } catch (Exception be) {
                    log.warn("Failed to backup corrupted config file", be);
                }
            }
        }
        IdeConfig defaultConfig = new IdeConfig();
        saveConfig(defaultConfig);
        return defaultConfig;
    }

    public void saveConfig(IdeConfig newConfig) {
        this.config = newConfig;
        try {
            objectMapper.writeValue(configPath.toFile(), config);
            log.info("Saved IDE config to {}", configPath);
            notifyListeners();
        } catch (IOException e) {
            log.error("Failed to write IDE config to {}", configPath, e);
        }
    }

    public void resetToDefaults() {
        saveConfig(new IdeConfig());
    }

    public void addListener(Consumer<IdeConfig> listener) {
        listeners.add(listener);
    }

    private void notifyListeners() {
        for (Consumer<IdeConfig> listener : listeners) {
            try {
                listener.accept(config);
            } catch (Exception e) {
                log.warn("Error notifying config listener", e);
            }
        }
    }
}

