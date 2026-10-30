package com.creedpdf.orbitalrailgun.client.astraea;

import com.creedpdf.orbitalrailgun.astraea.Timeline;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Random;

import static com.creedpdf.orbitalrailgun.client.astraea.AstraeaDraw.*;

/**
 * The Ascension ritual, in the world (everyone nearby sees it):
 * <ul>
 *   <li>two ritual circles burn into the ground round the bearer, counter-rotating, with a ghost circle hovering above
 *   and pillars of light rising from the outer ring;</li>
 *   <li>a rift tears open high overhead: a jagged lens whose outline wanders, a swirling void of pale-gold nebula and
 *   hard stars inside, a white-hot rim of crackling filaments with lightning arcing across it, slivers of sky peeling
 *   away from its edge, light spilling down;</li>
 *   <li>a star is born in it: a hard white point with long diffraction spikes, pulsing faintly, flashing brighter on
 *   each pulse, swelling until it fills the rift;</li>
 *   <li>its light comes down as a pillar - a white core in a warm sheath, ribbons spiralling round it - onto the lance
 *   held overhead;</li>
 *   <li>streams of light spiral in from all round into the bearer; at the moment of Ascension a flash, a shock ring
 *   racing across the ground, and the rift seals.</li>
 * </ul>
 */
final class RitualFx {
    private RitualFx() {}

    static final double RIFT_HEIGHT = 34;

    static void draw(VertexConsumerProvider.Immediate buf, Matrix4f m, Vec3d cam, AstraeaState.Ritual r, float pt) {
        float t = (float) AstraeaState.ritualT(r.player(), pt);
        if (t < 0 || t > Timeline.R_END + 30) return;
        Vec3d a = r.anchor();
        float life = smooth(t / 30f) * (1f - smooth((t - (Timeline.R_END - 24)) / 30f));
        float starK = clamp01((t - Timeline.R_STAR) / (float) (Timeline.R_DESCEND - Timeline.R_STAR));
        float pulse = (float) Math.pow(0.5 + 0.5 * Math.sin(Math.PI * 2 * (t - Timeline.R_STAR) / 22.0 - Math.PI / 2), 10) * (t > Timeline.R_STAR ? 1 : 0);
        float impact = t >= Timeline.R_IMPACT ? Math.max(0f, 1f - (t - Timeline.R_IMPACT) / 26f) : 0f;

        sigils(buf, m, cam, a, t, life, starK, pulse, impact);
        Vec3d rc = a.add(0, RIFT_HEIGHT, 0);
        float open = smooth((t - Timeline.R_RIFT) / (float) (Timeline.R_RIFT_OPEN - Timeline.R_RIFT))
                * (1f - smooth((t - Timeline.R_CLOSE) / (float) (Timeline.R_END - 14 - Timeline.R_CLOSE)));
        if (open > 0.001f) rift(buf, m, cam, rc, t, open, r.player());
        if (t > Timeline.R_STAR - 8 && t < Timeline.R_END) star(buf, m, cam, rc.add(0, -0.6, 0), t, starK, pulse, open);
        if (t > Timeline.R_DESCEND && t < Timeline.R_CLOSE + 16) beam(buf, m, cam, rc.add(0, -0.6, 0), a.add(0, 2.5, 0), t);
        if (t > Timeline.R_ABSORB && t < Timeline.R_IMPACT + 4) absorb(buf, m, cam, a.add(0, 1.4, 0), t, r.player());
        if (impact > 0) shock(buf, m, cam, a, t - Timeline.R_IMPACT, impact);
    }

    // ------------------------------------------------------------------------------------------- the circles

    private static void sigils(VertexConsumerProvider.Immediate buf, Matrix4f m, Vec3d cam, Vec3d a, float t, float life, float starK, float pulse,
                               float impact) {
        if (life <= 0.001f) return;
        Vector3f c = rel(a.add(0, 0.06, 0), cam);
        Vector3f u = new Vector3f(1, 0, 0), w = new Vector3f(0, 0, 1);
        float bright = life * (0.55f + 0.25f * starK + 0.3f * pulse + 0.6f * impact);
        VertexConsumer outer = buf.getBuffer(BeamRenderLayers.textured(SIGIL_OUTER));
        quad(outer, m, c, u, w, 7.6f * (0.8f + 0.2f * smooth(t / 24f)), t * 0.004f, WARM, bright);
        quad(outer, m, new Vector3f(c).add(0, 0.8f + 0.25f * MathHelper.sin(t * 0.05f), 0), u, w, 6.4f, -t * 0.006f, WHITE, bright * 0.28f);
        VertexConsumer inner = buf.getBuffer(BeamRenderLayers.textured(SIGIL_INNER));
        quad(inner, m, new Vector3f(c).add(0, 0.02f, 0), u, w, 3.7f, -t * 0.011f, WHITE, bright);
        // pillars of light from the outer ring's eight points
        VertexConsumer g = buf.getBuffer(BeamRenderLayers.GLOW);
        float ph = smooth((t - 20) / 40f) * life;
        if (ph > 0.01f) {
            for (int k = 0; k < 8; k++) {
                float ang = k * MathHelper.PI / 4 + t * 0.004f;
                Vector3f base = new Vector3f(c).add(MathHelper.cos(ang) * 6.2f, 0, MathHelper.sin(ang) * 6.2f);
                float hgt = 2.5f + 9f * starK + 4f * pulse;
                strip(g, m, base, new Vector3f(base).add(0, hgt * ph, 0), 0.5f, 0.05f, WARM, 0.5f * ph, 0f);
                strip(g, m, base, new Vector3f(base).add(0, hgt * 0.7f * ph, 0), 0.14f, 0.02f, WHITE, 0.9f * ph, 0f);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- the rift

    private static float outline(float th, float a, float b, float t, long seed) {
        float c = MathHelper.cos(th), s = MathHelper.sin(th);
        float e = a * b / (float) Math.sqrt((b * c) * (b * c) + (a * s) * (a * s) + 1e-6f);
        float jag = 1f + 0.16f * MathHelper.sin(th * 7 + seed % 11) + 0.08f * MathHelper.sin(th * 17 + t * 0.05f + seed % 5)
                + 0.05f * MathHelper.sin(th * 31 - t * 0.09f);
        return e * jag;
    }

    private static void rift(VertexConsumerProvider.Immediate buf, Matrix4f m, Vec3d cam, Vec3d rc, float t, float open, int seedId) {
        long seed = seedId * 7919L;
        Vector3f c = rel(rc, cam);
        float yaw = (seedId * 0.7f) % MathHelper.TAU, tilt = 0.2f;
        Vector3f u = new Vector3f(MathHelper.cos(yaw), 0, MathHelper.sin(yaw));
        Vector3f w = new Vector3f(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw));
        w = new Vector3f(w).mul(MathHelper.cos(tilt)).add(0, MathHelper.sin(tilt), 0);
        float A = 3f + 13f * open, B = 0.35f + 6.5f * (float) Math.pow(open, 1.3);
        int n = 120;
        Vector3f[] p = new Vector3f[n + 1];
        float[] rr = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            float th = i * MathHelper.TAU / n;
            rr[i] = outline(th, A, B, t, seed);
            p[i] = new Vector3f(c).add(new Vector3f(u).mul(MathHelper.cos(th) * rr[i])).add(new Vector3f(w).mul(MathHelper.sin(th) * rr[i]));
        }
        float maxR = A * 1.25f;
        float swirl = t * 0.008f;
        // the void: a fan, its texture turning slowly (alpha-blended so it darkens the sky)
        VertexConsumer v = buf.getBuffer(BeamRenderLayers.translucent(RIFT_VOID));
        for (int i = 0; i < n; i++) {
            float th0 = i * MathHelper.TAU / n, th1 = (i + 1) * MathHelper.TAU / n;
            float[] uv0 = fanUv(th0, rr[i] / maxR, swirl), uv1 = fanUv(th1, rr[i + 1] / maxR, swirl);
            tv(v, m, c, 1, 1, 1, open, 0.5f, 0.5f);
            tv(v, m, p[i], 1, 1, 1, open, uv0[0], uv0[1]);
            tv(v, m, p[i + 1], 1, 1, 1, open, uv1[0], uv1[1]);
            tv(v, m, c, 1, 1, 1, open, 0.5f, 0.5f);
        }
        // a second, additive pass of the void for the light inside it
        VertexConsumer va = buf.getBuffer(BeamRenderLayers.textured(RIFT_VOID));
        for (int i = 0; i < n; i++) {
            float th0 = i * MathHelper.TAU / n, th1 = (i + 1) * MathHelper.TAU / n;
            float[] uv0 = fanUv(th0, rr[i] / maxR, -swirl * 1.6f), uv1 = fanUv(th1, rr[i + 1] / maxR, -swirl * 1.6f);
            tv(va, m, c, WARM[0], WARM[1], WARM[2], open * 0.45f, 0.5f, 0.5f);
            tv(va, m, p[i], WARM[0], WARM[1], WARM[2], open * 0.45f, uv0[0], uv0[1]);
            tv(va, m, p[i + 1], WARM[0], WARM[1], WARM[2], open * 0.45f, uv1[0], uv1[1]);
            tv(va, m, c, WARM[0], WARM[1], WARM[2], open * 0.45f, 0.5f, 0.5f);
        }
        // the rim: a white-hot band of crackling filaments, in the rift's plane
        VertexConsumer rim = buf.getBuffer(BeamRenderLayers.textured(RIFT_RIM));
        float band = 0.55f + 1.1f * open;
        for (int i = 0; i < n; i++) {
            Vector3f d0 = new Vector3f(p[i]).sub(c).normalize(), d1 = new Vector3f(p[i + 1]).sub(c).normalize();
            Vector3f in0 = new Vector3f(p[i]).sub(new Vector3f(d0).mul(band * 0.45f)), out0 = new Vector3f(p[i]).add(new Vector3f(d0).mul(band * 0.55f));
            Vector3f in1 = new Vector3f(p[i + 1]).sub(new Vector3f(d1).mul(band * 0.45f)), out1 = new Vector3f(p[i + 1]).add(new Vector3f(d1).mul(band * 0.55f));
            float u0 = i / 12f + t * 0.03f, u1 = (i + 1) / 12f + t * 0.03f;
            float al = Math.min(1f, open * 1.5f);
            tv(rim, m, in0, 1, 1, 1, al, u0, 0);
            tv(rim, m, in1, 1, 1, 1, al, u1, 0);
            tv(rim, m, out1, 1, 1, 1, al, u1, 1);
            tv(rim, m, out0, 1, 1, 1, al, u0, 1);
        }
        VertexConsumer g = buf.getBuffer(BeamRenderLayers.GLOW);
        // soft halo round the rim and light spilling below
        for (int i = 0; i < n; i++) {
            Vector3f d0 = new Vector3f(p[i]).sub(c).normalize(), d1 = new Vector3f(p[i + 1]).sub(c).normalize();
            v(g, m, p[i], WHITE, 0.35f * open);
            v(g, m, p[i + 1], WHITE, 0.35f * open);
            v(g, m, new Vector3f(p[i + 1]).add(new Vector3f(d1).mul(3f)), WARM, 0f);
            v(g, m, new Vector3f(p[i]).add(new Vector3f(d0).mul(3f)), WARM, 0f);
        }
        Vector3f below = new Vector3f(c).add(0, -0.3f, 0);
        ring(g, m, below, u, w, 0f, A * 1.6f, 40, WARM, 0.12f * open, 0f);
        // lightning arcing across the rim
        long tick = (long) (t / 2);
        Random rnd = new Random(seed ^ tick * 31L);
        int bolts = (int) (3 + 6 * open);
        for (int k = 0; k < bolts; k++) {
            int i0 = rnd.nextInt(n), span = 3 + rnd.nextInt(12);
            Vector3f a0 = p[i0], a1 = p[(i0 + span) % n];
            Vector3f mid = new Vector3f(a0).add(a1).mul(0.5f);
            Vector3f out = new Vector3f(mid).sub(c).normalize().mul(1.2f + rnd.nextFloat() * 2.5f);
            mid.add(out);
            float al = (0.6f + 0.4f * rnd.nextFloat()) * open;
            bolt(g, m, a0, mid, 0.9f, 0.06f, WARM, al, rnd.nextLong());
            bolt(g, m, mid, a1, 0.9f, 0.06f, WARM, al, rnd.nextLong());
        }
        // slivers of sky peeling off the edge
        Random sh = new Random(seed);
        for (int k = 0; k < 28; k++) {
            int i0 = sh.nextInt(n);
            float ph = ((t * 0.012f + sh.nextFloat()) % 1f);
            Vector3f d = new Vector3f(p[i0]).sub(c).normalize();
            Vector3f at = new Vector3f(p[i0]).add(new Vector3f(d).mul(ph * 6f)).add(0, -ph * 4f, 0);
            float s = 0.35f * (1 - ph);
            Vector3f[] bs = basis(new Vector3f(at).add(0, 0.001f, 0));
            float ang = ph * 8 + k;
            Vector3f e1 = new Vector3f(bs[0]).mul(MathHelper.cos(ang) * s).add(new Vector3f(bs[1]).mul(MathHelper.sin(ang) * s));
            Vector3f e2 = new Vector3f(bs[1]).mul(MathHelper.cos(ang) * s).sub(new Vector3f(bs[0]).mul(MathHelper.sin(ang) * s));
            float al = (1 - ph) * 0.8f * open;
            v(g, m, new Vector3f(at).add(e1), WHITE, al);
            v(g, m, new Vector3f(at).add(e2), WHITE, al);
            v(g, m, new Vector3f(at).sub(e1), WHITE, al * 0.3f);
            v(g, m, new Vector3f(at).sub(e1), WHITE, al * 0.3f);
        }
    }

    private static float[] fanUv(float th, float rn, float swirl) {
        float a = th + swirl;
        return new float[]{0.5f + 0.48f * MathHelper.cos(a) * Math.min(1f, rn), 0.5f + 0.48f * MathHelper.sin(a) * Math.min(1f, rn)};
    }

    // ------------------------------------------------------------------------------------------- the star

    private static void star(VertexConsumerProvider.Immediate buf, Matrix4f m, Vec3d cam, Vec3d at, float t, float k, float pulse, float open) {
        float born = smooth((t - (Timeline.R_STAR - 8)) / 30f);
        float fade = 1f - smooth((t - Timeline.R_IMPACT) / 20f);
        float I = born * fade * (0.15f + 0.85f * (float) Math.pow(k, 1.4) + 0.4f * pulse * (0.3f + k));
        if (t >= Timeline.R_DESCEND) I = Math.max(I, fade);
        if (I <= 0.003f) return;
        Vector3f c = rel(at, cam);
        float size = 2.4f + 9.5f * k + 3.5f * pulse;
        VertexConsumer s = buf.getBuffer(BeamRenderLayers.textured(STAR));
        sprite(s, m, c, size, t * 0.002f, WHITE, Math.min(1f, I));
        sprite(s, m, c, size * 0.6f, -t * 0.004f + 0.5f, WARM, Math.min(1f, I * 0.7f));
        VertexConsumer h = buf.getBuffer(BeamRenderLayers.textured(GLOW));
        sprite(h, m, c, size * 2.6f, 0, WARM, 0.35f * I);
        sprite(h, m, c, size * 0.5f, 0, WHITE, Math.min(1f, I * 1.2f));
    }

    // ------------------------------------------------------------------------------------------- the beam

    private static void beam(VertexConsumerProvider.Immediate buf, Matrix4f m, Vec3d cam, Vec3d from, Vec3d to, float t) {
        float h = smooth((t - Timeline.R_DESCEND) / 22f);
        float fade = 1f - smooth((t - Timeline.R_IMPACT - 2) / (float) (Timeline.R_CLOSE + 16 - Timeline.R_IMPACT - 2));
        if (fade <= 0) return;
        Vec3d headW = from.lerp(to, h);
        Vector3f a = rel(from, cam), b = rel(headW, cam);
        float fl = 0.9f + 0.1f * MathHelper.sin(t * 2.3f);
        VertexConsumer g = buf.getBuffer(BeamRenderLayers.GLOW);
        strip(g, m, a, b, 5.0f * fl, 4.2f * fl, WARM, 0.16f * fade, 0.2f * fade);
        strip(g, m, a, b, 2.0f * fl, 1.8f * fl, WARM, 0.45f * fade, 0.5f * fade);
        strip(g, m, a, b, 0.6f, 0.55f, WHITE, fade, fade);
        // ribbons spiralling down round it
        int segs = 48;
        for (int k = 0; k < 3; k++) {
            Vector3f prev = null;
            for (int i = 0; i <= segs; i++) {
                float s = i / (float) segs;
                if (s > h) break;
                float ang = k * MathHelper.TAU / 3 + s * 18f - t * 0.35f;
                float rad = 0.9f + 0.4f * MathHelper.sin(s * 9 + t * 0.2f);
                Vector3f p = new Vector3f(a).lerp(b, h > 0 ? s / h : 0).add(MathHelper.cos(ang) * rad, 0, MathHelper.sin(ang) * rad);
                if (prev != null) strip(g, m, prev, p, 0.12f, 0.12f, WHITE, 0.55f * fade, 0.55f * fade);
                prev = p;
            }
        }
        // the head, and where it lands
        glowBall(g, m, b, 1.6f + (h >= 1 ? 1.2f * MathHelper.sin(t * 0.9f) : 0), WHITE, fade);
        VertexConsumer tr = buf.getBuffer(BeamRenderLayers.textured(TRAIL));
        ribbon(tr, m, b, a, 1.4f, 1.4f, -t * 0.08f, 3 - t * 0.08f, WHITE, 0.8f * fade, 0.3f * fade);
    }

    // ------------------------------------------------------------------------------------------- absorption

    private static void absorb(VertexConsumerProvider.Immediate buf, Matrix4f m, Vec3d cam, Vec3d chest, float t, int seedId) {
        float k = clamp01((t - Timeline.R_ABSORB) / (float) (Timeline.R_IMPACT - Timeline.R_ABSORB));
        VertexConsumer g = buf.getBuffer(BeamRenderLayers.GLOW);
        Random r = new Random(seedId * 131L);
        Vector3f c = rel(chest, cam);
        int n = 44;
        for (int i = 0; i < n; i++) {
            float yaw = r.nextFloat() * MathHelper.TAU, el = 0.1f + r.nextFloat() * 1.2f;
            float rad = 5f + r.nextFloat() * 4f;
            Vector3f start = new Vector3f(c).add(MathHelper.cos(yaw) * MathHelper.cos(el) * rad, MathHelper.sin(el) * rad, MathHelper.sin(yaw) * MathHelper.cos(el) * rad);
            float spin = (r.nextBoolean() ? 1 : -1) * (2f + r.nextFloat() * 2f);
            float ph = ((t - Timeline.R_ABSORB) / (12f + r.nextFloat() * 6f) + r.nextFloat()) % 1f;
            Vector3f p0 = path(start, c, ph - 0.14f, spin), p1 = path(start, c, ph, spin);
            float al = (0.3f + 0.7f * k) * smooth(ph * 4) ;
            strip(g, m, p0, p1, 0.05f, 0.16f, WARM, 0f, al);
            strip(g, m, p0, p1, 0.02f, 0.06f, WHITE, 0f, al);
        }
        glowBall(g, m, c, 0.8f + 1.6f * k, WHITE, 0.4f + 0.6f * k);
    }

    private static Vector3f path(Vector3f start, Vector3f end, float s, float spin) {
        s = clamp01(s);
        float e = (float) Math.pow(s, 1.6);
        Vector3f p = new Vector3f(start).lerp(end, e);
        Vector3f off = new Vector3f(start).sub(end);
        float rad = off.length() * (1 - e) * 0.35f;
        float ang = s * spin * MathHelper.PI;
        Vector3f[] b = basis(off);
        return p.add(new Vector3f(b[0]).mul(MathHelper.cos(ang) * rad)).add(new Vector3f(b[1]).mul(MathHelper.sin(ang) * rad));
    }

    // ------------------------------------------------------------------------------------------- the moment

    private static void shock(VertexConsumerProvider.Immediate buf, Matrix4f m, Vec3d cam, Vec3d a, float dt, float k) {
        VertexConsumer g = buf.getBuffer(BeamRenderLayers.GLOW);
        Vector3f c = rel(a.add(0, 0.12, 0), cam);
        Vector3f u = new Vector3f(1, 0, 0), w = new Vector3f(0, 0, 1);
        float r = 1 + dt * 1.6f;
        ring(g, m, c, u, w, r, r + 1.2f, 64, WHITE, 0.9f * k, 0f);
        ring(g, m, c, u, w, r * 0.6f, r * 0.6f + 0.6f, 48, WARM, 0.6f * k, 0f);
        Vector3f top = new Vector3f(c).add(0, 60f, 0);
        strip(g, m, c, top, 3.5f * k, 0.5f, WHITE, k, 0f);
        glowBall(g, m, new Vector3f(c).add(0, 1.4f, 0), 3f + dt * 0.5f, WHITE, k);
    }
}
