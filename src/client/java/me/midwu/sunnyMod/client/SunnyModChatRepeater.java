package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/** Collapses consecutive identical incoming chat messages into one line with a repeat count. */
public final class SunnyModChatRepeater implements ClientModInitializer {
    /** A repeated message stays in the same burst for up to 30 seconds. */
    private static final long REPEAT_WINDOW_MS = 30_000L;

    private static String lastMessage;
    private static long lastSeen;
    private static int repeats;
    private static boolean initialized;
    private static boolean replacing;

    @Override
    public void onInitializeClient() {
        init();
    }

    public static void init() {
        if (initialized) return;
        initialized = true;

        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, playerChatMessage, sender, boundChatType, timeStamp) ->
                handle(message));
    }

    private static boolean handle(Text message) {
        String text = message == null ? "" : message.getString();
        if (text.isEmpty() || replacing) return true;

        long now = System.currentTimeMillis();
        boolean repeated = text.equals(lastMessage) && now - lastSeen <= REPEAT_WINDOW_MS;

        if (!repeated) {
            lastMessage = text;
            lastSeen = now;
            repeats = 0;
            return true;
        }

        lastSeen = now;
        repeats++;

        // Cancel the incoming duplicate and replace the existing visible entry.
        // The chat therefore stays on one line while the count grows: (1x), (2x), ...
        replacing = true;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.inGameHud != null) {
                Text replacement = Text.literal(text + " (" + repeats + "x)");
                SunnyModChatHudHelper.replaceLastMessage(client.inGameHud.getChatHud(), replacement);
            }
        } finally {
            replacing = false;
        }
        return false;
    }

    public static void reset() {
        lastMessage = null;
        lastSeen = 0L;
        repeats = 0;
    }
}
