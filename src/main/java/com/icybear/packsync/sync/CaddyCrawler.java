package com.icybear.packsync.sync;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Recursively crawls a Caddy file server using its JSON directory listing API.
 * <p>
 * Caddy returns JSON when the request includes {@code Accept: application/json}.
 * The response is an array of objects:
 * <pre>
 * [
 *   {"name": "file.jar", "size": 1024, "url": "/path/file.jar", "mod_time": "2026-...", "is_dir": false},
 *   ...
 * ]
 * </pre>
 */
public class CaddyCrawler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;

    /**
     * Represents a single remote file entry.
     */
    public static class RemoteFileEntry {
        private final String relativePath;
        private final long size;
        private final Instant modTime;
        private final boolean isDir;

        public RemoteFileEntry(String relativePath, long size, Instant modTime, boolean isDir) {
            this.relativePath = relativePath;
            this.size = size;
            this.modTime = modTime;
            this.isDir = isDir;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public long getSize() {
            return size;
        }

        public Instant getModTime() {
            return modTime;
        }

        public boolean isDir() {
            return isDir;
        }

        @Override
        public String toString() {
            return "RemoteFileEntry{" + relativePath + ", size=" + size + ", dir=" + isDir + "}";
        }
    }

    public CaddyCrawler(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Recursively crawls the remote server starting from the given path.
     *
     * @param baseUrl the server base URL (e.g., "https://server.com/modpack/")
     * @param path    the relative path to crawl (e.g., "" for root, "mods/" for mods dir)
     * @return a flattened list of all remote files (non-directory entries)
     */
    public List<RemoteFileEntry> crawl(String baseUrl, String path) {
        String url = baseUrl + path;
        // Ensure directory URLs end with /
        if (!url.endsWith("/")) {
            url += "/";
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("[PackSync] Failed to crawl {}: HTTP {}", url, response.statusCode());
                return Collections.emptyList();
            }

            List<Map<String, Object>> entries = GSON.fromJson(
                    response.body(),
                    new TypeToken<List<Map<String, Object>>>() {}.getType()
            );

            if (entries == null) {
                return Collections.emptyList();
            }

            List<RemoteFileEntry> results = new ArrayList<>();
            for (Map<String, Object> entry : entries) {
                String name = (String) entry.get("name");
                if (name == null) continue;

                // Skip hidden files used for sync metadata
                if (name.startsWith(".")) continue;

                boolean isDir = Boolean.TRUE.equals(entry.get("is_dir"));
                double size = entry.get("size") != null ? ((Number) entry.get("size")).doubleValue() : 0;
                String modTimeStr = (String) entry.get("mod_time");
                Instant modTime = parseModTime(modTimeStr);

                String relativePath = path.isEmpty() ? name : path + name;

                if (isDir) {
                    // Recurse into subdirectory
                    String subPath = relativePath.endsWith("/") ? relativePath : relativePath + "/";
                    results.addAll(crawl(baseUrl, subPath));
                } else {
                    results.add(new RemoteFileEntry(relativePath, (long) size, modTime, false));
                }
            }

            return results;
        } catch (Exception e) {
            LOGGER.error("[PackSync] Error crawling {}: {}", url, e.getMessage());
            return Collections.emptyList();
        }
    }

    private Instant parseModTime(String modTimeStr) {
        if (modTimeStr == null || modTimeStr.isEmpty()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(modTimeStr));
        } catch (DateTimeParseException e) {
            LOGGER.debug("[PackSync] Could not parse mod_time '{}', using epoch", modTimeStr);
            return Instant.EPOCH;
        }
    }
}
