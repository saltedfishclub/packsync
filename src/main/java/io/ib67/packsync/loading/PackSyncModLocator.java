package io.ib67.packsync.loading;

import io.ib67.packsync.PackSync;
import io.ib67.packsync.UpdateEvent;
import io.ib67.packsync.data.SyncConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static io.ib67.packsync.PackSync.log;

public class PackSyncModLocator implements IModFileCandidateLocator {
    private static final boolean CAN_SHOW_DIALOG = !GraphicsEnvironment.isHeadless() || Boolean.getBoolean("packsync.allowDialogs");
    private final Object initLock = new Object();
    private volatile PackSync packSync;

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        var currentPackSync = initializePackSync();
        if (currentPackSync == null) {
            return;
        }
        try (var list = Files.list(currentPackSync.managedModsDir())) {
            list.filter(Files::isRegularFile)
                    .forEach(path -> pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT.withLocator(this),
                            IncompatibleFileReporting.WARN_ALWAYS));
        } catch (IOException e) {
            log("Error occurred while attempting to scan mods");
            e.printStackTrace();
        }
    }

    private PackSync initializePackSync() {
        if (packSync != null) {
            return packSync;
        }
        synchronized (initLock) {
            if (packSync != null) {
                return packSync;
            }
            var gameDir = FMLPaths.GAMEDIR.get();
            var syncConfig = loadSyncConfig(gameDir);
            PackSyncGui gui = null;
            if (CAN_SHOW_DIALOG) {
                gui = new PackSyncGui();
                gui.setVisible(true);
            }
            var currentPackSync = new PackSync(gameDir, syncConfig, gui);
            try {
                Files.createDirectories(currentPackSync.managedModsDir());
                Files.createDirectories(currentPackSync.stateDir());
            } catch (Exception e) {
                log("PackSync will not load because an error occurred");
                e.printStackTrace();
                return null;
            }

            try {
                var newManifest = currentPackSync.performSync();
                if (newManifest != null) {
                    log("Update completed. Saving manifest cache...");
                    currentPackSync.saveLocalManifest(newManifest);
                }
            } catch (Exception e) {
                log("Error occurred during update");
                e.printStackTrace();
                if (gui != null) {
                    gui.accept(new UpdateEvent.SyncError(e.getMessage(), e));
                }
            }
            packSync = currentPackSync;
            return currentPackSync;
        }
    }

    private static SyncConfig loadSyncConfig(Path gameDir) {
        var configPath = gameDir.resolve("packsync.json");
        var config = new SyncConfig(new ArrayList<>());
        if (Files.exists(configPath)) {
            try (var reader = Files.newBufferedReader(configPath)) {
                config = PackSync.SERIALIZER.fromJson(reader, SyncConfig.class);
            } catch (IOException e) {
                log("Cannot parse config, falling back to noop configuration.");
            }
        } else {
            try {
                Files.writeString(configPath, PackSync.SERIALIZER.toJson(config));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return config;
    }

    @Override
    public String toString() {
        return "{packsync managed mods locator}";
    }
}
