package com.creedpdf.orbitalrailgun.client.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

import java.util.List;
import java.util.Locale;

/**
 * Deluxs Armory terminal kit: the look of a classified command terminal. Near-black glass, hairline
 * rules, small monochrome text, red accents, file-tree navigation and bracketed text commands
 * instead of big buttons. Everything is fills + the vanilla font, so it scales with the canvas.
 */
public final class Term {
    private Term() {}

    public static final int BG = 0xF4030304, BG2 = 0xFF0A0A0B, RULE = 0xFF2A1210, RULE_HI = 0xFF5E1A16;
    public static final int RED = 0xFFE8382E, RED_DIM = 0xFF6A1A16, TEXT = 0xFFCFCCC4, DIM = 0xFF6A6864, FAINT = 0xFF34322F;
    public static final int AMBER = 0xFFFFA62B, OK = 0xFF7FD67A, ORANGE = 0xFFFF7A1E, VIOLET = 0xFFB07CFF;

    /** A clickable rectangle, collected while drawing. */
    public record Hit(int x, int y, int w, int h, Runnable action, boolean enabled) {
        public boolean in(float mx, float my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    // ---- text --------------------------------------------------------------------------------

    public static void text(DrawContext ctx, TextRenderer tr, String s, float x, float y, int color, float scale) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x, y, 0);
        ctx.getMatrices().scale(scale, scale, 1f);
        ctx.drawText(tr, s, 0, 0, color, false);
        ctx.getMatrices().pop();
    }

    public static float width(TextRenderer tr, String s, float scale) {
        return tr.getWidth(s) * scale;
    }

    /** Text revealed character by character ({@code t} = ticks since it started, ~3 chars/tick). */
    public static void typed(DrawContext ctx, TextRenderer tr, String s, float x, float y, int color, float scale, float t) {
        int n = MathHelper.clamp((int) (t * 3f), 0, s.length());
        if (n <= 0) return;
        String shown = s.substring(0, n);
        text(ctx, tr, shown, x, y, color, scale);
        if (n < s.length()) {
            float w = width(tr, shown, scale);
            ctx.fill((int) (x + w + 1), (int) y, (int) (x + w + 1 + 4 * scale), (int) (y + 7 * scale), color);
        }
    }

    /** {@code LABEL ........ VALUE} row. */
    public static void kv(DrawContext ctx, TextRenderer tr, String k, String v, int x, int y, int w, int vColor, float scale) {
        text(ctx, tr, k, x, y, DIM, scale);
        float vw = width(tr, v, scale);
        float kw = width(tr, k, scale);
        float dot = width(tr, ".", scale);
        int dots = Math.max(0, (int) ((w - kw - vw - 6) / dot));
        if (dots > 0) text(ctx, tr, ".".repeat(dots), x + kw + 3, y, FAINT, scale);
        text(ctx, tr, v, x + w - vw, y, vColor, scale);
    }

    /** ASCII meter: {@code [||||||||......]  64%}. */
    public static void meter(DrawContext ctx, TextRenderer tr, float value, int x, int y, int cells, int on, float scale) {
        value = MathHelper.clamp(value, 0f, 1f);
        int lit = Math.round(value * cells);
        StringBuilder a = new StringBuilder("["), b = new StringBuilder();
        for (int i = 0; i < cells; i++) (i < lit ? a : b).append(i < lit ? '|' : '.');
        text(ctx, tr, a.toString(), x, y, on, scale);
        float aw = width(tr, a.toString(), scale);
        text(ctx, tr, b + "]", x + aw, y, FAINT, scale);
        String pct = String.format(Locale.ROOT, " %3d%%", (int) (value * 100));
        text(ctx, tr, pct, x + aw + width(tr, b + "]", scale), y, TEXT, scale);
    }

    // ---- structure -----------------------------------------------------------------------------

    public static void hline(DrawContext ctx, int x0, int x1, int y, int color) {
        ctx.fill(x0, y, x1, y + 1, color);
    }

    public static void vline(DrawContext ctx, int x, int y0, int y1, int color) {
        ctx.fill(x, y0, x + 1, y1, color);
    }

    public static void box(DrawContext ctx, int x, int y, int w, int h, int color) {
        hline(ctx, x, x + w, y, color);
        hline(ctx, x, x + w, y + h - 1, color);
        vline(ctx, x, y, y + h, color);
        vline(ctx, x + w - 1, y, y + h, color);
    }

    /** Small corner ticks instead of a full frame. */
    public static void corners(DrawContext ctx, int x, int y, int w, int h, int len, int color) {
        UiKit.brackets(ctx, x, y, w, h, len, color);
    }

    /** Whole-screen backdrop: glass, faint scanlines, slow interference band, vignette. */
    public static void backdrop(DrawContext ctx, int w, int h, float time) {
        ctx.fill(0, 0, w, h, BG);
        for (int y = 0; y < h; y += 2) ctx.fill(0, y, w, y + 1, 0x0C0A0000);
        int band = (int) ((time * 1.3f) % (h + 30)) - 15;
        for (int i = 0; i < 12; i++) {
            int y = band + i;
            if (y < 0 || y >= h) continue;
            ctx.fill(0, y, w, y + 1, ((int) (10 * (1 - Math.abs(i - 6) / 6f)) << 24) | 0xE8382E);
        }
        // darker edges
        for (int i = 0; i < 10; i++) {
            int a = (int) (60 * (1 - i / 10f)) << 24;
            ctx.fill(i, 0, i + 1, h, a);
            ctx.fill(w - i - 1, 0, w - i, h, a);
        }
    }

    /** Top classification strip and a path / status line under it. Returns the y below it. */
    public static int header(DrawContext ctx, TextRenderer tr, int w, String path, String status, int statusColor, float time) {
        ctx.fill(0, 0, w, 9, 0xFF140504);
        String cls = "CLASSIFIED // DELUXS ARMORY ORBITAL COMMAND // EYES ONLY";
        float cw = width(tr, cls, 0.6f);
        text(ctx, tr, cls, (w - cw) / 2f, 2, RED, 0.6f);
        hline(ctx, 0, w, 9, RED_DIM);
        // prompt
        boolean caret = ((int) (time / 8)) % 2 == 0;
        text(ctx, tr, "root@deluxs-armory", 4, 13, RED, 0.75f);
        float pw = width(tr, "root@deluxs-armory", 0.75f);
        text(ctx, tr, ":" + path + "$" + (caret ? "_" : ""), 4 + pw, 13, TEXT, 0.75f);
        float sw = width(tr, status, 0.75f);
        text(ctx, tr, status, w - sw - 4, 13, statusColor, 0.75f);
        hline(ctx, 4, w - 4, 22, RULE);
        return 25;
    }

    /** Slowly scrolling hex dump in the footer; decoration. */
    public static void footer(DrawContext ctx, TextRenderer tr, int w, int h, float time, String hint) {
        hline(ctx, 4, w - 4, h - 11, RULE);
        StringBuilder sb = new StringBuilder();
        long seed = (long) (time / 6);
        for (int i = 0; i < 18; i++) {
            long v = (seed + i) * 0x9E3779B97F4A7C15L;
            sb.append(String.format(Locale.ROOT, "%04X ", (int) ((v >>> 40) & 0xFFFF)));
        }
        text(ctx, tr, sb.toString(), 4, h - 8, FAINT, 0.55f);
        float hw = width(tr, hint, 0.6f);
        text(ctx, tr, hint, w - hw - 4, h - 8, DIM, 0.6f);
    }

    /**
     * File-tree entry. {@code depth} indents with tree glyphs; selected entries are drawn as an
     * inverted bar. Returns the hit rectangle (or null if not clickable).
     */
    public static Hit node(DrawContext ctx, TextRenderer tr, String name, int x, int y, int w, int depth, boolean last,
                           boolean selected, boolean enabled, boolean dir, String tag, int tagColor, float mx, float my, Runnable action) {
        String prefix = depth == 0 ? "" : "  ".repeat(depth - 1) + (last ? "└ " : "├ ");
        float sc = 0.75f;
        int h = 9;
        boolean hover = enabled && mx >= x && mx < x + w && my >= y - 1 && my < y + h - 1;
        if (selected) ctx.fill(x - 1, y - 1, x + w, y + h - 1, RED);
        else if (hover) ctx.fill(x - 1, y - 1, x + w, y + h - 1, 0x40E8382E);
        int col = selected ? 0xFF050505 : !enabled ? FAINT : dir ? RED : TEXT;
        text(ctx, tr, prefix, x, y, selected ? 0xFF050505 : FAINT, sc);
        float px = width(tr, prefix, sc);
        text(ctx, tr, name + (dir ? "/" : ""), x + px, y, col, sc);
        if (tag != null) {
            float tw = width(tr, tag, 0.6f);
            text(ctx, tr, tag, x + w - tw - 2, y + 1, selected ? 0xFF050505 : tagColor, 0.6f);
        }
        return action == null ? null : new Hit(x - 1, y - 1, w + 1, h, action, enabled);
    }

    /**
     * Bracketed text command: {@code [ EXECUTE ]}. Hover inverts it. Disabled commands are dim
     * and struck through.
     */
    public static Hit cmd(DrawContext ctx, TextRenderer tr, String label, int x, int y, int accent, boolean enabled,
                          float mx, float my, float scale, Runnable action) {
        String s = "[ " + label + " ]";
        float w = width(tr, s, scale);
        int h = (int) (9 * scale) + 3;
        boolean hover = enabled && mx >= x - 2 && mx < x + w + 2 && my >= y - 2 && my < y + h - 1;
        if (hover) ctx.fill(x - 2, y - 2, (int) (x + w + 2), y + h - 1, accent);
        int col = hover ? 0xFF050505 : enabled ? accent : FAINT;
        text(ctx, tr, s, x, y, col, scale);
        if (!enabled) hline(ctx, x, (int) (x + w), (int) (y + 3.5f * scale), FAINT);
        return new Hit(x - 2, y - 2, (int) w + 4, h + 1, action, enabled);
    }

    /** Section title: {@code >> NAME} with a rule to the right. */
    public static void section(DrawContext ctx, TextRenderer tr, String name, int x, int y, int w, int color) {
        text(ctx, tr, ">> " + name, x, y, color, 0.75f);
        float tw = width(tr, ">> " + name, 0.75f);
        hline(ctx, (int) (x + tw + 4), x + w, y + 3, RULE);
    }

    /** Wrapped paragraph in small text; returns the y after it. */
    public static int para(DrawContext ctx, TextRenderer tr, String s, int x, int y, int w, int color, float scale) {
        List<String> lines = UiKit.wrap(tr, s, (int) (w / scale));
        for (String l : lines) {
            text(ctx, tr, l, x, y, color, scale);
            y += (int) Math.ceil(9 * scale);
        }
        return y;
    }

    /** Boot overlay: typed log lines over black, then a hard cut. {@code t} in ticks; done at 18. */
    public static void boot(DrawContext ctx, TextRenderer tr, int w, int h, float t, String[] lines) {
        if (t >= 18) return;
        ctx.fill(0, 0, w, h, 0xFF030303);
        for (int i = 0; i < lines.length; i++) {
            typed(ctx, tr, lines[i], 8, 10 + i * 9, i == lines.length - 1 ? RED : DIM, 0.75f, t - i * 3);
        }
    }
}
