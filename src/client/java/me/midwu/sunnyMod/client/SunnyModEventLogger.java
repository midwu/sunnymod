package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import me.midwu.sunnyMod.mixin.client.SunnyModBossBarHudAccessor;
import net.minecraft.text.Text;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SunnyMod's raw server/client event recorder.
 *
 * This intentionally records the raw text/value first. Parsing into Skills
 * events comes later, after we know exactly what the server sends.
 *
 * File:
 *   config/sunnyMod/event_log.txt
 *
 * Captures:
 *   CHAT       - player/chat messages
 *   GAME       - server game/system messages
 *   ACTIONBAR  - GAME messages with overlay=true
 *   BOSSBAR    - boss/progression bars (name + percent + style/color)
 *   TITLE      - title/subtitle calls
 *   XP_PACKET  - vanilla player XP bar packet updates
 *   HEALTH     - vanilla health packet updates
 *
 * The mixin records title/subtitle and network packets that are not exposed
 * through a convenient Fabric client event.
 */
public final class SunnyModEventLogger implements ClientModInitializer {
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Path LOG_FILE =
            ShopLogger.getConfigDir().resolve("event_log.txt");

    private static final Map<UUID, BossSnapshot> LAST_BOSS_BARS = new LinkedHashMap<>();

    private static boolean initialized = false;
    private static boolean bossBarCaptureErrorLogged = false;

    @Override
    public void onInitializeClient() {
        init();
    }

    /**
     * Safe to call from an existing SunnyMod client initializer.
     * This lets the logger be integrated without adding a second Fabric
     * client entrypoint.
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        try {
            Files.createDirectories(LOG_FILE.getParent());
            write("LOGGER", "SunnyMod event logger started");
            write("LOGGER", "File=" + LOG_FILE.toAbsolutePath());
        } catch (Throwable t) {
            System.err.println("[SunnyModEventLogger] Failed to initialize log file: " + t);
        }

        ClientReceiveMessageEvents.CHAT.register((message, playerChatMessage, sender, boundChatType, timeStamp) ->
                write("CHAT", text(message)));

        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                write(overlay ? "ACTIONBAR" : "GAME", text(message)));

        ClientTickEvents.END_CLIENT_TICK.register(SunnyModEventLogger::tickBossBars);
    }

    private static String text(Text message) {
        if (message == null) return "";
        // Preserve both the user-visible text and formatting codes. Formatting
        // can be useful later when reverse-engineering server messages.
        return message.getString().replace("\r", "\\r").replace("\n", "\\n");
    }

    public static void logTitle(Text title) {
        write("TITLE", text(title));
    }

    public static void logSubtitle(Text subtitle) {
        write("SUBTITLE", text(subtitle));
    }

    public static void logTitleTiming(int fadeIn, int stay, int fadeOut) {
        write("TITLE_TIMING",
                "fadeIn=" + fadeIn + ",stay=" + stay + ",fadeOut=" + fadeOut);
    }

    public static void logExperience(float progress, int totalExperience, int level) {
        write("XP_PACKET",
                "progress=" + progress
                        + ",totalExperience=" + totalExperience
                        + ",level=" + level);
    }

    public static void logHealth(float health, int food) {
        write("HEALTH", "health=" + health + ",food=" + food);
    }

    private static void tickBossBars(MinecraftClient client) {
        if (client == null || client.world == null) {
            if (!LAST_BOSS_BARS.isEmpty()) {
                LAST_BOSS_BARS.clear();
                write("BOSSBAR_CLEAR", "world changed/disconnected");
            }
            return;
        }

        try {
            BossBarHud hud = client.inGameHud.getBossBarHud();

            Map<UUID, ClientBossBar> bars =
                    ((SunnyModBossBarHudAccessor) (Object) hud).sunnymod$getBossBars();

            Map<UUID, BossSnapshot> current = new LinkedHashMap<>();

            for (Map.Entry<UUID, ClientBossBar> entry : bars.entrySet()) {
                UUID id = entry.getKey();
                ClientBossBar bar = entry.getValue();

                BossSnapshot snapshot = new BossSnapshot(
                        text(bar.getName()),
                        bar.getPercent(),
                        bar.getColor().name(),
                        bar.getStyle().name()
                );

                current.put(id, snapshot);

                BossSnapshot previous = LAST_BOSS_BARS.get(id);
                if (previous == null) {
                    write("BOSSBAR_ADD", formatBoss(id, snapshot));
                } else if (!previous.equals(snapshot)) {
                    write("BOSSBAR_UPDATE", formatBoss(id, snapshot));
                }
            }

            for (UUID id : LAST_BOSS_BARS.keySet()) {
                if (!current.containsKey(id)) {
                    write("BOSSBAR_REMOVE", "id=" + id);
                }
            }

            LAST_BOSS_BARS.clear();
            LAST_BOSS_BARS.putAll(current);
        } catch (Throwable t) {
            // Don't spam the log every tick if a mapping/runtime differs.
            if (!bossBarCaptureErrorLogged) {
                write("ERROR", "bossbar capture failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                bossBarCaptureErrorLogged = true;
            }
            LAST_BOSS_BARS.clear();
        }
    }

    private static String formatBoss(UUID id, BossSnapshot snapshot) {
        return "id=" + id
                + ",name=" + quote(snapshot.name)
                + ",percent=" + snapshot.percent
                + ",color=" + snapshot.color
                + ",style=" + snapshot.style;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static void write(String type, String payload) {
        String line = "[" + LocalDateTime.now().format(TS) + "] "
                + type + " " + payload;
        try {
            Files.createDirectories(LOG_FILE.getParent());
            try (BufferedWriter out = Files.newBufferedWriter(
                    LOG_FILE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                out.write(line);
                out.newLine();
            }
        } catch (IOException e) {
            System.err.println("[SunnyModEventLogger] " + line);
            System.err.println("[SunnyModEventLogger] Failed to write event_log.txt: " + e);
        }
    }

    private record BossSnapshot(String name, float percent, String color, String style) {}
}
