package com.creedpdf.orbitalrailgun.client.render;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Real 3D planets: textured spheres lit per vertex by a star (soft terminator, dark night side),
 * an atmospheric limb glow, and optional shaded rings. Positions are camera-relative (camera at
 * the origin), so the limb faces the viewer correctly.
 */
public final class PlanetDraw {
    private PlanetDraw() {}

    private static final int LAT = 28, LON = 48;

    public static void planet(VertexConsumerProvider cp, Matrix4f m, Identifier tex, Vector3f c, float r, Quaternionf spin,
                              Vector3f toLight, float ambient, float[] atmo, float atmoStrength) {
        VertexConsumer vc = cp.getBuffer(BeamRenderLayers.solid(tex));
        Vector3f L = new Vector3f(toLight).normalize();
        for (int i = 0; i < LAT; i++) {
            float v0 = i / (float) LAT, v1 = (i + 1) / (float) LAT;
            for (int j = 0; j < LON; j++) {
                float u0 = j / (float) LON, u1 = (j + 1) / (float) LON;
                vert(vc, m, c, r, spin, L, ambient, u0, v0);
                vert(vc, m, c, r, spin, L, ambient, u1, v0);
                vert(vc, m, c, r, spin, L, ambient, u1, v1);
                vert(vc, m, c, r, spin, L, ambient, u0, v1);
            }
        }
        if (atmo != null && atmoStrength > 0) {
            VertexConsumer g = cp.getBuffer(BeamRenderLayers.GLOW);
            float ar = r * 1.045f;
            for (int i = 0; i < LAT; i++) {
                float v0 = i / (float) LAT, v1 = (i + 1) / (float) LAT;
                for (int j = 0; j < LON; j++) {
                    float u0 = j / (float) LON, u1 = (j + 1) / (float) LON;
                    atmoVert(g, m, c, ar, L, atmo, atmoStrength, u0, v0);
                    atmoVert(g, m, c, ar, L, atmo, atmoStrength, u1, v0);
                    atmoVert(g, m, c, ar, L, atmo, atmoStrength, u1, v1);
                    atmoVert(g, m, c, ar, L, atmo, atmoStrength, u0, v1);
                }
            }
        }
    }

    /** Flat ring system around a planet, lit by the star; the planet's shadow darkens the far side. */
    public static void rings(VertexConsumerProvider cp, Matrix4f m, Identifier tex, Vector3f c, Vector3f normal, float inner, float outer,
                             Vector3f toLight, float planetR) {
        VertexConsumer vc = cp.getBuffer(BeamRenderLayers.translucent(tex));
        Vector3f n = new Vector3f(normal).normalize();
        Vector3f a = new Vector3f(n).cross(Math.abs(n.y) < 0.9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0)).normalize();
        Vector3f b = new Vector3f(n).cross(a).normalize();
        Vector3f L = new Vector3f(toLight).normalize();
        float lit = 0.35f + 0.65f * Math.abs(n.dot(L));
        int seg = 96;
        for (int k = 0; k < seg; k++) {
            float t0 = k / (float) seg * MathHelper.TAU, t1 = (k + 1) / (float) seg * MathHelper.TAU;
            for (int side = 0; side < 2; side++) {
                float[] th = side == 0 ? new float[]{t0, t1} : new float[]{t1, t0};
                ringVert(vc, m, c, a, b, inner, th[0], 0f, lit, L, planetR);
                ringVert(vc, m, c, a, b, inner, th[1], 0f, lit, L, planetR);
                ringVert(vc, m, c, a, b, outer, th[1], 1f, lit, L, planetR);
                ringVert(vc, m, c, a, b, outer, th[0], 1f, lit, L, planetR);
            }
        }
    }

    private static void ringVert(VertexConsumer vc, Matrix4f m, Vector3f c, Vector3f a, Vector3f b, float rad, float th, float v,
                                 float lit, Vector3f L, float planetR) {
        Vector3f off = new Vector3f(a).mul(MathHelper.cos(th) * rad).add(new Vector3f(b).mul(MathHelper.sin(th) * rad));
        // shadow: points behind the planet (opposite the star) within its radius of the light axis
        float along = off.dot(L);
        float perp = new Vector3f(off).sub(new Vector3f(L).mul(along)).length();
        float shade = along < 0 && perp < planetR ? 0.15f : 1f;
        Vector3f p = off.add(c);
        float k = lit * shade;
        BeamRenderer.texVertRaw(vc, m, p, k, k * 0.95f, k * 0.88f, 1f, th / MathHelper.TAU * 6f, v);
    }

    private static void vert(VertexConsumer vc, Matrix4f m, Vector3f c, float r, Quaternionf spin, Vector3f L, float ambient, float u, float v) {
        float lat = MathHelper.PI * (0.5f - v), lon = MathHelper.TAU * u;
        Vector3f n = new Vector3f(MathHelper.cos(lat) * MathHelper.cos(lon), MathHelper.sin(lat), MathHelper.cos(lat) * MathHelper.sin(lon));
        spin.transform(n);
        float d = n.dot(L);
        float k = ambient + (1f - ambient) * smooth(-0.18f, 0.55f, d) * (0.6f + 0.4f * Math.max(0, d));
        float warm = (float) Math.exp(-(d / 0.14f) * (d / 0.14f)) * 0.18f;
        Vector3f p = new Vector3f(n).mul(r).add(c);
        BeamRenderer.texVertRaw(vc, m, p, Math.min(1, k + warm), Math.min(1, k + warm * 0.5f), Math.min(1, k * 1.04f), 1f, u, v);
    }

    private static void atmoVert(VertexConsumer g, Matrix4f m, Vector3f c, float r, Vector3f L, float[] col, float s, float u, float v) {
        float lat = MathHelper.PI * (0.5f - v), lon = MathHelper.TAU * u;
        Vector3f n = new Vector3f(MathHelper.cos(lat) * MathHelper.cos(lon), MathHelper.sin(lat), MathHelper.cos(lat) * MathHelper.sin(lon));
        Vector3f p = new Vector3f(n).mul(r).add(c);
        Vector3f view = new Vector3f(p).negate().normalize();
        float edge = (float) Math.pow(1f - Math.abs(n.dot(view)), 3.0);
        float light = smooth(-0.3f, 0.4f, n.dot(L));
        float a = Math.min(1f, edge * (0.1f + 0.9f * light) * s);
        g.vertex(m, p.x, p.y, p.z).color(col[0], col[1], col[2], a).next();
    }

    static float smooth(float e0, float e1, float x) {
        float t = MathHelper.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
