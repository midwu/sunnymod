package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Observes player-inventory count changes during a Skills session.
 *
 * This intentionally reports inventory deltas, not claimed skill actions. A
 * delta is only attributed to the session; the mod does not guess whether an
 * item came from farming, mining, a chest, a trade, or another source.
 */
public final class SkillsResourceTracker implements ClientModInitializer {
    private static final Map<String, Long> SESSION_DELTAS = new LinkedHashMap<>();
    private static Map<String, Long> lastCounts = new LinkedHashMap<>();
    private static long observedSessionStart = -1L;
    private static int ticks;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++ticks < 5) return;
            ticks = 0;
            tick(client);
        });
    }

    private static void tick(MinecraftClient client) {
        if (client.player == null) {
            lastCounts = new LinkedHashMap<>();
            observedSessionStart = -1L;
            return;
        }

        long sessionStart = SkillsDataStore.sessionStartTime();
        if (sessionStart != observedSessionStart) {
            observedSessionStart = sessionStart;
            SESSION_DELTAS.clear();
            lastCounts = snapshot(client);
            return;
        }

        if (sessionStart <= 0L) {
            lastCounts = snapshot(client);
            return;
        }

        Map<String, Long> current = snapshot(client);
        if (!lastCounts.isEmpty()) {
            for (Map.Entry<String, Long> entry : current.entrySet()) {
                long previous = lastCounts.getOrDefault(entry.getKey(), 0L);
                long delta = entry.getValue() - previous;
                if (delta != 0L) {
                    SESSION_DELTAS.merge(entry.getKey(), delta, Long::sum);
                    if (SESSION_DELTAS.get(entry.getKey()) == 0L) {
                        SESSION_DELTAS.remove(entry.getKey());
                    }
                }
            }
            for (Map.Entry<String, Long> entry : lastCounts.entrySet()) {
                if (!current.containsKey(entry.getKey()) && entry.getValue() != 0L) {
                    SESSION_DELTAS.merge(entry.getKey(), -entry.getValue(), Long::sum);
                    if (SESSION_DELTAS.get(entry.getKey()) == 0L) {
                        SESSION_DELTAS.remove(entry.getKey());
                    }
                }
            }
        }
        lastCounts = current;
    }

    private static Map<String, Long> snapshot(MinecraftClient client) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ItemStack stack : client.player.getInventory().getMainStacks()) {
            if (stack.isEmpty()) continue;
            String key = Registries.ITEM.getId(stack.getItem()).toString();
            counts.merge(key, (long) stack.getCount(), Long::sum);
        }
        return counts;
    }

    public static synchronized Map<String, Long> snapshotDeltas() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(SESSION_DELTAS));
    }

    public static synchronized List<Map.Entry<String, Long>> positiveDeltas(int limit) {
        List<Map.Entry<String, Long>> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : SESSION_DELTAS.entrySet()) {
            if (entry.getValue() > 0L) result.add(Map.entry(entry.getKey(), entry.getValue()));
        }
        result.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return result.subList(0, Math.min(limit, result.size()));
    }

    public static synchronized void reset() {
        SESSION_DELTAS.clear();
        lastCounts = new LinkedHashMap<>();
        observedSessionStart = -1L;
    }
}
