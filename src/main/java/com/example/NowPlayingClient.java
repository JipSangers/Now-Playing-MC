package com.example;

import com.google.gson.Gson;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.gui.registry.GuiRegistry;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

public class NowPlayingClient implements ClientModInitializer, ModMenuApi {

    // --- Config ---
    public static NowPlayingConfig config;

    // --- Companion process ---
    private static volatile Process csharpProcess;

    // --- Networking constants ---
    private static final String BASE_URL = "http://localhost:58888";
    private static final String INFO_ENDPOINT = BASE_URL + "/media_info";
    private static final String IMAGE_ENDPOINT = BASE_URL + "/media_image.jpg";

    private static final int CONNECT_TIMEOUT_MS = 1000;
    private static final int READ_TIMEOUT_MS = 1000;

    // --- Texture ---
    private static final Identifier NOW_PLAYING_IMAGE_ID = Identifier.of("nowplaying", "media");

    // --- JSON ---
    private static final Gson GSON = new Gson();

    private static class MediaInfo {
        String title;
        String artist;
        String app;
        String status;
        String position;
        String start;
        String end;
    }

    // --- Render/State snapshot (single source of truth) ---
    private static final class Snapshot {
        final String title;
        final String artist;
        final boolean isSpotify;
        final boolean isMediaActive;
        final boolean isPlaying;

        final double targetProgress;     // 0..1
        final double targetPositionSec;  // seconds
        final double targetEndSec;       // seconds

        final boolean imageLoaded;
        final int coverTexW;
        final int coverTexH;

        Snapshot(
                String title,
                String artist,
                boolean isSpotify,
                boolean isMediaActive,
                boolean isPlaying,
                double targetProgress,
                double targetPositionSec,
                double targetEndSec,
                boolean imageLoaded,
                int coverTexW,
                int coverTexH
        ) {
            this.title = title;
            this.artist = artist;
            this.isSpotify = isSpotify;
            this.isMediaActive = isMediaActive;
            this.isPlaying = isPlaying;
            this.targetProgress = targetProgress;
            this.targetPositionSec = targetPositionSec;
            this.targetEndSec = targetEndSec;
            this.imageLoaded = imageLoaded;
            this.coverTexW = coverTexW;
            this.coverTexH = coverTexH;
        }

        static Snapshot loading() {
            return new Snapshot(
                    "Loading Now Playing...",
                    "Loading Artist...",
                    false,
                    false,
                    false,
                    0.0,
                    0.0,
                    0.0,
                    false,
                    0,
                    0
            );
        }

        Snapshot withImage(boolean loaded, int w, int h) {
            return new Snapshot(
                    title, artist, isSpotify, isMediaActive, isPlaying,
                    targetProgress, targetPositionSec, targetEndSec,
                    loaded, w, h
            );
        }

        Snapshot withTextAndPlayback(
                String title,
                String artist,
                boolean isSpotify,
                boolean isMediaActive,
                boolean isPlaying,
                double targetProgress,
                double targetPositionSec,
                double targetEndSec
        ) {
            return new Snapshot(
                    title, artist, isSpotify, isMediaActive, isPlaying,
                    targetProgress, targetPositionSec, targetEndSec,
                    imageLoaded, coverTexW, coverTexH
            );
        }
    }

    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>(Snapshot.loading());

    // --- Polling scheduler ---
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static ScheduledExecutorService scheduler;

    // --- Image change detection ---
    private static final AtomicLong lastImageCrc = new AtomicLong(0L);
    private static final AtomicLong lastImageFetchNanos = new AtomicLong(0L);
    private static final long IMAGE_FETCH_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(5);

    // --- Cached text/app to decide if we should fetch image more eagerly ---
    private static volatile String lastTitleRaw = "";
    private static volatile String lastArtistRaw = "";
    private static volatile boolean lastIsSpotify = false;

    // --- Smoothing (render thread) ---
    private static volatile double currentProgress = 0.0;
    private static volatile double currentPositionSec = 0.0;
    private static volatile double currentEndSec = 0.0;

    private static volatile long lastRenderUpdateNanos = System.nanoTime();
    private static final double PROGRESS_SMOOTHING_FACTOR = 0.15;
    private static final double TIME_SMOOTHING_FACTOR = 0.10;

    @Override
    @Environment(EnvType.CLIENT)
    public void onInitializeClient() {
        // --- AutoConfig ---
        AutoConfig.register(NowPlayingConfig.class, JanksonConfigSerializer::new);
        config = AutoConfig.getConfigHolder(NowPlayingConfig.class).getConfig();

        GuiRegistry registry = AutoConfig.getGuiRegistry(NowPlayingConfig.class);
        registry.registerPredicateProvider(
                (i18n, field, cfg, defaults, guiRegistry) ->
                        new CustomSideGuiProvider().get(i18n, field, cfg, defaults, guiRegistry),
                field -> field.getType().equals(NowPlayingConfig.Side.class)
        );

        // --- Ensure companion is ready ---
        if (!NowPlayingFileManager.ensureExecutableReady()) {
            System.err.println("[NowPlayingMod] Failed to prepare C# executable. Mod functionality might be limited.");
            SNAPSHOT.set(new Snapshot(
                    "Error: C# server not found/extracted.",
                    "Check logs for details.",
                    false,
                    false,
                    false,
                    0.0, 0.0, 0.0,
                    false, 0, 0
            ));
            return;
        }

        // --- Start companion and polling ---
        launchCSharpScript();
        startPolling();

        // Client stop: stop polling + kill companion + free texture
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdownEverything());

        // If you use singleplayer integrated server, you may want this too
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            System.out.println("[NowPlayingMod] Integrated server stopped. Stopping C# server and polling.");
            shutdownEverything();
        });

        // --- HUD rendering ---
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            Snapshot s = SNAPSHOT.get();
            if (!s.isMediaActive) return;

            // Smooth timing
            long now = System.nanoTime();
            double dt = (now - lastRenderUpdateNanos) / 1_000_000_000.0;
            lastRenderUpdateNanos = now;

            // Clamp to avoid massive jumps after a freeze
            double smoothFactor = Math.min(dt * 60.0, 5.0);

            // Smooth progress
            currentProgress += (s.targetProgress - currentProgress) * PROGRESS_SMOOTHING_FACTOR * smoothFactor;
            currentProgress = clamp01(currentProgress);

            // Smooth time (only advance locally when playing)
            if (isDisplayableMedia(s)) {
                if (s.isPlaying) {
                    currentPositionSec += dt;
                }
                currentPositionSec += (s.targetPositionSec - currentPositionSec) * TIME_SMOOTHING_FACTOR * smoothFactor;
                currentPositionSec = Math.min(currentPositionSec, s.targetEndSec);

                currentEndSec += (s.targetEndSec - currentEndSec) * TIME_SMOOTHING_FACTOR * smoothFactor;
            } else {
                currentPositionSec += (0.0 - currentPositionSec) * TIME_SMOOTHING_FACTOR * smoothFactor;
                currentEndSec += (0.0 - currentEndSec) * TIME_SMOOTHING_FACTOR * smoothFactor;
            }

            // Layout constants
            int textPadding = 6;
            int lineHeight = 10;

            int baseCoverSize = 32;
            int maxCoverSize = 64;

            int barHeight = 2;
            int barPadding = 2;
            int imageTextSpacing = 10;
            int timelineGap = 4;
            int minTimelineWidth = 80;

            Text mediaTitle = Text.literal(s.title);
            Text artistName = Text.literal(s.artist);
            int mediaTitleWidth = client.textRenderer.getWidth(mediaTitle);
            int artistNameWidth = client.textRenderer.getWidth(artistName);

            int textBlockHeight = 0;
            if (config.showMediaTitle) textBlockHeight += lineHeight;
            if (config.showArtistName) textBlockHeight += lineHeight;

            int contentHeight = textBlockHeight;

            if (config.showTimeline) {
                if (textBlockHeight > 0) contentHeight += timelineGap;
                contentHeight += barHeight + barPadding + lineHeight;
            }

            int coverSize = (s.imageLoaded && config.showCoverArt) ? Math.max(baseCoverSize, contentHeight) : 0;
            coverSize = Math.min(coverSize, maxCoverSize);

            int unifiedContentHeight = Math.max(contentHeight, coverSize);
            int panelHeight = unifiedContentHeight + (textPadding * 2);

            int textBlockWidth = 0;
            if (config.showMediaTitle) textBlockWidth = Math.max(textBlockWidth, mediaTitleWidth);
            if (config.showArtistName) textBlockWidth = Math.max(textBlockWidth, artistNameWidth);
            if (config.showTimeline && textBlockWidth < minTimelineWidth) textBlockWidth = minTimelineWidth;

            int panelWidth = 0;
            if (textBlockWidth > 0) panelWidth = textBlockWidth;

            if (s.imageLoaded && config.showCoverArt) {
                panelWidth += coverSize;
                if (textBlockWidth > 0) panelWidth += imageTextSpacing;
            }
            panelWidth += (textPadding * 2);

            if (panelWidth < 100 && !(s.imageLoaded && config.showCoverArt && textBlockWidth == 0)) {
                panelWidth = 100;
            } else if (textBlockWidth == 0 && s.imageLoaded && config.showCoverArt) {
                panelWidth = coverSize + (textPadding * 2);
            }

            int screenWidth = client.getWindow().getScaledWidth();
            int screenHeight = client.getWindow().getScaledHeight();

            int panelX = (config.sidePosition == NowPlayingConfig.Side.LEFT) ? 0 : screenWidth - panelWidth;
            int panelY = (int) ((screenHeight - panelHeight) * (config.yPosition / 100.0));

            int contentStartY = panelY + (panelHeight - unifiedContentHeight) / 2;

            // Background
            if (shouldDrawPanel()) {
                int bgColor = ((int) (config.backgroundOpacity * 2.55) << 24) | 0x000000;
                drawContext.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, bgColor);
            }

            int imageStartX = panelX + textPadding;
            int textStartX = panelX + textPadding;
            if (s.imageLoaded && config.showCoverArt) {
                textStartX += coverSize + imageTextSpacing;
            }

            // Cover art (center-cropped)
            if (s.imageLoaded && config.showCoverArt && s.coverTexW > 0 && s.coverTexH > 0) {
                int srcSize = Math.min(s.coverTexW, s.coverTexH);
                int srcU = (s.coverTexW - srcSize) / 2;
                int srcV = (s.coverTexH - srcSize) / 2;

                drawContext.drawTexture(
                        RenderPipelines.GUI_TEXTURED,
                        NOW_PLAYING_IMAGE_ID,
                        imageStartX,
                        contentStartY,
                        srcU, srcV,
                        coverSize, coverSize,
                        srcSize, srcSize,
                        s.coverTexW, s.coverTexH
                );
            }

            // Text
            int currentY = contentStartY;

            if (config.showMediaTitle) {
                drawContext.drawTextWithShadow(client.textRenderer, mediaTitle, textStartX, currentY, 0xFFFFFFFF);
                currentY += lineHeight;
            }
            if (config.showArtistName) {
                drawContext.drawTextWithShadow(client.textRenderer, artistName, textStartX, currentY, 0xFFAAAAAA);
                currentY += lineHeight;
            }

            // Timeline
            if (config.showTimeline && s.targetEndSec > 0) {
                if (config.showMediaTitle || config.showArtistName) currentY += timelineGap;

                int barY = currentY;
                int barX = textStartX;

                if (config.showPlayStatusIcon) {
                    String playPauseSymbol = s.isPlaying ? "❚❚" : "▶";
                    int iconWidth = client.textRenderer.getWidth(playPauseSymbol);
                    int iconHeight = client.textRenderer.fontHeight;
                    int iconY = barY - (iconHeight / 2) + (barHeight / 2);

                    drawContext.drawTextWithShadow(
                            client.textRenderer,
                            Text.literal(playPauseSymbol),
                            textStartX,
                            iconY,
                            0xFFFFFFFF
                    );

                    barX += iconWidth + 6;
                }

                int barWidth = (panelX + panelWidth - textPadding) - barX;

                drawContext.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF222222);
                drawContext.fill(barX, barY, barX + (int) (barWidth * currentProgress), barY + barHeight, 0xFFD3D3D3);

                currentY += barHeight + barPadding;

                String currentPosString = parseSecondsToTimeStamp(currentPositionSec);
                String endPosString = parseSecondsToTimeStamp(currentEndSec);

                drawContext.drawTextWithShadow(client.textRenderer, Text.literal(currentPosString), barX, currentY, 0xFFAAAAAA);

                int endPosWidth = client.textRenderer.getWidth(endPosString);
                int endPosTextX = barX + barWidth - endPosWidth;

                drawContext.drawTextWithShadow(client.textRenderer, Text.literal(endPosString), endPosTextX, currentY, 0xFFAAAAAA);
            }
        });
    }

    // -------------------------
    // Polling + Image handling
    // -------------------------

    private static void startPolling() {
        if (RUNNING.getAndSet(true)) return;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NowPlaying-Poller");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                pollOnce();
            } catch (Throwable t) {
                // Don’t crash the scheduler thread; show a stable error state.
                System.err.println("[NowPlayingMod] Poll error: " + t.getMessage());
                setErrorSnapshot("An error occurred.", "");
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    private static void pollOnce() {
        // Ensure server running
        if (csharpProcess == null || !csharpProcess.isAlive()) {
            SNAPSHOT.set(new Snapshot(
                    "C# Server Not Running",
                    "Please check logs.",
                    false,
                    false,
                    false,
                    0.0, 0.0, 0.0,
                    false, 0, 0
            ));

            // Try to relaunch (lightly)
            launchCSharpScript();
            return;
        }

        MediaInfo info = fetchMediaInfo();
        if (info == null) {
            clearSnapshotAndTexture();
            return;
        }

        String title = ellipsizeText(info.title != null ? info.title : "");
        String artist = ellipsizeText(info.artist != null ? info.artist : "");

        String appName = info.app != null ? info.app : "";
        boolean isSpotify = appName.toLowerCase(Locale.ROOT).contains("spotify");

        String status = info.status != null ? info.status : "";
        boolean isPlaying = "Playing".equalsIgnoreCase(status);

        boolean isActiveStatus = "Playing".equalsIgnoreCase(status) || "Paused".equalsIgnoreCase(status);
        boolean isMediaActive =
                isActiveStatus
                        && !title.isEmpty()
                        && !"(none)".equalsIgnoreCase(title)
                        && !"(unknown)".equalsIgnoreCase(title);

        double positionSec = parseTimeToSeconds(info.position);
        double startSec = parseTimeToSeconds(info.start);
        double endSec = parseTimeToSeconds(info.end);

        double targetProgress;
        double targetPositionSec;
        double targetEndSec;

        if (endSec > startSec) {
            targetPositionSec = positionSec;
            targetEndSec = endSec;

            targetProgress = (positionSec - startSec) / (endSec - startSec);
            targetProgress = clamp01(targetProgress);
        } else {
            targetProgress = 0.0;
            targetPositionSec = 0.0;
            targetEndSec = 0.0;
        }

        Snapshot prev = SNAPSHOT.get();

        // Update snapshot (text + playback targets)
        Snapshot updated = prev.withTextAndPlayback(
                title,
                artist,
                isSpotify,
                isMediaActive,
                isPlaying,
                targetProgress,
                targetPositionSec,
                targetEndSec
        );
        SNAPSHOT.set(updated);

        // Decide if we should fetch image
        boolean textChanged = !Objects.equals(title, lastTitleRaw) || !Objects.equals(artist, lastArtistRaw);
        boolean appChanged = (isSpotify != lastIsSpotify);

        lastTitleRaw = title;
        lastArtistRaw = artist;
        lastIsSpotify = isSpotify;

        boolean shouldTryImage =
                isMediaActive
                        && config != null
                        && config.showCoverArt
                        && (textChanged || appChanged || !prev.imageLoaded || imageCooldownPassed());

        if (shouldTryImage) {
            fetchAndMaybeUpdateTexture(textChanged || appChanged);
        }

        // If media not active, clear texture (optional)
        if (!isMediaActive) {
            clearTextureOnly();
        }
    }

    private static boolean imageCooldownPassed() {
        long last = lastImageFetchNanos.get();
        long now = System.nanoTime();
        return (now - last) >= IMAGE_FETCH_COOLDOWN_NANOS;
    }

    private static void fetchAndMaybeUpdateTexture(boolean eager) {
        lastImageFetchNanos.set(System.nanoTime());

        byte[] bytes = fetchBytes(IMAGE_ENDPOINT);
        if (bytes == null || bytes.length == 0) {
            clearTextureOnly();
            return;
        }

        long crc = crc32(bytes);
        long prevCrc = lastImageCrc.get();

        // If not eager and same image, do nothing
        if (!eager && prevCrc == crc) {
            return;
        }

        // Decode image
        NativeImage img;
        try {
            img = NativeImage.read(bytes);
        } catch (Exception e) {
            System.err.println("[NowPlayingMod] Failed to decode album art: " + e.getMessage());
            clearTextureOnly();
            return;
        }

        int w = img.getWidth();
        int h = img.getHeight();

        // Register texture on MC thread
        NativeImage finalImg = img;
        MinecraftClient.getInstance().execute(() -> {
            try {
                MinecraftClient.getInstance().getTextureManager().destroyTexture(NOW_PLAYING_IMAGE_ID);
                NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "nowplaying_media", finalImg);
                MinecraftClient.getInstance().getTextureManager().registerTexture(NOW_PLAYING_IMAGE_ID, texture);

                lastImageCrc.set(crc);

                // Update snapshot with image info
                Snapshot s = SNAPSHOT.get();
                SNAPSHOT.set(s.withImage(true, w, h));
            } catch (Exception ex) {
                System.err.println("[NowPlayingMod] Failed to register texture: " + ex.getMessage());

                // Make sure we free the NativeImage if registration failed
                try { finalImg.close(); } catch (Exception ignored) {}

                Snapshot s = SNAPSHOT.get();
                SNAPSHOT.set(s.withImage(false, 0, 0));
            }
        });
    }

    private static MediaInfo fetchMediaInfo() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(INFO_ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int status = conn.getResponseCode();
            if (status != 200) return null;

            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) content.append(line);
                return GSON.fromJson(content.toString(), MediaInfo.class);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] fetchBytes(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != 200) return null;

            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void clearSnapshotAndTexture() {
        SNAPSHOT.set(new Snapshot(
                "",
                "",
                false,
                false,
                false,
                0.0, 0.0, 0.0,
                false, 0, 0
        ));
        clearTextureOnly();
    }

    private static void clearTextureOnly() {
        Snapshot s = SNAPSHOT.get();
        if (s.imageLoaded || s.coverTexW != 0 || s.coverTexH != 0) {
            SNAPSHOT.set(s.withImage(false, 0, 0));
        }
        MinecraftClient.getInstance().execute(() ->
                MinecraftClient.getInstance().getTextureManager().destroyTexture(NOW_PLAYING_IMAGE_ID)
        );
    }

    private static void setErrorSnapshot(String title, String artist) {
        SNAPSHOT.set(new Snapshot(
                title,
                artist,
                false,
                false,
                false,
                0.0, 0.0, 0.0,
                false, 0, 0
        ));
        clearTextureOnly();
    }

    // -------------------------
    // Process management
    // -------------------------

    private static void launchCSharpScript() {
        if (csharpProcess != null && csharpProcess.isAlive()) {
            return;
        }

        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            Path modConfigDir = configDir.resolve("nowplaying");
            Path csharpExeFolder = modConfigDir.resolve("nowPlayingServer");
            File csharpExeFile = csharpExeFolder.resolve("ConsoleApp6.exe").toFile();

            if (!csharpExeFile.exists()) {
                System.err.println("[NowPlayingMod ERROR] C# server executable not found at: " + csharpExeFile.getAbsolutePath());
                System.err.println("[NowPlayingMod INFO] Ensure the published server folder exists inside: " + modConfigDir.toAbsolutePath());
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(csharpExeFile.getAbsolutePath());
            pb.directory(csharpExeFile.getParentFile());
            pb.redirectErrorStream(true);

            csharpProcess = pb.start();
            System.out.println("[NowPlayingMod] C# server launched. PID: " + csharpProcess.pid());

            Thread logThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(csharpProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[C# Server] " + line);
                    }
                } catch (Exception e) {
                    System.err.println("[NowPlayingMod] Error reading C# server output: " + e.getMessage());
                }
            }, "NowPlaying-CSharp-Log");
            logThread.setDaemon(true);
            logThread.start();

            Runtime.getRuntime().addShutdownHook(new Thread(NowPlayingClient::shutdownEverything, "NowPlaying-ShutdownHook"));
        } catch (Exception e) {
            System.err.println("[NowPlayingMod ERROR] Failed to launch C# server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void stopCSharpScript() {
        Process p = csharpProcess;
        if (p == null) return;

        try {
            if (p.isAlive()) {
                p.destroy();
                boolean terminated = p.waitFor(10, TimeUnit.SECONDS);
                if (!terminated) p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { p.destroyForcibly(); } catch (Exception ignored) {}
        } finally {
            csharpProcess = null;
        }
    }

    private static void shutdownEverything() {
        // Stop polling
        RUNNING.set(false);
        if (scheduler != null) {
            try {
                scheduler.shutdownNow();
            } catch (Exception ignored) {}
            scheduler = null;
        }

        // Stop companion
        stopCSharpScript();

        // Clear texture
        clearTextureOnly();
    }

    // -------------------------
    // Helpers
    // -------------------------

    private static boolean isDisplayableMedia(Snapshot s) {
        return s.title != null
                && !s.title.isEmpty()
                && !s.title.equals("Loading Now Playing...")
                && s.targetProgress >= 0.0
                && s.targetEndSec > 0.0;
    }

    private static boolean shouldDrawPanel() {
        return config.showArtistName
                || config.showTimeline
                || config.showCoverArt
                || config.showMediaTitle
                || config.showPlayStatusIcon;
    }

    private static String parseSecondsToTimeStamp(double seconds) {
        if (Double.isNaN(seconds) || seconds < 0) return "0:00";

        int totalSeconds = (int) Math.round(seconds);

        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int remainingSeconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, remainingSeconds);
        }
        return String.format("%d:%02d", minutes, remainingSeconds);
    }

    private static double parseTimeToSeconds(String time) {
        if (time == null || time.isEmpty() || "(unknown)".equalsIgnoreCase(time)) return 0.0;
        try {
            String[] parts = time.split(":");
            if (parts.length == 3) {
                int hours = Integer.parseInt(parts[0]);
                int minutes = Integer.parseInt(parts[1]);
                double seconds = Double.parseDouble(parts[2]);
                return hours * 3600.0 + minutes * 60.0 + seconds;
            } else if (parts.length == 2) {
                int minutes = Integer.parseInt(parts[0]);
                double seconds = Double.parseDouble(parts[1]);
                return minutes * 60.0 + seconds;
            }
            return 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static String ellipsizeText(String text) {
        if (text == null) return "";
        if (text.length() > 25) return text.substring(0, 25) + "...";
        return text;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static long crc32(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> AutoConfig.getConfigScreen(NowPlayingConfig.class, parent).get();
    }
}
