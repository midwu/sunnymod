package me.midwu.sunnyMod.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Diagnostic: reads sign block entities that the server has already sent to the client
 * (no clicking required) and dumps their text + raw NBT to config/sunnyMod/sign_dump.txt.
 *
 * Commands:
 *   /signdump [radius]        dump every loaded sign within radius blocks (default 64, max 256)
 *   /signdump packets on|off  log incoming sign block-entity update packets to the event log
 *                             (PACKET_SIGN_UPDATE) so you can measure how fast signs refresh
 */
public final class SignDump {

    private static final Path OUT = ShopLogger.getConfigDir().resolve("sign_dump.txt");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Line 2 of a shop sign looks like "Selling 276" / "Buying 12". Heuristic only. */
    private static final Pattern SHOP_LINE2 = Pattern.compile("^(Selling|Buying)\\b.*", Pattern.CASE_INSENSITIVE);

    private static volatile boolean logPackets = false;
    private static boolean initialized;

    private SignDump() {}

    public static boolean isLoggingPackets() { return logPackets; }

    public static void init() {
        if (initialized) return;
        initialized = true;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("signdump")
                        .executes(ctx -> run(ctx.getSource(), 64))
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument(
                                        "radius", IntegerArgumentType.integer(1, 256))
                                .executes(ctx -> run(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius"))))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("packets")
                                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                                        .executes(ctx -> {
                                            logPackets = true;
                                            ctx.getSource().sendFeedback(Text.literal(
                                                    "§a[SignDump] Logging sign update packets to event_log.txt"));
                                            return 1;
                                        }))
                                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                                        .executes(ctx -> {
                                            logPackets = false;
                                            ctx.getSource().sendFeedback(Text.literal(
                                                    "§e[SignDump] Sign packet logging off"));
                                            return 1;
                                        })))));
    }

    private static int run(FabricClientCommandSource source, int radius) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        ClientPlayerEntity player = mc.player;
        if (world == null || player == null) return 0;

        BlockPos origin = player.getBlockPos();
        ChunkPos pc = player.getChunkPos();
        int chunkRadius = (radius >> 4) + 1;

        List<SignBlockEntity> signs = new ArrayList<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(pc.x + dx, pc.z + dz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof SignBlockEntity sign && sign.getPos().isWithinDistance(origin, radius)) {
                        signs.add(sign);
                    }
                }
            }
        }
        signs.sort(Comparator.<SignBlockEntity>comparingInt(s -> s.getPos().getX())
                .thenComparingInt(s -> s.getPos().getY())
                .thenComparingInt(s -> s.getPos().getZ()));

        int shopLike = 0;
        List<String> preview = new ArrayList<>();
        try (BufferedWriter w = Files.newBufferedWriter(OUT)) {
            w.write("# SignDump " + LocalDateTime.now().format(TS)
                    + " | center=" + origin.getX() + " " + origin.getY() + " " + origin.getZ()
                    + " | radius=" + radius + " | signs=" + signs.size());
            w.newLine();
            for (SignBlockEntity sign : signs) {
                BlockPos p = sign.getPos();
                String[] front = lines(sign.getFrontText());
                String[] back = lines(sign.getBackText());
                boolean shop = SHOP_LINE2.matcher(front[1]).matches();
                if (shop) {
                    shopLike++;
                    if (preview.size() < 5) {
                        preview.add("§7" + p.getX() + " " + p.getY() + " " + p.getZ() + "§f "
                                + String.join(" | ", front));
                    }
                }
                w.write("--- " + p.getX() + " " + p.getY() + " " + p.getZ()
                        + " | " + Registries.BLOCK.getId(sign.getCachedState().getBlock()).getPath()
                        + " | shopLike=" + shop
                        + " | waxed=" + sign.isWaxed());
                w.newLine();
                w.write("front: " + String.join(" | ", front));
                w.newLine();
                w.write("back:  " + String.join(" | ", back));
                w.newLine();
                // Raw NBT keeps the original text components (hover/click events, colours, etc.)
                w.write("nbt:   " + sign.createNbt(world.getRegistryManager()));
                w.newLine();
            }
        } catch (Exception e) {
            source.sendFeedback(Text.literal("§c[SignDump] Failed to write dump: " + e.getMessage()));
            return 0;
        }

        source.sendFeedback(Text.literal("§a[SignDump] " + signs.size() + " signs (" + shopLike
                + " shop-like) within " + radius + " blocks → config/sunnyMod/sign_dump.txt"));
        for (String line : preview) source.sendFeedback(Text.literal(line));
        return signs.size();
    }

    private static String[] lines(SignText text) {
        Text[] messages = text.getMessages(false);
        String[] out = new String[4];
        for (int i = 0; i < 4; i++) {
            out[i] = (i < messages.length && messages[i] != null) ? messages[i].getString() : "";
        }
        return out;
    }
}