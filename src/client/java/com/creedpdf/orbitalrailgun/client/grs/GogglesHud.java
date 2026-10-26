package com.creedpdf.orbitalrailgun.client.grs;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.ui.Term;
import com.creedpdf.orbitalrailgun.grs.Grs;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.lwjgl.opengl.GL11;

import java.util.Locale;

/**
 * The display inside the Gungnir goggles, drawn over your view while you wear them with the visor down:
 * <ul>
 *   <li>the lens itself - an amber-tinted vignette with a fine grid and scanlines;</li>
 *   <li>a live feed of the Gungnir Railway Station on Jupiter's rings, the spear an orb racing round its track;</li>
 *   <li>a turning reticle with range and coordinates of the surface under it, brackets closing in on a lock;</li>
 *   <li>the power ladder - SURGICAL to RAGNAROK - with the crater and blast it will make;</li>
 *   <li>the strike in flight (release, descent, impact) and the recall countdown.</li>
 * </ul>
 */
public final class GogglesHud {
    private GogglesHud() {}

    private static final Identifier LENS = RailgunMod.id("textures/grs/hud/lens.png");
    private static final Identifier RETICLE = RailgunMod.id("textures/grs/hud/reticle.png");
    private static final Identifier STATION = RailgunMod.id("textures/grs/hud/station.png");
    private static final int OR = 0xFFFF8A2A, OR_HI = 0xFFFFC27A, OR_DIM = 0xFF7A3A12, DARK = 0xC0080503, RED = 0xFFFF4A3A;

    public static void render(DrawContext ctx, float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || !GrsClient.worn()) return;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        TextRenderer tr = mc.textRenderer;
        float time = GrsClient.now() + pt;
        float boot = GrsClient.wornAt < 0 ? 1 : MathHelper.clamp((time - GrsClient.wornAt) / 40f, 0, 1);
        if (!GrsClient.visor) {
            Term.text(ctx, tr, "GUNGNIR VISOR UP  ·  [" + GrsClient.key(GrsClient.VISOR) + "]", 8, h - 14, OR_DIM, 0.75f);
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // the lens
        RenderSystem.setShaderColor(1f, 0.85f, 0.7f, 0.92f * boot);
        ctx.drawTexture(LENS, 0, 0, w, h, 0, 0, 960, 540, 960, 540);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        // amber wash and scanlines
        ctx.fill(0, 0, w, h, ((int) (18 * boot) << 24) | 0x301400);
        int scan = (int) ((time * 3) % h);
        ctx.fill(0, scan, w, scan + 1, 0x18FF9A40);
        if (boot < 1) {
            bootSequence(ctx, tr, w, h, boot);
            return;
        }
        boolean earth = GrsClient.onEarth();
        stationFeed(ctx, tr, 10, 10, time);
        yieldLadder(ctx, tr, w - 118, h / 2 - 78, time);
        reticle(ctx, tr, w, h, time, earth);
        flight(ctx, tr, w, h, time);
        footer(ctx, tr, w, h, time, earth);
        RenderSystem.disableBlend();
    }

    // ------------------------------------------------------------------------------------------- boot

    private static void bootSequence(DrawContext ctx, TextRenderer tr, int w, int h, float boot) {
        String[] lines = {"GUNGNIR FIRE CONTROL  ·  GRS-01", "LINK  EARTH  >>  JUPITER RING B", "SPEAR TELEMETRY  ........  LAPPING", "VISOR CALIBRATED"};
        int y = h / 2 - 24;
        for (int i = 0; i < lines.length; i++) {
            float k = MathHelper.clamp(boot * 5 - i, 0, 1);
            if (k <= 0) break;
            Term.typed(ctx, tr, lines[i], w / 2f - 90, y + i * 12, i == lines.length - 1 ? OR_HI : OR, 1f, k);
        }
        int bw = (int) (180 * boot);
        ctx.fill(w / 2 - 90, y + 54, w / 2 - 90 + bw, y + 56, OR);
    }

    // ------------------------------------------------------------------------------------------- station feed

    private static void stationFeed(DrawContext ctx, TextRenderer tr, int x, int y, float time) {
        int pw = 196, ph = 92;
        ctx.fill(x, y, x + pw, y + ph, DARK);
        Term.corners(ctx, x, y, pw, ph, 6, OR);
        Term.text(ctx, tr, "GRS · GUNGNIR RAILWAY STATION", x + 6, y + 5, OR_HI, 0.75f);
        Term.hline(ctx, x + 6, x + pw - 6, y + 14, OR_DIM);
        // the schematic, turning slowly, with the spear racing round it
        int cx = x + 40, cy = y + 54, r = 30;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(cx, cy, 0);
        ctx.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotation(time * 0.003f));
        RenderSystem.setShaderColor(1f, 0.55f, 0.2f, 0.85f);
        ctx.drawTexture(STATION, -r, -r, r * 2, r * 2, 0, 0, 256, 256, 256, 256);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        ctx.getMatrices().pop();
        float a = GrsSpace.lapAngle(time);
        float tr2 = r * 0.82f;
        for (int i = 18; i >= 0; i--) {
            float aa = a - i * 0.09f;
            float k = 1 - i / 19f;
            int px = (int) (cx + MathHelper.cos(aa) * tr2), py = (int) (cy + MathHelper.sin(aa) * tr2);
            int s = i == 0 ? 2 : 1;
            int col = ((int) (255 * k * k) << 24) | (i == 0 ? 0xFFE0B0 : 0xFF7A20);
            ctx.fill(px - s, py - s, px + s + 1, py + s + 1, col);
        }
        // read-outs
        int tx = x + 80, ty = y + 22;
        String spear = state();
        Term.text(ctx, tr, "LOCATION", tx, ty, OR_DIM, 0.6f);
        Term.text(ctx, tr, "JUPITER · RING B · 1.62 RJ", tx, ty + 7, OR, 0.65f);
        Term.text(ctx, tr, "SPEAR", tx, ty + 18, OR_DIM, 0.6f);
        Term.text(ctx, tr, spear, tx, ty + 25, spear.startsWith("LAP") ? OR_HI : RED, 0.65f);
        Term.text(ctx, tr, "LAP  " + String.format(Locale.ROOT, "%.2fs", Grs.LAP_TICKS / 20f) + "   v = c", tx, ty + 36, OR, 0.65f);
        Term.text(ctx, tr, "LINK  43.1 LIGHT-MIN", tx, ty + 47, OR, 0.65f);
        float lap = (time % Grs.LAP_TICKS) / Grs.LAP_TICKS;
        Term.meter(ctx, tr, lap, tx, ty + 58, 14, OR, 0.6f);
    }

    private static String state() {
        long now = GrsClient.now();
        if (GrsClient.firedAt >= 0) {
            long s = now - GrsClient.firedAt;
            if (s < Grs.RELEASE) return "BREAKING ORBIT";
            if (s < Grs.IMPACT) return "DESCENDING";
            if (s < Grs.IMPACT + 40) return "IMPACT";
        }
        if (now < GrsClient.readyAt) return "RECALLING";
        return "LAPPING";
    }

    // ------------------------------------------------------------------------------------------- power

    private static void yieldLadder(DrawContext ctx, TextRenderer tr, int x, int y, float time) {
        int pw = 108, ph = 156;
        ctx.fill(x, y, x + pw, y + ph, DARK);
        Term.corners(ctx, x, y, pw, ph, 6, OR);
        Term.text(ctx, tr, "YIELD", x + 6, y + 5, OR_HI, 0.75f);
        Term.text(ctx, tr, "[" + GrsClient.key(GrsClient.YIELD_UP) + "/" + GrsClient.key(GrsClient.YIELD_DOWN) + "]", x + 40, y + 6, OR_DIM, 0.6f);
        int y0 = y + 18;
        for (int i = Grs.MAX_YIELD; i >= Grs.MIN_YIELD; i--) {
            int row = y0 + (Grs.MAX_YIELD - i) * 10;
            boolean on = i <= GrsClient.power, cur = i == GrsClient.power;
            int col = cur ? OR_HI : on ? OR : OR_DIM;
            int bw = 10 + i * 4;
            if (cur) {
                float p = 0.6f + 0.4f * MathHelper.sin(time * 0.4f);
                ctx.fill(x + 4, row - 1, x + pw - 4, row + 8, ((int) (60 * p) << 24) | 0xFF7A20);
            }
            ctx.fill(x + 6, row + 1, x + 6 + bw, row + 6, col);
            Term.text(ctx, tr, Grs.yieldName(i), x + 56, row + 1, col, 0.6f);
        }
        int by = y0 + 104;
        Term.hline(ctx, x + 6, x + pw - 6, by, OR_DIM);
        int r = Grs.radius(GrsClient.power);
        Term.text(ctx, tr, Grs.yieldName(GrsClient.power), x + 6, by + 4, OR_HI, 1f);
        Term.text(ctx, tr, r == 0 ? "NO CRATER" : "CRATER  Ø" + r * 2 + " m", x + 6, by + 16, OR, 0.65f);
        Term.text(ctx, tr, "BLAST   " + (int) Grs.blastRadius(GrsClient.power) + " m", x + 6, by + 24, OR, 0.65f);
    }

    // ------------------------------------------------------------------------------------------- reticle

    private static void reticle(DrawContext ctx, TextRenderer tr, int w, int h, float time, boolean earth) {
        int cx = w / 2, cy = h / 2;
        boolean lock = GrsClient.locked != null;
        float s = lock ? 44 + 6 * MathHelper.sin(time * 0.5f) : 52;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(cx, cy, 0);
        ctx.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotation(time * (lock ? 0.05f : 0.012f)));
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        RenderSystem.setShaderColor(1f, lock ? 0.62f : 0.48f, 0.15f, lock ? 1f : 0.75f);
        ctx.drawTexture(RETICLE, (int) -s, (int) -s, (int) (s * 2), (int) (s * 2), 0, 0, 256, 256, 256, 256);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.defaultBlendFunc();
        ctx.getMatrices().pop();
        if (lock) {
            float k = 1 - (GrsClient.now() - GrsClient.lockedAt) / (float) GrsClient.LOCK_TICKS;
            int b = (int) (s + 10 + 20 * Math.exp(-(GrsClient.now() - GrsClient.lockedAt) / 4.0));
            Term.corners(ctx, cx - b, cy - b, b * 2, b * 2, 10, OR_HI);
            ctx.fill(cx - 40, cy + b + 4, (int) (cx - 40 + 80 * k), cy + b + 6, OR);
            Term.text(ctx, tr, "LOCKED", cx - Term.width(tr, "LOCKED", 0.8f) / 2, cy - b - 11, OR_HI, 0.8f);
        }
        if (!earth) {
            String t = "NO EARTH UPLINK";
            Term.text(ctx, tr, t, cx - Term.width(tr, t, 1f) / 2, cy + 60, RED, 1f);
            return;
        }
        BlockPos p = lock ? GrsClient.locked : GrsClient.hover;
        MinecraftClient mc = MinecraftClient.getInstance();
        String l1, l2;
        if (p == null) {
            l1 = "NO SURFACE";
            l2 = "RANGE " + (int) Grs.RANGE + " m";
        } else {
            double d = Math.sqrt(p.getSquaredDistance(mc.player.getPos()));
            l1 = String.format(Locale.ROOT, "RNG %d m", (int) d);
            l2 = p.getX() + "  " + p.getY() + "  " + p.getZ();
        }
        Term.text(ctx, tr, l1, cx + 58, cy - 8, OR_HI, 0.8f);
        Term.text(ctx, tr, l2, cx + 58, cy + 2, OR, 0.65f);
    }

    // ------------------------------------------------------------------------------------------- the strike in flight

    private static void flight(DrawContext ctx, TextRenderer tr, int w, int h, float time) {
        if (GrsClient.firedAt < 0) return;
        float s = time - GrsClient.firedAt;
        if (s > Grs.IMPACT + 80) return;
        int cx = w / 2;
        String big;
        int col = OR_HI;
        if (s < Grs.RELEASE) big = String.format(Locale.ROOT, "IMPACT  T-%.1f", (Grs.IMPACT - s) / 20f);
        else if (s < Grs.IMPACT) big = "DESCENT";
        else {
            big = "IMPACT CONFIRMED";
            col = RED;
        }
        float sc = 1.6f;
        Term.text(ctx, tr, big, cx - Term.width(tr, big, sc) / 2, h * 0.22f, col, sc);
        // the frame flares as it lands
        float f = s >= Grs.IMPACT ? (float) Math.exp(-(s - Grs.IMPACT) / 6) : s > Grs.RELEASE ? 0.3f : 0;
        if (f > 0.02f) {
            int a = (int) (160 * f) << 24;
            ctx.fill(0, 0, w, 6, a | 0xFF8A2A);
            ctx.fill(0, h - 6, w, h, a | 0xFF8A2A);
            ctx.fill(0, 0, 6, h, a | 0xFF8A2A);
            ctx.fill(w - 6, 0, w, h, a | 0xFF8A2A);
        }
    }

    private static void footer(DrawContext ctx, TextRenderer tr, int w, int h, float time, boolean earth) {
        long now = GrsClient.now();
        if (GrsClient.msg != null && now - GrsClient.msgAt < 80) {
            float a = MathHelper.clamp(1 - (now - GrsClient.msgAt - 60) / 20f, 0, 1);
            int col = (((int) (255 * a)) << 24) | (GrsClient.msgColor & 0xFFFFFF);
            Term.text(ctx, tr, GrsClient.msg, w / 2f - Term.width(tr, GrsClient.msg, 0.9f) / 2, h - 52, col, 0.9f);
        }
        if (now < GrsClient.readyAt) {
            String t = String.format(Locale.ROOT, "SPEAR RETURNING TO THE RING  ·  %.1fs", (GrsClient.readyAt - now) / 20f);
            Term.text(ctx, tr, t, w / 2f - Term.width(tr, t, 0.7f) / 2, h - 40, OR, 0.7f);
        }
        String hint = "[" + GrsClient.key(GrsClient.FIRE) + "] " + (GrsClient.locked != null ? "CALL GUNGNIR" : "LOCK") + "    ["
                + GrsClient.key(GrsClient.YIELD_UP) + "/" + GrsClient.key(GrsClient.YIELD_DOWN) + "] YIELD    [" + GrsClient.key(GrsClient.VISOR)
                + "] VISOR";
        Term.text(ctx, tr, hint, w / 2f - Term.width(tr, hint, 0.7f) / 2, h - 30, earth ? OR_DIM : 0xFF5A2A1A, 0.7f);
    }
}
