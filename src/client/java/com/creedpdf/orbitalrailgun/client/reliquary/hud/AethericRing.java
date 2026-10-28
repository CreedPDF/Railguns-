package com.creedpdf.orbitalrailgun.client.reliquary.hud;

import com.creedpdf.orbitalrailgun.client.reliquary.ReliquaryClientState;
import com.creedpdf.orbitalrailgun.client.reliquary.input.ReliquaryInput;
import com.creedpdf.orbitalrailgun.reliquary.ModDataComponentTypes;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

/**
 * The Aetheric Charge, shown only as geometry: a textureless ring of vertex-coloured triangles round the crosshair. A
 * faint full track; a neon-blue arc that fills clockwise from the top as charge builds (100 = a full circle); when full
 * it pulses and a second, outer ring appears - the sign the Orbital Link is ready. Drawn while a Reliquary weapon is
 * held. No text, no textures, no fonts.
 */
public final class AethericRing {
    private AethericRing() {}

    private static float shown;

    public static void render(DrawContext ctx, float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || !ReliquaryInput.holding(mc)) return;
        float target = ReliquaryClientState.charge / (float) ModDataComponentTypes.MAX_CHARGE;
        shown += (target - shown) * 0.15f;
        float cx = ctx.getScaledWindowWidth() / 2f, cy = ctx.getScaledWindowHeight() / 2f;
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        boolean full = target >= 1f;
        float time = ReliquaryClientState.ticks + pt;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        arc(m, cx, cy, 9f, 11f, 0f, 1f, 1f, 1f, 1f, 0.18f);
        float glow = full ? 0.75f + 0.25f * MathHelper.sin(time * 0.3f) : 0.85f;
        arc(m, cx, cy, 8.5f, 11.5f, 0f, shown, 0.22f, 0.62f, 1f, glow);
        if (full) arc(m, cx, cy, 13f, 14f, 0f, 1f, 0.6f, 0.85f, 1f, 0.35f + 0.3f * MathHelper.sin(time * 0.3f + 1f));
        RenderSystem.disableBlend();
    }

    /** A ring band from fraction a0 to a1 of a full turn, clockwise from 12 o'clock. */
    private static void arc(Matrix4f m, float cx, float cy, float r0, float r1, float a0, float a1, float r, float g, float b, float a) {
        if (a1 - a0 <= 0.001f) return;
        int segs = Math.max(2, (int) (64 * (a1 - a0)));
        BufferBuilder buf = Tessellator.getInstance().getBuffer();
        buf.begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
        for (int i = 0; i <= segs; i++) {
            float t = (a0 + (a1 - a0) * i / segs) * MathHelper.TAU - MathHelper.HALF_PI;
            float c = MathHelper.cos(t), s = MathHelper.sin(t);
            buf.vertex(m, cx + c * r1, cy + s * r1, 0).color(r, g, b, a).next();
            buf.vertex(m, cx + c * r0, cy + s * r0, 0).color(r, g, b, a).next();
        }
        BufferRenderer.drawWithGlobalProgram(buf.end());
    }
}
