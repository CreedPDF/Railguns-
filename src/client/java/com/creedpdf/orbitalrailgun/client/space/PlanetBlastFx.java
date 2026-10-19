package com.creedpdf.orbitalrailgun.client.space;

import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.space.Blast;
import com.creedpdf.orbitalrailgun.space.Body;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The death of a planet, in phases (see {@link Blast}):
 * <ol>
 *   <li>the beam strikes and a glowing wound opens; fractures race out from it across the crust
 *       and the whole surface lights up from within;</li>
 *   <li>the core collapses: the planet shrinks, dims, and the cracks go white;</li>
 *   <li>detonation: a blinding flash, the crust breaks into ~140 tumbling fragments with molten
 *       faces cooling from white to red to black, a plasma shell, an equatorial shockwave ring
 *       racing outward and a spreading cloud of dust;</li>
 *   <li>forever after: a slowly drifting debris field and a dim dust cloud where the planet was.</li>
 * </ol>
 */
public final class PlanetBlastFx {
    private PlanetBlastFx() {}

    private static final int LIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    public static final Identifier CRACKS = BodyDraw.tex("planet_cracks");
    public static final Identifier PLASMA = BodyDraw.tex("plasma");
    public static final Identifier DUST = BodyDraw.tex("dust");
    public static final Identifier SHOCK = com.creedpdf.orbitalrailgun.RailgunMod.id("textures/effect/tint_shockwave.png");

    /** Direction (from the planet's centre) of the side facing Earth, where the beam hits. */
    public static Vector3f impactDir(Body b) {
        net.minecraft.util.math.Vec3d d = Body.EARTH.center.subtract(b.center).normalize();
        return new Vector3f((float) d.x, (float) d.y, (float) d.z);
    }

    /** @param impactOverride direction of the beam's entry point from the centre (null = toward Earth). */
    public static void draw(VertexConsumerProvider cp, Matrix4f m, Body b, Vector3f c, float r, Vector3f L, float time, float e,
                            float alpha, int segments, Vector3f impactOverride) {
        Vector3f impact = impactOverride != null ? impactOverride : impactDir(b);
        if (e < Blast.DETONATE) {
            float heat = MathHelper.clamp(e / Blast.CRACK_END, 0f, 1f);
            float collapse = MathHelper.clamp((e - Blast.CRACK_END) / (Blast.DETONATE - Blast.CRACK_END), 0f, 1f);
            float scale = 1f - 0.09f * collapse * collapse + 0.006f * MathHelper.sin(e * 1.7f) * collapse;
            BodyDraw.surface(cp, m, b, c, r, L, time, alpha, segments, scale, heat + collapse * 1.5f, impact);
            // the wound where the beam bores in
            Vector3f hit = new Vector3f(impact).mul(r * scale).add(c);
            VertexConsumer g = cp.getBuffer(BeamRenderLayers.GLOW);
            float pulse = 0.8f + 0.2f * MathHelper.sin(e * 0.9f);
            glowBall(g, m, hit, r * (0.05f + 0.12f * heat), 1f, 0.95f, 0.85f, alpha * pulse);
            glowBall(g, m, hit, r * (0.15f + 0.3f * heat), 1f, 0.55f, 0.2f, 0.45f * alpha);
            if (collapse > 0) glowBall(g, m, c, r * (1.05f + 0.3f * collapse), 1f, 0.7f, 0.4f, 0.25f * collapse * alpha);
            return;
        }
        float d = e - Blast.DETONATE;
        Random rand = Random.create(b.ordinal() * 7919L);
        Vector3f ringN = new Vector3f(0.25f, 1f, -0.18f).rotateY(b.ordinal()).normalize();

        // fragments
        shards(cp, m, b, c, r, L, time, d, alpha);

        VertexConsumer g = cp.getBuffer(BeamRenderLayers.GLOW);
        // flash
        if (d < 80) {
            float f = d / 80f;
            glowBall(g, m, c, r * (1.5f + 7f * f), 1f, 1f, 0.95f, (1f - f) * (1f - f) * alpha);
            glowBall(g, m, c, r * (3f + 16f * f), 1f, 0.85f, 0.6f, 0.4f * (1f - f) * alpha);
        }
        // the exposed, dying core
        if (d < 600) {
            float f = d / 600f;
            glowBall(g, m, c, r * 0.55f * (1f - 0.7f * f), 1f, 0.85f - 0.5f * f, 0.5f - 0.4f * f, (1f - f) * alpha);
            glowBall(g, m, c, r * 1.2f * (1f - 0.5f * f), 1f, 0.45f, 0.15f, 0.35f * (1f - f) * alpha);
        }
        // plasma shell
        if (d < 520) {
            float f = d / 520f;
            float ease = 1f - (float) Math.exp(-d / 150f);
            float rr = r * (1.1f + 6.5f * ease);
            float a = (1f - f) * (1f - f) * alpha;
            VertexConsumer pl = cp.getBuffer(BeamRenderLayers.textured(PLASMA));
            Quaternionf q = new Quaternionf().rotationY(d * 0.002f).rotateX(0.4f);
            BodyDraw.sphere(pl, m, c, rr, q, 24, 48, (n, out) -> {
                out[0] = a; out[1] = a * (0.75f - 0.35f * f); out[2] = a * (0.45f - 0.35f * f); out[3] = a;
            });
            Quaternionf q2 = new Quaternionf().rotationY(-d * 0.003f).rotateZ(0.7f);
            float rr2 = rr * 0.82f;
            BodyDraw.sphere(pl, m, c, rr2, q2, 20, 40, (n, out) -> {
                out[0] = a * 0.9f; out[1] = a * 0.9f * (0.9f - 0.6f * f); out[2] = a * 0.9f * (0.7f - 0.6f * f); out[3] = a;
            });
        }
        // shockwave rings racing out along the equator
        if (d < 700) {
            float f = d / 700f;
            float ease = 1f - (1f - f) * (1f - f);
            VertexConsumer sh = cp.getBuffer(BeamRenderLayers.textured(SHOCK));
            float w = r * (1.3f + 26f * ease);
            ring(sh, m, c, ringN, w, w * 1.18f, 1f, 0.85f, 0.65f, (1f - f) * alpha);
            float w2 = r * (1.2f + 18f * ease);
            ring(sh, m, c, ringN, w2, w2 * 1.05f, 1f, 1f, 1f, 0.7f * (1f - f) * alpha);
            if (d < 300) {
                float f3 = d / 300f;
                float w3 = r * (1.1f + 9f * f3);
                ring(sh, m, c, new Vector3f(ringN).rotateX(0.9f), w3, w3 * 1.1f, 0.6f, 0.75f, 1f, 0.6f * (1f - f3) * alpha);
            }
        }
        // dust cloud
        dust(cp, m, b, c, r, L, d, alpha, rand);
    }

    // ---- crack network (pre-detonation) ----------------------------------------------------------

    static void cracks(VertexConsumerProvider cp, Matrix4f m, Vector3f c, float r, Quaternionf q, Vector3f impact, float heat,
                       int lat, int lon, float alpha) {
        VertexConsumer vc = cp.getBuffer(BeamRenderLayers.textured(CRACKS));
        Vector3f imp = new Vector3f(impact).normalize();
        float spread = Math.min(1f, heat) * 3.4f;
        float white = Math.max(0f, heat - 1f) / 1.5f;
        BodyDraw.sphere(vc, m, c, r * 1.003f, q, lat, lon, (n, out) -> {
            float ang = (float) Math.acos(MathHelper.clamp(n.dot(imp), -1f, 1f));
            float k = MathHelper.clamp((spread - ang) * 1.4f, 0f, 1f);
            float i = k * (0.6f + 0.4f * Math.min(1f, heat)) * alpha;
            out[0] = i;
            out[1] = i * (0.45f + 0.5f * white);
            out[2] = i * (0.12f + 0.8f * white);
            out[3] = i;
        });
        if (heat > 0.6f) {
            float a = (heat - 0.6f) * 0.5f * alpha;
            VertexConsumer g = cp.getBuffer(BeamRenderLayers.GLOW);
            glowBall(g, m, c, r * 1.01f, 1f, 0.35f + 0.4f * white, 0.1f + 0.5f * white, Math.min(0.5f, a));
        }
    }

    // ---- fragments --------------------------------------------------------------------------------

    private record Shard(Vector3f dir, float speed, Vector3f axis, float spin, List<Vector3f[]> tris) {}

    private static final Map<Body, List<Shard>> SHARDS = new EnumMap<>(Body.class);

    private static List<Shard> shards(Body b) {
        return SHARDS.computeIfAbsent(b, PlanetBlastFx::buildShards);
    }

    /** Icosphere (1280 triangles) cut into ~140 Voronoi chunks. */
    private static List<Shard> buildShards(Body b) {
        List<Vector3f[]> tris = icosphere(3);
        Random rand = Random.create(b.ordinal() * 104729L + 17);
        int n = 140;
        Vector3f[] seeds = new Vector3f[n];
        float golden = MathHelper.PI * (3f - (float) Math.sqrt(5f));
        for (int i = 0; i < n; i++) {
            float y = 1 - (i + 0.5f) / n * 2;
            float rad = (float) Math.sqrt(1 - y * y);
            float th = golden * i + rand.nextFloat() * 0.4f;
            seeds[i] = new Vector3f(MathHelper.cos(th) * rad, y, MathHelper.sin(th) * rad).add(
                    (rand.nextFloat() - 0.5f) * 0.2f, (rand.nextFloat() - 0.5f) * 0.2f, (rand.nextFloat() - 0.5f) * 0.2f).normalize();
        }
        List<List<Vector3f[]>> groups = new ArrayList<>();
        for (int i = 0; i < n; i++) groups.add(new ArrayList<>());
        for (Vector3f[] t : tris) {
            Vector3f cen = new Vector3f(t[0]).add(t[1]).add(t[2]).normalize();
            int best = 0;
            float bd = -2;
            for (int i = 0; i < n; i++) {
                float d = cen.dot(seeds[i]);
                if (d > bd) { bd = d; best = i; }
            }
            groups.get(best).add(t);
        }
        List<Shard> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Vector3f[]> g = groups.get(i);
            if (g.isEmpty()) continue;
            Vector3f dir = new Vector3f();
            for (Vector3f[] t : g) dir.add(t[0]).add(t[1]).add(t[2]);
            dir.normalize();
            Vector3f axis = new Vector3f(rand.nextFloat() - 0.5f, rand.nextFloat() - 0.5f, rand.nextFloat() - 0.5f).normalize();
            out.add(new Shard(dir, 0.55f + rand.nextFloat() * 1.1f, axis, (rand.nextFloat() - 0.5f) * 2f, g));
        }
        return out;
    }

    private static void shards(VertexConsumerProvider cp, Matrix4f m, Body b, Vector3f c, float r, Vector3f L, float time, float d, float alpha) {
        Vector3f l = new Vector3f(L).normalize();
        float ease = 1f - (float) Math.exp(-d / 300f);
        float drift = d * 0.00045f;
        // molten faces cool: white -> yellow -> orange -> deep red -> dark
        float cool = MathHelper.clamp(d / 900f, 0f, 1f);
        float hr = 1f, hg = Math.max(0.05f, 0.95f - 1.4f * cool), hb = Math.max(0f, 0.8f - 2.2f * cool);
        float hot = Math.max(0.12f, 1f - 0.85f * cool);
        Quaternionf q = BodyDraw.spin(b, time - d);
        VertexConsumer rock = cp.getBuffer(alpha >= 0.999f ? BeamRenderLayers.solid(BodyDraw.albedo(b)) : BeamRenderLayers.translucent(BodyDraw.albedo(b)));
        VertexConsumer glow = cp.getBuffer(BeamRenderLayers.GLOW);
        Vector3f tmp = new Vector3f();
        for (Shard s : shards(b)) {
            float dist = r * (s.speed * (0.25f + 6.5f * ease) + drift * s.speed * 40f);
            Vector3f centre = new Vector3f(s.dir).mul(r + dist).add(c);
            Quaternionf rot = new Quaternionf().rotationAxis(s.spin * d * 0.0035f, s.axis.x, s.axis.y, s.axis.z).mul(q);
            Vector3f apexLocal = new Vector3f(s.dir).mul(-0.62f * r);
            for (Vector3f[] t : s.tris) {
                Vector3f[] p = new Vector3f[3];
                float[] uu = new float[3], vv = new float[3];
                for (int k = 0; k < 3; k++) {
                    tmp.set(t[k]).mul(r).sub(new Vector3f(s.dir).mul(r));
                    p[k] = rot.transform(new Vector3f(tmp)).add(centre);
                    uu[k] = (float) (Math.atan2(t[k].z, t[k].x) / MathHelper.TAU);
                    if (uu[k] < 0) uu[k] += 1f;
                    vv[k] = (float) (Math.acos(MathHelper.clamp(t[k].y, -1, 1)) / MathHelper.PI);
                }
                if (Math.abs(uu[0] - uu[1]) > 0.5f || Math.abs(uu[0] - uu[2]) > 0.5f) {
                    for (int k = 0; k < 3; k++) if (uu[k] < 0.5f) uu[k] += 1f;
                }
                Vector3f nrm = rot.transform(new Vector3f(t[0]).add(t[1]).add(t[2]).normalize());
                float k = BodyDraw.lit(nrm, l, 0.03f);
                for (int v = 0; v < 4; v++) {
                    int idx = Math.min(v, 2);
                    rock.vertex(m, p[idx].x, p[idx].y, p[idx].z).color(k, k, k, alpha).texture(uu[idx], vv[idx]).light(LIGHT).normal(0, 1, 0).next();
                }
                Vector3f apex = rot.transform(new Vector3f(apexLocal)).add(centre);
                for (int e2 = 0; e2 < 3; e2++) {
                    Vector3f a = p[e2], bb = p[(e2 + 1) % 3];
                    float ga = hot * alpha;
                    glow.vertex(m, a.x, a.y, a.z).color(hr, hg, hb, ga * 0.6f).next();
                    glow.vertex(m, bb.x, bb.y, bb.z).color(hr, hg, hb, ga * 0.6f).next();
                    glow.vertex(m, apex.x, apex.y, apex.z).color(hr, hg * 1.05f, hb + 0.1f, ga).next();
                    glow.vertex(m, apex.x, apex.y, apex.z).color(hr, hg * 1.05f, hb + 0.1f, ga).next();
                }
            }
        }
    }

    // ---- dust -------------------------------------------------------------------------------------

    private static void dust(VertexConsumerProvider cp, Matrix4f m, Body b, Vector3f c, float r, Vector3f L, float d, float alpha, Random rand) {
        VertexConsumer vc = cp.getBuffer(BeamRenderLayers.translucent(DUST));
        float ease = 1f - (float) Math.exp(-d / 420f);
        float fade = d < 60 ? d / 60f : 1f;
        float settle = 0.35f + 0.65f * (float) Math.exp(-d / 2200f);
        Vector3f l = new Vector3f(L).normalize();
        for (int i = 0; i < 220; i++) {
            Vector3f dir = new Vector3f(rand.nextFloat() - 0.5f, (rand.nextFloat() - 0.5f) * 0.6f, rand.nextFloat() - 0.5f).normalize();
            float dist = r * (0.6f + rand.nextFloat() * 6.5f * ease);
            Vector3f p = new Vector3f(dir).mul(dist).add(c);
            float size = r * (0.5f + rand.nextFloat() * 1.4f) * (0.6f + ease);
            float k = 0.25f + 0.5f * Math.max(0, dir.dot(l));
            float a = 0.22f * fade * settle * alpha;
            float rot = rand.nextFloat() * MathHelper.TAU + d * 0.0006f * (rand.nextFloat() - 0.5f);
            billboard(vc, m, p, size, rot, k * 0.85f, k * 0.72f, k * 0.6f, a);
        }
    }

    // ---- primitives -------------------------------------------------------------------------------

    static void glowBall(VertexConsumer g, Matrix4f m, Vector3f c, float r, float cr, float cg, float cb, float a) {
        if (a <= 0.003f) return;
        int lat = 12, lon = 24;
        for (int i = 0; i < lat; i++) {
            for (int j = 0; j < lon; j++) {
                float[][] uv = {{j / (float) lon, i / (float) lat}, {(j + 1) / (float) lon, i / (float) lat},
                        {(j + 1) / (float) lon, (i + 1) / (float) lat}, {j / (float) lon, (i + 1) / (float) lat}};
                for (float[] t : uv) {
                    Vector3f n = PlanetDrawMath.unit(t[0], t[1]);
                    Vector3f p = new Vector3f(n).mul(r).add(c);
                    Vector3f view = new Vector3f(p).normalize();
                    float facing = Math.abs(n.dot(view));
                    float aa = a * facing * facing;
                    g.vertex(m, p.x, p.y, p.z).color(cr, cg, cb, aa).next();
                }
            }
        }
    }

    static void ring(VertexConsumer vc, Matrix4f m, Vector3f c, Vector3f normal, float inner, float outer, float r, float g, float b, float a) {
        Vector3f n = new Vector3f(normal).normalize();
        Vector3f u = new Vector3f(n).cross(Math.abs(n.y) < 0.9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0)).normalize();
        Vector3f v = new Vector3f(n).cross(u).normalize();
        int seg = 128;
        for (int k = 0; k < seg; k++) {
            float t0 = k / (float) seg * MathHelper.TAU, t1 = (k + 1) / (float) seg * MathHelper.TAU;
            Vector3f[] q = {
                    pt(c, u, v, inner, t0), pt(c, u, v, inner, t1), pt(c, u, v, outer, t1), pt(c, u, v, outer, t0)};
            float[][] uv = {{t0 / MathHelper.TAU * 8, 0}, {t1 / MathHelper.TAU * 8, 0}, {t1 / MathHelper.TAU * 8, 1}, {t0 / MathHelper.TAU * 8, 1}};
            for (int side = 0; side < 2; side++) {
                for (int i = 0; i < 4; i++) {
                    int j = side == 0 ? i : 3 - i;
                    vc.vertex(m, q[j].x, q[j].y, q[j].z).color(r, g, b, a).texture(uv[j][0], uv[j][1]).light(LIGHT).normal(0, 1, 0).next();
                }
            }
        }
    }

    private static Vector3f pt(Vector3f c, Vector3f u, Vector3f v, float rad, float th) {
        return new Vector3f(u).mul(MathHelper.cos(th) * rad).add(new Vector3f(v).mul(MathHelper.sin(th) * rad)).add(c);
    }

    /** Camera-facing quad (the camera is at the origin of render space). */
    static void billboard(VertexConsumer vc, Matrix4f m, Vector3f p, float size, float rot, float r, float g, float b, float a) {
        Vector3f view = new Vector3f(p).normalize();
        Vector3f right = new Vector3f(view).cross(Math.abs(view.y) < 0.95f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0)).normalize();
        Vector3f up = new Vector3f(right).cross(view).normalize();
        float cs = MathHelper.cos(rot) * size, sn = MathHelper.sin(rot) * size;
        Vector3f ax = new Vector3f(right).mul(cs).add(new Vector3f(up).mul(sn));
        Vector3f ay = new Vector3f(up).mul(cs).sub(new Vector3f(right).mul(sn));
        float[][] c = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
        for (float[] k : c) {
            Vector3f q = new Vector3f(p).add(new Vector3f(ax).mul(k[0])).add(new Vector3f(ay).mul(k[1]));
            vc.vertex(m, q.x, q.y, q.z).color(r, g, b, a).texture(k[2], k[3]).light(LIGHT).normal(0, 1, 0).next();
        }
    }

    /** Subdivided icosahedron, unit radius. */
    static List<Vector3f[]> icosphere(int subdiv) {
        float t = (1f + (float) Math.sqrt(5f)) / 2f;
        Vector3f[] v = {
                new Vector3f(-1, t, 0), new Vector3f(1, t, 0), new Vector3f(-1, -t, 0), new Vector3f(1, -t, 0),
                new Vector3f(0, -1, t), new Vector3f(0, 1, t), new Vector3f(0, -1, -t), new Vector3f(0, 1, -t),
                new Vector3f(t, 0, -1), new Vector3f(t, 0, 1), new Vector3f(-t, 0, -1), new Vector3f(-t, 0, 1)};
        for (Vector3f x : v) x.normalize();
        int[][] f = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6},
                {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        List<Vector3f[]> tris = new ArrayList<>();
        for (int[] x : f) tris.add(new Vector3f[]{v[x[0]], v[x[1]], v[x[2]]});
        for (int s = 0; s < subdiv; s++) {
            List<Vector3f[]> next = new ArrayList<>();
            for (Vector3f[] tr : tris) {
                Vector3f a = mid(tr[0], tr[1]), b = mid(tr[1], tr[2]), c = mid(tr[2], tr[0]);
                next.add(new Vector3f[]{tr[0], a, c});
                next.add(new Vector3f[]{tr[1], b, a});
                next.add(new Vector3f[]{tr[2], c, b});
                next.add(new Vector3f[]{a, b, c});
            }
            tris = next;
        }
        return tris;
    }

    private static Vector3f mid(Vector3f a, Vector3f b) {
        return new Vector3f(a).add(b).normalize();
    }
}
