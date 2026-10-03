package me.midwu.sunnyMod.client;

import me.midwu.sunnyMod.mixin.client.SunnyModChatHudAccess;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.text.Text;

import java.util.List;

/** Small bridge for replacing the newest chat entry without growing the chat. */
public final class SunnyModChatHudHelper {
    private SunnyModChatHudHelper() {}

    public static void replaceLastMessage(ChatHud hud, Text replacement) {
        SunnyModChatHudAccess access = (SunnyModChatHudAccess) (Object) hud;
        List<ChatHudLine> messages = access.sunnymod$getMessages();
        if (messages.isEmpty()) {
            hud.addMessage(replacement);
            return;
        }

        // ChatHud keeps newest entries at index 0.
        messages.remove(0);

        // Remove the wrapped visible lines belonging to that same newest entry.
        List<ChatHudLine.Visible> visible = access.sunnymod$getVisibleMessages();
        while (!visible.isEmpty()) {
            ChatHudLine.Visible line = visible.remove(0);
            if (line.endOfEntry()) break;
        }

        hud.addMessage(replacement);
    }
}
