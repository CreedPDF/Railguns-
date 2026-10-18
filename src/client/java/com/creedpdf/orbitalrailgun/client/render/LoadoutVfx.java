package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
import com.creedpdf.orbitalrailgun.loadout.Loadout.Option;
import com.creedpdf.orbitalrailgun.loadout.LoadoutEffects;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static com.creedpdf.orbitalrailgun.client.render.BeamRenderer.*;

/**
 * Client visuals for loadout options that are geometry rather than particles: satellite beams,
 * braided arcs, plasma bell, lens rings, singularity, sunburst fireball, shield dome and the
 * targeting laser.
 */
public final class LoadoutVfx {
    private LoadoutVfx() {}

    private static final int LIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;

    // =========================================================================================
    // While firing
    // =========================================================================================

    static void firing(VertexConsumerProvider cp, Matrix4f m, MatrixStack matrices, RailgunEntity rg, Loadout l, Beam b,
                       Vector3f surface, float p, float time, Pal pal, float width, Vec3d origin, float tickDelta) {
        float sustain = p < 0.6f ? 1f : (float) Math.pow(1f - (p - 0.6f) / 0.4f, 1.6);
        Option mz = l.muzzle();
        if (mz == Option.SCATTER) {
            for (Vec3d sat : LoadoutEffects.scatterPoints(rg.getWorld(), rg.getTarget())) {
                Beam sb = new Beam(b.a, toLocal(sat, origin));
                texTube(tex(cp, pal.core), m, sb, 0.45f * width, pal.c[0], pal.c[1], pal.c[2], 0.9f * sustain, 5f, time * 3f, 0f, 0.05f, time);
                texTube(tex(cp, pal.plasma), m, sb, 1.0f * width, pal.p[0], pal.p[1], pal.p[2], 0.7f * sustain, 9f, time * 2f, 0f, 0.1f, time + 5);
                glowSphere(glow(cp), m, sb.b, 3f, pal.h[0], pal.h[1] * 0.5f, pal.h[2] * 0.3f, 0.35f * sustain);
            }
        } else if (mz == Option.TRIPRONG) {
            braids(cp, m, b, time, sustain);
        } else if (mz == Option.SHROUD) {
            for (int k = 0; k < 7; k++) {
                float d = k * 1.1f;
                float r = (1.8f + k * 0.7f) * width * (1f + 0.1f * MathHelper.sin(time * 1.3f + k));
                Vector3f c = new Vector3f(b.dir).mul(d).add(b.a);
                texRing(tex(cp, BeamRenderLayers.TINT_SHOCKWAVE), m, c, b.dir, r * 0.6f, r, 1f, 0.45f, 0.15f, 0.7f * sustain * (1 - k / 8f), time * 0.05f * (k % 2 == 0 ? 1 : -1));
            }
        } else if (mz == Option.LANCER) {
            for (int k = 0; k < 3; k++) {
                Vector3f c = new Vector3f(b.dir).mul(0.6f + k * 0.9f).add(b.a);
                float r = 1.2f - k * 0.25f;
                texRing(tex(cp, BeamRenderLayers.TINT_SHOCKWAVE), m, c, b.dir, r, r + 0.35f, 0.5f, 0.95f, 1f, 0.9f * sustain, time * 0.1f);
            }
            glowTube(glow(cp), m, b, 0.35f, 0.7f, 0.95f, 1f, 0.5f * sustain, time);
        } else if (mz == Option.GRAVITY && p < 0.35f) {
            float q = p / 0.35f;
            Vector3f up = new Vector3f(0, 1, 0);
            for (int k = 0; k < 3; k++) {
                float r = 22f * (1f - q) + k * 2f;
                texRing(tex(cp, BeamRenderLayers.TINT_SHOCKWAVE), m, new Vector3f(surface).add(0, 1f + k, 0), up, r, r + 2f, 0.7f, 0.4f, 1f, 0.8f * (1 - q), time * 0.03f);
            }
        }

        Option ammo = l.ammo();
        if (ammo == Option.SINGULARITY) singularity(cp, m, matrices, surface, rg.getStateTicks() + tickDelta, time);
        else if (ammo == Option.SUNBURST) sunburst(cp, m, surface, p, time);
        else if (ammo == Option.ION) {
            Random r = Random.create((long) (time / 2) * 31L + 7L);
            for (int k = 0; k < 10; k++) {
                Vector3f a = new Vector3f(surface).add(0, 1, 0);
                Vector3f e = new Vector3f(surface).add((r.nextFloat() - 0.5f) * 20, r.nextFloat() * 6, (r.nextFloat() - 0.5f) * 20);
                arc(glow(cp), m, a, e, r, 0.08f, 0.8f, 0.55f, 1f, 0.8f * sustain);
            }
        } else if (ammo == Option.CRYO) {
            Vector3f up = new Vector3f(0, 1, 0);
            for (int k = 0; k < 3; k++) {
                float r = 6f + k * 5f + p * 6f;
                texRing(tex(cp, BeamRenderLayers.TINT_SHOCKWAVE), m, new Vector3f(surface).add(0, 0.1f + k * 0.05f, 0), up, r, r + 1.5f, 0.6f, 0.95f, 1f, 0.5f * (1 - p), time * 0.004f);
            }
        }
    }

    private static void braids(VertexConsumerProvider cp, Matrix4f m, Beam b, float time, float a) {
        Random rand = Random.create((long) (time / 2) * 131L + 3L);
        int segs = Math.max(8, (int) (b.length / 2.5f));
        for (int k = 0; k < 3; k++) {
            Vector3f prev = null;
            for (int s = 0; s <= segs; s++) {
                float d = b.length * s / segs;
                float ang = d * 0.35f + time * 0.6f + k * MathHelper.TAU / 3f;
                float r = 2.2f + 0.4f * rand.nextFloat();
                Vector3f pnt = new Vector3f(b.dir).mul(d).add(b.a)
                        .add(new Vector3f(b.u).mul(MathHelper.cos(ang) * r)).add(new Vector3f(b.v).mul(MathHelper.sin(ang) * r));
                if (prev != null) arc(glow(cp), m, prev, pnt, rand, 0.06f, 1f, 0.85f, 0.35f, 0.85f * a);
                prev = pnt;
            }
        }
    }

    /** Micro black hole: an opaque sphere with an accretion disk and a lensing rim, then collapse. */
    private static void singularity(VertexConsumerProvider cp, Matrix4f m, MatrixStack matrices, Vector3f surface, float t, float time) {
        Vector3f core = new Vector3f(surface).add(0, 3, 0);
        if (t < 50) {
            float grow = Math.min(1f, t / 12f);
            float r = 0.5f + 4.0f * grow;
            solidSphere(cp.getBuffer(RenderLayer.getEntityCutoutNoCull(BeamRenderLayers.SINGULARITY)), m, matrices.peek().getNormalMatrix(), core, r, time);
            glowSphere(glow(cp), m, core, r * 1.08f, 0.6f, 0.3f, 1f, 0.5f);
            glowSphere(glow(cp), m, core, r * 1.6f, 0.4f, 0.1f, 0.8f, 0.18f);
            Vector3f tilt = new Vector3f(0.25f, 1f, 0.1f).normalize();
            for (int k = 0; k < 3; k++) {
                float rin = r * (1.4f + k * 0.5f), rout = rin + r * 0.6f;
                texRing(tex(cp, BeamRenderLayers.TINT_SHOCKWAVE), m, core, tilt, rin, rout, 0.75f, 0.45f, 1f, 0.8f - k * 0.2f, time * (0.05f + k * 0.02f));
            }
        } else if (t < 60) {
            float q = (t - 50) / 10f;
            glowSphere(glow(cp), m, core, 2f + q * 16f, 0.85f, 0.7f, 1f, 0.9f * (1 - q));
            texRing(tex(cp, BeamRenderLayers.TINT_SHOCKWAVE), m, new Vector3f(surface).add(0, 0.2f, 0), new Vector3f(0, 1, 0),
                    q * 30f, q * 30f + 4f, 0.8f, 0.6f, 1f, 0.9f * (1 - q), time * 0.01f);
        }
    }

    private static void sunburst(VertexConsumerProvider cp, Matrix4f m, Vector3f surface, float p, float time) {
        float grow = Math.min(1f, p * 2.5f);
        float fade = p < 0.6f ? 1f : 1f - (p - 0.6f) / 0.4f;
        Vector3f c = new Vector3f(surface).add(0, 2 + grow * 10f, 0);
        texSphere(tex(cp, BeamRenderLayers.TINT_PLASMA), m, c, 5f + grow * 16f, 0.9f, 1f, 0.5f, 0.85f * fade, time * 0.04f, time * 0.03f);
        glowSphere(glow(cp), m, c, 4f + grow * 10f, 1f, 1f, 0.85f, 0.9f * fade);
        glowSphere(glow(cp), m, c, 12f + grow * 30f, 0.6f, 0.9f, 0.2f, 0.18f * fade);
        // Stem
        Beam stem = new Beam(new Vector3f(surface), c);
        texTube(tex(cp, BeamRenderLayers.TINT_HALO), m, stem, 3f + grow * 4f, 0.8f, 0.9f, 0.4f, 0.5f * fade, 8f, -time * 0.4f, 0f, 0.2f, time);
        Vector3f up = new Vector3f(0, 1, 0);
        float w = 10f + p * 140f;
        texRing(tex(cp, BeamRenderLayers.TINT_SHOCKWAVE), m, new Vector3f(surface).add(0, 0.3f, 0), up, w, w + 12f, 0.9f, 1f, 0.6f, 0.8f * (1 - p), time * 0.006f);
    }

    // =========================================================================================
    // Always (while deployed): shield dome, targeting laser
    // =========================================================================================

    public static void always(RailgunEntity rg, float tickDelta, MatrixStack matrices, VertexConsumerProvider cp) {
        Loadout l = rg.getLoadout();
        RailgunState st = rg.getState();
        if (!st.isOperational()) return;
        Matrix4f m = matrices.peek().getPositionMatrix();
        float time = rg.age + tickDelta;
        if (l.has(Option.SHIELD)) shield(cp, m, time);
        if (l.has(Option.TARGETING) && rg.hasTarget() && st != RailgunState.FIRING) {
            Vec3d origin = rg.getLerpedPos(tickDelta);
            Vec3d dir = rg.getAimDirection(tickDelta);
            Vec3d side = new Vec3d(dir.z, 0, -dir.x).normalize().multiply(1.45);
            Vec3d from = origin.add(0, RailgunConfig.PIVOT_Y - 0.25, 0).add(side).add(dir.multiply(4.5));
            Vector3f a = toLocal(from, origin);
            Vector3f bpt = toLocal(Vec3d.ofCenter(rg.getTarget()), origin);
            Beam beam = new Beam(a, bpt);
            float flick = 0.8f + 0.2f * MathHelper.sin(time * 3.1f);
            glowTube(glow(cp), m, beam, 0.05f, 1f, 0.1f, 0.08f, 0.9f * flick, time);
            glowTube(glow(cp), m, beam, 0.16f, 1f, 0.05f, 0.03f, 0.25f * flick, time);
            glowSphere(glow(cp), m, bpt, 0.35f, 1f, 0.2f, 0.15f, 0.9f);
            glowSphere(glow(cp), m, bpt, 1.1f, 1f, 0.05f, 0.03f, 0.3f * flick);
        }
    }

    private static void shield(VertexConsumerProvider cp, Matrix4f m, float time) {
        Vector3f c = new Vector3f(0, 2, 0);
        float r = (float) LoadoutEffects.SHIELD_RADIUS;
        float pulse = 0.75f + 0.25f * MathHelper.sin(time * 0.15f);
        VertexConsumer vc = tex(cp, BeamRenderLayers.SHIELD_HEX);
        final int lat = 14, lon = 32;
        float scroll = time * 0.004f;
        for (int i = 0; i < lat; i++) {
            double th0 = -0.25 + (Math.PI / 2 + 0.25) * i / lat, th1 = -0.25 + (Math.PI / 2 + 0.25) * (i + 1) / lat;
            float v0 = i / (float) lat * 3f + scroll, v1 = (i + 1) / (float) lat * 3f + scroll;
            for (int j = 0; j < lon; j++) {
                double ph0 = 2 * Math.PI * j / lon, ph1 = 2 * Math.PI * (j + 1) / lon;
                float u0 = j / (float) lon * 8f, u1 = (j + 1) / (float) lon * 8f;
                float a0 = shieldAlpha(th0) * pulse, a1 = shieldAlpha(th1) * pulse;
                dome(vc, m, c, r, th0, ph0, u0, v0, a0);
                dome(vc, m, c, r, th0, ph1, u1, v0, a0);
                dome(vc, m, c, r, th1, ph1, u1, v1, a1);
                dome(vc, m, c, r, th1, ph0, u0, v1, a1);
            }
        }
        // Scanning band sweeping up the dome.
        float band = (time * 0.01f) % 1f;
        double th = band * Math.PI / 2;
        float rr = r * (float) Math.cos(th);
        texRing(tex(cp, BeamRenderLayers.TINT_SHOCKWAVE), m, new Vector3f(0, 2 + r * (float) Math.sin(th), 0), new Vector3f(0, 1, 0),
                rr - 0.4f, rr + 0.1f, 0.4f, 0.85f, 1f, 0.5f * (1 - band), 0f);
    }

    private static float shieldAlpha(double th) {
        // Brighter towards the rim (Fresnel-ish) and fading into the ground at the skirt.
        float rim = (float) (0.35 + 0.65 * Math.pow(1 - Math.sin(Math.max(0, th)), 2));
        float skirt = (float) MathHelper.clamp((th + 0.25) / 0.25, 0, 1);
        return 0.45f * rim * skirt;
    }

    private static void dome(VertexConsumer vc, Matrix4f m, Vector3f c, float r, double th, double ph, float u, float v, float a) {
        float ct = (float) Math.cos(th);
        vc.vertex(m, c.x + r * ct * (float) Math.cos(ph), c.y + r * (float) Math.sin(th), c.z + r * ct * (float) Math.sin(ph))
                .color(0.55f, 0.85f, 1f, a).texture(u, v).light(LIGHT).normal(0, 1, 0).next();
    }

    private static void solidSphere(VertexConsumer vc, Matrix4f m, Matrix3f n, Vector3f c, float radius, float time) {
        final int lat = 12, lon = 20;
        float off = time * 0.01f;
        for (int i = 0; i < lat; i++) {
            double th0 = Math.PI * i / lat - Math.PI / 2, th1 = Math.PI * (i + 1) / lat - Math.PI / 2;
            for (int j = 0; j < lon; j++) {
                double ph0 = 2 * Math.PI * j / lon, ph1 = 2 * Math.PI * (j + 1) / lon;
                sv(vc, m, n, c, radius, th0, ph0, j / (float) lon + off, i / (float) lat);
                sv(vc, m, n, c, radius, th1, ph0, j / (float) lon + off, (i + 1) / (float) lat);
                sv(vc, m, n, c, radius, th1, ph1, (j + 1) / (float) lon + off, (i + 1) / (float) lat);
                sv(vc, m, n, c, radius, th0, ph1, (j + 1) / (float) lon + off, i / (float) lat);
            }
        }
    }

    private static void sv(VertexConsumer vc, Matrix4f m, Matrix3f n, Vector3f c, float r, double th, double ph, float u, float v) {
        float ct = (float) Math.cos(th);
        float nx = ct * (float) Math.cos(ph), ny = (float) Math.sin(th), nz = ct * (float) Math.sin(ph);
        vc.vertex(m, c.x + r * nx, c.y + r * ny, c.z + r * nz).color(1f, 1f, 1f, 1f).texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV).light(LIGHT).normal(n, nx, ny, nz).next();
    }
}
