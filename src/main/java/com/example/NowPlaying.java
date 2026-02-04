package com.example;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NowPlaying implements ModInitializer {

    // Mod ID 
    public static final String MOD_ID = "nowplaying";

    // Shared logger for the entire mod
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Common initialization (runs on both client and server)
        LOGGER.info("NowPlaying mod initialized");
    }
}
