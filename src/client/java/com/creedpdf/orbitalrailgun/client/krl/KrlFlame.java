package com.creedpdf.orbitalrailgun.client.krl;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * A 3D rocket exhaust: real geometry, not billboards. The plume is three nested lathed shells (a white-hot core, the
 * orange body, a faint red mantle), each a ring-by-ring surface of revolution along the thrust axis whose radius flares
 * and tapers, flickering. They are drawn additively with a scrolling turbulence texture, and every vertex is faded by
 * how edge-on it is to the camera, so the volume reads as glowing gas with soft edges from any angle. Inside the core
 * sit the shock diamonds (small bright double cones), and the nozzle exit is a glowing disc.
 */
public final class KrlFlame {
    private KrlFlame() {}

    private static final int SEG = 16, RINGS = 14;

    /** One shell: length fraction, radius scale, colour, alpha. */
    private static final float[][] LAYERS = {
            {0.36f, 0.6f, 1f, 0.96f, 0.84f, 1.0f},
            {0.74f, 1.0f, 1f, 0.62f, 0.22f, 0.62f},
            {1.06f, 1.5f, 1f, 0.32f, 0.08f, 0.28f},
    };

    /**
     * The three plume shells. Positions are relative to the camera (the camera sits at the origin).
     *
     * @param vc   an additive layer with the turbulence (plasma) texture
     * @param from nozzle exit
     * @param dir  the direction the exhaust travels (unit)
     */
    public static void plume(Matrix4f m, VertexConsumer vc, Vec3d from, Vec3d dir, float len, float rad, float k, float time) {
        if (k <= 0.01f || len <= 0.05f) return;
        Vec3d[] b = basis(dir);
        for (int l = 0; l < LAYERS.length; l++) {
            float[] L = LAYERS[l];
            shell(m, vc, from, dir, b[0], b[1], len * L[0], rad * L[1], L[2], L[3], L[4], L[5] * k, time + l * 1.7f, l);
        }
    }

    /** The shock diamonds inside the core, on a white additive layer. */
    public static void diamonds(Matrix4f m, VertexConsumer vc, Vec3d from, Vec3d dir, float len, float rad, float k, float time) {
        if (k <= 0.02f) return;
        Vec3d[] b = basis(dir);
        float coreLen = len * 0.62f;
        int n = 4;
        for (int i = 0; i < n; i++) {
            float c = (0.16f + i * 0.2f) * coreLen;
            float h = 0.08f * coreLen + 0.25f * rad;
            float rd = rad * (0.42f - i * 0.06f);
            float a = k * (0.75f - i * 0.15f) * (0.85f + 0.15f * MathHelper.sin(time * 3.1f + i));
            Vec3d at = from.add(dir.multiply(c - h / 2));
            spindle(m, vc, at, dir, b[0], b[1], h, rd, 1f, 0.92f, 0.7f, a);
        }
    }

    // ------------------------------------------------------------------------------------------- shells

    private static void shell(Matrix4f m, VertexConsumer vc, Vec3d from, Vec3d dir, Vec3d e1, Vec3d e2, float len, float rad, float r, float g,
                              float bl, float alpha, float time, int layer) {
        Vec3d[][] pos = new Vec3d[RINGS + 1][SEG + 1];
        Vec3d[][] nor = new Vec3d[RINGS + 1][SEG + 1];
        for (int i = 0; i <= RINGS; i++) {
            float v = i / (float) RINGS;
            // flares just past the exit, then tapers to a point
            float rr = rad * (0.72f + 0.7f * (float) Math.sqrt(v)) * (float) Math.pow(1 - v, 0.85);
            rr *= 1 + 0.07f * MathHelper.sin(time * 2.3f + v * 9f) + 0.04f * MathHelper.sin(time * 5.7f + v * 21f + layer);
            Vec3d c = from.add(dir.multiply(v * len));
            for (int j = 0; j <= SEG; j++) {
                double a = j * Math.PI * 2 / SEG;
                Vec3d radial = e1.multiply(Math.cos(a)).add(e2.multiply(Math.sin(a)));
                pos[i][j] = c.add(radial.multiply(rr));
                nor[i][j] = radial;
            }
        }
        for (int i = 0; i < RINGS; i++) {
            for (int j = 0; j < SEG; j++) {
                corner(m, vc, pos[i][j], nor[i][j], i, j, r, g, bl, alpha, time);
                corner(m, vc, pos[i + 1][j], nor[i + 1][j], i + 1, j, r, g, bl, alpha, time);
                corner(m, vc, pos[i + 1][j + 1], nor[i + 1][j + 1], i + 1, j + 1, r, g, bl, alpha, time);
                corner(m, vc, pos[i][j + 1], nor[i][j + 1], i, j + 1, r, g, bl, alpha, time);
            }
        }
    }

    private static void corner(Matrix4f m, VertexConsumer vc, Vec3d p, Vec3d n, int i, int j, float r, float g, float b, float alpha, float time) {
        float v = i / (float) RINGS;
        // facing the camera (at the origin) glows, edge-on fades: soft silhouette
        double len = p.length();
        float face = len < 1e-4 ? 1 : (float) Math.abs(n.dotProduct(p) / len);
        face = (float) Math.pow(face, 0.9);
        float along = (float) Math.pow(1 - v, 1.15) * MathHelper.clamp(v * 14 + 0.25f, 0, 1);
        float a = alpha * face * along;
        // cooler towards the tail
        float cr = MathHelper.lerp(v * 0.6f, r, 0.95f), cg = MathHelper.lerp(v * 0.6f, g, 0.22f), cb = MathHelper.lerp(v * 0.6f, b, 0.05f);
        float u = j / (float) SEG * 2f, tv = v * 2.6f - time * 0.14f;
        KrlDraw.v(vc, m, (float) p.x, (float) p.y, (float) p.z, u, tv, cr * a, cg * a, cb * a, 1f);
    }

    /** A small double cone (a shock diamond) of length h from `at` along dir. */
    private static void spindle(Matrix4f m, VertexConsumer vc, Vec3d at, Vec3d dir, Vec3d e1, Vec3d e2, float h, float rd, float r, float g, float b,
                                float alpha) {
        int rings = 6, seg = 12;
        Vec3d[][] pos = new Vec3d[rings + 1][seg + 1];
        Vec3d[][] nor = new Vec3d[rings + 1][seg + 1];
        for (int i = 0; i <= rings; i++) {
            float w = i / (float) rings;
            float rr = rd * (float) Math.pow(Math.sin(Math.PI * w), 0.8);
            Vec3d c = at.add(dir.multiply(w * h));
            for (int j = 0; j <= seg; j++) {
                double a = j * Math.PI * 2 / seg;
                Vec3d radial = e1.multiply(Math.cos(a)).add(e2.multiply(Math.sin(a)));
                pos[i][j] = c.add(radial.multiply(rr));
                nor[i][j] = radial;
            }
        }
        for (int i = 0; i < rings; i++)
            for (int j = 0; j < seg; j++) {
                int[][] q = {{i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j + 1}};
                for (int[] c : q) {
                    Vec3d p = pos[c[0]][c[1]];
                    double len = p.length();
                    float face = len < 1e-4 ? 1 : (float) Math.abs(nor[c[0]][c[1]].dotProduct(p) / len);
                    float a = alpha * face;
                    KrlDraw.v(vc, m, (float) p.x, (float) p.y, (float) p.z, 0.5f, 0.5f, r * a, g * a, b * a, 1f);
                }
            }
    }

    /** Two unit vectors perpendicular to d and each other. */
    static Vec3d[] basis(Vec3d d) {
        Vec3d ref = Math.abs(d.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Vec3d e1 = d.crossProduct(ref).normalize();
        Vec3d e2 = d.crossProduct(e1).normalize();
        return new Vec3d[]{e1, e2};
    }
}
