package com.icybear.packsync.loading;

import com.icybear.packsync.sync.*;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.minecraftforge.fml.loading.progress.StartupMessageManager;
import org.slf4j.Logger;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Custom Forge mod locator that synchronizes mod files from a remote Caddy server
 * before Forge discovers them.
 * <p>
 * This runs during the earliest phase of Forge mod loading, via Java's ServiceLoader
 * mechanism. It:
 * <ol>
 *     <li>Reads {@code packsync.properties} for the server URL(s)</li>
 *     <li>Checks {@code .revision} for changes</li>
 *     <li>If changed, crawls the remote server and syncs all files</li>
 *     <li>Processes {@code .removal} to delete obsolete files</li>
 *     <li>Returns all {@code .jar} files from the managed mods directory for Forge to load</li>
 * </ol>
 * <p>
 * Multiple mirror URLs are supported. If a mirror fails (even after retries),
 * the next mirror in the list is tried automatically. The error dialog is only
 * shown when all mirrors have been exhausted.
 * <p>
 * If any errors occur during sync (e.g. HTTP 404, download failures), the local revision
 * is NOT updated and a dialog prompts the user to decide whether to continue launching.
 * <p>
 * Registered via {@code META-INF/services/net.minecraftforge.forgespi.locating.IModLocator}.
 */
public class PackSyncModLocator extends AbstractJarFileModLocator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MANAGED_MODS_DIR = ".packsync/managed-mods";
    private static final String SUFFIX = ".jar";

    private Path gameDir;
    private Path managedModsDir;
    private boolean syncPerformed = false;

    @Override
    public void initArguments(Map<String, ?> arguments) {
        this.gameDir = FMLPaths.GAMEDIR.get();
        this.managedModsDir = gameDir.resolve(MANAGED_MODS_DIR);

        try {
            Files.createDirectories(managedModsDir);
        } catch (Exception e) {
            LOGGER.error("[PackSync] Failed to create managed mods directory: {}", managedModsDir, e);
        }

        // Perform sync during init (runs before scanCandidates)
        performSync();
    }

    /**
     * Performs the full synchronization workflow with mirror failover.
     * <p>
     * Iterates through all configured mirror URLs in order. If a mirror fails
     * with an exception (network error, HTTP error, etc.), the next mirror is
     * tried automatically. The error dialog is only shown when all mirrors
     * have been exhausted.
     * <p>
     * If file synchronization completes but some individual files fail (partial
     * errors), the local revision cache is NOT updated so the next launch will
     * retry, and the user is prompted.
     */
    private void performSync() {
        StartupMessageManager.addModMessage("[PackSync] Initializing...");

        PackSyncConfig config = new PackSyncConfig(gameDir);
        if (!config.isEnabled()) {
            LOGGER.info("[PackSync] Sync is disabled or not configured.");
            StartupMessageManager.addModMessage("[PackSync] Sync disabled or not configured.");
            return;
        }

        List<String> mirrors = config.getMirrorUrls();
        LOGGER.info("[PackSync] Remote URL(s): {}", mirrors);
        StartupMessageManager.addModMessage("[PackSync] Checking for updates...");

        RevisionManager revisionManager = new RevisionManager(gameDir);
        Exception lastException = null;

        for (int mirrorIndex = 0; mirrorIndex < mirrors.size(); mirrorIndex++) {
            String baseUrl = mirrors.get(mirrorIndex);
            boolean isLastMirror = (mirrorIndex == mirrors.size() - 1);
            String mirrorLabel = mirrors.size() > 1
                    ? String.format(" (mirror %d/%d)", mirrorIndex + 1, mirrors.size())
                    : "";

            try {
                LOGGER.info("[PackSync] Trying mirror: {}{}", baseUrl, mirrorLabel);
                if (!mirrorLabel.isEmpty()) {
                    StartupMessageManager.addModMessage("[PackSync] Trying mirror " + (mirrorIndex + 1) + "/" + mirrors.size() + "...");
                }

                // Check revision
                String remoteRevision = revisionManager.fetchRemoteRevision(baseUrl);

                if (remoteRevision == null) {
                    StartupMessageManager.addModMessage("[PackSync] Cannot reach server. Skipping sync.");
                    return;
                }

                if (!revisionManager.needsUpdate(baseUrl)) {
                    StartupMessageManager.addModMessage("[PackSync] Already up to date (rev: " + remoteRevision + ")");
                    return;
                }

                // Crawl the remote file tree
                StartupMessageManager.addModMessage("[PackSync] Scanning remote files...");
                CaddyCrawler crawler = new CaddyCrawler(revisionManager.getHttpClient());
                List<CaddyCrawler.RemoteFileEntry> remoteFiles = crawler.crawl(baseUrl, "");
                LOGGER.info("[PackSync] Found {} remote files.", remoteFiles.size());
                StartupMessageManager.addModMessage("[PackSync] Found " + remoteFiles.size() + " remote files.");

                // Synchronize files
                FileSynchronizer synchronizer = new FileSynchronizer(revisionManager.getHttpClient());
                int updated = synchronizer.synchronize(baseUrl, gameDir, remoteFiles);

                // Process removals
                RemovalProcessor removalProcessor = new RemovalProcessor(revisionManager.getHttpClient());
                int removed = removalProcessor.processRemovals(baseUrl, gameDir);

                // Check if sync had errors
                boolean hasErrors = synchronizer.hasErrors();

                if (hasErrors) {
                    int failedCount = synchronizer.getFailedCount();
                    String errorSummary = String.format(
                            "[PackSync] Sync completed with errors. Updated %d, removed %d, failed %d files. (rev: %s)",
                            updated, removed, failedCount, remoteRevision);
                    StartupMessageManager.addModMessage(errorSummary);
                    LOGGER.warn(errorSummary);

                    // Do NOT save the local revision so the next launch will retry
                    LOGGER.warn("[PackSync] Local revision NOT updated due to {} sync error(s).", failedCount);

                    // Prompt the user
                    showSyncErrorDialog(failedCount, updated, removed);
                } else {
                    // All good — save the new revision
                    revisionManager.saveLocalRevision(remoteRevision);
                    syncPerformed = true;

                    String summary = String.format("[PackSync] Sync complete. Updated %d, removed %d files. (rev: %s)",
                            updated, removed, remoteRevision);
                    StartupMessageManager.addModMessage(summary);
                    LOGGER.info(summary);
                }

                // This mirror succeeded (or had partial errors handled above) — stop trying mirrors
                return;

            } catch (Exception e) {
                lastException = e;
                LOGGER.error("[PackSync] Mirror {} failed{}: {}", baseUrl, mirrorLabel, e.getMessage());

                if (!isLastMirror) {
                    String failoverMsg = String.format(
                            "[PackSync] Mirror %d/%d failed, trying next mirror...",
                            mirrorIndex + 1, mirrors.size());
                    StartupMessageManager.addModMessage(failoverMsg);
                    LOGGER.info(failoverMsg);
                }
            }
        }

        // All mirrors exhausted
        LOGGER.error("[PackSync] All {} mirror(s) failed", mirrors.size(), lastException);
        StartupMessageManager.addModMessage("[PackSync] ERROR: All mirrors failed - " + lastException.getMessage());
        showSyncExceptionDialog(lastException);
    }

    /**
     * Shows a dialog informing the user that some files failed to sync,
     * and asks whether to continue launching the game.
     */
    private void showSyncErrorDialog(int failedCount, int updatedCount, int removedCount) {
        String title = "PackSync - Update Incomplete";
        String message = String.format(
                "PackSync encountered errors during mod synchronization.\n\n"
                + "  Successfully updated: %d file(s)\n"
                + "  Successfully removed: %d file(s)\n"
                + "  Failed to sync: %d file(s)\n\n"
                + "The local revision has NOT been updated, so the sync will\n"
                + "be retried on the next launch.\n\n"
                + "Do you want to start the game anyway?\n"
                + "(Some mods may be missing or outdated)",
                updatedCount, removedCount, failedCount);

        promptContinueOrExit(title, message);
    }

    /**
     * Shows a dialog informing the user that sync failed with an exception,
     * and asks whether to continue launching the game.
     */
    private void showSyncExceptionDialog(Exception e) {
        String title = "PackSync - Sync Failed";
        String message = String.format(
                "PackSync failed to synchronize mods from the server.\n\n"
                + "Error: %s\n\n"
                + "The local revision has NOT been updated, so the sync will\n"
                + "be retried on the next launch.\n\n"
                + "Do you want to start the game anyway?\n"
                + "(Mods may be missing or outdated)",
                e.getMessage());

        promptContinueOrExit(title, message);
    }

    /**
     * Displays a Yes/No confirmation dialog. If the user selects "No" (or closes
     * the dialog), the game process is terminated.
     */
    private void promptContinueOrExit(String title, String message) {
        try {
            // Ensure we're on the EDT for Swing
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to default L&F
        }

        LOGGER.info("[PackSync] Prompting user: {}", title);

        int choice = JOptionPane.showConfirmDialog(
                null,
                message,
                title,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (choice != JOptionPane.YES_OPTION) {
            LOGGER.info("[PackSync] User chose not to continue. Exiting.");
            System.exit(1);
        }

        LOGGER.info("[PackSync] User chose to continue despite sync errors.");
    }

    @Override
    public Stream<Path> scanCandidates() {
        LOGGER.debug("[PackSync] Scanning managed mods directory: {}", managedModsDir);
        if (!Files.isDirectory(managedModsDir)) {
            return Stream.empty();
        }

        try {
            return Files.list(managedModsDir)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(SUFFIX))
                    .peek(p -> LOGGER.debug("[PackSync] Found managed mod: {}", p.getFileName()));
        } catch (Exception e) {
            LOGGER.error("[PackSync] Failed to list managed mods", e);
            return Stream.empty();
        }
    }

    @Override
    public String name() {
        return "packsync managed mods";
    }

    @Override
    public String toString() {
        return "{PackSync mod locator at " + managedModsDir + "}";
    }
}
