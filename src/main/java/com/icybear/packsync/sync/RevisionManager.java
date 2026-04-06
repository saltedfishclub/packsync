package com.icybear.packsync.sync;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Manages the {@code .revision} file for change detection.
 * <p>
 * The remote server hosts a {@code .revision} file at the root URL.
 * This class fetches it, compares against a locally cached copy, and
 * determines whether a full sync is required.
 */
public class RevisionManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String REVISION_FILE = ".revision";
    private static final String CACHE_DIR = ".packsync";
    private static final String CACHE_FILE = "revision.cache";

    private final Path cacheFilePath;
    private final HttpClient httpClient;

    public RevisionManager(Path gameDir) {
        Path cacheDir = gameDir.resolve(CACHE_DIR);
        this.cacheFilePath = cacheDir.resolve(CACHE_FILE);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            LOGGER.error("[PackSync] Failed to create cache directory: {}", cacheDir, e);
        }
    }

    /**
     * Fetches the remote .revision content.
     *
     * @param baseUrl the remote server base URL
     * @return the revision string, or null on failure
     */
    public String fetchRemoteRevision(String baseUrl) {
        String url = baseUrl + REVISION_FILE;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body().trim();
            } else {
                LOGGER.warn("[PackSync] Failed to fetch .revision from {}: HTTP {}", url, response.statusCode());
                throw new RuntimeException("Failed to fetch revision from update server");
            }
        } catch (Exception e) {
            LOGGER.error("[PackSync] Error fetching .revision from {}: {}", url, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Reads the locally cached revision.
     *
     * @return the cached revision string, or null if not cached
     */
    public String getLocalRevision() {
        if (Files.exists(cacheFilePath)) {
            try {
                return Files.readString(cacheFilePath).trim();
            } catch (IOException e) {
                LOGGER.error("[PackSync] Failed to read local revision cache", e);
            }
        }
        return null;
    }

    /**
     * Saves the revision to local cache.
     */
    public void saveLocalRevision(String revision) {
        try {
            Files.writeString(cacheFilePath, revision);
        } catch (IOException e) {
            LOGGER.error("[PackSync] Failed to save revision cache", e);
        }
    }

    /**
     * Checks whether the remote revision differs from the local cache.
     *
     * @param baseUrl the remote server base URL
     * @return true if an update is needed, false otherwise
     */
    public boolean needsUpdate(String baseUrl) {
        String remote = fetchRemoteRevision(baseUrl);
        if (remote == null) {
            LOGGER.warn("[PackSync] Could not determine remote revision. Skipping sync.");
            return false;
        }
        String local = getLocalRevision();
        boolean needs = !remote.equals(local);
        if (needs) {
            LOGGER.info("[PackSync] Revision changed: local='{}' remote='{}'", local, remote);
        } else {
            LOGGER.info("[PackSync] Already up to date (rev: {})", remote);
        }
        return needs;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }
}
