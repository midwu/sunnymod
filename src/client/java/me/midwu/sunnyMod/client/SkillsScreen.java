package me.midwu.sunnyMod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** F10 live Skills dashboard. */
public final class SkillsScreen extends Screen {
    private static final int PAD = 14;
    private static final int HEADER_H = 58;
    private static final int FOOTER_H = 34;
    private static final int ROW_H = 40;
    private static final int BAR_H = 7;

    private List<SkillProgress> rows = new ArrayList<>();
    private boolean sortByLevel = false;
    private int scrollOffset = 0;

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
    protected void init() {
        rebuildButtons();
    }

    private int visibleRows() {
        return Math.max(1, (height - HEADER_H - FOOTER_H) / ROW_H);
    }

    private void rebuildButtons() {
        clearChildren();
        int footerY = height - FOOTER_H + 6;

        addDrawableChild(ButtonWidget.builder(
                        Text.literal(sortByLevel ? "Sort: Level" : "Sort: Name"),
                        b -> { sortByLevel = !sortByLevel; reload(); rebuildButtons(); })
                .dimensions(PAD, footerY, 105, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> {
                    reload(); rebuildButtons();
                })
                .dimensions(PAD + 110, footerY, 75, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(width - PAD - 75, footerY, 75, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        rows = new ArrayList<>(SkillsDataStore.snapshot());
        if (sortByLevel) {
            rows.sort(Comparator.comparingInt(SkillProgress::level).reversed()
                    .thenComparing(SkillProgress::name, String.CASE_INSENSITIVE_ORDER));
        }

        ctx.drawCenteredTextWithShadow(textRenderer, "Sunny Skills", width / 2, 10, 0xFFFFD700);
        ctx.drawCenteredTextWithShadow(textRenderer,
                rows.size() + " skills tracked live from progression bars",
                width / 2, 25, 0xFFFFFFFF);
        ctx.drawText(textRenderer, "F10 · live server data", PAD, 42, 0xFF666666, false);

        if (rows.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    "No Skills data yet. Let a Skills progression bar appear first.",
                    width / 2, HEADER_H + 25, 0xFFAAAAAA);
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }

        int visible = visibleRows();
        int maxScroll = Math.max(0, rows.size() - visible);
        scrollOffset = Math.min(scrollOffset, maxScroll);
        int end = Math.min(rows.size(), scrollOffset + visible);

        int nameX = PAD;
        int levelX = Math.min(145, width / 4);
        int barX = Math.min(235, width / 3);
        int barW = Math.max(150, width - barX - PAD - 150);
        int rightX = barX + barW + 12;

        ctx.drawText(textRenderer, "Skill", nameX, HEADER_H - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Level", levelX, HEADER_H - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Progress", barX, HEADER_H - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Remaining", rightX, HEADER_H - 12, 0xFFAAAAAA, false);

        for (int i = scrollOffset; i < end; i++) {
            SkillProgress skill = rows.get(i);
            int y = HEADER_H + (i - scrollOffset) * ROW_H;

            if (mouseX >= PAD && mouseX < width - PAD && mouseY >= y && mouseY < y + ROW_H) {
                ctx.fill(PAD, y, width - PAD, y + ROW_H, 0x55333333);
            }

            ctx.drawText(textRenderer, skill.name(), nameX, y + 3, 0xFFFFFFFF, false);
            ctx.drawText(textRenderer, Integer.toString(skill.level()), levelX, y + 3, 0xFFFFD700, false);

            int barY = y + 5;
            ctx.fill(barX, barY, barX + barW, barY + BAR_H, 0xFF333333);
            int filled = (int) Math.round(barW * skill.normalizedPercent());
            if (filled > 0) {
                ctx.fill(barX, barY, barX + filled, barY + BAR_H, 0xFF77CC55);
            }

            String progress = SkillsDataStore.formatXp(skill.currentXp()) + "/"
                    + SkillsDataStore.formatXp(skill.requiredXp()) + " XP";
            ctx.drawText(textRenderer, progress, barX, y + 15, 0xFFCCCCCC, false);

            String remaining = skill.isMaxed() ? "MAX" : SkillsDataStore.formatXp(skill.remainingXp());
            int remainingWidth = textRenderer.getWidth(remaining);
            ctx.drawText(textRenderer, remaining, width - PAD - remainingWidth, y + 8,
                    skill.isMaxed() ? 0xFF77DD77 : 0xFFAAAAAA, false);

            double avg = SkillsDataStore.averageXpPerHour(skill.name());
            String rate = avg > 0 ? SkillsDataStore.formatRate(avg) : "-- XP/hr";
            String perSecond = avg > 0 ? SkillsDataStore.formatRatePerSecond(
                    SkillsDataStore.averageXpPerSecond(skill.name())) : "-- XP/s";
            ctx.drawText(textRenderer, rate + "  " + perSecond, barX, y + 24, 0xFF888888, false);

            long eta = SkillsDataStore.etaToNextLevelSeconds(skill.name());
            String etaText = skill.isMaxed() ? "MAX" : "ETA " + SkillsDataStore.formatEta(eta);
            int etaWidth = textRenderer.getWidth(etaText);
            ctx.drawText(textRenderer, etaText, width - PAD - etaWidth, y + 20,
                    0xFF888888, false);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int visible = visibleRows();
        int maxScroll = Math.max(0, rows.size() - visible);
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

    public void close() {
        MinecraftClient.getInstance().setScreen(null);
    }
}
