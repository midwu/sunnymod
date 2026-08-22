package me.midwu.sunnymod.client.render;

import me.midwu.sunnymod.client.HighlightPoint; // or your BlockPos wrapper
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class HighlightRenderManager {

    public static void render(WorldRenderContext context, List<HighlightPoint> boxes) {
        MinecraftClient client = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers(); // or custom if you want full pipeline

        MatrixStack matrices = context.matrixStack();

        for (HighlightPoint point : boxes) {
            Box box = new Box(point.x - 0.5, point.y, point.z - 0.5, point.x + 0.5, point.y + 1, point.z + 0.5);

            // Camera-relative offset (critical for no jitter)
            Vec3d cam = client.gameRenderer.getCamera().getPos();
            matrices.push();
            matrices.translate(-cam.x, -cam.y, -cam.z);

            // Draw outlined box (green, lineWidth 2.0)
            SignFinderRenderUtils.drawOutlinedBox(matrices, immediate, box, 0x00FF00, 2.0f);

            matrices.pop();
        }
    }
}