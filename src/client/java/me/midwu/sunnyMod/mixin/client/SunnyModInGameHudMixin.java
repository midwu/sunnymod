package me.midwu.sunnyMod.mixin.client;

import me.midwu.sunnyMod.client.SunnyModEventLogger;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class SunnyModInGameHudMixin {
    @Inject(method = "setTitle", at = @At("HEAD"))
    private void sunnymod$logTitle(Text title, CallbackInfo ci) {
        SunnyModEventLogger.logTitle(title);
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"))
    private void sunnymod$logSubtitle(Text subtitle, CallbackInfo ci) {
        SunnyModEventLogger.logSubtitle(subtitle);
    }

    @Inject(method = "setTitleTicks", at = @At("HEAD"))
    private void sunnymod$logTitleTiming(
            int fadeInTicks, int stayTicks, int fadeOutTicks, CallbackInfo ci) {
        SunnyModEventLogger.logTitleTiming(
                fadeInTicks, stayTicks, fadeOutTicks);
    }
}
