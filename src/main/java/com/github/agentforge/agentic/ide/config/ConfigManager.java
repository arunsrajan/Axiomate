package com.github.agentforge.agentic.ide.config;

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
 * Manages loading and saving user configuration for AgentForge IDE.
 */
public class ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);
    private static final String APP_DIR_NAME = ".agentic-ide";
    private static final String CONFIG_FILE_NAME = "config.json";

    private static ConfigManager instance;

    private final ObjectMapper objectMapper;
    private final Path configPath;
    private IdeConfig config;
    private final List<Consumer<IdeConfig>> listeners = new ArrayList<>();

    private ConfigManager() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        String userHome = System.getProperty("user.home", ".");
        Path appDir = Paths.get(userHome, APP_DIR_NAME);
        try {
            if (!Files.exists(appDir)) {
                Files.createDirectories(appDir);
            }
        } catch (IOException e) {
            log.warn("Could not create app directory: {}", appDir, e);
        }
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
