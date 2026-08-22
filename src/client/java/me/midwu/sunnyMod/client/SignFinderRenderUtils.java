package me.midwu.sunnymod.client.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ColorHelper;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class SignFinderRenderUtils {

    public static void drawOutlinedBox(MatrixStack matrices, VertexConsumerProvider vertices, Box box, int color, float lineWidth) {
        matrices.push();

        // Camera-relative offset (exact SignFinder trick)
        Vec3d camOffset = MinecraftClient.getInstance().getBlockEntityRenderDispatcher().getCamera().getPos().multiply(-1);
        matrices.translate(camOffset.x, camOffset.y, camOffset.z);

        VertexConsumer lineConsumer = vertices.getBuffer(RenderLayer.getDebugLineStrip(lineWidth)); // or custom pipeline if you want full see-through

        // 24 line vertices for the 12 edges
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;
        float r = ColorHelper.Argb.getRed(color) / 255f;
        float g = ColorHelper.Argb.getGreen(color) / 255f;
        float b = ColorHelper.Argb.getBlue(color) / 255f;

        // Bottom
        lineConsumer.vertex(x1, y1, z1).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x2, y1, z1).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x2, y1, z2).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x1, y1, z2).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x1, y1, z1).color(r, g, b, 1f).normal(0, 0, 0).next();

        // Top
        lineConsumer.vertex(x1, y2, z1).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x2, y2, z1).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x2, y2, z2).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x1, y2, z2).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x1, y2, z1).color(r, g, b, 1f).normal(0, 0, 0).next();

        // Vertical
        lineConsumer.vertex(x1, y1, z1).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x1, y2, z1).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x2, y1, z1).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x2, y2, z1).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x2, y1, z2).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x2, y2, z2).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x1, y1, z2).color(r, g, b, 1f).normal(0, 0, 0).next();
        lineConsumer.vertex(x1, y2, z2).color(r, g, b, 1f).normal(0, 0, 0).next();

        matrices.pop();
    }

    // Optional: solid filled box (for the "filled" look SignFinder uses)
    public static void drawSolidBox(MatrixStack matrices, VertexConsumerProvider vertices, Box box, int color) {
        // Same camera offset + draw 6 quads with solid color (VertexFormat.POSITION_COLOR)
        // ... (exact implementation from SignFinder's RenderUtils)
    }
}