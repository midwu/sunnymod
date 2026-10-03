package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.entity.boss.BossBar;
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

/** Raw server/client diagnostics. Boss bars are logged only on state changes. */
public final class SunnyModEventLogger implements ClientModInitializer {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Path LOG_FILE = ShopLogger.getConfigDir().resolve("event_log.txt");
    private static final Map<UUID, BossSnapshot> LAST_BOSS_BARS = new LinkedHashMap<>();
    private static boolean initialized;
    private static boolean bossBarCaptureErrorLogged;

    @Override
    public void onInitializeClient() { init(); }

    public static void init() {
        if (initialized) return;
        initialized = true;
        try {
            Files.createDirectories(LOG_FILE.getParent());
            write("LOGGER", "SunnyMod event logger started");
            write("LOGGER", "File=" + LOG_FILE.toAbsolutePath());
        } catch (Throwable t) {
            System.err.println("[SunnyModEventLogger] Failed to initialize: " + t);
        }

        ClientReceiveMessageEvents.CHAT.register((message, playerChatMessage, sender, boundChatType, timeStamp) ->
                write("CHAT", text(message)));
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                write(overlay ? "ACTIONBAR" : "GAME", text(message)));
        ClientTickEvents.END_CLIENT_TICK.register(SunnyModEventLogger::tickBossBars);
    }

    private static String text(Text message) {
        if (message == null) return "";
        return message.getString().replace("\r", "\\r").replace("\n", "\\n");
    }

    public static void logTitle(Text title) { write("TITLE", text(title)); }
    public static void logSubtitle(Text subtitle) { write("SUBTITLE", text(subtitle)); }
    public static void logTitleTiming(int fadeIn, int stay, int fadeOut) {
        write("TITLE_TIMING", "fadeIn=" + fadeIn + ",stay=" + stay + ",fadeOut=" + fadeOut);
    }
    public static void logExperience(float progress, int totalExperience, int level) {
        write("XP_PACKET", "progress=" + progress + ",totalExperience=" + totalExperience + ",level=" + level);
    }
    public static void logHealth(float health, int food) {
        write("HEALTH", "health=" + health + ",food=" + food);
    }

    /** Kept for compatibility with the old render mixin, but intentionally does no disk I/O. */
    public static void logRenderedBossBar(BossBar bossBar, int x, int y) {
        if (bossBar != null) SkillsDataStore.updateFromBossBar(text(bossBar.getName()), bossBar.getPercent());
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
            Map<UUID, ClientBossBar> bars = ((SunnyModBossBarHudAccessor) (Object) hud).sunnymod$getBossBars();
            Map<UUID, BossSnapshot> current = new LinkedHashMap<>();

            for (Map.Entry<UUID, ClientBossBar> entry : bars.entrySet()) {
                UUID id = entry.getKey();
                ClientBossBar bar = entry.getValue();
                String bossName = text(bar.getName());
                SkillsDataStore.updateFromBossBar(bossName, bar.getPercent());
                BossSnapshot snapshot = new BossSnapshot(bossName, bar.getPercent(),
                        bar.getColor().name(), bar.getStyle().name());
                current.put(id, snapshot);
                BossSnapshot previous = LAST_BOSS_BARS.get(id);
                if (previous == null) write("BOSSBAR_ADD", formatBoss(id, snapshot));
                else if (!previous.equals(snapshot)) write("BOSSBAR_UPDATE", formatBoss(id, snapshot));
            }

            for (UUID id : LAST_BOSS_BARS.keySet()) {
                if (!current.containsKey(id)) write("BOSSBAR_REMOVE", "id=" + id);
            }
            LAST_BOSS_BARS.clear();
            LAST_BOSS_BARS.putAll(current);
        } catch (Throwable t) {
            if (!bossBarCaptureErrorLogged) {
                write("ERROR", "bossbar capture failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                bossBarCaptureErrorLogged = true;
            }
            LAST_BOSS_BARS.clear();
        }
    }

    private static String formatBoss(UUID id, BossSnapshot snapshot) {
        return "id=" + id + ",name=" + quote(snapshot.name) + ",percent=" + snapshot.percent
                + ",color=" + snapshot.color + ",style=" + snapshot.style;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static void write(String type, String payload) {
        String line = "[" + LocalDateTime.now().format(TS) + "] " + type + " " + payload;
        try {
            Files.createDirectories(LOG_FILE.getParent());
            try (BufferedWriter out = Files.newBufferedWriter(LOG_FILE, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
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
