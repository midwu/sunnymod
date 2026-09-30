package me.midwu.sunnyMod.mixin.client;

import me.midwu.sunnyMod.client.SunnyModEventLogger;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render-path probe for the vanilla boss-bar renderer.
 *
 * This is deliberately separate from reading BossBarHud.bossBars: if another
 * mod/server interaction changes how the bar is represented, seeing the
 * render call is the most direct proof that Minecraft is actually drawing a
 * BossBar and gives us the exact BossBar object used for rendering.
 */
@Mixin(BossBarHud.class)
public abstract class SunnyModBossBarHudMixin {
    @Inject(method = "renderBossBar", at = @At("HEAD"))
    private void sunnymod$logRenderedBossBar(
            DrawContext context,
            int x,
            int y,
            BossBar bossBar,
            int width,
            Identifier[] textures,
            Identifier[] notchedTextures,
            CallbackInfo ci) {
        SunnyModEventLogger.logRenderedBossBar(
                bossBar,
                x,
                y,
                width);
    }
}
