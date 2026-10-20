package com.creedpdf.orbitalrailgun.client.space;

import com.creedpdf.orbitalrailgun.client.hud.RailgunHud;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.space.Body;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * Atmospheric entry: plasma wraps the visor in orange fire, the camera shakes and sparks stream
 * up past you - which also hides the moment the world under you changes. Climbing out of an
 * atmosphere fades through blue to black instead.
 */
public final class ReentryFx {
    private ReentryFx() {}

    private static final int LENGTH = 80;
    private static int ticks = -1;
    private static boolean down;
    private static Body body = Body.EARTH;

    public static void start(Body b, boolean descending) {
        body = b;
        down = descending;
        ticks = 0;
    }

    static void tick(MinecraftClient client, ClientPlayerEntity p) {
        if (ticks < 0) return;
        ticks++;
        if (ticks > LENGTH) {
            ticks = -1;
            return;
        }
        float f = intensity(0f);
        if (down && hasAir()) {
            RailgunHud.shake(0.35f * f);
            Random r = p.getWorld().random;
            for (int i = 0; i < (int) (14 * f); i++) {
                Vec3d e = p.getPos().add((r.nextDouble() - 0.5) * 3, -1 + r.nextDouble() * 3, (r.nextDouble() - 0.5) * 3);
                p.getWorld().addParticle(ModParticles.REENTRY_FIRE, e.x, e.y, e.z, 0, 0.9 + r.nextDouble(), 0);
            }
        }
    }

    private static boolean hasAir() {
        return body == Body.EARTH || body == Body.MARS || body == Body.VENUS;
    }

    private static float intensity(float pt) {
        if (ticks < 0) return 0f;
        float t = ticks + pt;
        float in = MathHelper.clamp(t / 6f, 0f, 1f);
        float out = MathHelper.clamp((LENGTH - t) / 40f, 0f, 1f);
        return in * out;
    }

    public static void hud(DrawContext dc, float pt) {
        float f = intensity(pt);
        if (f <= 0.01f) return;
        int w = dc.getScaledWindowWidth(), h = dc.getScaledWindowHeight();
        if (!down) {
            int a = (int) (f * 140);
            dc.fill(0, 0, w, h, (a << 24) | 0x0A1430);
            return;
        }
        if (!hasAir()) {
            int a = (int) (f * 90);
            dc.fill(0, 0, w, h, (a << 24) | 0x101010);
            return;
        }
        // plasma sheath: bright at the bottom and edges, flickering
        float flick = 0.85f + 0.15f * MathHelper.sin((ticks + pt) * 2.7f);
        int bands = 18;
        for (int i = 0; i < bands; i++) {
            float k = i / (float) bands;
            float a = f * flick * (1f - k) * (1f - k);
            int edge = (int) (k * Math.min(w, h) * 0.45f);
            int col = argb(a * 0.55f, 1f, 0.45f + 0.35f * (1 - k), 0.12f + 0.2f * (1 - k));
            dc.fill(0, h - edge - 6, w, h - edge, col);
            dc.fill(0, edge, w, edge + 4, argb(a * 0.25f, 1f, 0.5f, 0.15f));
            dc.fill(edge, 0, edge + 4, h, argb(a * 0.3f, 1f, 0.5f, 0.15f));
            dc.fill(w - edge - 4, 0, w - edge, h, argb(a * 0.3f, 1f, 0.5f, 0.15f));
        }
        dc.fill(0, 0, w, h, argb(f * 0.18f * flick, 1f, 0.55f, 0.2f));
    }

    private static int argb(float a, float r, float g, float b) {
        return ((int) (MathHelper.clamp(a, 0, 1) * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}
