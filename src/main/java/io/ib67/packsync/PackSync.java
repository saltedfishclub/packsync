package io.ib67.packsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.ib67.packsync.data.CaddyFileObject;
import io.ib67.packsync.data.SyncConfig;
import io.ib67.packsync.data.VersionManifest;
import io.ib67.packsync.data.adapter.InstantAdapter;
import io.ib67.packsync.util.BiGenerator;
import io.ib67.packsync.util.CompletableFutureRoller;
import net.minecraftforge.fml.StartupMessageManager;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.net.http.HttpResponse.BodyHandlers.ofString;

public class PackSync {
    public static final Gson SERIALIZER = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .create();
    public static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final UnaryOperator<HttpRequest.Builder> IDENTITY = UnaryOperator.identity();
    private static final UnaryOperator<HttpRequest.Builder> ACCEPTS_JSON = t -> t.header("Accept", "application/json");

    protected final Path gameDir;
    protected final SyncConfig syncConfig;
    protected final UpdateEvent.Listener listener;

    public PackSync(Path gameDir, SyncConfig syncConfig, UpdateEvent.Listener listener) {
        this.gameDir = Objects.requireNonNull(gameDir);
        this.syncConfig = Objects.requireNonNull(syncConfig);
        this.listener = listener == null ? e -> {} : listener;
    }

    public Path managedModsDir() {
        return gameDir.resolve("mods/packsync-managed");
    }

    public Path stateDir() {
        return gameDir.resolve(".packsync");
    }

    public void saveLocalManifest(VersionManifest manifest) {
        var file = stateDir().resolve("version-cache.json");
        try {
            Files.writeString(file, PackSync.SERIALIZER.toJson(manifest));
        } catch (IOException e) {
            log("Failed to save local manifest cache: " + e.getMessage());
        }
    }

    @Nullable
    VersionManifest loadLocalManifest() {
        var file = stateDir().resolve("version-cache.json");
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
                        return CompletableFuture.failedStage(new IOException(
                                "Expect status 200 from " + req.uri() + " but got " + resp.statusCode()));
                    return CompletableFuture.completedFuture(resp.body());
                });
    }

    <T> CompletableFuture<T> fetchFromMirrors(String relativePath, HttpResponse.BodyHandler<T> handler,
            UnaryOperator<HttpRequest.Builder> modifier) {
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
            var list = (List<CaddyFileObject>) PackSync.SERIALIZER.fromJson(response,
                    CaddyFileObject.TYPE_OF_LIST.getType());
            for (CaddyFileObject caddyFileObject : list) {
                if (caddyFileObject.directory()) {
                    listFiles(relativePath.resolve(caddyFileObject.name())).accept(c);
                    continue;
                }
                c.accept(relativePath.resolve(caddyFileObject.name()), caddyFileObject);
            }
        };
    }

    public VersionManifest performSync() throws Exception {
        listener.accept(new UpdateEvent.StartUpdateCheck());
        var local = loadLocalManifest();
        var remote = fetchFromMirrors("version.json", ofString(), IDENTITY)
                .thenApply(str -> PackSync.SERIALIZER.fromJson(str, VersionManifest.class)).join();
        listener.accept(new UpdateEvent.FetchManifest(remote));
        var shouldUpdate = local == null || !remote.version().equals(local.version());
        if (!shouldUpdate) {
            log("Local version matches remote, no sync needed.");
            listener.accept(new UpdateEvent.UpdateFinished(false));
            return null;
        }
        try {
            var tasks = new HashMap<Path, CaddyFileObject>();
            listFiles(Path.of("")).accept(tasks::put);
            listener.accept(new UpdateEvent.FetchedTaskList(tasks));
            for (var entry : tasks.entrySet()) {
                attemptUpdateSingle(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            log("Failure occurred while attempting to update: " + e.getMessage());
            listener.accept(new UpdateEvent.SyncError(e.getMessage(), e));
            throw e;
        }
        log("Update successfully. Performing removal action...");
        for (var entry : remote.removalFileHashes().entrySet()) {
            var fileName = entry.getKey();
            var sha256sum = entry.getValue();
            if (sha256sum == null || fileName == null) {
                continue;
            }
            var targetPath = gameDir.resolve(fileName);
            if (!targetPath.startsWith(gameDir)) {
                log("Skipping " + targetPath + " because it is out of gameDir");
                continue;
            }
            if (!Files.exists(targetPath)) {
                log("Skipping " + targetPath + " because it does not exist");
                continue;
            }
            if (sha256sum.equalsIgnoreCase(readSHA256Hex(targetPath))) {
                log("Removing " + targetPath + " as requested by remote manifest.");
                listener.accept(new UpdateEvent.FileRemoved(targetPath));
                Files.deleteIfExists(targetPath);
            }
        }
        return remote;
    }

    private void attemptUpdateSingle(Path destination, CaddyFileObject caddyFileObject) throws IOException {
        var shouldUpdate = !Files.exists(destination);
        if (!shouldUpdate)
            // the file must exist to support this check, otherwise IOException is thrown.
            shouldUpdate = Files.getLastModifiedTime(gameDir.resolve(destination)).toInstant()
                    .isBefore(caddyFileObject.modificationTime());
        if (!shouldUpdate)
            shouldUpdate = Files.size(destination) != caddyFileObject.size();

        if (shouldUpdate) {
            log("Updating " + destination);
            listener.accept(new UpdateEvent.AboutToDownload(destination));
            fetchFromMirrors(destination.toString(), HttpResponse.BodyHandlers.ofFile(destination), IDENTITY)
                    .thenAccept(p -> listener.accept(new UpdateEvent.FileDownloaded(true, p)))
                    .exceptionally(t -> {
                        listener.accept(new UpdateEvent.FileDownloaded(false, destination));
                        return null;
                    }).join();
        }
    }

    public static void log(String message) {
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
