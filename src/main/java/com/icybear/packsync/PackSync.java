package com.icybear.packsync;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * PackSync - Modpack synchronization from a remote Caddy file server.
 * <p>
 * The heavy lifting (file sync, mod discovery) is done in {@code PackSyncModLocator}
 * which runs during early boot via ServiceLoader. This @Mod class is a lightweight
 * placeholder that provides Forge with a valid mod entry.
 */
@Mod(PackSync.MODID)
public class PackSync {
    public static final String MODID = "packsync";
    private static final Logger LOGGER = LogUtils.getLogger();

    public PackSync(FMLJavaModLoadingContext context) {
        context.getModEventBus().addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[PackSync] Mod initialized. Sync was handled during early boot by PackSyncModLocator.");
    }
}
