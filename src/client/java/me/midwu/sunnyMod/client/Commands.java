package me.midwu.sunnyMod.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Commands implements ClientModInitializer {

    // Matches [/warp serstore] style clickable chat links
    private static final Pattern CHAT_WARP_LINK = Pattern.compile(
            "\\[/warp ([\\w]+)\\]");

    // Confirmed teleport messages from the server
    private static final String TP_RETURNING    = "→ ᴛᴘ: Returning to previous location.";
    private static final String TP_TELEPORTING  = "→ ᴛᴘ: Teleporting...";

    // Whether we're expecting a /back swap (set when /back command is sent)
    private static volatile boolean pendingBack   = false;
    // Whether we're expecting a /tpaccept teleport
    private static volatile boolean pendingTpAccept = false;
    // Destination warp recorded when /tpa or /tpahere is sent
    private static volatile String  pendingWarpName = "";

    @Override
    public void onInitializeClient() {

        // ── Outgoing command listener ─────────────────────────────────────────
        ClientSendMessageEvents.COMMAND.register(command -> {
            String cmd = command.trim().toLowerCase();

            if (cmd.startsWith("warp ")) {
                String warpName = command.trim().substring("warp ".length()).trim();
                if (!warpName.isEmpty()) {
                    ShopLogger.pushWarp("/warp " + warpName);
                }

            } else if (cmd.equals("back")) {
                // Mark as pending — only actually swap when we see the TP confirmation message
                pendingBack = true;

            } else if (cmd.equals("spawn")) {
                ShopLogger.pushWarp("/spawn");

            } else if (cmd.equals("tpaccept")) {
                // Will teleport to wherever the tpahere request originated from.
                // We can't know the destination warp, so we clear current warp.
                pendingTpAccept = true;
            }
        });

        // ── Incoming chat message listener for TP confirmations ───────────────
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString();

            // /back confirmed — now actually swap
            if (pendingBack && text.contains(TP_RETURNING)) {
                ShopLogger.swapWarps();
                pendingBack = false;
            }

            // /spawn or /tpaccept confirmed by "Teleporting..." message
            if (text.contains(TP_TELEPORTING)) {
                if (pendingTpAccept) {
                    // Teleported via tpaccept — destination unknown, clear warp
                    ShopLogger.pushWarp("");
                    pendingTpAccept = false;
                }
                // /spawn is already pushed on command send, no extra handling needed
            }

            // Clickable chat warp links: [/warp serstore]
            Matcher m = CHAT_WARP_LINK.matcher(text);
            if (m.find()) {
                // This just detects the link exists in chat — the actual teleport
                // happens when the player clicks it, which fires another COMMAND event
                // for "warp [name]", so it's handled by the outgoing listener above.
                // No action needed here.
            }
        });

        // ── Command registration ───────────────────────────────────────────────
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(buildCommandTree("sunnymod"));
            dispatcher.register(buildCommandTree("sm"));
        });
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> buildCommandTree(String root) {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal(root)

                // ── earnings ──────────────────────────────────────────────────────
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("earnings")
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("items")
                                .executes(context -> {
                                    MinecraftClient client = MinecraftClient.getInstance();
                                    if (client.player == null) return 1;
                                    client.player.sendMessage(
                                            Text.literal("=== Total Items Sold ==="), false);
                                    for (Map.Entry<String, Integer> entry : EarningsDetector.getItemTotals().entrySet()) {
                                        client.player.sendMessage(
                                                Text.literal("- " + entry.getKey() + ": " + entry.getValue()), false);
                                    }
                                    return 1;
                                })
                        )
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("total")
                                .executes(context -> {
                                    MinecraftClient client = MinecraftClient.getInstance();
                                    if (client.player == null) return 1;
                                    client.player.sendMessage(Text.literal(
                                            "=== Total Money Earned ===\nTotal: $" +
                                                    String.format("%,.2f", EarningsDetector.getTotalAmount())), false);
                                    return 1;
                                })
                        )
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("reset")
                                .executes(context -> {
                                    EarningsDetector.reset();
                                    MinecraftClient client = MinecraftClient.getInstance();
                                    if (client.player == null) return 1;
                                    if (Config.get().showEarningsReset()) {
                                        client.player.sendMessage(
                                                Text.literal("§aEarnings reset to $0.00"), false);
                                    }
                                    return 1;
                                })
                        )
                )

                // ── fishing ───────────────────────────────────────────────────────
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("fishing")
                                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("adjusttime")
                                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument(
                                                        "offset", IntegerArgumentType.integer(0, 2))
                                                .executes(context -> {
                                                    int offset = context.getArgument("offset", Integer.class);
                                                    Config.get().timeOffset = offset;
                                                    Config.save();
                                                    MinecraftClient client = MinecraftClient.getInstance();
                                                    if (client.player == null) return 1;
                                                    if (Config.get().showFishingOffset()) {
                                                        client.player.sendMessage(Text.literal(
                                                                "§aFishing timer offset set to +" + offset + " hours"), false);
                                                    }
                                                    return 1;
                                                })
                                        )
                                )

                        // Commented out until fishing method/rod tracking is re-enabled
                        // .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("setmethod") ...
                        // .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("setrod") ...
                )

                // ── shoplogger ────────────────────────────────────────────────────
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("shoplogger")
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("setwarp")
                                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument(
                                                "warp", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String input = StringArgumentType.getString(context, "warp").trim();
                                            String warpName = input.startsWith("/warp ")
                                                    ? input.substring("/warp ".length()).trim()
                                                    : input.startsWith("warp ")
                                                      ? input.substring("warp ".length()).trim()
                                                      : input;
                                            ShopLogger.pushWarp("/warp " + warpName);
                                            MinecraftClient client = MinecraftClient.getInstance();
                                            if (client.player != null && Config.get().showWarpSet()) {
                                                client.player.sendMessage(Text.literal(
                                                        "§a[Shop] Warp set to: /warp " + warpName), false);
                                            }
                                            return 1;
                                        })
                                )
                        )
                )

                // ── hud ───────────────────────────────────────────────────────────
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("hud")
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("edit")
                                .executes(context -> {
                                    MinecraftClient client = MinecraftClient.getInstance();
                                    if (client.player != null) {
                                        client.execute(() -> client.setScreen(new HudEditScreen()));
                                    }
                                    return 1;
                                })
                        )
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("reset")
                                .executes(context -> {
                                    Config.get().resetHudDefaults();
                                    Config.save();
                                    MinecraftClient client = MinecraftClient.getInstance();
                                    if (client.player != null) {
                                        client.player.sendMessage(
                                                Text.literal("§aHUD positions reset to defaults"), false);
                                    }
                                    return 1;
                                })
                        )
                );
    }
}