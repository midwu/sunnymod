package me.midwu.sunnyMod.mixin.client;

import me.midwu.sunnyMod.client.SunnyModChatRepeater;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Intercepts the actual ChatHud insertion point, including server/system chat. */
@Mixin(ChatHud.class)
public abstract class SunnyModChatHudMixin {
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), cancellable = true)
    private void sunnymod$collapseRepeatedMessage(Text message, CallbackInfo ci) {
        if (!SunnyModChatRepeater.handle(message)) {
            ci.cancel();
        }
    }
}
