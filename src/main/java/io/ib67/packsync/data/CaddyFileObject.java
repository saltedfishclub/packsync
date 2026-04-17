package io.ib67.packsync.data;

import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public record CaddyFileObject(
        String name,
        long size,
        String url,
        @SerializedName("mod_time")
        Instant modificationTime,
        @SerializedName("is_dir")
        boolean directory,
        @SerializedName("is_symlink")
        boolean symlink
) {
        public static final TypeToken<List<CaddyFileObject>> TYPE_OF_LIST = new TypeToken<>(){};
}
