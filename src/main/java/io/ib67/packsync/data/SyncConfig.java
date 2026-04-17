package io.ib67.packsync.data;

import java.net.URI;
import java.net.URL;
import java.util.List;

public record SyncConfig(
        List<URI> mirrors
) {
}
