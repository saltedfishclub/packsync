package io.ib67.packsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import io.ib67.packsync.data.adapter.InstantAdapter;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.net.http.HttpClient;
import java.time.Instant;

@Mod(PackSync.MODID)
public class PackSync {
    public static final String MODID = "packsync";
    public static final Gson SERIALIZER = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .create();
    public static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    public PackSync(FMLJavaModLoadingContext context) {
    }
}
