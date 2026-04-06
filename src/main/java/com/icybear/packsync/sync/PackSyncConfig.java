package com.icybear.packsync.sync;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Early-boot configuration reader for PackSync.
 * <p>
 * Reads {@code packsync.properties} from the game directory. This file must exist
 * and contain at minimum a {@code remote.url} property pointing to the Caddy file
 * server root.
 * <p>
 * Multiple mirror URLs can be specified as a comma-separated list. The first
 * reachable mirror will be used, and if it fails mid-sync the next mirror
 * is tried automatically.
 * <p>
 * Example {@code packsync.properties}:
 * <pre>
 * remote.url=https://primary.example.com/modpack/,https://mirror1.example.com/modpack/,https://mirror2.example.com/modpack/
 * enabled=true
 * </pre>
 */
public class PackSyncConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONFIG_FILE = "packsync.properties";

    private final List<String> mirrorUrls;
    private final boolean enabled;
    private final boolean loaded;

    public PackSyncConfig(Path gameDir) {
        Path configPath = gameDir.resolve(CONFIG_FILE);
        Properties props = new Properties();
        boolean loadedOk = false;
        List<String> urls = new ArrayList<>();
        boolean en = false;

        if (Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                props.load(is);
                String rawUrl = props.getProperty("remote.url", "").trim();
                en = Boolean.parseBoolean(props.getProperty("enabled", "true"));

                // Parse comma-separated mirror URLs
                if (!rawUrl.isEmpty()) {
                    for (String part : rawUrl.split(",")) {
                        String u = part.trim();
                        if (!u.isEmpty()) {
                            // Ensure each URL ends with /
                            urls.add(u.endsWith("/") ? u : u + "/");
                        }
                    }
                }

                loadedOk = !urls.isEmpty();
                if (!loadedOk) {
                    LOGGER.warn("[PackSync] Config loaded but 'remote.url' is empty.");
                } else if (urls.size() > 1) {
                    LOGGER.info("[PackSync] Configured {} mirror(s): {}", urls.size(), urls);
                }
            } catch (IOException e) {
                LOGGER.error("[PackSync] Failed to read config file: {}", configPath, e);
            }
        } else {
            LOGGER.warn("[PackSync] Config file not found: {}. Sync is disabled.", configPath);
            // Create a template config for convenience
            try {
                Files.writeString(configPath,
                        "# PackSync Configuration\n" +
                        "# Set the URL(s) of your Caddy file server root\n" +
                        "# Multiple mirrors can be comma-separated; failover is automatic\n" +
                        "remote.url=\n" +
                        "# Set to false to disable syncing\n" +
                        "enabled=true\n");
                LOGGER.info("[PackSync] Created template config at: {}", configPath);
            } catch (IOException e) {
                LOGGER.error("[PackSync] Failed to create template config", e);
            }
        }

        this.mirrorUrls = Collections.unmodifiableList(urls);
        this.enabled = en && loadedOk;
        this.loaded = loadedOk;
    }

    /**
     * Returns the primary remote URL (first mirror).
     * Kept for backward compatibility.
     */
    public String getRemoteUrl() {
        return mirrorUrls.isEmpty() ? "" : mirrorUrls.get(0);
    }

    /**
     * Returns the ordered list of mirror URLs.
     * The first entry is the primary; subsequent entries are fallbacks.
     */
    public List<String> getMirrorUrls() {
        return mirrorUrls;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isLoaded() {
        return loaded;
    }
}
