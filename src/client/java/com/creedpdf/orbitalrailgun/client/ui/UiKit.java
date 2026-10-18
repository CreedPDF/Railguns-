package com.creedpdf.orbitalrailgun.client.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/**
 * Drawing kit for the ARES-9 interfaces (tablet, pod console, HUD). Everything is drawn from
 * fills and the vanilla font so it scales cleanly with the zoomed-in canvases:
 * chamfered panels with glowing edges, corner brackets, hex-grid backgrounds, scanlines,
 * segmented bars, letter-spaced titles, tags and radial gauges.
 */
public final class UiKit {
    private UiKit() {}

    public static final int BG = 0xF0050A10, PANEL = 0xD00A121A, PANEL_HI = 0xE0101C28;
    public static final int CYAN = 0xFF33E0FF, CYAN_DIM = 0xFF1A6F85, RED = 0xFFFF3B30, AMBER = 0xFFFFB020,
            GREEN = 0xFF4CFF8A, TEXT = 0xFFE6F2FF, MUTED = 0xFF6E8496, VIOLET = 0xFFB070FF;

    /** Panel with 45-degree cut corners (top-left and bottom-right), filled, with a 1 px edge. */
    public static void chamfer(DrawContext ctx, int x, int y, int w, int h, int cut, int fill, int edge) {
        for (int r = 0; r < h; r++) {
            int l = r < cut ? cut - r : 0;
            int rr = r >= h - cut ? r - (h - cut) + 1 : 0;
            ctx.fill(x + l, y + r, x + w - rr, y + r + 1, fill);
            if (edge != 0) {
                ctx.fill(x + l, y + r, x + l + 1, y + r + 1, edge);
                ctx.fill(x + w - rr - 1, y + r, x + w - rr, y + r + 1, edge);
            }
        }
        if (edge != 0) {
            ctx.fill(x + cut, y, x + w, y + 1, edge);
            ctx.fill(x, y + h - 1, x + w - cut, y + h, edge);
        }
    }

    /** Glowing accent edge: a bright line plus a faint halo row on each side. */
    public static void glowLine(DrawContext ctx, int x0, int y, int x1, int color) {
        int rgb = color & 0xFFFFFF;
        ctx.fill(x0, y - 1, x1, y, (0x30 << 24) | rgb);
        ctx.fill(x0, y, x1, y + 1, color);
        ctx.fill(x0, y + 1, x1, y + 2, (0x30 << 24) | rgb);
    }

    public static void brackets(DrawContext ctx, int x, int y, int w, int h, int len, int color) {
        ctx.fill(x, y, x + len, y + 1, color);
        ctx.fill(x, y, x + 1, y + len, color);
        ctx.fill(x + w - len, y, x + w, y + 1, color);
        ctx.fill(x + w - 1, y, x + w, y + len, color);
        ctx.fill(x, y + h - 1, x + len, y + h, color);
        ctx.fill(x, y + h - len, x + 1, y + h, color);
        ctx.fill(x + w - len, y + h - 1, x + w, y + h, color);
        ctx.fill(x + w - 1, y + h - len, x + w, y + h, color);
    }

    /** Faint hexagonal dot grid, drifting slowly. */
    public static void hexGrid(DrawContext ctx, int x, int y, int w, int h, float time, int color) {
        int step = 12;
        int off = (int) (time * 0.2f) % step;
        for (int gy = 0; gy < h; gy += step / 2) {
            int row = gy / (step / 2);
            for (int gx = (row % 2) * step / 2 - off; gx < w; gx += step) {
                if (gx < 0) continue;
                ctx.fill(x + gx, y + gy, x + gx + 1, y + gy + 1, color);
            }
        }
    }

    public static void scanlines(DrawContext ctx, int x, int y, int w, int h, float time) {
        for (int yy = 0; yy < h; yy += 3) ctx.fill(x, y + yy, x + w, y + yy + 1, 0x14000000);
        int band = (int) ((time * 2.2f) % (h + 40)) - 20;
        for (int i = 0; i < 20; i++) {
            int yy = band + i;
            if (yy < 0 || yy >= h) continue;
            int a = (int) (14 * (1 - Math.abs(i - 10) / 10f));
            ctx.fill(x, y + yy, x + w, y + yy + 1, (a << 24) | 0x33E0FF);
        }
    }

    /** Segmented bar, lit segments graded from {@code from} to {@code to}. */
    public static void segBar(DrawContext ctx, int x, int y, int w, int h, float value, int segs, int from, int to, int off) {
        int sw = Math.max(2, w / segs);
        int lit = (int) Math.ceil(MathHelper.clamp(value, 0, 1) * segs);
        for (int i = 0; i < segs; i++) {
            int sx = x + i * sw;
            ctx.fill(sx, y, sx + sw - 1, y + h, i < lit ? lerp(from, to, (float) i / Math.max(1, segs - 1)) : off);
        }
    }

    public static void spaced(DrawContext ctx, TextRenderer tr, String text, float x, float y, int color, float spacing, float scale, boolean shadow) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x, y, 0);
        ctx.getMatrices().scale(scale, scale, 1f);
        float cx = 0;
        for (int i = 0; i < text.length(); i++) {
            String c = String.valueOf(text.charAt(i));
            ctx.drawText(tr, c, (int) cx, 0, color, shadow);
            cx += tr.getWidth(c) + spacing;
        }
        ctx.getMatrices().pop();
    }

    public static float spacedWidth(TextRenderer tr, String text, float spacing, float scale) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) w += tr.getWidth(String.valueOf(text.charAt(i))) + spacing;
        return (w - spacing) * scale;
    }

    /** Small filled tag with dark text, e.g. a state chip. */
    public static int tag(DrawContext ctx, TextRenderer tr, String text, int x, int y, int color) {
        int w = tr.getWidth(text) + 8;
        chamfer(ctx, x, y, w, 11, 3, color, 0);
        ctx.drawText(tr, text, x + 4, y + 2, 0xFF05080C, false);
        return w;
    }

    /** Radial arc gauge (value 0..1) drawn as a ring of dots. */
    public static void arcGauge(DrawContext ctx, int cx, int cy, int r, float value, int on, int off) {
        int n = 48;
        for (int i = 0; i < n; i++) {
            float f = i / (float) (n - 1);
            float a = (float) Math.toRadians(135 + 270 * f);
            int px = cx + (int) (MathHelper.cos(a) * r), py = cy + (int) (MathHelper.sin(a) * r);
            ctx.fill(px - 1, py - 1, px + 1, py + 1, f <= value ? on : off);
        }
    }

    public static void ring(DrawContext ctx, int cx, int cy, int r, int color, int dots) {
        for (int i = 0; i < dots; i++) {
            float a = i / (float) dots * MathHelper.TAU;
            int px = cx + (int) (MathHelper.cos(a) * r), py = cy + (int) (MathHelper.sin(a) * r);
            ctx.fill(px, py, px + 1, py + 1, color);
        }
    }

    public static void line(DrawContext ctx, float x0, float y0, float x1, float y1, int color) {
        int n = (int) Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) + 1;
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            int px = (int) (x0 + (x1 - x0) * t), py = (int) (y0 + (y1 - y0) * t);
            ctx.fill(px, py, px + 1, py + 1, color);
        }
    }

    /** Octagon outline, used for the pod / emplacement schematics. */
    public static void octagon(DrawContext ctx, float cx, float cy, float r, float rot, int color) {
        for (int k = 0; k < 8; k++) {
            float a0 = rot + k * MathHelper.TAU / 8f + MathHelper.PI / 8f, a1 = a0 + MathHelper.TAU / 8f;
            line(ctx, cx + MathHelper.cos(a0) * r, cy + MathHelper.sin(a0) * r, cx + MathHelper.cos(a1) * r, cy + MathHelper.sin(a1) * r, color);
        }
    }

    public static int lerp(int a, int b, float t) {
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    public static int alpha(int color, float a) {
        return ((int) (((color >>> 24) & 0xFF) * MathHelper.clamp(a, 0, 1)) << 24) | (color & 0xFFFFFF);
    }

    /** Word-wraps text to a pixel width. */
    public static java.util.List<String> wrap(TextRenderer tr, String text, int width) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.isEmpty() ? word : line + " " + word;
            if (tr.getWidth(test) > width && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }
}
