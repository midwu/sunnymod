package me.midwu.sunnyMod.mixin.client;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ChatHud.class)
public interface SunnyModChatHudAccess {
    @Accessor("messages")
    List<ChatHudLine> sunnymod$getMessages();

    @Accessor("visibleMessages")
    List<ChatHudLine.Visible> sunnymod$getVisibleMessages();
}
