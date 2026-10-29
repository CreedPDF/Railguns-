package com.creedpdf.orbitalrailgun.client.hangeki;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.mixin.PostEffectProcessorAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectPass;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * Real impact frames, after the reference the owner gave: the actual rendered frame is hard-cut through a run of
 * stylised versions of itself (post shader {@code minecraft:shaders/program/hangeki_impact.fsh}): grainy crushed
 * monochrome, black with white speed lines tearing out of the impact, burned-out silhouettes and line art, a crimson
 * card with black silhouettes, an inverted bone-white card. Every few frames the card and the speed-line pattern change.
 * Over the calm and burned-out cards a white wireframe is drawn (a tilted wire globe round the impact, a broken web of
 * straight lines), like the reference. No text. The run then settles back into the scene.
 */
public final class ImpactFrames {
    private ImpactFrames() {}

    private static final Identifier POST = RailgunMod.id("shaders/post/hangeki_impact.json");
    private static PostEffectProcessor processor;
    private static boolean broken;
    private static int fbW, fbH;

    private static long start = -1;
    private static float length;
    private static int seed;
    private static int[] cards = new int[0];
    private static final float CARD = 0.065f;
    private static final Random RNG = new Random();

    static void register() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return RailgunMod.id("hangeki_impact_frames");
            }

            @Override
            public void reload(ResourceManager manager) {
                close();
                broken = false;
            }
        });
    }

    private static void close() {
        if (processor != null) processor.close();
        processor = null;
    }

    /** A full run (a parried projectile) or a short one (a counter, a guard reflect). */
    public static void start(boolean big) {
        start = System.nanoTime();
        seed = RNG.nextInt(100000);
        RNG.setSeed(seed);
        if (big) {
            length = 1.55f;
            int[] lead = {4, 1, 2, 3, 1, 0, 2, 1};
            int n = (int) (1.1f / CARD);
            cards = new int[n];
            for (int i = 0; i < n; i++) {
                if (i < lead.length) cards[i] = lead[i];
                else {
                    float r = RNG.nextFloat();
                    cards[i] = r < 0.3f ? 1 : r < 0.55f ? 2 : r < 0.8f ? 0 : r < 0.92f ? 3 : 4;
                    if (cards[i] == cards[i - 1]) cards[i] = (cards[i] + 1) % 3;
                }
            }
        } else {
            length = 0.6f;
            cards = new int[]{4, 2, 1, 0, 2};
        }
    }

    public static boolean active() {
        return start >= 0 && t() < length;
    }

    private static float t() {
        return (System.nanoTime() - start) / 1e9f;
    }

    /** {mode, strength, card index} now. */
    private static float[] state() {
        float t = t();
        float hard = cards.length * CARD;
        if (t < hard) {
            int i = Math.min(cards.length - 1, (int) (t / CARD));
            return new float[]{cards[i], 1f, i};
        }
        float k = (t - hard) / Math.max(0.01f, length - hard);
        return new float[]{0, (1 - k) * (1 - k), cards.length};
    }

    // ------------------------------------------------------------------------------------------- the post pass

    /** Called by GameRendererMixin after the world is drawn, before the HUD. */
    public static void renderPost(float tickDelta) {
        if (!active() || broken) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        int w = mc.getWindow().getFramebufferWidth(), h = mc.getWindow().getFramebufferHeight();
        try {
            if (processor == null) {
                processor = new PostEffectProcessor(mc.getTextureManager(), mc.getResourceManager(), mc.getFramebuffer(), POST);
                fbW = -1;
            }
            if (w != fbW || h != fbH) {
                processor.setupDimensions(w, h);
                fbW = w;
                fbH = h;
            }
            float[] s = state();
            for (PostEffectPass pass : ((PostEffectProcessorAccessor) processor).orbitalRailgun$passes()) {
                var prog = pass.getProgram();
                prog.getUniformByNameOrDummy("Mode").set(s[0]);
                prog.getUniformByNameOrDummy("Strength").set(s[1]);
                prog.getUniformByNameOrDummy("Seed").set((float) ((seed + (int) s[2] * 131) % 1000));
                prog.getUniformByNameOrDummy("Clock").set((System.nanoTime() / 1_000_000L % 100_000L) / 1000f);
                prog.getUniformByNameOrDummy("Center").set(0.5f, 0.5f);
            }
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            processor.render(tickDelta);
        } catch (Exception e) {
            RailgunMod.LOGGER.warn("HANGEKI impact frames shader unavailable, using the overlay only", e);
            broken = true;
            close();
        }
    }

    // ------------------------------------------------------------------------------------------- the overlay

    private static final int BONE = 0xFFF7EE, CRIMSON = 0xDB0A12, INK = 0x080606;

    /** The wireframe over the frames (and, if the shader could not load, the speed lines too). */
    static void renderHud(DrawContext ctx) {
        if (!active()) return;
        float[] s = state();
        int mode = (int) s[0];
        float a = s[1];
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        float cx = w / 2f, cy = h / 2f;
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        VertexConsumer b = ctx.getVertexConsumers().getBuffer(RenderLayer.getGui());
        Random r = new Random(seed * 31L + (long) s[2]);
        if (broken) {
            // no shader: a plain stand-in for the cards
            int bg = mode == 3 ? CRIMSON : mode == 4 ? BONE : INK;
            rect(b, m, 0, 0, w, h, argb(a * (mode == 0 ? 0.45f : 0.9f), bg));
            if (mode != 0) speedLines(b, m, cx, cy, w, h, r, argb(a, mode >= 3 ? INK : BONE));
        }
        int wire = mode == 4 || mode == 3 ? INK : BONE;
        float wa = a * (mode == 1 ? 0.0f : mode == 0 ? 0.7f : 0.9f);
        if (wa > 0.02f) {
            int c = argb(wa, wire);
            // a tilted wire globe round the impact, turning
            float t = (System.nanoTime() - start) / 1e9f;
            float R = Math.min(w, h) * (0.24f + 0.05f * t);
            float tilt = 0.32f;
            for (int i = -3; i <= 3; i++) {
                float lat = i / 4f;
                float rr = R * MathHelper.sqrt(1 - lat * lat);
                ellipse(b, m, cx, cy + lat * R * 0.85f, rr, rr * tilt, 0.8f, c);
            }
            for (int i = 0; i < 8; i++) {
                float ang = i * MathHelper.PI / 8 + t * 0.9f;
                ellipse(b, m, cx, cy, R * Math.abs(MathHelper.cos(ang)), R * 0.85f, 0.8f, c);
            }
            // two big orbit rings, flattened
            ellipse(b, m, cx, cy + R * 0.25f, R * 1.9f, R * 0.42f, 1.2f, c);
            ellipse(b, m, cx, cy + R * 0.55f, R * 2.8f, R * 0.6f, 1.0f, argb(wa * 0.7f, wire));
            // a broken web of straight lines
            float px = r.nextFloat() * w, py = r.nextFloat() * h;
            for (int i = 0; i < 9; i++) {
                float qx = r.nextFloat() * w, qy = r.nextFloat() * h;
                line(b, m, px, py, qx, qy, 0.8f, argb(wa * 0.8f, i % 4 == 0 ? CRIMSON : wire));
                px = qx;
                py = qy;
            }
        }
        ctx.draw();
    }

    private static void speedLines(VertexConsumer b, Matrix4f m, float cx, float cy, int w, int h, Random r, int c) {
        float R = (float) Math.hypot(w, h);
        for (int i = 0; i < 70; i++) {
            float ang = r.nextFloat() * MathHelper.TAU;
            float r0 = R * (0.08f + r.nextFloat() * 0.3f);
            float wd = 2 + r.nextFloat() * 22;
            float x1 = cx + MathHelper.cos(ang) * r0, y1 = cy + MathHelper.sin(ang) * r0;
            float x2 = cx + MathHelper.cos(ang) * R, y2 = cy + MathHelper.sin(ang) * R;
            float nx = -MathHelper.sin(ang) * wd * 0.5f, ny = MathHelper.cos(ang) * wd * 0.5f;
            quad(b, m, x1, y1, x2 - nx, y2 - ny, x2 + nx, y2 + ny, x1, y1, c);
        }
    }

    private static void ellipse(VertexConsumer b, Matrix4f m, float cx, float cy, float rx, float ry, float lw, int c) {
        int n = 40;
        for (int i = 0; i < n; i++) {
            float a0 = i * MathHelper.TAU / n, a1 = (i + 1) * MathHelper.TAU / n;
            line(b, m, cx + MathHelper.cos(a0) * rx, cy + MathHelper.sin(a0) * ry, cx + MathHelper.cos(a1) * rx, cy + MathHelper.sin(a1) * ry, lw, c);
        }
    }

    static void quad(VertexConsumer b, Matrix4f m, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int c) {
        b.vertex(m, x1, y1, 0).color(c).next();
        b.vertex(m, x2, y2, 0).color(c).next();
        b.vertex(m, x3, y3, 0).color(c).next();
        b.vertex(m, x4, y4, 0).color(c).next();
    }

    static void rect(VertexConsumer b, Matrix4f m, float x0, float y0, float x1, float y1, int c) {
        quad(b, m, x0, y0, x0, y1, x1, y1, x1, y0, c);
    }

    static void line(VertexConsumer b, Matrix4f m, float x1, float y1, float x2, float y2, float w, int c) {
        float dx = x2 - x1, dy = y2 - y1, l = MathHelper.sqrt(dx * dx + dy * dy);
        if (l < 1e-3f) return;
        float nx = -dy / l * w * 0.5f, ny = dx / l * w * 0.5f;
        quad(b, m, x1 - nx, y1 - ny, x2 - nx, y2 - ny, x2 + nx, y2 + ny, x1 + nx, y1 + ny, c);
    }

    static int argb(float a, int rgb) {
        return (MathHelper.clamp((int) (a * 255), 0, 255) << 24) | (rgb & 0xFFFFFF);
    }
}
