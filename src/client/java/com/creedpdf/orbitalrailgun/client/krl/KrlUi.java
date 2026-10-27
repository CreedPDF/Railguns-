package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.krl.KrlText;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Drawing kit for the KRL / ARES screens, in the KRL typefaces: sub-pixel fills, framed panels, mono and heading text,
 * dashed lines, circles and arcs, buttons and click regions. Coordinates are in a screen's own virtual canvas units.
 */
public final class KrlUi {
    public DrawContext dc;
    public TextRenderer tr;
    public float mx, my;

    /** A clickable rectangle collected while drawing. */
    public record Hit(float x, float y, float w, float h, Runnable action, boolean enabled) {
        public boolean in(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    public final List<Hit> hits = new ArrayList<>();

    public void begin(DrawContext dc, float mx, float my) {
        this.dc = dc;
        this.tr = MinecraftClient.getInstance().textRenderer;
        this.mx = mx;
        this.my = my;
        hits.clear();
    }

    public boolean hover(float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------------------------------- shapes

    public void fill(float x0, float y0, float x1, float y1, int c) {
        var ms = dc.getMatrices();
        ms.push();
        ms.scale(0.25f, 0.25f, 1);
        dc.fill(Math.round(x0 * 4), Math.round(y0 * 4), Math.round(x1 * 4), Math.round(y1 * 4), c);
        ms.pop();
    }

    public void outline(float x0, float y0, float x1, float y1, float w, int c) {
        fill(x0, y0, x1, y0 + w, c);
        fill(x0, y1 - w, x1, y1, c);
        fill(x0, y0, x0 + w, y1, c);
        fill(x1 - w, y0, x1, y1, c);
    }

    /** A panel: fill, hairline border, bright corner ticks. */
    public void panel(float x0, float y0, float x1, float y1, int fill, int edge, int tick) {
        fill(x0, y0, x1, y1, fill);
        outline(x0, y0, x1, y1, 0.5f, edge);
        float k = 4;
        fill(x0, y0, x0 + k, y0 + 1, tick);
        fill(x0, y0, x0 + 1, y0 + k, tick);
        fill(x1 - k, y0, x1, y0 + 1, tick);
        fill(x1 - 1, y0, x1, y0 + k, tick);
        fill(x0, y1 - 1, x0 + k, y1, tick);
        fill(x0, y1 - k, x0 + 1, y1, tick);
        fill(x1 - k, y1 - 1, x1, y1, tick);
        fill(x1 - 1, y1 - k, x1, y1, tick);
    }

    public void line(float x0, float y0, float x1, float y1, float w, int c, float dash) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) return;
        float step = Math.max(0.4f, w * 0.8f);
        for (float d = 0; d < len; d += step) {
            if (dash > 0 && ((int) (d / dash)) % 2 == 1) continue;
            float x = x0 + dx * d / len, y = y0 + dy * d / len;
            fill(x - w / 2, y - w / 2, x + w / 2, y + w / 2, c);
        }
    }

    public void circle(float cx, float cy, float r, float w, int c, int dashEvery) {
        arc(cx, cy, r, 0, (float) (Math.PI * 2), w, c, dashEvery);
    }

    public void arc(float cx, float cy, float r, float a0, float a1, float w, int c, int dashEvery) {
        int n = Math.max(16, Math.min(360, (int) (r * Math.abs(a1 - a0) * 1.0f)));
        for (int i = 0; i <= n; i++) {
            if (dashEvery > 0 && (i / dashEvery) % 2 == 1) continue;
            double a = a0 + (a1 - a0) * i / n;
            float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
            fill(x - w / 2, y - w / 2, x + w / 2, y + w / 2, c);
        }
    }

    public void texQuad(Identifier id, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float alpha) {
        RenderSystem.setShaderTexture(0, id);
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.enableBlend();
        Matrix4f m = dc.getMatrices().peek().getPositionMatrix();
        BufferBuilder bb = Tessellator.getInstance().getBuffer();
        bb.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        bb.vertex(m, x0, y0, 0).texture(u0, v0).color(1f, 1f, 1f, alpha).next();
        bb.vertex(m, x0, y1, 0).texture(u0, v1).color(1f, 1f, 1f, alpha).next();
        bb.vertex(m, x1, y1, 0).texture(u1, v1).color(1f, 1f, 1f, alpha).next();
        bb.vertex(m, x1, y0, 0).texture(u1, v0).color(1f, 1f, 1f, alpha).next();
        BufferRenderer.drawWithGlobalProgram(bb.end());
    }

    // ------------------------------------------------------------------------------------------- text

    private void draw(Text t, float x, float y, int color, float sc, boolean shadow) {
        var ms = dc.getMatrices();
        ms.push();
        ms.translate(x, y, 0);
        ms.scale(sc, sc, 1);
        dc.drawText(tr, t, 0, 0, color, shadow);
        ms.pop();
    }

    /** Read-out text (Share Tech Mono). */
    public void text(String s, float x, float y, int color, float sc) {
        draw(KrlText.t(s), x, y, color, sc, false);
    }

    public void textR(String s, float x, float y, int color, float sc) {
        text(s, x - tw(s, sc), y, color, sc);
    }

    public void textC(String s, float x, float y, int color, float sc) {
        text(s, x - tw(s, sc) / 2, y, color, sc);
    }

    public float tw(String s, float sc) {
        return tr.getWidth(KrlText.t(s)) * sc;
    }

    /** Heading text (Rajdhani Bold). */
    public void head(String s, float x, float y, int color, float sc) {
        draw(KrlText.h(s), x, y, color, sc, false);
    }

    public void headC(String s, float x, float y, int color, float sc) {
        head(s, x - hw(s, sc) / 2, y, color, sc);
    }

    public void headR(String s, float x, float y, int color, float sc) {
        head(s, x - hw(s, sc), y, color, sc);
    }

    public float hw(String s, float sc) {
        return tr.getWidth(KrlText.h(s)) * sc;
    }

    /** Wrapped read-out text; returns the y below it. */
    public float para(String s, float x, float y, float w, int color, float sc, float lineH) {
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String tryLine = line.isEmpty() ? word : line + " " + word;
            if (tw(tryLine, sc) > w && !line.isEmpty()) {
                text(line.toString(), x, y, color, sc);
                y += lineH;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tryLine);
            }
        }
        if (!line.isEmpty()) {
            text(line.toString(), x, y, color, sc);
            y += lineH;
        }
        return y;
    }

    /** LABEL ......... VALUE. */
    public void kv(String k, String v, float x, float y, float w, int kc, int vc, int dotc, float sc) {
        text(k, x, y, kc, sc);
        float kw = tw(k, sc), vw = tw(v, sc);
        float dot = Math.max(0.5f, tw(".", sc));
        int n = (int) ((w - kw - vw - 4) / dot);
        if (n > 0) text(".".repeat(n), x + kw + 2, y, dotc, sc);
        text(v, x + w - vw, y, vc, sc);
    }

    // ------------------------------------------------------------------------------------------- controls

    /**
     * A button: filled when hovered or active, outlined otherwise, a heading-font label. Returns true if hovered.
     */
    public boolean button(String label, float x, float y, float w, float h, int accent, int ink, boolean active, boolean enabled, float sc,
                          Runnable action) {
        boolean hv = enabled && hover(x, y, w, h);
        int bg = !enabled ? 0x60202522 : active ? accent : hv ? mul(accent, 0.45f) : 0xC0141815;
        fill(x, y, x + w, y + h, bg);
        outline(x, y, x + w, y + h, 0.5f, enabled ? (active || hv ? accent : mul(accent, 0.55f)) : 0xFF3A403B);
        int tc = !enabled ? 0xFF5E665F : active ? ink : hv ? 0xFFFFFFFF : accent;
        headC(label, x + w / 2, y + h / 2 - 4.2f * sc / 0.6f * 0.6f, tc, sc);
        hits.add(new Hit(x, y, w, h, action, enabled));
        return hv;
    }

    /** A horizontal bar meter. */
    public void meter(float x, float y, float w, float h, float v, int on, int off) {
        v = MathHelper.clamp(v, 0, 1);
        fill(x, y, x + w, y + h, off);
        fill(x, y, x + w * v, y + h, on);
    }

    /** A segmented meter (cells); returns the hovered cell or -1. */
    public int segments(float x, float y, float w, float h, int cells, int lit, int on, int off, boolean enabled) {
        float gap = 1.2f, cw = (w - gap * (cells - 1)) / cells;
        int hv = -1;
        for (int i = 0; i < cells; i++) {
            float cx = x + i * (cw + gap);
            boolean h0 = enabled && hover(cx, y, cw, h);
            if (h0) hv = i;
            fill(cx, y, cx + cw, y + h, i < lit ? on : h0 ? mul(on, 0.5f) : off);
        }
        return hv;
    }

    public static int mul(int argb, float k) {
        int a = argb >>> 24, r = (int) (((argb >> 16) & 255) * k), g = (int) (((argb >> 8) & 255) * k), b = (int) ((argb & 255) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int alpha(int rgb, float a) {
        return ((int) (MathHelper.clamp(a, 0, 1) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    /** Run the first enabled hit under the cursor; returns 1 hit enabled, 0 hit disabled, -1 none. */
    public int click(float px, float py) {
        for (Hit h : new ArrayList<>(hits)) {
            if (h.in(px, py)) {
                if (h.enabled() && h.action() != null) {
                    h.action().run();
                    return 1;
                }
                return 0;
            }
        }
        return -1;
    }
}
