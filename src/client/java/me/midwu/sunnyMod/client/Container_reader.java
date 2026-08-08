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
import org.lwjgl.glfw.GLFW;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class Container_reader implements ClientModInitializer {

    private static final Path CONFIG_DIR = ShopLogger.getConfigDir();
    private static final Path CSV_FILE   = CONFIG_DIR.resolve("container_dump.csv");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String HEADER =
            "DumpTime,ScreenTitle,SlotIndex,ItemId,DisplayName,Count,Lore";

    // Manual edge detection — glfwGetKey returns the *held* state, not a
    // one-shot press event, so we track the previous tick's state ourselves
    // to fire exactly once per press (same pattern HudEditScreen uses for
    // its mouse button).
    private static boolean wasKeyDown = false;

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            System.err.println("[ContainerReader] Failed to create config directory: " + e.getMessage());
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long windowHandle = client.getWindow().getHandle();
            boolean isKeyDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_F5) == GLFW.GLFW_PRESS;

            if (isKeyDown && !wasKeyDown) {
                if (client.currentScreen instanceof HandledScreen<?> handledScreen) {
                    dumpContainer(handledScreen);
                } else if (client.player != null) {
                    client.player.sendMessage(Text.literal(
                            "§e[ContainerReader] No container-style menu open — nothing to dump."), false);
                }
            }
            wasKeyDown = isKeyDown;
        });
    }

    private void dumpContainer(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        String screenTitle = escapeCsv(screen.getTitle().getString());
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

        List<Slot> slots = screen.getScreenHandler().slots;
        int written = 0;

        boolean needsHeader = !Files.exists(CSV_FILE);
        try (BufferedWriter writer = Files.newBufferedWriter(
                CSV_FILE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            if (needsHeader) {
                writer.write(HEADER);
                writer.newLine();
            }

            for (Slot slot : slots) {
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) continue;

                String itemId = String.valueOf(stack.getItem());
                String displayName = stack.getName().getString();
                int count = stack.getCount();

                // Grab every tooltip line (name box + lore) so we don't lose
                // anything before we know what format the price text is in.
                List<Text> tooltip = stack.getTooltip(
                        Item.TooltipContext.DEFAULT, client.player, TooltipType.BASIC);
                StringBuilder lore = new StringBuilder();
                for (int i = 1; i < tooltip.size(); i++) { // skip index 0, it's the name again
                    if (!lore.isEmpty()) lore.append(" | ");
                    lore.append(tooltip.get(i).getString());
                }

                String line = timestamp + "," +
                        screenTitle + "," +
                        slot.getIndex() + "," +
                        escapeCsv(itemId) + "," +
                        escapeCsv(displayName) + "," +
                        count + "," +
                        escapeCsv(lore.toString());

                writer.write(line);
                writer.newLine();
                written++;
            }
        } catch (IOException e) {
            System.err.println("[ContainerReader] Failed to write dump: " + e.getMessage());
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§c[ContainerReader] Failed to save dump!"), false);
            }
            return;
        }

        if (client.player != null) {
            client.player.sendMessage(Text.literal(
                    "§a[ContainerReader] Dumped §f" + written + " §aslot(s) from §f\"" + screen.getTitle().getString() + "\"§a → container_dump.csv"), false);
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n"))
            return "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }
}