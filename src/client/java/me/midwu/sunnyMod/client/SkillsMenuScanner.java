package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatically snapshots the server's /skills handled screen.
 *
 * The menu contains one item per skill. Its display name is e.g. "Mining Skill"
 * and its tooltip contains Level, Progress and Total Experience. We only parse
 * the known skill entries, so Player Level, Prestige, XP multiplier, etc. are
 * intentionally ignored.
 */
public final class SkillsMenuScanner implements ClientModInitializer {
    private static final Pattern LEVEL = Pattern.compile(
            "^\\s*Level:\\s*(\\d+)\\s*/\\s*\\d+\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROGRESS = Pattern.compile(
            "^\\s*Progress:\\s*([0-9,.]+)\\s*/\\s*([0-9,.]+)\\s*XP\\s*$", Pattern.CASE_INSENSITIVE);

    private static int ticksUntilScan = 0;
    private static String lastFingerprint = "";

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
                lastFingerprint = "";
                return;
            }

            String title = screen.getTitle().getString().trim();
            if (!title.equalsIgnoreCase("Skills")) {
                lastFingerprint = "";
                return;
            }

            if (--ticksUntilScan > 0) return;
            ticksUntilScan = 5; // 4 scans/sec while the /skills menu is open.
            scan(client, screen);
        });
    }

    private static void scan(MinecraftClient client, HandledScreen<?> screen) {
        StringBuilder fingerprint = new StringBuilder();
        int updated = 0;

        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            String displayName = stack.getName().getString();
            if (!displayName.toLowerCase().endsWith(" skill")) continue;

            List<Text> tooltip = stack.getTooltip(
                    Item.TooltipContext.DEFAULT, client.player, TooltipType.BASIC);

            int level = -1;
            double current = -1;
            double required = -1;

            for (int i = 1; i < tooltip.size(); i++) {
                String line = tooltip.get(i).getString();

                Matcher levelMatch = LEVEL.matcher(line);
                if (levelMatch.matches()) {
                    level = Integer.parseInt(levelMatch.group(1));
                    continue;
                }

                Matcher progressMatch = PROGRESS.matcher(line);
                if (progressMatch.matches()) {
                    current = parseNumber(progressMatch.group(1));
                    required = parseNumber(progressMatch.group(2));
                }
            }

            if (level < 0 || current < 0 || required < 0) continue;

            String skillName = displayName.substring(0, displayName.length() - " Skill".length()).trim();
            fingerprint.append(skillName).append('|')
                    .append(level).append('|')
                    .append(current).append('|')
                    .append(required).append(';');

            if (SkillsDataStore.updateFromMenu(skillName, level, current, required)) {
                updated++;
            }
        }

        String newFingerprint = fingerprint.toString();
        if (!newFingerprint.equals(lastFingerprint)) {
            lastFingerprint = newFingerprint;
            if (updated > 0) {
                System.out.println("[SunnyMod Skills] /skills snapshot updated " + updated + " skills");
            }
        }
    }

    private static double parseNumber(String raw) {
        return Double.parseDouble(raw.replace(",", ""));
    }
}
