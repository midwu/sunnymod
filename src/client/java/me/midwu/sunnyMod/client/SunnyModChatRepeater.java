package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/** Collapses consecutive identical chat/system messages into one line with a repeat count. */
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
    }

    /**
     * Called directly from ChatHud.addMessage, so this catches both player chat
     * and server/system messages that are rendered into the normal chat HUD.
     *
     * @return true when the incoming message should be displayed normally;
     *         false when it was collapsed into the previous entry.
     */
    public static boolean handle(Text message) {
        if (replacing || message == null) return true;

        String text = message.getString();
        if (text.isEmpty()) return true;

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

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud == null) return true;

        // Preserve the original message styling/components and only append the count.
        Text replacement = message.copy().append(Text.literal(" (" + repeats + "x)"));

        // Cancel the duplicate and replace the newest visible entry in-place.
        replacing = true;
        try {
            SunnyModChatHudHelper.replaceLastMessage(client.inGameHud.getChatHud(), replacement);
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
