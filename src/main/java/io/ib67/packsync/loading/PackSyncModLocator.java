package io.ib67.packsync.loading;

import io.ib67.packsync.PackSync;
import io.ib67.packsync.UpdateEvent;
import io.ib67.packsync.data.SyncConfig;
import io.ib67.packsync.util.Proxies;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModProvider;
import net.minecraftforge.forgespi.locating.IModLocator;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.ib67.packsync.PackSync.log;

public class PackSyncModLocator extends AbstractJarFileModProvider implements IModLocator {
    private static final boolean CAN_SHOW_DIALOG = !GraphicsEnvironment.isHeadless() || Boolean.getBoolean("packsync.allowDialogs");
    private PackSync packSync;

    @Override
    public String name() {
        return "packsync managed mods locator";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
        var gameDir = FMLPaths.GAMEDIR.get();
        var syncConfig = loadSyncConfig(gameDir);
        PackSyncGui gui = null;
        if (CAN_SHOW_DIALOG) {
            gui = new PackSyncGui();
            gui.setVisible(true);
        }
        packSync = new PackSync(gameDir, syncConfig, gui);
        try {
            Files.createDirectories(packSync.managedModsDir());
            Files.createDirectories(packSync.stateDir());
        } catch (Exception e) {
            log("PackSync will not load because an error occurred");
            e.printStackTrace();
            return;
        }

        try {
            var newManifest = packSync.performSync();
            if (newManifest == null) {
                return;
            }
            log("Update completed. Saving manifest cache...");
            packSync.saveLocalManifest(newManifest);
        } catch (Exception e) {
            log("Error occurred during update");
            e.printStackTrace();
            if (gui != null) gui.accept(new UpdateEvent.SyncError(e.getMessage(), e));
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
    public List<ModFileOrException> scanMods() {
        try (var list = Files.list(packSync.managedModsDir())) {
            return list.map(this::createMod)
                    .map(it -> it.file() == null ? it : new ModFileOrException(Proxies.wrapPrioritized(it.file()), it.ex()))
                    .toList();
        } catch (IOException e) {
            log("Error occurred while attempting to scan mods: ");
            e.printStackTrace();
        }
        return List.of();
    }
}
