package me.midwu.sunnyMod.mixin.client;

import me.midwu.sunnyMod.client.SunnyModEventLogger;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.AdvancementUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.CooldownUpdateS2CPacket;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListHeaderS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.RecipeBookAddS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreResetS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SetCursorItemS2CPacket;
import net.minecraft.network.packet.s2c.play.SetPlayerInventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.SetTradeOffersS2CPacket;
import net.minecraft.network.packet.s2c.play.StatisticsS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Raw packet probes for server->client mechanisms that can carry useful
 * structured data without appearing as chat/actionbar/boss-bar text.
 *
 * We intentionally use packet.toString() here. The goal of this first
 * collection pass is to preserve the server's raw payload shape before
 * SunnyMod decides what fields are worth parsing.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class SunnyModDataPacketMixin {

    private static void log(String type, Object packet) {
        SunnyModEventLogger.write(type, String.valueOf(packet));
    }

    @Inject(method = "onOpenScreen", at = @At("HEAD"))
    private void sunnymod$openScreen(OpenScreenS2CPacket packet, CallbackInfo ci) {
        log("PACKET_OPEN_SCREEN", packet);
    }

    @Inject(method = "onOverlayMessage", at = @At("HEAD"))
    private void sunnymod$overlay(OverlayMessageS2CPacket packet, CallbackInfo ci) {
        log("PACKET_ACTIONBAR", packet);
    }

    @Inject(method = "onInventory", at = @At("HEAD"))
    private void sunnymod$inventory(InventoryS2CPacket packet, CallbackInfo ci) {
        log("PACKET_INVENTORY", packet);
    }

    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("HEAD"))
    private void sunnymod$slotUpdate(
            ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        log("PACKET_SLOT_UPDATE", packet);
    }

    @Inject(method = "onSetPlayerInventory", at = @At("HEAD"))
    private void sunnymod$playerInventory(
            SetPlayerInventoryS2CPacket packet, CallbackInfo ci) {
        log("PACKET_PLAYER_INVENTORY", packet);
    }

    @Inject(method = "onSetCursorItem", at = @At("HEAD"))
    private void sunnymod$cursorItem(
            SetCursorItemS2CPacket packet, CallbackInfo ci) {
        log("PACKET_CURSOR_ITEM", packet);
    }

    @Inject(method = "onPlaySound", at = @At("HEAD"))
    private void sunnymod$sound(PlaySoundS2CPacket packet, CallbackInfo ci) {
        log("PACKET_SOUND", packet);
    }

    @Inject(method = "onPlaySoundFromEntity", at = @At("HEAD"))
    private void sunnymod$entitySound(
            PlaySoundFromEntityS2CPacket packet, CallbackInfo ci) {
        log("PACKET_ENTITY_SOUND", packet);
    }

    @Inject(method = "onParticle", at = @At("HEAD"))
    private void sunnymod$particle(
            ParticleS2CPacket packet, CallbackInfo ci) {
        log("PACKET_PARTICLE", packet);
    }

    @Inject(method = "onCooldownUpdate", at = @At("HEAD"))
    private void sunnymod$cooldown(
            CooldownUpdateS2CPacket packet, CallbackInfo ci) {
        log("PACKET_COOLDOWN", packet);
    }

    @Inject(method = "onAdvancements", at = @At("HEAD"))
    private void sunnymod$advancements(
            AdvancementUpdateS2CPacket packet, CallbackInfo ci) {
        log("PACKET_ADVANCEMENTS", packet);
    }

    @Inject(method = "onStatistics", at = @At("HEAD"))
    private void sunnymod$statistics(
            StatisticsS2CPacket packet, CallbackInfo ci) {
        log("PACKET_STATISTICS", packet);
    }

    @Inject(method = "onPlayerList", at = @At("HEAD"))
    private void sunnymod$playerList(
            PlayerListS2CPacket packet, CallbackInfo ci) {
        log("PACKET_PLAYER_LIST", packet);
    }

    @Inject(method = "onPlayerListHeader", at = @At("HEAD"))
    private void sunnymod$playerListHeader(
            PlayerListHeaderS2CPacket packet, CallbackInfo ci) {
        log("PACKET_PLAYER_LIST_HEADER", packet);
    }

    @Inject(method = "onScoreboardObjectiveUpdate", at = @At("HEAD"))
    private void sunnymod$scoreboardObjective(
            ScoreboardObjectiveUpdateS2CPacket packet, CallbackInfo ci) {
        log("PACKET_SCOREBOARD_OBJECTIVE", packet);
    }

    @Inject(method = "onScoreboardDisplay", at = @At("HEAD"))
    private void sunnymod$scoreboardDisplay(
            ScoreboardDisplayS2CPacket packet, CallbackInfo ci) {
        log("PACKET_SCOREBOARD_DISPLAY", packet);
    }

    @Inject(method = "onScoreboardScoreReset", at = @At("HEAD"))
    private void sunnymod$scoreboardScoreReset(ScoreboardScoreResetS2CPacket packet, CallbackInfo ci) {
        log("PACKET_SCOREBOARD_SCORE_RESET", packet);
    }

    @Inject(method = "onScoreboardScoreUpdate", at = @At("HEAD"))
    private void sunnymod$scoreboardScore(
            ScoreboardScoreUpdateS2CPacket packet, CallbackInfo ci) {
        log("PACKET_SCOREBOARD_SCORE", packet);
    }

    @Inject(method = "onSetTradeOffers", at = @At("HEAD"))
    private void sunnymod$tradeOffers(SetTradeOffersS2CPacket packet, CallbackInfo ci) {
        log("PACKET_TRADE_OFFERS", packet);
    }

    @Inject(method = "onRecipeBookAdd", at = @At("HEAD"))
    private void sunnymod$recipeBook(
            RecipeBookAddS2CPacket packet, CallbackInfo ci) {
        log("PACKET_RECIPE_BOOK", packet);
    }

    @Inject(method = "onCustomPayload", at = @At("HEAD"))
    private void sunnymod$customPayload(
            CustomPayload payload, CallbackInfo ci) {
        log("PACKET_CUSTOM_PAYLOAD", payload);
    }
}
