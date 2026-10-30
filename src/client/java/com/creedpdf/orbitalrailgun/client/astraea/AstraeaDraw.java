package com.creedpdf.orbitalrailgun.client.astraea;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Drawing primitives for ASTRAEA's light (camera at the origin; positions camera-relative). Untextured ones go to the
 * additive POSITION_COLOR glow layer; textured ones to the additive / translucent textured layers (position, colour,
 * uv, light, normal).
 */
public final class AstraeaDraw {
    private AstraeaDraw() {}

    public static final int LIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;

    public static Identifier tex(String n) {
        return RailgunMod.id("textures/astraea/" + n + ".png");
    }

    public static final Identifier RIFT_VOID = tex("rift_void"), RIFT_RIM = tex("rift_rim"), SIGIL_OUTER = tex("sigil_outer"),
            SIGIL_INNER = tex("sigil_inner"), STAR = tex("star_flare"), GLOW = tex("glow"), TRAIL = tex("trail"),
            FRACTURE = tex("earth_fracture"), HAZE = tex("ember_haze");

    public static final float[] WHITE = {1f, 0.98f, 0.94f}, WARM = {1f, 0.9f, 0.72f}, GOLD = {1f, 0.8f, 0.45f}, EMBER = {1f, 0.42f, 0.1f},
            MAGMA = {1f, 0.22f, 0.04f};

    public static Vector3f rel(Vec3d p, Vec3d c) {
        return new Vector3f((float) (p.x - c.x), (float) (p.y - c.y), (float) (p.z - c.z));
    }

    // ---- glow (POSITION_COLOR)

    public static void v(VertexConsumer g, Matrix4f m, Vector3f p, float[] c, float a) {
        g.vertex(m, p.x, p.y, p.z).color(c[0], c[1], c[2], a).next();
    }

    /** A strip a->b, widths wa / wb, turned about its axis to face the camera (at the origin). */
    public static void strip(VertexConsumer g, Matrix4f m, Vector3f a, Vector3f b, float wa, float wb, float[] col, float aa, float ab) {
        Vector3f d = new Vector3f(b).sub(a), mid = new Vector3f(a).add(b).mul(0.5f);
        Vector3f side = d.cross(mid, new Vector3f());
        if (side.lengthSquared() < 1e-12f) return;
        side.normalize();
        Vector3f sa = new Vector3f(side).mul(wa * 0.5f), sb = new Vector3f(side).mul(wb * 0.5f);
        v(g, m, new Vector3f(a).sub(sa), col, aa);
        v(g, m, new Vector3f(b).sub(sb), col, ab);
        v(g, m, new Vector3f(b).add(sb), col, ab);
        v(g, m, new Vector3f(a).add(sa), col, aa);
    }

    /** A flat ring (or disc, r0 = 0) in the plane of u, w round c. */
    public static void ring(VertexConsumer g, Matrix4f m, Vector3f c, Vector3f u, Vector3f w, float r0, float r1, int segs, float[] col, float a0,
                            float a1) {
        for (int i = 0; i < segs; i++) {
            float t0 = i * MathHelper.TAU / segs, t1 = (i + 1) * MathHelper.TAU / segs;
            Vector3f d0 = new Vector3f(u).mul(MathHelper.cos(t0)).add(new Vector3f(w).mul(MathHelper.sin(t0)));
            Vector3f d1 = new Vector3f(u).mul(MathHelper.cos(t1)).add(new Vector3f(w).mul(MathHelper.sin(t1)));
            v(g, m, new Vector3f(d0).mul(r0).add(c), col, a0);
            v(g, m, new Vector3f(d1).mul(r0).add(c), col, a0);
            v(g, m, new Vector3f(d1).mul(r1).add(c), col, a1);
            v(g, m, new Vector3f(d0).mul(r1).add(c), col, a1);
        }
    }

    public static Vector3f[] basis(Vector3f d) {
        Vector3f n = new Vector3f(d);
        if (n.lengthSquared() < 1e-9f) n.set(0, 1, 0);
        n.normalize();
        Vector3f u = Math.abs(n.y) < 0.9f ? new Vector3f(0, 1, 0).cross(n, new Vector3f()) : new Vector3f(1, 0, 0).cross(n, new Vector3f());
        u.normalize();
        return new Vector3f[]{u, new Vector3f(n).cross(u, new Vector3f()).normalize()};
    }

    /** A soft disc facing the camera. */
    public static void glowBall(VertexConsumer g, Matrix4f m, Vector3f c, float r, float[] col, float a) {
        Vector3f[] b = basis(c);
        ring(g, m, c, b[0], b[1], 0f, r, 20, col, a, 0f);
    }

    /**
     * A jagged lightning bolt from a to b (midpoint displacement), drawn as a bright core and a soft halo, each segment
     * as two crossed quads so it reads from any side.
     */
    public static void bolt(VertexConsumer g, Matrix4f m, Vector3f a, Vector3f b, float jag, float width, float[] col, float alpha, long seed) {
        Random r = new Random(seed);
        int n = 12;
        Vector3f[] pts = new Vector3f[n + 1];
        pts[0] = new Vector3f(a);
        pts[n] = new Vector3f(b);
        subdivide(pts, 0, n, jag, r);
        for (int i = 0; i < n; i++) {
            cross(g, m, pts[i], pts[i + 1], width * 3.5f, col, alpha * 0.25f);
            cross(g, m, pts[i], pts[i + 1], width, WHITE, alpha);
        }
    }

    private static void subdivide(Vector3f[] p, int i0, int i1, float jag, Random r) {
        if (i1 - i0 < 2) return;
        int mid = (i0 + i1) / 2;
        Vector3f m = new Vector3f(p[i0]).add(p[i1]).mul(0.5f);
        m.add((r.nextFloat() - 0.5f) * jag, (r.nextFloat() - 0.5f) * jag, (r.nextFloat() - 0.5f) * jag);
        p[mid] = m;
        subdivide(p, i0, mid, jag * 0.55f, r);
        subdivide(p, mid, i1, jag * 0.55f, r);
    }

    private static void cross(VertexConsumer g, Matrix4f m, Vector3f a, Vector3f b, float w, float[] col, float al) {
        Vector3f d = new Vector3f(b).sub(a);
        if (d.lengthSquared() < 1e-10f) return;
        Vector3f[] bs = basis(d);
        for (Vector3f s : bs) {
            Vector3f o = new Vector3f(s).mul(w * 0.5f);
            v(g, m, new Vector3f(a).sub(o), col, al);
            v(g, m, new Vector3f(b).sub(o), col, al);
            v(g, m, new Vector3f(b).add(o), col, al);
            v(g, m, new Vector3f(a).add(o), col, al);
        }
    }

    // ---- textured (POSITION_COLOR_TEXTURE_LIGHT_NORMAL)

    public static void tv(VertexConsumer vc, Matrix4f m, Vector3f p, float r, float g, float b, float a, float u, float v) {
        vc.vertex(m, p.x, p.y, p.z).color(r, g, b, a).texture(u, v).light(LIGHT).normal(0, 1, 0).next();
    }

    /** A textured square centred on c in the plane of u, w (half-size s), rotated by rot. */
    public static void quad(VertexConsumer vc, Matrix4f m, Vector3f c, Vector3f u, Vector3f w, float s, float rot, float[] col, float a) {
        float cr = MathHelper.cos(rot), sr = MathHelper.sin(rot);
        Vector3f ax = new Vector3f(u).mul(cr).add(new Vector3f(w).mul(sr)).mul(s);
        Vector3f ay = new Vector3f(w).mul(cr).sub(new Vector3f(u).mul(sr)).mul(s);
        tv(vc, m, new Vector3f(c).sub(ax).sub(ay), col[0], col[1], col[2], a, 0, 0);
        tv(vc, m, new Vector3f(c).add(ax).sub(ay), col[0], col[1], col[2], a, 1, 0);
        tv(vc, m, new Vector3f(c).add(ax).add(ay), col[0], col[1], col[2], a, 1, 1);
        tv(vc, m, new Vector3f(c).sub(ax).add(ay), col[0], col[1], col[2], a, 0, 1);
    }

    /** A camera-facing textured sprite. */
    public static void sprite(VertexConsumer vc, Matrix4f m, Vector3f c, float s, float rot, float[] col, float a) {
        Vector3f[] b = basis(c);
        quad(vc, m, c, b[0], b[1], s, rot, col, a);
    }

    /** A textured ribbon a->b (v across, u along from u0 to u1), widths wa / wb, facing the camera. */
    public static void ribbon(VertexConsumer vc, Matrix4f m, Vector3f a, Vector3f b, float wa, float wb, float u0, float u1, float[] col, float aa,
                              float ab) {
        Vector3f d = new Vector3f(b).sub(a), mid = new Vector3f(a).add(b).mul(0.5f);
        Vector3f side = d.cross(mid, new Vector3f());
        if (side.lengthSquared() < 1e-12f) return;
        side.normalize();
        Vector3f sa = new Vector3f(side).mul(wa * 0.5f), sb = new Vector3f(side).mul(wb * 0.5f);
        tv(vc, m, new Vector3f(a).sub(sa), col[0], col[1], col[2], aa, u0, 0);
        tv(vc, m, new Vector3f(b).sub(sb), col[0], col[1], col[2], ab, u1, 0);
        tv(vc, m, new Vector3f(b).add(sb), col[0], col[1], col[2], ab, u1, 1);
        tv(vc, m, new Vector3f(a).add(sa), col[0], col[1], col[2], aa, u0, 1);
    }

    public static float smooth(float x) {
        x = MathHelper.clamp(x, 0f, 1f);
        return x * x * (3 - 2 * x);
    }

    public static float clamp01(float x) {
        return MathHelper.clamp(x, 0f, 1f);
    }
}
