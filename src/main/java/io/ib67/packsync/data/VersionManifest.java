package io.ib67.packsync.data;

import com.google.gson.JsonSyntaxException;
import io.ib67.packsync.PackSync;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public record VersionManifest(
        String version,
        Map<String, String> removalFileHashes
) {
    public VersionManifest {
        Objects.requireNonNull(version, "version cannot be null");
        if (removalFileHashes == null) {
            removalFileHashes = new HashMap<>();
        }
    }
    public static VersionManifest fetch(URI baseUri) throws IOException, InterruptedException {
        var defaultHttpClient = PackSync.HTTP_CLIENT;
        var revision = baseUri.resolve("version.json");
        var revisionResp = defaultHttpClient.send(HttpRequest.newBuilder(revision).build(), HttpResponse.BodyHandlers.ofString());
        if(revisionResp.statusCode() != 200){
            throw new IOException("failed to fetch version from "+revisionResp);
        }
        try {
            return PackSync.SERIALIZER.fromJson(revisionResp.body(),  VersionManifest.class);
        } catch (JsonSyntaxException e) {
            throw new IOException("Malformed JSON from remote: "+ revision,e);
        }
    }
}
