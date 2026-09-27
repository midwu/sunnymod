package me.midwu.sunnyMod.mixin.client;

import me.midwu.sunnyMod.client.SunnyModEventLogger;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class SunnyModClientPlayNetworkHandlerMixin {
    @Inject(method = "onExperienceBarUpdate", at = @At("HEAD"))
    private void sunnymod$logExperiencePacket(
            ExperienceBarUpdateS2CPacket packet, CallbackInfo ci) {
        SunnyModEventLogger.logExperience(
                packet.getBarProgress(),
                packet.getExperienceLevel(),
                packet.getExperience());
    }

    @Inject(method = "onHealthUpdate", at = @At("HEAD"))
    private void sunnymod$logHealthPacket(
            HealthUpdateS2CPacket packet, CallbackInfo ci) {
        SunnyModEventLogger.logHealth(
                packet.getHealth(),
                packet.getFood());
    }
}
