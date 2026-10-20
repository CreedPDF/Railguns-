package com.creedpdf.orbitalrailgun.client.space;

import com.creedpdf.orbitalrailgun.entity.Ares0Entity;
import com.creedpdf.orbitalrailgun.space.Blast;
import com.creedpdf.orbitalrailgun.space.Body;
import com.creedpdf.orbitalrailgun.space.EvaSuit;
import com.creedpdf.orbitalrailgun.space.SpaceDims;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Locale;

/** EVA helmet visor read-outs, vacuum and core-collapse warnings, and detonation flashes. */
public final class SpaceHud {
    private SpaceHud() {}

    private static final int CYAN = 0xFF7FE6FF, DIM = 0xA07FC8E0, RED = 0xFFFF5040, AMBER = 0xFFFFB040;

    public static void render(DrawContext dc, float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null || mc.options.hudHidden) return;
        int sw = dc.getScaledWindowWidth(), sh = dc.getScaledWindowHeight();
        TextRenderer tr = mc.textRenderer;
        float time = w.getTime() + pt;

        flash(dc, w, pt, sw, sh);
        Banner.render(dc, pt);
        ReentryFx.hud(dc, pt);

        boolean airless = SpaceDims.isAirless(w);
        boolean helmet = EvaSuit.wearing(p, EquipmentSlot.HEAD);
        boolean sealed = EvaSuit.full(p);

        if (helmet) visor(dc, sw, sh, time);
        if (helmet || airless) {
            int x = 8, y = 8;
            dc.drawText(tr, "ARES EVA", x, y, CYAN, false);
            y += 11;
            String seal = sealed ? "SEAL OK  O2 100%" : "SEAL OPEN";
            dc.drawText(tr, seal, x, y, sealed ? DIM : (airless && blink(time) ? RED : AMBER), false);
            y += 11;
            Vec3d v = p.getVelocity();
            double speed = v.length() * 20.0;
            dc.drawText(tr, String.format(Locale.ROOT, "SPD %6.1f m/s", speed), x, y, DIM, false);
            y += 11;
            if (SpaceDims.isSpace(w)) {
                Body near = null;
                double best = Double.MAX_VALUE;
                for (Body b : Body.ALL) {
                    if (ClientPlanets.gone(b)) continue;
                    double d = p.getPos().distanceTo(b.center) - b.radius;
                    if (d < best) { best = d; near = b; }
                }
                if (near != null) {
                    dc.drawText(tr, String.format(Locale.ROOT, "%s  %,.0f m", near.displayName(), best), x, y, DIM, false);
                    y += 11;
                    double entry = best - near.atmosphere;
                    if (entry < 400 && near.landable()) {
                        dc.drawText(tr, near == Body.EARTH ? "ENTRY INTERFACE - HOME" : "ENTRY INTERFACE", x, y, blink(time) ? AMBER : DIM, false);
                        y += 11;
                    }
                }
                dc.drawText(tr, "GRAV 0.00 g", x, y, DIM, false);
            } else {
                dc.drawText(tr, String.format(Locale.ROOT, "GRAV %.2f g   ALT %.0f", SpaceDims.gravity(w), p.getY()), x, y, DIM, false);
            }
            if (EvaSuit.jetpack(p) && Jetpack.thrust() > 0.05f) {
                int bw = 60, bx = x, by = y + 13;
                dc.fill(bx, by, bx + bw, by + 3, 0x60204050);
                dc.fill(bx, by, bx + (int) (bw * Jetpack.thrust()), by + 3, Jetpack.boosting() ? 0xFFFFB040 : 0xFF7FE6FF);
            }
        }

        if (airless && !sealed && !p.isCreative() && !p.isSpectator()) {
            float a = 0.25f + 0.2f * MathHelper.sin(time * 0.5f);
            vignette(dc, sw, sh, 0xFF2010, a);
            String s = "NO PRESSURE SEAL - VACUUM EXPOSURE";
            if (blink(time)) dc.drawText(tr, s, (sw - tr.getWidth(s)) / 2, sh / 2 - 40, RED, true);
            String s2 = "A full ARES EVA suit is required to survive here";
            dc.drawText(tr, s2, (sw - tr.getWidth(s2)) / 2, sh / 2 - 28, 0xFFFFA090, true);
        }

        Body planet = SpaceDims.planetOf(w);
        if (planet != null) {
            float e = ClientPlanets.elapsed(planet, pt);
            if (e >= 0 && e < Blast.EJECT + 10) {
                vignette(dc, sw, sh, 0xFF3000, 0.15f + 0.35f * e / Blast.EJECT);
                String s = "CORE COLLAPSE - PLANET UNSTABLE";
                if (blink(time)) dc.drawText(tr, s, (sw - tr.getWidth(s)) / 2, sh / 2 - 52, RED, true);
            }
        }
    }

    /** Thin visor frame: corner brackets, a horizon tick and a faint scanline. */
    private static void visor(DrawContext dc, int w, int h, float time) {
        int c = 0x507FE6FF, len = 18, m = 4;
        dc.fill(m, m, m + len, m + 1, c);
        dc.fill(m, m, m + 1, m + len, c);
        dc.fill(w - m - len, m, w - m, m + 1, c);
        dc.fill(w - m - 1, m, w - m, m + len, c);
        dc.fill(m, h - m - 1, m + len, h - m, c);
        dc.fill(m, h - m - len, m + 1, h - m, c);
        dc.fill(w - m - len, h - m - 1, w - m, h - m, c);
        dc.fill(w - m - 1, h - m - len, w - m, h - m, c);
        int sy = (int) ((time * 1.3f) % h);
        dc.fill(0, sy, w, sy + 1, 0x0A7FE6FF);
        // soft dark rim of the helmet glass
        for (int i = 0; i < 10; i++) {
            int a = (int) (36 * (1 - i / 10f));
            dc.fill(0, i * 2, w, i * 2 + 2, a << 24);
            dc.fill(0, h - i * 2 - 2, w, h - i * 2, a << 24);
        }
    }

    private static void vignette(DrawContext dc, int w, int h, int rgb, float strength) {
        for (int i = 0; i < 16; i++) {
            float k = 1f - i / 16f;
            int a = (int) (MathHelper.clamp(strength, 0, 1) * 255 * k * k * 0.5f);
            int e = (int) (i * Math.min(w, h) * 0.02f);
            int col = (a << 24) | rgb;
            dc.fill(0, e, w, e + 3, col);
            dc.fill(0, h - e - 3, w, h - e, col);
            dc.fill(e, 0, e + 3, h, col);
            dc.fill(w - e - 3, 0, w - e, h, col);
        }
    }

    /** White-out when a planet detonates somewhere you can see it. */
    private static void flash(DrawContext dc, ClientWorld w, float pt, int sw, int sh) {
        float best = 0f;
        for (Body b : Body.ALL) {
            float e = ClientPlanets.elapsed(b, pt);
            if (e < Blast.DETONATE || e > Blast.DETONATE + 70) continue;
            boolean visible;
            if (w.getRegistryKey() == World.OVERWORLD) visible = Ares0Entity.visible(w, b);
            else visible = SpaceDims.isAirless(w) && SpaceDims.planetOf(w) != b;
            if (!visible) continue;
            float f = 1f - (e - Blast.DETONATE) / 70f;
            best = Math.max(best, f * f * (w.getRegistryKey() == World.OVERWORLD ? 0.75f : 0.95f));
        }
        if (best > 0.01f) dc.fill(0, 0, sw, sh, ((int) (best * 255) << 24) | 0xFFF4E6);
    }

    private static boolean blink(float time) {
        return ((int) (time / 6)) % 2 == 0;
    }
}
