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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Downloads and synchronizes files from the remote server to the local game
 * directory.
 * <p>
 * For each remote file that is newer than the local copy:
 * <ol>
 * <li>Creates a {@code .bak} backup of the existing local file</li>
 * <li>Downloads the remote file and writes it to the local path</li>
 * </ol>
 * <p>
 * Progress messages are sent to {@link StartupMessageManager} for display on
 * the Forge loading screen.
 */
public class FileSynchronizer {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final HttpClient httpClient;
    private int updatedCount = 0;
    private int skippedCount = 0;
    private int failedCount = 0;

    public FileSynchronizer(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Synchronizes all files from the remote file list to the local game directory.
     *
     * @param baseUrl     the remote server base URL
     * @param gameDir     the local game directory (.minecraft)
     * @param remoteFiles the list of remote file entries from crawling
     * @return the number of files updated
     */
    public int synchronize(String baseUrl, Path gameDir, List<CaddyCrawler.RemoteFileEntry> remoteFiles) {
        updatedCount = 0;
        skippedCount = 0;
        failedCount = 0;
        int total = remoteFiles.size();

        for (int i = 0; i < total; i++) {
            CaddyCrawler.RemoteFileEntry entry = remoteFiles.get(i);
            String relativePath = entry.getRelativePath();
            Path localFile = gameDir.resolve(relativePath);

            String progressMsg = String.format("[PackSync] Checking %s (%d/%d)", relativePath, i + 1, total);
            StartupMessageManager.addModMessage(progressMsg);

            try {
                if (shouldUpdate(localFile, entry)) {
                    String downloadMsg = String.format("[PackSync] Downloading %s (%d/%d)", relativePath, i + 1, total);
                    StartupMessageManager.addModMessage(downloadMsg);
                    LOGGER.info(downloadMsg);

                    // Ensure parent directories exist
                    Files.createDirectories(localFile.getParent());

                    // Create backup if the file already exists
                    if (Files.exists(localFile)) {
                        backupFile(localFile);
                    }

                    // Download the file
                    downloadFile(baseUrl + relativePath, localFile);

                    // Set the modification time to match the remote
                    if (entry.getModTime() != null && !entry.getModTime().equals(Instant.EPOCH)) {
                        Files.setLastModifiedTime(localFile, FileTime.from(entry.getModTime()));
                    }

                    updatedCount++;
                } else {
                    skippedCount++;
                }
            } catch (Exception e) {
                LOGGER.error("[PackSync] Failed to sync file: {}", relativePath, e);
                StartupMessageManager.addModMessage("[PackSync] ERROR syncing " + relativePath);
                failedCount++;
            }
        }

        String completeMsg = String.format("[PackSync] Sync phase complete. Updated %d, skipped %d, failed %d files.",
                updatedCount, skippedCount, failedCount);
        StartupMessageManager.addModMessage(completeMsg);
        LOGGER.info(completeMsg);

        return updatedCount;
    }

    /**
     * Determines whether a local file needs to be updated from the remote.
     * <p>
     * A file is re-downloaded if any of the following are true:
     * <ul>
     * <li>The local file does not exist</li>
     * <li>The remote modification time is strictly newer</li>
     * <li>The local file size does not match the remote size</li>
     * </ul>
     */
    private boolean shouldUpdate(Path localFile, CaddyCrawler.RemoteFileEntry remote) {
        if (!Files.exists(localFile)) {
            return true;
        }
        try {
            // Size mismatch — likely a corrupted or incomplete download
            long localSize = Files.size(localFile);
            if (localSize != remote.getSize()) {
                LOGGER.debug("[PackSync] Size mismatch for {}: local={} remote={}", localFile.getFileName(), localSize,
                        remote.getSize());
                return true;
            }
            // Modification time — remote is newer
            Instant localModTime = Files.getLastModifiedTime(localFile).toInstant();
            return remote.getModTime().isAfter(localModTime);
        } catch (Exception e) {
            LOGGER.warn("[PackSync] Could not check file attributes for {}, will re-download", localFile);
            return true;
        }
    }

    /**
     * Creates a .bak backup of the given file.
     */
    private void backupFile(Path file) {
        Path backup = file.resolveSibling(file.getFileName().toString() + ".bak");
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug("[PackSync] Backed up {} -> {}", file.getFileName(), backup.getFileName());
        } catch (Exception e) {
            LOGGER.warn("[PackSync] Failed to backup {}: {}", file, e.getMessage());
        }
    }

    /**
     * Downloads a file from the given URL to the target path.
     * <p>
     * To prevent file corruption on interrupted downloads, the file is first
     * written to a temporary {@code .download} file in the same directory,
     * then atomically moved to the final path. If the download is interrupted,
     * only the temporary file is left behind — the original file remains intact.
     */
    private void downloadFile(String url, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 200) {
            // Download to a temporary file first to avoid corrupting the target on failure
            Path tempFile = target.resolveSibling(target.getFileName().toString() + ".download");
            try (InputStream is = response.body()) {
                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            // Atomically move temp file to the final target
            try {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Fallback for filesystems that don't support atomic moves
                Files.copy(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            throw new RuntimeException("HTTP " + response.statusCode() + " downloading " + url);
        }
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    /**
     * Returns true if any file downloads failed during synchronization.
     */
    public boolean hasErrors() {
        return failedCount > 0;
    }
}
