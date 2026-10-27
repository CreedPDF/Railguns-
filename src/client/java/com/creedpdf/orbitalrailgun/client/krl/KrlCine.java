package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.krl.KrlFlight;
import com.creedpdf.orbitalrailgun.krl.KrlNet;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.Locale;

/**
 * The ride, for the player who launched with the cutscene on:
 * <ol>
 *   <li>PAD - from the ground beside the launcher, the last seconds of the motor's burn, liftoff and the boom;</li>
 *   <li>RIDE - on the spear as it climbs and pitches over into the stratosphere;</li>
 *   <li>APOGEE - a slow orbit round it as it hangs at the top and noses over;</li>
 *   <li>DIVE - on the spear again as it falls, faster and faster, the tip glowing ahead of you;</li>
 *   <li>GROUND - from the ground near the target as it comes down (a white point at night) and hits.</li>
 * </ol>
 * The camera is pure flight-time maths, so it is smooth whatever the network does. Sneak to leave.
 */
public final class KrlCine {
    private KrlCine() {}

    private static int loopsFor = -1;
    private static boolean wasSneak;

    @Nullable
    static KrlClient.CFlight flight() {
        if (KrlClient.riding < 0) return null;
        KrlClient.CFlight f = KrlClient.flight(KrlClient.riding);
        return f != null && KrlClient.here(f) ? f : null;
    }

    public static boolean active() {
        KrlClient.CFlight f = flight();
        if (f == null) return false;
        double t = f.t(0);
        return t >= -KrlFlight.PAD_LEAD - 5 && t < KrlFlight.TOTAL + KrlFlight.AFTER + 5;
    }

    /** True while the camera rides on the spear itself. */
    static boolean onSpear(double t) {
        return (t >= KrlFlight.RIDE && t < KrlFlight.ORBIT) || (t >= KrlFlight.FALL_RIDE && t < KrlFlight.GROUND);
    }

    static void tick(MinecraftClient mc) {
        KrlClient.CFlight f = flight();
        if (f == null) {
            loopsFor = -1;
            return;
        }
        if (loopsFor != f.id) {
            loopsFor = f.id;
            KrlClient.riderLoops(mc, f.id);
        }
        boolean sn = mc.options.sneakKey.isPressed();
        if (sn && !wasSneak && active()) ClientPlayNetworking.send(KrlNet.SKIP, PacketByteBufs.create());
        wasSneak = sn;
    }

    // ------------------------------------------------------------------------------------------- frames

    private static final Vec3d UP = new Vec3d(0, 1, 0);

    static Vec3d side(KrlFlight p) {
        Vec3d s = p.h.crossProduct(UP);
        return s.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : s.normalize();
    }

    /** "Up" for a rider on the spear: away from the target while it climbs vertically, overhead when level, toward it when diving. */
    static Vec3d upOn(KrlFlight p, Vec3d d) {
        Vec3d u = side(p).crossProduct(d);
        return u.lengthSquared() < 1e-6 ? UP : u.normalize();
    }

    private static double ground(double x, double z, double fallback) {
        var w = KrlClient.world();
        if (w == null) return fallback;
        int ix = MathHelper.floor(x), iz = MathHelper.floor(z);
        if (!w.isChunkLoaded(ix >> 4, iz >> 4)) {
            KrlSurvey s = KrlSurvey.latest();
            return s != null && s.covers(x, z) ? s.heightAt(x, z) : fallback;
        }
        return w.getTopY(Heightmap.Type.MOTION_BLOCKING, ix, iz);
    }

    private static double sm(double x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * x * (x * (x * 6 - 15) + 10);
    }

    /** {x, y, z, yaw, pitch} or null. */
    public static double[] camera(float pt) {
        if (!active()) return null;
        KrlClient.CFlight f = flight();
        KrlFlight p = f.path;
        double t = f.t(pt);
        Vec3d eye, look;
        if (t < KrlFlight.RIDE) {
            Vec3d pc = p.padCam();
            double push = sm((t + KrlFlight.PAD_LEAD) / (KrlFlight.PAD_LEAD + KrlFlight.RIDE));
            Vec3d toPad = f.launch.subtract(pc).multiply(1, 0, 1).normalize();
            eye = pc.add(toPad.multiply(3.5 * push));
            eye = new Vec3d(eye.x, Math.max(eye.y, ground(eye.x, eye.z, eye.y) + 1.7), eye.z);
            Vec3d rest = f.launch.add(p.rail.multiply(3.2));
            if (t <= 0) look = rest;
            else {
                Vec3d sp = KrlFx.nozzle(f, t).add(p.dir(t).multiply(3.5));
                look = rest.lerp(sp, sm(t / 10.0));
            }
        } else if (t < KrlFlight.ORBIT) {
            Vec3d n = KrlFx.nozzle(f, t), d = p.dir(t), u = upOn(p, d);
            eye = n.subtract(d.multiply(0.4)).add(u.multiply(1.05)).add(side(p).multiply(0.3));
            look = n.add(d.multiply(16)).add(u.multiply(0.6));
        } else if (t < KrlFlight.FALL_RIDE) {
            double k = (t - KrlFlight.ORBIT) / (KrlFlight.FALL_RIDE - KrlFlight.ORBIT);
            Vec3d n = KrlFx.nozzle(f, t), d = p.dir(t);
            Vec3d c = n.add(d.multiply(3.5));
            double phi = -0.5 + 2.1 * sm(k);
            double R = 15 - 3 * k;
            eye = c.add(side(p).multiply(Math.cos(phi) * R)).add(p.h.multiply(-Math.sin(phi) * R)).add(0, 3.2 - 2.5 * k, 0);
            look = c;
        } else if (t < KrlFlight.GROUND) {
            Vec3d n = KrlFx.nozzle(f, t), d = p.dir(t), u = upOn(p, d);
            eye = n.add(d.multiply(0.6)).add(u.multiply(1.0)).add(side(p).multiply(-0.25));
            look = n.add(d.multiply(30)).add(u.multiply(0.3));
        } else {
            Vec3d gc = p.groundCam();
            eye = new Vec3d(gc.x, ground(gc.x, gc.z, gc.y) + 1.8, gc.z);
            double ti = t - KrlFlight.TOTAL;
            Vec3d after = f.target.add(0, 14 + Math.max(0, ti) * 0.18, 0);
            if (t < KrlFlight.TOTAL) look = KrlFx.nose(f, t);
            else look = KrlFx.nose(f, KrlFlight.TOTAL - 0.01).lerp(after, sm(ti / 14));
        }
        Vec3d shake = KrlClient.shake(eye, pt);
        if (onSpear(t)) {
            double m = p.mach(t);
            double a = 0.012 + 0.02 * Math.min(1, m / 6) + (t > KrlFlight.FALL_RIDE ? 0.03 * KrlFx.heat(f, t) : 0);
            double tt = (KrlClient.now() + pt) * 2.3;
            shake = shake.add(Math.sin(tt * 3.1) * a, Math.sin(tt * 4.7 + 1) * a, Math.sin(tt * 3.9 + 2) * a);
        }
        eye = eye.add(shake);
        Vec3d dv = look.subtract(eye);
        double hz = Math.sqrt(dv.x * dv.x + dv.z * dv.z);
        double yaw = Math.toDegrees(Math.atan2(dv.z, dv.x)) - 90;
        double pitch = -Math.toDegrees(Math.atan2(dv.y, hz));
        return new double[]{eye.x, eye.y, eye.z, yaw, pitch};
    }

    // ------------------------------------------------------------------------------------------- sound levels

    static double windVolume() {
        KrlClient.CFlight f = flight();
        if (f == null) return 0;
        double t = f.t(0);
        if (!onSpear(t)) return t >= KrlFlight.ORBIT && t < KrlFlight.FALL_RIDE ? 0.25 : 0;
        return 0.15 + 0.85 * Math.min(1, f.path.speed(t) / 6);
    }

    static double plasmaVolume() {
        KrlClient.CFlight f = flight();
        if (f == null) return 0;
        double t = f.t(0);
        return onSpear(t) ? KrlFx.heat(f, t) : 0;
    }

    // ------------------------------------------------------------------------------------------- fog / far plane

    /** Fog distance overrides while high in the ride (so the ground below is not lost in terrain fog), or null. */
    @Nullable
    public static float[] fog(float viewDistance) {
        if (!active()) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d c = mc.gameRenderer.getCamera().getPos();
        double alt = c.y - ground(c.x, c.z, 64);
        if (alt < 60) return null;
        float k = (float) MathHelper.clamp((alt - 60) / 160, 0, 1);
        return new float[]{MathHelper.lerp(k, viewDistance * 0.9f, viewDistance * 3f), MathHelper.lerp(k, viewDistance, viewDistance * 7f)};
    }

    // ------------------------------------------------------------------------------------------- the ground beyond the loaded chunks

    static void farTerrain(WorldRenderContext ctx) {
        KrlSurvey s = KrlSurvey.latest();
        if (s == null) return;
        Vec3d cam = ctx.camera().getPos();
        double alt = cam.y - ground(cam.x, cam.z, 64);
        if (alt < 90) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        double inner = mc.options.getClampedViewDistance() * 16 - 20;
        float day = KrlClient.daylight();
        float lightK = 0.06f + 0.94f * day;
        float[] fogc = RenderSystem.getShaderFogColor();
        Matrix4f m = ctx.matrixStack().peek().getPositionMatrix();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        BufferBuilder bb = Tessellator.getInstance().getBuffer();
        bb.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        int n = s.n, cell = s.cell;
        int step = alt > 400 ? 2 : 1;
        for (int j = 0; j < n - step; j += step)
            for (int i = 0; i < n - step; i += step) {
                double x0 = s.x0 + i * cell + cell / 2.0, z0 = s.z0 + j * cell + cell / 2.0;
                double x1 = x0 + cell * step, z1 = z0 + cell * step;
                double cx = (x0 + x1) / 2 - cam.x, cz = (z0 + z1) / 2 - cam.z;
                double dh = Math.sqrt(cx * cx + cz * cz);
                if (dh < inner) continue;
                int k = s.k(i, j);
                float[] col = KrlSurvey.natural(k);
                float haze = (float) MathHelper.clamp((Math.sqrt(dh * dh + alt * alt) - inner) / 3200.0, 0, 0.75);
                float r = MathHelper.lerp(haze, col[0] * lightK, fogc[0]), g = MathHelper.lerp(haze, col[1] * lightK, fogc[1]),
                        b = MathHelper.lerp(haze, col[2] * lightK, fogc[2]);
                float y00 = hy(s, i, j), y10 = hy(s, i + step, j), y11 = hy(s, i + step, j + step), y01 = hy(s, i, j + step);
                bb.vertex(m, (float) (x0 - cam.x), (float) (y00 - cam.y), (float) (z0 - cam.z)).color(r, g, b, 1f).next();
                bb.vertex(m, (float) (x0 - cam.x), (float) (y01 - cam.y), (float) (z1 - cam.z)).color(r, g, b, 1f).next();
                bb.vertex(m, (float) (x1 - cam.x), (float) (y11 - cam.y), (float) (z1 - cam.z)).color(r, g, b, 1f).next();
                bb.vertex(m, (float) (x1 - cam.x), (float) (y10 - cam.y), (float) (z0 - cam.z)).color(r, g, b, 1f).next();
            }
        BufferRenderer.drawWithGlobalProgram(bb.end());
        RenderSystem.enableCull();
    }

    private static float hy(KrlSurvey s, int i, int j) {
        int k = s.k(i, j);
        int h = s.h(i, j);
        return (KrlSurvey.wet(k) ? Math.max(h, s.sea) : h) - 2.5f;
    }

    // ------------------------------------------------------------------------------------------- HUD

    private static final int RED = 0xFFFFB02E, DIM = 0xFFB8B4A6, WHITE = 0xFFF2F0E8;

    static void hud(DrawContext dc, float pt) {
        if (!active()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        KrlClient.CFlight f = flight();
        KrlFlight p = f.path;
        double t = f.t(pt);
        int W = dc.getScaledWindowWidth(), H = dc.getScaledWindowHeight();
        TextRenderer tr = mc.textRenderer;
        // letterbox
        float in = (float) MathHelper.clamp((t + KrlFlight.PAD_LEAD + 5) / 12, 0, 1) * (float) MathHelper.clamp((KrlFlight.TOTAL + KrlFlight.AFTER - t) / 10, 0, 1);
        int bar = (int) (H * 0.105f * in);
        dc.fill(0, 0, W, bar, 0xFF000000);
        dc.fill(0, H - bar, W, H, 0xFF000000);
        // a short dip to black at each cut
        float dip = 0;
        for (int cut : new int[]{KrlFlight.RIDE, KrlFlight.ORBIT, KrlFlight.FALL_RIDE, KrlFlight.GROUND}) {
            double d = Math.abs(t - cut);
            if (d < 3) dip = Math.max(dip, (float) (1 - d / 3));
        }
        if (dip > 0.01f) dc.fill(0, 0, W, H, ((int) (dip * 255) << 24));
        double ti = t - KrlFlight.TOTAL;

        var ms = dc.getMatrices();
        // telemetry, bottom left
        if (t > -KrlFlight.PAD_LEAD + 10) {
            int x = 10, y = H - bar - 62;
            String phase;
            if (t < -com.creedpdf.orbitalrailgun.krl.Krl.FLAME) phase = "TURRET SLEW  -  TARGET BEARING";
            else if (t < 0) phase = "IGNITION  -  MOTOR AT FULL THRUST";
            else if (t < KrlFx.BURNOUT) phase = "POWERED ASCENT";
            else if (t < KrlFlight.BOOST) phase = "BURNOUT  BALLISTIC COAST";
            else if (t < KrlFlight.BOOST + KrlFlight.STALL) phase = "APOGEE  PITCH-OVER";
            else if (t < KrlFlight.TOTAL) phase = KrlFx.heat(f, t) > 0.3 ? "RE-ENTRY  PLASMA SHEATH" : "TERMINAL DIVE";
            else phase = "IMPACT";
            double tc = Math.min(t, KrlFlight.TOTAL);
            double mach = t < 0 ? 0 : p.mach(tc);
            double alt = t < 0 ? 0 : p.altitudeKm(tc);
            double vel = mach * speedOfSound(alt);
            line(dc, tr, x, y, (t < 0 ? "T-" : "T+") + String.format(Locale.ROOT, "%05.1f s", Math.abs(t) / 20), WHITE, 1f);
            line(dc, tr, x, y + 11, String.format(Locale.ROOT, "MACH %4.1f   %,6.0f m/s", mach, vel), RED, 1f);
            line(dc, tr, x, y + 22, String.format(Locale.ROOT, "ALT  %5.1f km   DOWNRANGE %,5.0f m", alt, progress(f, tc)), RED, 1f);
            if (t > KrlFlight.BOOST + KrlFlight.STALL && t < KrlFlight.TOTAL) {
                double tk = stagnationK(mach);
                line(dc, tr, x, y + 33, String.format(Locale.ROOT, "STAGNATION %,6.0f K   q %,5.0f kPa", tk, dynPressure(alt, vel) / 1000), DIM, 1f);
            }
            line(dc, tr, x, y + 44, phase, DIM, 0.75f);
        }
        // captions
        caption(dc, tr, W, H, t, KrlFlight.ORBIT + 8, 60,
                String.format(Locale.ROOT, "APOGEE  %.1f km  -  STRATOSPHERE", p.altitudeKm(KrlFlight.BOOST + 4)), RED);
        caption(dc, tr, W, H, t, KrlFlight.FALL_RIDE + 6, 50, String.format(Locale.ROOT, "TERMINAL DIVE  -  MACH %.0f", p.machMax), RED);
        caption(dc, tr, W, H, t, KrlFlight.GROUND + 4, 60, String.format(Locale.ROOT, "IMPACT POINT  %d, %d", (int) Math.floor(f.target.x),
                (int) Math.floor(f.target.z)), WHITE);
        if (t < -KrlFlight.PAD_LEAD + 80) {
            float a = (float) MathHelper.clamp(1 - (t + KrlFlight.PAD_LEAD - 50) / 30, 0, 1);
            String s = "[" + mc.options.sneakKey.getBoundKeyLocalizedText().getString().toUpperCase(Locale.ROOT) + "] LEAVE THE RIDE";
            ms.push();
            ms.translate(W - 10 - tr.getWidth(com.creedpdf.orbitalrailgun.krl.KrlText.t(s)) * 0.75f, bar + 8, 0);
            ms.scale(0.75f, 0.75f, 1);
            dc.drawText(tr, com.creedpdf.orbitalrailgun.krl.KrlText.t(s), 0, 0, ((int) (a * 255) << 24) | 0xC0C0C0, false);
            ms.pop();
        }
    }

    private static void line(DrawContext dc, TextRenderer tr, int x, int y, String s, int color, float scale) {
        var ms = dc.getMatrices();
        ms.push();
        ms.translate(x, y, 0);
        ms.scale(scale, scale, 1);
        com.creedpdf.orbitalrailgun.client.ui.Px.snap(dc);
        dc.drawText(tr, com.creedpdf.orbitalrailgun.krl.KrlText.t(s, com.creedpdf.orbitalrailgun.client.ui.Px.of(dc)), 0, 0, color, true);
        ms.pop();
    }

    /** A small, quiet caption under the top letterbox bar with a hairline rule. */
    private static void caption(DrawContext dc, TextRenderer tr, int W, int H, double t, double at, double len, String s, int color) {
        double k = (t - at) / len;
        if (k < 0 || k > 1) return;
        float a = MathHelper.clamp((float) Math.min(1, Math.min(k * 6, (1 - k) * 4)), 0, 1);
        var txt = com.creedpdf.orbitalrailgun.krl.KrlText.h(s);
        float sc = 0.85f;
        float w = tr.getWidth(txt) * sc;
        float y = H * 0.105f + 8;
        var ms = dc.getMatrices();
        ms.push();
        ms.translate(W / 2f - w / 2f, y, 0);
        ms.scale(sc, sc, 1);
        com.creedpdf.orbitalrailgun.client.ui.Px.snap(dc);
        txt = com.creedpdf.orbitalrailgun.krl.KrlText.h(s, com.creedpdf.orbitalrailgun.client.ui.Px.of(dc));
        dc.drawText(tr, txt, 0, 0, ((int) (a * 235) << 24) | (color & 0xFFFFFF), false);
        ms.pop();
        int ai = (int) (a * 150) << 24;
        dc.fill((int) (W / 2f - w / 2f), (int) (y + 10), (int) (W / 2f + w / 2f), (int) (y + 10) + 1, ai | (color & 0xFFFFFF));
    }

    private static double progress(KrlClient.CFlight f, double t) {
        Vec3d q = f.path.pos(Math.max(0, t));
        return Math.hypot(q.x - f.launch.x, q.z - f.launch.z);
    }

    /** Speed of sound (m/s) at an altitude (km), from the 1976 US Standard Atmosphere's temperature profile. */
    public static double speedOfSound(double km) {
        return Math.sqrt(1.4 * 287.05 * KrlScreen.Atmos.temperature(km));
    }

    /** Stagnation temperature (K) behind the bow shock: ideal-gas value, capped by dissociation (real-gas effects). */
    public static double stagnationK(double mach) {
        double ideal = 216.65 * (1 + 0.2 * mach * mach);
        return ideal < 3000 ? ideal : 3000 + (ideal - 3000) * 0.55;
    }

    public static double dynPressure(double km, double v) {
        return 0.5 * KrlScreen.Atmos.density(km) * v * v;
    }
}
