package io.ib67.packsync.loading;

import io.ib67.packsync.data.VersionManifest;

import java.net.URI;
import java.nio.file.Path;
import java.util.function.Consumer;

public sealed interface UpdateEvent {
    record StartUpdateCheck() implements UpdateEvent {}

    record FetchManifest(VersionManifest manifest) implements UpdateEvent {};

    record AboutToDownload(URI url) implements UpdateEvent {}

    record FileDownloaded(URI url, Path destination) implements UpdateEvent {}

    record UpdateFinished() implements UpdateEvent {}

    interface Listener extends Consumer<UpdateEvent> {}
}
