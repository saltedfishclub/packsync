package io.ib67.packsync;

import io.ib67.packsync.data.CaddyFileObject;
import io.ib67.packsync.data.VersionManifest;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

public sealed interface UpdateEvent {
    record StartUpdateCheck() implements UpdateEvent {}

    record FetchManifest(VersionManifest manifest) implements UpdateEvent {}

    record FetchedTaskList(Map<Path, CaddyFileObject> tasks) implements UpdateEvent {}

    record AboutToDownload(Path destination) implements UpdateEvent {}

    record FileDownloaded(boolean success, Path destination) implements UpdateEvent {}

    record FileRemoved(Path file) implements UpdateEvent {}

    record UpdateFinished(boolean shouldUpdate) implements UpdateEvent {}

    record SyncError(String message, Throwable cause) implements UpdateEvent {}

    interface Listener extends Consumer<UpdateEvent> {}
}
