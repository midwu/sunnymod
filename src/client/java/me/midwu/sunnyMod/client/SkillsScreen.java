package me.midwu.sunnyMod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** F10 live Skills dashboard with progression, session, history and server-data views. */
public final class SkillsScreen extends Screen {
    private static final int PAD = 14;
    private static final int HEADER_H = 82;
    private static final int FOOTER_H = 34;
    private static final int ROW_H = 46;
    private static final int BAR_H = 7;

    private enum Tab { OVERVIEW, SESSION, HISTORY, GUIDE, SERVER }

    private List<SkillProgress> rows = new ArrayList<>();
    private boolean sortByLevel;
    private int scrollOffset;
    private Tab tab = Tab.OVERVIEW;

    public SkillsScreen() {
        super(Text.literal("Sunny Skills"));
        reload();
    }

    private void reload() {
        rows = new ArrayList<>(SkillsDataStore.snapshot());
        if (sortByLevel) {
            rows.sort(Comparator.comparingInt(SkillProgress::level).reversed()
                    .thenComparing(SkillProgress::name, String.CASE_INSENSITIVE_ORDER));
        }
        scrollOffset = 0;
    }

    @Override
    protected void init() { rebuildButtons(); }

    private void rebuildButtons() {
        clearChildren();
        int y = 58;
        int x = PAD;
        int w = 82;
        for (Tab value : Tab.values()) {
            Tab target = value;
            addDrawableChild(ButtonWidget.builder(Text.literal(label(target)), b -> {
                        tab = target;
                        reload();
                        rebuildButtons();
                    }).dimensions(x, y, w, 20).build());
            x += w + 4;
        }

        int footerY = height - FOOTER_H + 6;
        if (tab == Tab.OVERVIEW) {
            addDrawableChild(ButtonWidget.builder(Text.literal(sortByLevel ? "Sort: Level" : "Sort: Name"), b -> {
                        sortByLevel = !sortByLevel; reload(); rebuildButtons();
                    }).dimensions(PAD, footerY, 105, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> {
                    reload(); rebuildButtons();
                }).dimensions(PAD + 110, footerY, 75, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset Session"), b -> {
                    SkillsDataStore.resetRates();
                    reload();
                }).dimensions(width / 2 - 45, footerY, 90, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(width - PAD - 75, footerY, 75, 20).build());
    }

    private static String label(Tab tab) {
        return switch (tab) {
            case OVERVIEW -> "Overview";
            case SESSION -> "Session";
            case HISTORY -> "History";
            case GUIDE -> "Guide";
            case SERVER -> "Server Data";
        };
    }

    private int visibleRows() {
        return Math.max(1, (height - HEADER_H - FOOTER_H) / ROW_H);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        rows = new ArrayList<>(SkillsDataStore.snapshot());
        if (sortByLevel) {
            rows.sort(Comparator.comparingInt(SkillProgress::level).reversed()
                    .thenComparing(SkillProgress::name, String.CASE_INSENSITIVE_ORDER));
        }

        ctx.drawCenteredTextWithShadow(textRenderer, "Sunny Skills", width / 2, 10, 0xFFFFD700);
        String session = "Session " + SkillsDataStore.formatDuration(SkillsDataStore.sessionDurationMs());
        int sw = textRenderer.getWidth(session);
        ctx.drawText(textRenderer, session, width - PAD - sw, 12, 0xFFAAAAAA, false);
        ctx.drawCenteredTextWithShadow(textRenderer, subtitle(), width / 2, 29, 0xFFFFFFFF);

        if (tab == Tab.OVERVIEW) renderOverview(ctx, mouseX, mouseY);
        else if (tab == Tab.SESSION) renderSession(ctx, mouseX, mouseY);
        else if (tab == Tab.HISTORY) renderHistory(ctx, mouseX, mouseY);
        else if (tab == Tab.GUIDE) renderGuide(ctx);
        else renderServerData(ctx, mouseX, mouseY);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private String subtitle() {
        return switch (tab) {
            case OVERVIEW -> rows.size() + " skills tracked live";
            case SESSION -> "Live analytics for the current session";
            case HISTORY -> "Last 50 locally saved sessions";
            case GUIDE -> "How SunnyMod measures Skills progression";
            case SERVER -> "Observed directly from the server's /skills menu";
        };
    }

    private void renderOverview(DrawContext ctx, int mouseX, int mouseY) {
        if (rows.isEmpty()) {
            drawEmpty(ctx, "No Skills data yet. Let a progression bar or /skills menu appear first.");
            return;
        }
        int visible = visibleRows();
        int maxScroll = Math.max(0, rows.size() - visible);
        scrollOffset = Math.min(scrollOffset, maxScroll);
        int end = Math.min(rows.size(), scrollOffset + visible);

        int nameX = PAD;
        int levelX = Math.min(145, width / 4);
        int barX = Math.min(235, width / 3);
        int barW = Math.max(150, width - barX - PAD - 190);
        int rightX = barX + barW + 12;

        ctx.drawText(textRenderer, "Skill", nameX, HEADER_H - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Level", levelX, HEADER_H - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Progress", barX, HEADER_H - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Rate / ETA", rightX, HEADER_H - 12, 0xFFAAAAAA, false);

        for (int i = scrollOffset; i < end; i++) {
            SkillProgress skill = rows.get(i);
            int y = HEADER_H + (i - scrollOffset) * ROW_H;
            boolean hovered = mouseX >= PAD && mouseX < width - PAD && mouseY >= y && mouseY < y + ROW_H;
            if (hovered) ctx.fill(PAD, y, width - PAD, y + ROW_H, 0x55333333);

            boolean fresh = SkillsDataStore.isHudFresh(skill);
            ctx.drawText(textRenderer, skill.name(), nameX, y + 3, fresh ? 0xFFFFFFFF : 0xFF888888, false);
            ctx.drawText(textRenderer, Integer.toString(skill.level()), levelX, y + 3, 0xFFFFD700, false);

            int barY = y + 5;
            ctx.fill(barX, barY, barX + barW, barY + BAR_H, 0xFF333333);
            int filled = (int) Math.round(barW * skill.normalizedPercent());
            if (filled > 0) ctx.fill(barX, barY, barX + filled, barY + BAR_H, 0xFF77CC55);

            String progress = SkillsDataStore.formatXp(skill.currentXp()) + "/"
                    + SkillsDataStore.formatXp(skill.requiredXp()) + " XP";
            ctx.drawText(textRenderer, progress, barX, y + 15, 0xFFCCCCCC, false);

            String sessionXp = "+" + SkillsDataStore.formatXp(SkillsDataStore.sessionXp(skill.name())) + " XP";
            ctx.drawText(textRenderer, sessionXp, barX, y + 28, 0xFF888888, false);

            String rate = SkillsDataStore.formatRatePerSecond(SkillsDataStore.xpPerSecond(skill.name()));
            String eta = skill.isMaxed() ? "MAX" : "ETA " + SkillsDataStore.formatEta(
                    SkillsDataStore.etaToNextLevelSeconds(skill.name()));
            ctx.drawText(textRenderer, rate, rightX, y + 5, 0xFFFFFFFF, false);
            ctx.drawText(textRenderer, eta, rightX, y + 20, 0xFF888888, false);
        }
    }

    private void renderSession(DrawContext ctx, int mouseX, int mouseY) {
        int y = HEADER_H;
        drawStat(ctx, "Duration", SkillsDataStore.formatDuration(SkillsDataStore.sessionDurationMs()), y); y += 28;
        drawStat(ctx, "Total Skill XP", SkillsDataStore.formatXp(SkillsDataStore.totalSessionXp()), y); y += 28;
        drawStat(ctx, "Current XP/s", SkillsDataStore.formatRatePerSecond(SkillsDataStore.currentSessionXpPerSecond()), y); y += 28;
        drawStat(ctx, "Average XP/hr", SkillsDataStore.formatRate(SkillsDataStore.averageSessionXpPerHour()), y); y += 28;
        drawStat(ctx, "XP events", Integer.toString(SkillsDataStore.sessionXpEvents()), y); y += 28;
        drawStat(ctx, "Level-ups", Integer.toString(SkillsDataStore.sessionLevelUps()), y); y += 28;
        drawStat(ctx, "Peak XP/s", SkillsDataStore.formatRatePerSecond(SkillsDataStore.sessionPeakXpPerSecond()), y); y += 28;
        drawStat(ctx, "Observed money", String.format(java.util.Locale.US, "$%,.2f", SkillsDataStore.observedMoney()), y); y += 28;
        drawStat(ctx, "Observed Job XP", SkillsDataStore.formatXp(SkillsDataStore.observedJobXp()), y); y += 34;

        ctx.drawText(textRenderer, "Observed inventory gains", PAD, y, 0xFFFFD700, true);
        y += 16;
        List<Map.Entry<String, Long>> items = SkillsResourceTracker.positiveDeltas(8);
        if (items.isEmpty()) {
            ctx.drawText(textRenderer, "No positive inventory delta observed yet.", PAD, y, 0xFF888888, false);
        } else {
            for (Map.Entry<String, Long> item : items) {
                ctx.drawText(textRenderer, "+" + item.getValue() + "  " + item.getKey(), PAD, y, 0xFFCCCCCC, false);
                y += 15;
            }
        }
    }

    private void drawStat(DrawContext ctx, String label, String value, int y) {
        ctx.drawText(textRenderer, label, PAD, y, 0xFFAAAAAA, false);
        int w = textRenderer.getWidth(value);
        ctx.drawText(textRenderer, value, width - PAD - w, y, 0xFFFFFFFF, true);
        ctx.fill(PAD, y + 17, width - PAD, y + 18, 0xFF333333);
    }

    private void renderHistory(DrawContext ctx, int mouseX, int mouseY) {
        List<SkillSessionSummary> history = SkillsSessionHistory.snapshot();
        if (history.isEmpty()) {
            drawEmpty(ctx, "No completed sessions yet. Reset a session after earning Skill XP to save it.");
            return;
        }
        int y = HEADER_H;
        int start = Math.min(scrollOffset, Math.max(0, history.size() - visibleRows()));
        int max = Math.min(history.size(), start + visibleRows());
        for (int i = start; i < max; i++) {
            SkillSessionSummary s = history.get(i);
            if (mouseX >= PAD && mouseX < width - PAD && mouseY >= y && mouseY < y + ROW_H) {
                ctx.fill(PAD, y, width - PAD, y + ROW_H, 0x55333333);
            }
            String date = java.time.Instant.ofEpochMilli(s.startedAt()).toString().replace('T', ' ');
            ctx.drawText(textRenderer, date.substring(0, Math.min(19, date.length())), PAD, y + 2, 0xFFAAAAAA, false);
            ctx.drawText(textRenderer, "Duration " + s.duration(), PAD, y + 17, 0xFF888888, false);
            ctx.drawText(textRenderer, "+" + SkillsDataStore.formatXp(s.totalSkillXp()) + " XP", PAD + 170, y + 2, 0xFFFFD700, true);
            ctx.drawText(textRenderer, SkillsDataStore.formatRatePerSecond(
                    s.durationMs() > 0 ? s.totalSkillXp() / (s.durationMs() / 1000.0) : 0.0), PAD + 170, y + 17, 0xFFCCCCCC, false);
            y += ROW_H;
        }
    }

    private void renderGuide(DrawContext ctx) {
        int y = HEADER_H;
        ctx.drawText(textRenderer, "Live progression", PAD, y, 0xFFFFD700, true); y += 17;
        y = guideLine(ctx, "/skills menu", "authoritative skill level / XP snapshot", y);
        y = guideLine(ctx, "Progression boss bar", "high-frequency active-skill XP updates", y);
        y = guideLine(ctx, "Current XP/s", "XP observed during the last 1 second", y);
        y = guideLine(ctx, "Average XP/hr", "session XP divided by elapsed session time", y);
        y = guideLine(ctx, "ETA", "remaining XP divided by the session average XP/s", y + 4);

        ctx.drawText(textRenderer, "Session analytics", PAD, y, 0xFFFFD700, true); y += 17;
        y = guideLine(ctx, "XP events", "positive observed XP changes; not a claimed action count", y);
        y = guideLine(ctx, "Inventory deltas", "observed item-count changes; source/skill attribution is not guessed", y);
        y = guideLine(ctx, "Observed money", "money detected by SunnyMod while a Skills session is active", y);
        y = guideLine(ctx, "Observed Job XP", "reserved metric for verified Job XP events", y);

        ctx.drawText(textRenderer, "Data quality", PAD, y + 6, 0xFFFFD700, true); y += 23;
        y = guideLine(ctx, "Fresh HUD data", "individual skills hide after 5 seconds without an update", y);
        y = guideLine(ctx, "Session history", "up to 50 completed local sessions are stored", y);
        y = guideLine(ctx, "Server Data tab", "raw /skills menu entries are shown without inventing meanings", y);
    }

    private int guideLine(DrawContext ctx, String key, String value, int y) {
        ctx.drawText(textRenderer, key, PAD, y, 0xFFFFFFFF, false);
        ctx.drawText(textRenderer, value, PAD + 110, y, 0xFFAAAAAA, false);
        return y + 18;
    }

    private void renderServerData(DrawContext ctx, int mouseX, int mouseY) {
        List<SkillsMenuCatalog.SkillsMenuEntry> entries = SkillsMenuCatalog.snapshot();
        if (entries.isEmpty()) {
            drawEmpty(ctx, "Open /skills so SunnyMod can observe the server's live menu data.");
            return;
        }
        int y = HEADER_H;
        int start = Math.min(scrollOffset, Math.max(0, entries.size() - visibleRows()));
        int max = Math.min(entries.size(), start + visibleRows());
        for (int i = start; i < max; i++) {
            SkillsMenuCatalog.SkillsMenuEntry entry = entries.get(i);
            ctx.drawText(textRenderer, entry.name(), PAD, y, 0xFFFFD700, true);
            List<String> tip = entry.tooltip();
            int lineY = y + 14;
            int lines = Math.min(2, tip.size());
            for (int j = 0; j < lines; j++) {
                String line = tip.get(j);
                if (line.length() > 90) line = line.substring(0, 87) + "...";
                ctx.drawText(textRenderer, line, PAD + 8, lineY, 0xFFAAAAAA, false);
                lineY += 12;
            }
            y += 42;
        }
        String updated = SkillsMenuCatalog.updatedAt() == 0 ? "never" :
                SkillsDataStore.formatDuration(System.currentTimeMillis() - SkillsMenuCatalog.updatedAt()) + " ago";
        ctx.drawText(textRenderer, "Menu snapshot: " + updated, PAD, height - FOOTER_H - 8, 0xFF666666, false);
    }

    private void drawEmpty(DrawContext ctx, String message) {
        ctx.drawCenteredTextWithShadow(textRenderer, message, width / 2, HEADER_H + 30, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int visible = visibleRows();
        int count = switch (tab) {
            case OVERVIEW -> rows.size();
            case HISTORY -> SkillsSessionHistory.snapshot().size();
            case GUIDE -> 0;
            case SERVER -> SkillsMenuCatalog.snapshot().size();
            case SESSION -> 0;
        };
        int maxScroll = Math.max(0, count - visible);
        scrollOffset -= (int) Math.signum(verticalAmount);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == 256 || input.key() == 299) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    public void close() { MinecraftClient.getInstance().setScreen(null); }
}
