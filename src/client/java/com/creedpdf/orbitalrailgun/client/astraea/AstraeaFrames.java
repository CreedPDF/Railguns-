package com.creedpdf.orbitalrailgun.client.astraea;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.mixin.PostEffectProcessorAccessor;
import com.creedpdf.orbitalrailgun.krl.KrlText;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.PostEffectPass;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * ASTRAEA's impact frames (post shader {@code minecraft:shaders/program/astraea_frames.fsh}), hard-cut through the
 * real rendered frame:
 * <ul>
 *   <li><b>Ascension</b>: the bearer, the column of light and the sigils as a rugged, boiling ink outline - bone card,
 *   ink card, gold card, white-out - with ritual circles round the bearer; over it, a ragged black band across the
 *   screen: <i>ASCENSION</i> and under it <i>SUCCESSFULLY ABSORBED</i>, the letters jolting with each card.</li>
 *   <li><b>The split</b>: the frame cracked along a jagged fault and shoved apart, crazed with fractures, radial blast
 *   streaks, ember / ink / negative / white-out cards, longer and harder.</li>
 * </ul>
 */
public final class AstraeaFrames {
    private AstraeaFrames() {}

    private static final Identifier POST = RailgunMod.id("shaders/post/astraea_frames.json");
    private static PostEffectProcessor processor;
    private static boolean broken;
    private static int fbW, fbH;

    private static long start = -1, bannerStart = -1;
    private static float length, card;
    private static int kind, seed;
    private static float tilt;
    private static int[] cards = new int[0];
    private static final Random RNG = new Random();

    static void register() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return RailgunMod.id("astraea_frames");
            }

            @Override
            public void reload(ResourceManager manager) {
                if (processor != null) processor.close();
                processor = null;
                broken = false;
            }
        });
    }

    public static void ascension() {
        kind = 0;
        start = System.nanoTime();
        bannerStart = start;
        seed = RNG.nextInt(100000);
        card = 0.075f;
        length = 1.9f;
        cards = new int[]{4, 1, 2, 1, 3, 2, 4, 1, 2, 3, 1, 2, 1, 4, 2, 1, 3, 2};
    }

    public static void split(float faultAngle) {
        kind = 1;
        start = System.nanoTime();
        seed = RNG.nextInt(100000);
        tilt = faultAngle;
        card = 0.07f;
        length = 2.6f;
        RNG.setSeed(seed);
        int[] lead = {4, 1, 3, 2, 1, 4, 2, 1, 3};
        int n = 30;
        cards = new int[n];
        for (int i = 0; i < n; i++) {
            if (i < lead.length) cards[i] = lead[i];
            else {
                float r = RNG.nextFloat();
                cards[i] = r < 0.35f ? 1 : r < 0.65f ? 2 : r < 0.82f ? 3 : 4;
                if (cards[i] == cards[i - 1]) cards[i] = 1 + cards[i] % 4;
            }
        }
    }

    public static boolean active() {
        return start >= 0 && t() < length;
    }

    private static float t() {
        return (System.nanoTime() - start) / 1e9f;
    }

    /** {card, strength, index}. */
    private static float[] state() {
        float t = t();
        float hard = cards.length * card;
        if (t < hard) {
            int i = Math.min(cards.length - 1, (int) (t / card));
            return new float[]{cards[i], 1f, i};
        }
        float k = (t - hard) / Math.max(0.01f, length - hard);
        return new float[]{0, (1 - k) * (1 - k), cards.length};
    }

    /** Called by GameRendererMixin after the world (and the space cutscene) is drawn, before the HUD. */
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
                prog.getUniformByNameOrDummy("Clock").set(t());
                prog.getUniformByNameOrDummy("Center").set(0.5f, kind == 0 ? 0.46f : 0.5f);
                prog.getUniformByNameOrDummy("Kind").set((float) kind);
                prog.getUniformByNameOrDummy("Tilt").set(tilt);
            }
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            processor.render(tickDelta);
        } catch (Exception e) {
            RailgunMod.LOGGER.warn("ASTRAEA impact frames shader unavailable, using the overlay only", e);
            broken = true;
            if (processor != null) processor.close();
            processor = null;
        }
    }

    // ------------------------------------------------------------------------------------------- the banner

    private static final int BONE = 0xFFF8EE, INK = 0x060504, GOLD = 0xFFC75C;
    private static final float BANNER = 3.6f;

    static void renderHud(DrawContext ctx) {
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        if (broken && active()) {
            // no shader: a plain stand-in flash
            float[] s = state();
            int mode = (int) s[0];
            int bg = mode == 4 ? BONE : mode == 3 && kind == 0 ? GOLD : mode == 2 && kind == 1 ? 0xC8400A : INK;
            fill(ctx.getVertexConsumers().getBuffer(RenderLayer.getGui()), m, 0, 0, w, h, argb(s[1] * (mode == 0 ? 0.3f : 0.85f), bg));
            ctx.draw();
        }
        if (bannerStart < 0) return;
        float t = (System.nanoTime() - bannerStart) / 1e9f;
        if (t > BANNER) {
            bannerStart = -1;
            return;
        }
        float in = MathHelper.clamp(t / 0.12f, 0f, 1f), out = MathHelper.clamp((BANNER - t) / 0.5f, 0f, 1f);
        float a = Math.min(in, out);
        boolean hard = active() && state()[1] >= 1f;
        int idx = hard ? (int) state()[2] : -1;
        Random r = new Random(seed * 17L + idx);
        float cy = h * 0.5f;
        float bh = Math.max(34f, h * 0.15f) * (0.6f + 0.4f * in);
        VertexConsumer b = ctx.getVertexConsumers().getBuffer(RenderLayer.getGui());
        // the band: a black strip with torn top and bottom edges
        int segs = 64;
        Random edge = new Random(seed);
        for (int i = 0; i < segs; i++) {
            float x0 = w * i / (float) segs, x1 = w * (i + 1) / (float) segs;
            float jt = (edge.nextFloat() - 0.5f) * bh * 0.16f, jb = (edge.nextFloat() - 0.5f) * bh * 0.16f;
            quad(b, m, x0, cy - bh / 2 + jt, x0, cy + bh / 2 + jb, x1, cy + bh / 2 + jb, x1, cy - bh / 2 + jt, argb(a * 0.92f, INK));
        }
        // gold rules, broken
        for (int s = -1; s <= 1; s += 2) {
            float y = cy + s * bh * 0.36f;
            float x = w * 0.08f;
            Random gr = new Random(seed + s * 7L);
            while (x < w * 0.92f) {
                float len = w * (0.04f + gr.nextFloat() * 0.12f);
                fill(b, m, x, y - 0.5f, Math.min(x + len, w * 0.92f), y + 0.5f, argb(a * 0.85f, GOLD));
                x += len + w * (0.005f + gr.nextFloat() * 0.02f);
            }
        }
        ctx.draw();
        // the words
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        float jx = hard ? (r.nextFloat() - 0.5f) * 6f : 0f, jy = hard ? (r.nextFloat() - 0.5f) * 3f : 0f;
        int col = argb(a, hard && idx % 3 == 1 ? GOLD : BONE);
        drawSpaced(ctx, tr, "ASCENSION", w / 2f + jx, cy - bh * 0.13f + jy, bh * 0.05f, 0.42f, col);
        drawSpaced(ctx, tr, "SUCCESSFULLY  ABSORBED", w / 2f - jx * 0.5f, cy + bh * 0.24f - jy * 0.5f, bh * 0.016f, 0.5f, argb(a * 0.95f, GOLD));
    }

    /** Text centred at (x, y) at a scale, with letter spacing (in ems). */
    private static void drawSpaced(DrawContext ctx, TextRenderer tr, String s, float x, float y, float scale, float spacing, int col) {
        if ((col >>> 24) < 4) return;
        float em = 9f;
        float px = scale * (float) MinecraftClient.getInstance().getWindow().getScaleFactor();
        float total = 0;
        for (char ch : s.toCharArray()) total += tr.getWidth(KrlText.h(String.valueOf(ch), px)) + em * spacing;
        total -= em * spacing;
        var ms = ctx.getMatrices();
        ms.push();
        ms.translate(x, y, 0);
        ms.scale(scale, scale, 1);
        float cx = -total / 2f;
        for (char ch : s.toCharArray()) {
            Text tx = KrlText.h(String.valueOf(ch), px);
            ctx.drawText(tr, tx, Math.round(cx), -4, col, false);
            cx += tr.getWidth(tx) + em * spacing;
        }
        ms.pop();
    }

    private static void quad(VertexConsumer b, Matrix4f m, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int c) {
        b.vertex(m, x1, y1, 0).color(c).next();
        b.vertex(m, x2, y2, 0).color(c).next();
        b.vertex(m, x3, y3, 0).color(c).next();
        b.vertex(m, x4, y4, 0).color(c).next();
    }

    private static void fill(VertexConsumer b, Matrix4f m, float x0, float y0, float x1, float y1, int c) {
        quad(b, m, x0, y0, x0, y1, x1, y1, x1, y0, c);
    }

    private static int argb(float a, int rgb) {
        return (MathHelper.clamp((int) (a * 255), 0, 255) << 24) | (rgb & 0xFFFFFF);
    }
}
