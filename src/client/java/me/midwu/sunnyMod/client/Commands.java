package me.midwu.sunnyMod.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Map;

public class Commands implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        // ── Outgoing command listener for warp tracking ───────────────────────
        ClientSendMessageEvents.COMMAND.register((command) -> {
            String cmd = command.trim().toLowerCase();

            if (cmd.startsWith("warp ")) {
                // /warp [name] — update current warp, remember previous
                String warpName = command.trim().substring("warp ".length()).trim();
                if (!warpName.isEmpty()) {
                    ShopLogger.pushWarp("/warp " + warpName);
                }
            } else if (cmd.equals("back")) {
                // /back — only swap if previous teleport was a /warp
                ShopLogger.swapWarps();
            }
        });

        // Register both /sunnymod and /sm
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
                                    client.player.sendMessage(Text.literal("=== Total Items Sold ==="), false);
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
                                    client.player.sendMessage(
                                            Text.literal("=== Total Money Earned ===\nTotal: $" +
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
                                        client.player.sendMessage(Text.literal("§aEarnings reset to $0.00"), false);
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
                                                client.player.sendMessage(
                                                        Text.literal("§aFishing timer offset set to +" + offset + " hours"), false);
                                            }
                                            return 1;
                                        })
                                )
                        )
                )

                // ── shoplogger ────────────────────────────────────────────────────
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("shoplogger")
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("setwarp")
                                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument(
                                                "warp", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String input = StringArgumentType.getString(context, "warp").trim();
                                            // Strip leading slash if present
                                            String warpName = input.startsWith("/warp ")
                                                    ? input.substring("/warp ".length()).trim()
                                                    : input.startsWith("warp ")
                                                      ? input.substring("warp ".length()).trim()
                                                      : input;
                                            // Only update the saved warp — no teleport
                                            ShopLogger.pushWarp("/warp " + warpName);
                                            MinecraftClient client = MinecraftClient.getInstance();
                                            if (client.player != null && Config.get().showWarpSet()) {
                                                client.player.sendMessage(
                                                        Text.literal("§a[Shop] Warp set to: /warp " + warpName), false);
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