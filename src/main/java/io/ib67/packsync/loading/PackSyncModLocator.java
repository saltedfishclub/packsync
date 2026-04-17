package io.ib67.packsync.loading;

import io.ib67.packsync.PackSync;
import io.ib67.packsync.data.CaddyFileObject;
import io.ib67.packsync.data.SyncConfig;
import io.ib67.packsync.data.VersionManifest;
import io.ib67.packsync.util.CompletableFutureRoller;
import io.ib67.packsync.util.BiGenerator;
import io.ib67.packsync.util.Proxies;
import net.minecraftforge.fml.StartupMessageManager;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModProvider;
import net.minecraftforge.forgespi.locating.IModLocator;

import javax.annotation.Nullable;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.net.http.HttpResponse.BodyHandlers.ofString;

//todo emit events, gui support
public class PackSyncModLocator extends AbstractJarFileModProvider implements IModLocator {
    private static final UnaryOperator<HttpRequest.Builder> IDENTITY = UnaryOperator.identity();
    private static final UnaryOperator<HttpRequest.Builder> ACCEPTS_JSON = t -> t.header("Accept", "application/json");
    private static final boolean CAN_SHOW_DIALOG = Boolean.getBoolean("packsync.allowDialogs") || GraphicsEnvironment.isHeadless();
    private Path gameDir;
    private Path packSyncStateDir;
    private Path managedModsDir;
    private SyncConfig syncConfig;

    @Override
    public String name() {
        return "packsync managed mods locator";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
        this.gameDir = FMLPaths.GAMEDIR.get();
        this.managedModsDir = gameDir.resolve("mods/packsync-managed");
        this.packSyncStateDir = gameDir.resolve(".packsync");

        try {
            Files.createDirectories(managedModsDir);
            Files.createDirectories(packSyncStateDir);
        } catch (Exception e) {
            log("Failed to create managed mods directory " + managedModsDir);
            e.printStackTrace();
        }

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
        this.syncConfig = config;
        try {
            var newManifest = performSync();
            saveLocalManifest(newManifest);
        } catch (Exception e) {
            log("Error occurred during update");
            e.printStackTrace();
        }
    }

    void saveLocalManifest(VersionManifest manifest) {
        var file = packSyncStateDir.resolve("version-cache.json");
        try {
            Files.writeString(file, PackSync.SERIALIZER.toJson(manifest));
        } catch (IOException e) {
            log("Failed to save local manifest cache: " + e.getMessage());
        }
    }

    @Nullable
    VersionManifest loadLocalManifest() {
        var file = packSyncStateDir.resolve("version-cache.json");
        if (Files.notExists(file)) {
            return null;
        }
        try (var reader = Files.newBufferedReader(file)) {
            return PackSync.SERIALIZER.fromJson(reader, VersionManifest.class);
        } catch (IOException e) {
            log("Cannot parse version-cache.json, treat as no local revision..");
            return null;
        }
    }

    <T> CompletableFuture<T> fetchAsync(HttpRequest req, HttpResponse.BodyHandler<T> bodyHandler) {
        return PackSync.HTTP_CLIENT.sendAsync(req, bodyHandler)
                .thenCompose(resp -> {
                    if (resp.statusCode() != 200)
                        return CompletableFuture.failedStage(new IOException("Expect status 200 from " + req.uri() + " but got " + resp.statusCode()));
                    return CompletableFuture.completedFuture(resp.body());
                });
    }

    <T> CompletableFuture<T> fetchFromMirrors(String relativePath, HttpResponse.BodyHandler<T> handler, UnaryOperator<HttpRequest.Builder> modifier) {
        if (syncConfig.mirrors().isEmpty()) {
            return CompletableFuture.failedFuture(new IOException("No mirrors available"));
        }
        var mirrors = syncConfig.mirrors().stream()
                .map(it -> modifier.apply(HttpRequest.newBuilder(it.resolve(relativePath))).build())
                .flatMap(it -> Stream.of(it, it, it)) // repeat as retry
                .toList();
        var firstTry = mirrors.get(0);
        return fetchAsync(firstTry, handler)
                .exceptionallyCompose(new CompletableFutureRoller<>(mirrors, it -> fetchAsync(it, handler)));
    }

    @SuppressWarnings("unchecked")
    BiGenerator<Path, CaddyFileObject> listFiles(Path relativePath) {
        return c -> {
            var response = fetchFromMirrors(relativePath.toString(), ofString(), ACCEPTS_JSON).join();
            var list = (List<CaddyFileObject>) PackSync.SERIALIZER.fromJson(response, CaddyFileObject.TYPE_OF_LIST.getType());
            for (CaddyFileObject caddyFileObject : list) {
                if (caddyFileObject.directory()) {
                    listFiles(relativePath.resolve(caddyFileObject.name())).accept(c);
                    continue;
                }
                c.accept(relativePath.resolve(caddyFileObject.name()), caddyFileObject);
            }
        };
    }

    VersionManifest performSync() throws Exception {
        var local = loadLocalManifest();
        var remote = fetchFromMirrors("version.json", ofString(), IDENTITY)
                .thenApply(str -> PackSync.SERIALIZER.fromJson(str, VersionManifest.class)).join();
        var shouldUpdate = local == null || !remote.version().equals(local.version());
        if (!shouldUpdate) {
            log("Local version matches remote, no sync needed.");
        }
        try {
            listFiles(Path.of("")).accept(this::attemptUpdateSingle);
        } catch (Exception e) {
            log("Failure occurred while attempting to update: " + e.getMessage());
            throw e;
        }
        log("Update successfully. Performing removal action...");
        for (var entry : remote.removalFileHashes().entrySet()) {
            var fileName = entry.getKey();
            var sha256sum = entry.getValue();
            if(sha256sum == null || fileName == null){
                continue;
            }
            var targetPath = gameDir.resolve(fileName);
            if (!targetPath.startsWith(gameDir)) {
                log("Skipping " + targetPath + " because it is out of gameDir");
                continue;
            }
            if (!Files.exists(targetPath)) {
                log("Skipping "+targetPath+" because it does not exist");
                continue;
            }
            if (sha256sum.equalsIgnoreCase(readSHA256Hex(targetPath))) {
                log("Removing " + targetPath + " as requested by remote manifest.");
                Files.deleteIfExists(targetPath);
            }
        }
        return remote;
    }

    private void attemptUpdateSingle(Path destination, CaddyFileObject caddyFileObject) throws IOException {
        // alias for mods
        if ("mods".equals(destination.getParent().toString())) {
            destination = managedModsDir.resolve(destination.getFileName());
        }
        var shouldUpdate = !Files.exists(destination);
        if (!shouldUpdate)
            // the file must exist to support this check, otherwise IOException is thrown.
            shouldUpdate = Files.getLastModifiedTime(gameDir.resolve(destination)).toInstant().isBefore(caddyFileObject.modificationTime());
        if (!shouldUpdate)
            shouldUpdate = Files.size(destination) != caddyFileObject.size();

        if (shouldUpdate) {
            log("Updating " + destination);
            fetchFromMirrors(destination.toString(), HttpResponse.BodyHandlers.ofFile(destination), IDENTITY).join();
        }
    }

    @Override
    public List<ModFileOrException> scanMods() {
        try (var list = Files.list(managedModsDir)) {
            return list.map(this::createMod)
                    .map(it -> it.file() == null ? it : new ModFileOrException(Proxies.wrapPrioritized(it.file()), it.ex()))
                    .toList();
        } catch (IOException e) {
            log("Error occurred while attempting to scan mods: ");
            // todo show dialog
            e.printStackTrace();
        }
        return List.of();
    }

    void log(String message) {
        StartupMessageManager.addModMessage("[PackSync] " + message);
    }

    // This method asserts the file exists.
    String readSHA256Hex(Path file) {
        try (var in = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            var hashBytes = digest.digest();
            var sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log("Failed to compute SHA-256 for " + file + ": " + e.getMessage());
            return "";
        }
    }
}
