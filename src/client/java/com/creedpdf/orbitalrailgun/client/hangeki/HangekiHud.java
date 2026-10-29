package com.creedpdf.orbitalrailgun.client.hangeki;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.hangeki.HangekiNet;
import com.creedpdf.orbitalrailgun.hangeki.ParryServer;
import com.creedpdf.orbitalrailgun.hangeki.TimeScale;
import com.creedpdf.orbitalrailgun.krl.KrlText;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * Everything HANGEKI draws on the screen:
 * <ul>
 *   <li>the threat meter, bottom-left, while the sword is in hand: a segmented bar that fills as the nearest threat
 *   closes in, with what it is and how many; in slow motion it flashes PARRY;</li>
 *   <li>slow motion itself: a crimson vignette, drifting speed lines, the window running out as a ring round the
 *   crosshair and a PARRY prompt; cinema bars during the stance;</li>
 *   <li>the impact frames: a burst of hard-cut anime frames (white flash, then black / red / white / amber cards
 *   with a perspective grid tunnel and burst wedges) with REFLECTED (or COUNTER) slammed across them in the
 *   Bangers impact face, settling and fading out.</li>
 * </ul>
 */
public final class HangekiHud {
    private HangekiHud() {}

    static final Style IMPACT = Style.EMPTY.withFont(RailgunMod.id("impact"));
    private static final Random RNG = new Random();

    // ---- impact frames
    private static long framesStart = -1;
    private static String framesText = "REFLECTED";
    private static float framesLen = 1.35f;
    private static int framesSeed;

    /** Slam the impact frames up. */
    public static void frames(String text, boolean big) {
        framesStart = System.nanoTime();
        framesText = text;
        framesLen = big ? 1.45f : 0.7f;
        framesSeed = RNG.nextInt();
    }

    public static boolean framesActive() {
        return framesStart >= 0 && (System.nanoTime() - framesStart) / 1e9f < framesLen;
    }

    public static void render(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) {
            if (framesActive()) frames(ctx, mc);
            return;
        }
        slowMotion(ctx, mc);
        if (!HangekiCine.active() && ParryClient.holding()) meter(ctx, mc);
        if (framesActive()) frames(ctx, mc);
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

    private static final int AMBER = 0xFFB02E, RED = 0xFF2A14, CRIMSON = 0x9A0A06, WHITE = 0xFFF6E8, BLACK = 0x050303, GOLD = 0xFFC860;

    // ------------------------------------------------------------------------------------------- the meter

    private static void meter(DrawContext ctx, MinecraftClient mc) {
        TextRenderer tr = mc.textRenderer;
        int h = ctx.getScaledWindowHeight();
        float target = ParryClient.meterKind == HangekiNet.K_NONE ? 0 : ParryClient.closeness;
        ParryClient.shown += (target - ParryClient.shown) * 0.25f;
        float v = ParryClient.shown;
        boolean slowed = ParryClient.slowed;
        long ms = System.currentTimeMillis();
        boolean blink = (ms / 110) % 2 == 0;
        int x0 = 8, y0 = h - 46, w = 168, hh = 36;
        MatrixStack s = ctx.getMatrices();
        Matrix4f m = s.peek().getPositionMatrix();
        VertexConsumer b = buf(ctx);
        // panel: dark glass with a slanted amber accent
        int panel = argb(0.62f, 0x120806);
        quad(b, m, x0, y0, x0, y0 + hh, x0 + w, y0 + hh, x0 + w + 8, y0, panel, panel, panel, panel);
        int accent = argb(1f, slowed ? (blink ? WHITE : RED) : v > 0.7f ? RED : AMBER);
        rect(b, m, x0, y0, x0 + 2, y0 + hh, accent);
        rect(b, m, x0, y0, x0 + w + 8, y0 + 1, argb(0.7f, slowed ? RED : AMBER));
        // segments
        int n = 20, sx = x0 + 8, sy = y0 + 17, sw = 6, sh = 9;
        int lit = (int) Math.ceil(v * n - 0.01f);
        for (int i = 0; i < n; i++) {
            float k = i / (float) (n - 1);
            int col;
            float a;
            if (slowed) {
                col = blink ? WHITE : RED;
                a = 1f;
            } else if (i < lit) {
                col = mix(AMBER, RED, k);
                a = v > 0.75f && blink ? 0.55f : 1f;
            } else {
                col = 0x3A1A12;
                a = 0.75f;
            }
            float px = sx + i * (sw + 2);
            quad(b, m, px + 2, sy, px, sy + sh, px + sw, sy + sh, px + sw + 2, sy, argb(a, col), argb(a, col), argb(a, col), argb(a, col));
        }
        ctx.draw();
        // labels
        String title = slowed ? "INCOMING" : ParryClient.meterKind == HangekiNet.K_NONE ? "HANGEKI // CLEAR" : "THREAT";
        ctx.drawText(tr, KrlText.t(title).styled(st -> st.withColor(slowed ? (blink ? WHITE : RED) : AMBER)), x0 + 8, y0 + 5, 0xFFFFFFFF, false);
        if (ParryClient.meterKind != HangekiNet.K_NONE) {
            String l = ParryClient.label + (ParryClient.count > 1 ? "  x" + ParryClient.count : "");
            MutableText lt = KrlText.t(l).styled(st -> st.withColor(0xF0E2D0));
            ctx.drawText(tr, lt, x0 + w - tr.getWidth(lt) + 4, y0 + 5, 0xFFFFFFFF, false);
        }
        if (slowed) {
            String key = HangekiClient.parryKey().getBoundKeyLocalizedText().getString().toUpperCase();
            MutableText p = KrlText.t("PARRY  [" + key + "]  /  RIGHT-CLICK").styled(st -> st.withColor(blink ? WHITE : RED));
            ctx.drawText(tr, p, x0 + 8, y0 + hh + 2 - 0, 0xFFFFFFFF, true);
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
        if (!cine && ParryClient.slowed) {
            boolean blink = (ms / 140) % 2 == 0;
            float a = MathHelper.clamp((ms - ParryClient.slowSince) / 180f, 0, 1);
            impactText(ctx, mc, "PARRY", cx, h * 0.72f, 1.05f, a, blink ? WHITE : RED, BLACK, 0f);
        }
    }

    // ------------------------------------------------------------------------------------------- impact frames

    /** Big text in the impact face: sheared, outlined by offset copies, centred on (x, y). */
    private static void impactText(DrawContext ctx, MinecraftClient mc, String s, float x, float y, float scale, float alpha, int fill, int back,
                                   float jitter) {
        if (alpha <= 0.02f) return;
        TextRenderer tr = mc.textRenderer;
        Text t = Text.literal(s).setStyle(IMPACT);
        int tw = tr.getWidth(t);
        MatrixStack ms = ctx.getMatrices();
        ms.push();
        ms.translate(x + (RNG.nextFloat() - 0.5f) * jitter, y + (RNG.nextFloat() - 0.5f) * jitter, 0);
        ms.scale(scale, scale, 1);
        ms.peek().getPositionMatrix().mul(new Matrix4f(1, 0, 0, 0, -0.22f, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1));
        float fx = -tw / 2f, fy = -11f;   // a 32 px Bangers capital spans about y-3 .. y+25
        int a = MathHelper.clamp((int) (alpha * 255), 0, 255) << 24;
        // a heavy offset shadow and an outline in the back colour, a red ghost, then the fill
        for (int[] o : new int[][]{{-2, 0}, {2, 0}, {0, -2}, {0, 2}, {-2, -2}, {2, 2}, {-2, 2}, {2, -2}, {5, 5}, {6, 6}})
            ctx.drawText(tr, t, (int) fx + o[0], (int) fy + o[1], a | back, false);
        ctx.drawText(tr, t, (int) fx - 4, (int) fy + 1, a | RED, false);
        ctx.drawText(tr, t, (int) fx, (int) fy, a | fill, false);
        ms.pop();
    }

    private static void frames(DrawContext ctx, MinecraftClient mc) {
        float t = (System.nanoTime() - framesStart) / 1e9f;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        float cx = w / 2f, cy = h / 2f;
        boolean big = framesLen > 1f;
        float hard = big ? 0.36f : 0.2f;
        // the frame cards: {bg, grid, wedge, text fill, text back}
        int[][] cards = big ? new int[][]{
                {WHITE, WHITE, WHITE, BLACK, WHITE},
                {BLACK, RED, WHITE, WHITE, RED},
                {RED, BLACK, BLACK, WHITE, BLACK},
                {WHITE, BLACK, RED, BLACK, WHITE},
                {BLACK, AMBER, RED, RED, WHITE},
                {CRIMSON, WHITE, GOLD, WHITE, BLACK}}
                : new int[][]{{WHITE, WHITE, WHITE, BLACK, WHITE}, {BLACK, RED, WHITE, WHITE, RED}, {RED, BLACK, BLACK, WHITE, BLACK}};
        float cardLen = hard / cards.length;
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        VertexConsumer b = buf(ctx);
        RNG.setSeed(framesSeed);
        float R = (float) Math.hypot(w, h);
        int bg, grid, wedge, fill, back;
        float bgA, fx;
        if (t < hard) {
            int ci = Math.min(cards.length - 1, (int) (t / cardLen));
            int[] c = cards[ci];
            bg = c[0];
            grid = c[1];
            wedge = c[2];
            fill = c[3];
            back = c[4];
            bgA = ci == 0 ? 1f : 0.9f;
            fx = 1f;
            RNG.setSeed(framesSeed + ci * 977L);
        } else {
            float k = (t - hard) / (framesLen - hard);
            bg = BLACK;
            grid = RED;
            wedge = WHITE;
            fill = WHITE;
            back = RED;
            bgA = 0.45f * (1 - k) * (1 - k);
            fx = (1 - k);
        }
        rect(b, m, 0, 0, w, h, argb(bgA, bg));
        if (t >= cardLen || t >= hard) {
            // perspective grid tunnel: concentric frames rushing outward plus rays to the vanishing point
            float zoom = (t * 2.6f) % 1f;
            float ga = (t < hard ? 1f : 0.7f) * fx;
            for (int i = 0; i < 9; i++) {
                float f = (float) Math.pow((i + zoom) / 9f, 2.2);
                float rx = w * 0.62f * f, ry = h * 0.62f * f;
                float lw = 1f + 3.5f * f;
                int c = argb(ga * (0.25f + 0.75f * f), grid);
                line(b, m, cx - rx, cy - ry, cx + rx, cy - ry, lw, c);
                line(b, m, cx - rx, cy + ry, cx + rx, cy + ry, lw, c);
                line(b, m, cx - rx, cy - ry, cx - rx, cy + ry, lw, c);
                line(b, m, cx + rx, cy - ry, cx + rx, cy + ry, lw, c);
            }
            for (int i = 0; i < 24; i++) {
                float a = i * MathHelper.TAU / 24 + 0.13f;
                line(b, m, cx + MathHelper.cos(a) * 18, cy + MathHelper.sin(a) * 18, cx + MathHelper.cos(a) * R, cy + MathHelper.sin(a) * R, 1.5f,
                        argb(ga * 0.8f, grid));
            }
            // burst wedges from the centre
            int nw = t < hard ? 26 : 18;
            for (int i = 0; i < nw; i++) {
                float a = RNG.nextFloat() * MathHelper.TAU;
                float r0 = 30 + RNG.nextFloat() * 50, r1 = R * (0.5f + RNG.nextFloat() * 0.6f);
                if (t >= hard) r0 += (t - hard) * 260;
                wedge(b, m, cx + MathHelper.cos(a) * r0, cy + MathHelper.sin(a) * r0, cx + MathHelper.cos(a) * r1, cy + MathHelper.sin(a) * r1,
                        10 + RNG.nextFloat() * 46, argb(fx * (t < hard ? 0.95f : 0.5f), wedge));
            }
            // a hard diagonal slash across the frame
            if (t < hard) {
                float o = (RNG.nextFloat() - 0.5f) * h * 0.4f;
                line(b, m, -20, cy + o + h * 0.5f, w + 20, cy + o - h * 0.5f, 6 + RNG.nextFloat() * 10, argb(1f, fill));
            }
        }
        ctx.draw();
        if (t >= cardLen * 0.7f) {
            TextRenderer tr = mc.textRenderer;
            int tw = tr.getWidth(Text.literal(framesText).setStyle(IMPACT));
            float base = w * (big ? 0.78f : 0.6f) / Math.max(1, tw);
            float settle = t < hard ? 1.18f - 0.18f * (t / hard) : 1f + 0.04f * (t - hard);
            float jitter = t < hard ? 14f : Math.max(0f, 8f * (1 - (t - hard) * 3));
            float alpha = t < hard ? 1f : MathHelper.clamp(1.4f * fx, 0, 1);
            impactText(ctx, mc, framesText, cx, cy, base * settle, alpha, fill, back, jitter);
        }
    }
}
