package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** Loads local history and safely closes a live Skills session on disconnect. */
public final class SkillsSessionLifecycle implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SkillsSessionHistory.load();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                SkillsDataStore.endSession());
    }
}
