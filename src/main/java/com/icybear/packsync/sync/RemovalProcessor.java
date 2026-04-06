package com.icybear.packsync.sync;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.progress.StartupMessageManager;
import org.slf4j.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Processes the {@code .removal} file from the remote server.
 * <p>
 * The {@code .removal} file contains one entry per line in the format:
 * <pre>
 * &lt;path relative to .minecraft&gt; &lt;sha256 hex hash&gt;
 * </pre>
 * Example:
 * <pre>
 * mods/old-mod-1.0.jar a1b2c3d4e5f6...
 * config/deprecated.toml 1234abcd5678...
 * </pre>
 * <p>
 * For safety, a file is only deleted if its SHA-256 hash matches the expected value.
 * This prevents accidental deletion of user-modified files.
 */
public class RemovalProcessor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String REMOVAL_FILE = ".removal";

    private final HttpClient httpClient;
    private int removedCount = 0;
    private int skippedCount = 0;

    public RemovalProcessor(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Fetches and processes the .removal file.
     *
     * @param baseUrl the remote server base URL
     * @param gameDir the local game directory
     * @return the number of files removed
     */
    public int processRemovals(String baseUrl, Path gameDir) {
        removedCount = 0;
        skippedCount = 0;

        String removalContent = fetchRemovalFile(baseUrl);
        if (removalContent == null || removalContent.isBlank()) {
            LOGGER.info("[PackSync] No .removal file found or it is empty.");
            return 0;
        }

        String[] lines = removalContent.split("\n");
        StartupMessageManager.addModMessage("[PackSync] Processing " + lines.length + " removal entries...");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            // Parse: "<path> <sha256>"
            int lastSpace = line.lastIndexOf(' ');
            if (lastSpace <= 0) {
                LOGGER.warn("[PackSync] Invalid .removal entry (no space separator): {}", line);
                continue;
            }

            String relativePath = line.substring(0, lastSpace).trim();
            String expectedHash = line.substring(lastSpace + 1).trim().toLowerCase();

            Path localFile = gameDir.resolve(relativePath);

            if (!Files.exists(localFile)) {
                LOGGER.debug("[PackSync] File already absent, skipping removal: {}", relativePath);
                skippedCount++;
                continue;
            }

            try {
                String actualHash = computeSha256(localFile);
                if (actualHash.equals(expectedHash)) {
                    // Create a backup before deletion
                    Path backup = localFile.resolveSibling(localFile.getFileName().toString() + ".bak");
                    Files.copy(localFile, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    Files.delete(localFile);
                    removedCount++;
                    String msg = String.format("[PackSync] Removed %s (hash matched)", relativePath);
                    LOGGER.info(msg);
                    StartupMessageManager.addModMessage(msg);
                } else {
                    skippedCount++;
                    LOGGER.warn("[PackSync] Hash mismatch for {}: expected={}, actual={}. Skipping removal.",
                            relativePath, expectedHash, actualHash);
                    StartupMessageManager.addModMessage("[PackSync] Skipped removal (hash mismatch): " + relativePath);
                }
            } catch (Exception e) {
                LOGGER.error("[PackSync] Error processing removal of {}: {}", relativePath, e.getMessage());
                skippedCount++;
            }
        }

        String completeMsg = String.format("[PackSync] Removal complete. Removed %d, skipped %d.", removedCount, skippedCount);
        StartupMessageManager.addModMessage(completeMsg);
        LOGGER.info(completeMsg);

        return removedCount;
    }

    /**
     * Fetches the .removal file content from the remote server.
     */
    private String fetchRemovalFile(String baseUrl) {
        String url = baseUrl + REMOVAL_FILE;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else if (response.statusCode() == 404) {
                LOGGER.debug("[PackSync] No .removal file on server (404)");
                return null;
            } else {
                LOGGER.warn("[PackSync] Failed to fetch .removal: HTTP {}", response.statusCode());
                return null;
            }
        } catch (Exception e) {
            LOGGER.error("[PackSync] Error fetching .removal: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Computes the SHA-256 hash of a file.
     *
     * @return lowercase hex string of the SHA-256 digest
     */
    private String computeSha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public int getRemovedCount() {
        return removedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }
}
