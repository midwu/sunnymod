package me.midwu.sunnyMod.mixin.client;

import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/**
 * Accesses the client-side boss bars using a Mixin accessor so the field name
 * is remapped correctly for the Minecraft version being run.
 */
@Mixin(BossBarHud.class)
public interface SunnyModBossBarHudAccessor {
    @Accessor("bossBars")
    Map<UUID, ClientBossBar> sunnymod$getBossBars();
}
