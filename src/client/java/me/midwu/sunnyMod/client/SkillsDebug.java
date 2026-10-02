package me.midwu.sunnyMod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Small diagnostic helper used by the Skills scanner/rate tracker.
 * It deliberately funnels diagnostics into the existing SunnyMod event log
 * so no second log file is needed.
 */
public final class SkillsDebug {
    private SkillsDebug() {}

    public static void log(String type, String payload) {
        SunnyModEventLogger.write(type, payload == null ? "" : payload);
    }

    public static void feedback(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || message == null || message.isEmpty()) {
            return;
        }
        client.player.sendMessage(Text.literal("[Skills] " + message), false);
    }
}
