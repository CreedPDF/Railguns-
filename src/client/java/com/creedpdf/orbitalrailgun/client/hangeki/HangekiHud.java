package com.creedpdf.orbitalrailgun.client.hangeki;

import com.creedpdf.orbitalrailgun.hangeki.HangekiNet;
import com.creedpdf.orbitalrailgun.hangeki.ParryServer;
import com.creedpdf.orbitalrailgun.hangeki.TimeScale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * Everything HANGEKI draws on the screen (no words in the middle of it):
 * <ul>
 *   <li>the threat panel, bottom-left, while the sword is in hand - styled after the reference's lock readout: a black
 *   plate fading out to the right, a bracket, and rows for the threat, its range (hairline ticks that fill as it
 *   closes), its class and the response (HOLD > REFUSE > RETURN, lit as the parry plays out);</li>
 *   <li>slow motion: a dark crimson vignette, faint drifting speed lines, the window running out as a ring round the
 *   crosshair; cinema bars during the stance;</li>
 *   <li>the impact frames' wireframe overlay ({@link ImpactFrames}).</li>
 * </ul>
 */
public final class HangekiHud {
    private HangekiHud() {}

    private static final Random RNG = new Random();

    public static void render(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        ImpactFrames.renderHud(ctx);
        if (mc.options.hudHidden) return;
        slowMotion(ctx, mc);
        if (!HangekiCine.active() && ParryClient.holding()) meter(ctx, mc);
    }

    // ------------------------------------------------------------------------------------------- primitives

    private static VertexConsumer buf(DrawContext ctx) {
        return ctx.getVertexConsumers().getBuffer(RenderLayer.getGui());
    }

    private static void quad(VertexConsumer b, Matrix4f m, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4,
                             int c1, int c2, int c3, int c4) {
        b.vertex(m, x1, y1, 0).color(c1).next();
        b.vertex(m, x2, y2, 0).color(c2).next();
        b.vertex(m, x3, y3, 0).color(c3).next();
        b.vertex(m, x4, y4, 0).color(c4).next();
    }

    private static void rect(VertexConsumer b, Matrix4f m, float x0, float y0, float x1, float y1, int c) {
        quad(b, m, x0, y0, x0, y1, x1, y1, x1, y0, c, c, c, c);
    }

    private static void line(VertexConsumer b, Matrix4f m, float x1, float y1, float x2, float y2, float w, int c) {
        float dx = x2 - x1, dy = y2 - y1, l = MathHelper.sqrt(dx * dx + dy * dy);
        if (l < 1e-3f) return;
        float nx = -dy / l * w * 0.5f, ny = dx / l * w * 0.5f;
        quad(b, m, x1 - nx, y1 - ny, x2 - nx, y2 - ny, x2 + nx, y2 + ny, x1 + nx, y1 + ny, c, c, c, c);
    }

    /** A long thin wedge from (x1,y1) (a point) to a base of width w at (x2,y2). */
    private static void wedge(VertexConsumer b, Matrix4f m, float x1, float y1, float x2, float y2, float w, int c) {
        float dx = x2 - x1, dy = y2 - y1, l = MathHelper.sqrt(dx * dx + dy * dy);
        if (l < 1e-3f) return;
        float nx = -dy / l * w * 0.5f, ny = dx / l * w * 0.5f;
        quad(b, m, x1, y1, x2 - nx, y2 - ny, x2 + nx, y2 + ny, x1, y1, c, c, c, c);
    }

    static int argb(float a, int rgb) {
        return (MathHelper.clamp((int) (a * 255), 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    static int mix(int a, int b, float t) {
        t = MathHelper.clamp(t, 0, 1);
        int r = (int) MathHelper.lerp(t, (a >> 16) & 255, (b >> 16) & 255);
        int g = (int) MathHelper.lerp(t, (a >> 8) & 255, (b >> 8) & 255);
        int bl = (int) MathHelper.lerp(t, a & 255, b & 255);
        return (r << 16) | (g << 8) | bl;
    }

    private static final int RED = 0xE0141C, WHITE = 0xF4EEE6, BLACK = 0x050303;

    // ------------------------------------------------------------------------------------------- the panel

    private static final int BONE = 0xF4EEE6, DIM = 0x6E6762, CRIM = 0xE0141C, PEACH = 0xF0B89A;

    private static void text(DrawContext ctx, TextRenderer tr, String s, float x, float y, int rgb, float a) {
        MatrixStack ms = ctx.getMatrices();
        ms.push();
        ms.translate(x, y, 0);
        ms.scale(0.75f, 0.75f, 1f);
        ctx.drawText(tr, Text.literal(s), 0, 0, argb(Math.max(0.05f, a), rgb), false);
        ms.pop();
    }

    private static String cls(byte kind) {
        return switch (kind) {
            case HangekiNet.K_PROJECTILE -> "PROJECTILE";
            case HangekiNet.K_MISSILE -> "ORDNANCE";
            case HangekiNet.K_BEAM -> "DIRECTED ENERGY";
            case HangekiNet.K_SONIC -> "SONIC";
            default -> "--";
        };
    }

    /** Bottom-left threat panel, in the style of the reference's lock readout: a fading black plate, a bracket, rows. */
    private static void meter(DrawContext ctx, MinecraftClient mc) {
        TextRenderer tr = mc.textRenderer;
        int h = ctx.getScaledWindowHeight();
        float target = ParryClient.meterKind == HangekiNet.K_NONE ? 0 : ParryClient.closeness;
        ParryClient.shown += (target - ParryClient.shown) * 0.25f;
        float v = ParryClient.shown;
        boolean slowed = ParryClient.slowed, none = ParryClient.meterKind == HangekiNet.K_NONE;
        long ms = System.currentTimeMillis();
        ParryClient.Stance st = ParryClient.stance(mc.player.getId());
        boolean stance = st != null && st.phase() == HangekiNet.PH_STANCE;
        boolean returned = ms - ParryClient.lastReturn < 1400;
        int x0 = 6, y0 = h - 66, w = 196, hh = 58;
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        VertexConsumer b = buf(ctx);
        int plate = argb(0.78f, 0x050404), clear = argb(0f, 0x050404);
        quad(b, m, x0, y0, x0, y0 + hh, x0 + w, y0 + hh, x0 + w, y0, plate, plate, clear, clear);
        int bracket = argb(0.9f, slowed || stance ? CRIM : BONE);
        rect(b, m, x0, y0, x0 + 1, y0 + hh, bracket);
        rect(b, m, x0, y0, x0 + 6, y0 + 1, bracket);
        rect(b, m, x0, y0 + hh - 1, x0 + 6, y0 + hh, bracket);
        int sep = argb(0.5f, BONE), sepEnd = argb(0f, BONE);
        quad(b, m, x0 + 4, y0 + 12, x0 + 4, y0 + 13, x0 + w - 20, y0 + 13, x0 + w - 20, y0 + 12, sep, sep, sepEnd, sepEnd);
        // range: a strip of hairline ticks
        int n = 30;
        float rx = x0 + 52, ry = y0 + 27;
        int lit = (int) Math.ceil(v * n - 0.01f);
        for (int i = 0; i < n; i++) {
            boolean on = i < lit;
            int col = !on ? DIM : (i > n * 0.66f || slowed) ? CRIM : BONE;
            float a = on ? (slowed && (ms / 120) % 2 == 0 ? 0.5f : 1f) : 0.35f;
            float th = i % 5 == 0 ? 7 : 5;
            rect(b, m, rx + i * 4, ry + 7 - th, rx + i * 4 + 2, ry + 7, argb(a, col));
        }
        ctx.draw();
        String header = stance ? "BLADE DRAWN" : slowed ? "TIME LOCK ENGAGED" : returned ? "RETURNED TO SENDER" : "HANGEKI  //  THREAT TRACK";
        int hc = slowed || stance ? (((ms / 260) % 2 == 0) ? CRIM : PEACH) : returned ? PEACH : BONE;
        text(ctx, tr, header, x0 + 5, y0 + 3, hc, 1f);
        text(ctx, tr, "THREAT", x0 + 5, y0 + 17, DIM, 1f);
        text(ctx, tr, none ? "NONE" : ParryClient.label + (ParryClient.count > 1 ? "  x" + ParryClient.count : ""), x0 + 52, y0 + 17, none ? DIM : BONE, 1f);
        text(ctx, tr, "RANGE", x0 + 5, y0 + 28, DIM, 1f);
        text(ctx, tr, "CLASS", x0 + 5, y0 + 38, DIM, 1f);
        text(ctx, tr, none ? "--" : cls(ParryClient.meterKind), x0 + 52, y0 + 38, none ? DIM : BONE, 1f);
        text(ctx, tr, "RESPONSE", x0 + 5, y0 + 48, DIM, 1f);
        float px = x0 + 52;
        String[] steps = {"HOLD", "REFUSE", "RETURN"};
        int active = returned ? 2 : stance ? 1 : slowed ? 0 : -1;
        for (int i = 0; i < 3; i++) {
            int c = i == active ? (i == 2 ? PEACH : BONE) : DIM;
            text(ctx, tr, steps[i], px, y0 + 48, c, 1f);
            px += tr.getWidth(steps[i]) * 0.75f + 3;
            if (i < 2) {
                text(ctx, tr, ">", px, y0 + 48, DIM, 1f);
                px += tr.getWidth(">") * 0.75f + 3;
            }
        }
    }

    // ------------------------------------------------------------------------------------------- slow motion

    private static void slowMotion(DrawContext ctx, MinecraftClient mc) {
        float sc = TimeScale.client;
        float k = MathHelper.clamp((1f - sc) / (1f - ParryServer.SLOW_SCALE), 0f, 1f);
        boolean cine = HangekiCine.active();
        if (k < 0.01f && !cine) return;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        float cx = w / 2f, cy = h / 2f;
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        VertexConsumer b = buf(ctx);
        long ms = System.currentTimeMillis();
        // warm, heavy air
        rect(b, m, 0, 0, w, h, argb(0.13f * k, 0x3A0804));
        // vignette: four bands fading inward
        int edge = argb(0.62f * k, 0x1A0200), in = argb(0f, 0x1A0200);
        float bw = w * 0.22f, bh = h * 0.26f;
        quad(b, m, 0, 0, 0, bh, w, bh, w, 0, edge, in, in, edge);
        quad(b, m, 0, h - bh, 0, h, w, h, w, h - bh, in, edge, edge, in);
        quad(b, m, 0, 0, 0, h, bw, h, bw, 0, edge, edge, in, in);
        quad(b, m, w - bw, 0, w - bw, h, w, h, w, 0, in, in, edge, edge);
        // drifting speed lines toward the centre
        RNG.setSeed(77);
        float rot = (ms % 60000) / 60000f * MathHelper.TAU * 0.6f;
        float R = (float) Math.hypot(w, h) * 0.6f;
        for (int i = 0; i < 44; i++) {
            float a = rot + RNG.nextFloat() * MathHelper.TAU;
            float r0 = R * (0.42f + RNG.nextFloat() * 0.25f + 0.08f * MathHelper.sin(ms / 400f + i));
            float ca = MathHelper.cos(a), sa = MathHelper.sin(a);
            wedge(b, m, cx + ca * r0, cy + sa * r0, cx + ca * R, cy + sa * R, 2f + RNG.nextFloat() * 5f, argb(0.16f * k, i % 3 == 0 ? RED : WHITE));
        }
        // cinema bars for the stance
        ParryClient.Stance st = ParryClient.stance(mc.player.getId());
        if (cine && st != null) {
            float u = ParryClient.progress(st, mc.getTickDelta());
            float e = ParryClient.smooth(Math.min(1f, u / 0.2f));
            float bar = h * 0.12f * e;
            rect(b, m, 0, 0, w, bar, argb(1f, BLACK));
            rect(b, m, 0, h - bar, w, h, argb(1f, BLACK));
            rect(b, m, 0, bar, w, bar + 1, argb(0.8f * e, RED));
            rect(b, m, 0, h - bar - 1, w, h - bar, argb(0.8f * e, RED));
        }
        // the window running out: a ring of ticks round the crosshair
        if (!cine && ParryClient.slowed && st != null && st.phase() == HangekiNet.PH_SLOW) {
            float u = ParryClient.progress(st, mc.getTickDelta());
            int segs = 48;
            int left = Math.round((1f - u) * segs);
            for (int i = 0; i < segs; i++) {
                float a0 = -MathHelper.HALF_PI + i * MathHelper.TAU / segs, a1 = a0 + MathHelper.TAU / segs * 0.7f;
                float r0 = 18, r1 = 23;
                int c = argb(i < left ? 0.95f : 0.18f, i < left ? (u > 0.7f && (ms / 90) % 2 == 0 ? WHITE : RED) : 0x502010);
                quad(b, m, cx + MathHelper.cos(a0) * r0, cy + MathHelper.sin(a0) * r0, cx + MathHelper.cos(a0) * r1, cy + MathHelper.sin(a0) * r1,
                        cx + MathHelper.cos(a1) * r1, cy + MathHelper.sin(a1) * r1, cx + MathHelper.cos(a1) * r0, cy + MathHelper.sin(a1) * r0, c, c, c, c);
            }
        }
        ctx.draw();
    }

}
