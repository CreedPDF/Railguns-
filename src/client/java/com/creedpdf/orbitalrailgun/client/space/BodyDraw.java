package com.creedpdf.orbitalrailgun.client.space;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.space.Blast;
import com.creedpdf.orbitalrailgun.space.Body;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws a celestial body as a fully lit 3D sphere: albedo texture shaded by the sun with a soft
 * terminator, night-side city lights (Earth), a separate cloud layer, an atmospheric limb glow
 * and ring systems that the planet's shadow falls across. A body ARES-0 has hit is handed to
 * {@link PlanetBlastFx} instead.
 */
public final class BodyDraw {
    private BodyDraw() {}

    public static Identifier tex(String name) {
        return RailgunMod.id("textures/space/" + name + ".png");
    }

    public static final Identifier EARTH_NIGHT = tex("earth_night"), EARTH_CLOUDS = tex("earth_clouds"),
            SATURN_RINGS = tex("saturn_rings"), VENUS_CLOUDS = tex("venus_clouds");
    private static final int LIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;

    public static Identifier albedo(Body b) {
        return tex(b.id);
    }

    /** Atmosphere limb colour + strength (null = airless). */
    public static float[] atmosphere(Body b) {
        return switch (b) {
            case EARTH -> new float[]{0.35f, 0.6f, 1f, 1.5f};
            case MARS -> new float[]{0.95f, 0.6f, 0.45f, 0.55f};
            case VENUS -> new float[]{1f, 0.85f, 0.55f, 1.2f};
            case JUPITER -> new float[]{0.9f, 0.8f, 0.65f, 0.55f};
            case SATURN -> new float[]{0.95f, 0.88f, 0.65f, 0.5f};
            default -> null;
        };
    }

    /** Spin (radians) at a given world time: each body turns at its own rate. */
    public static Quaternionf spin(Body b, float time) {
        float rate = switch (b) {
            case EARTH -> 0.00035f;
            case MOON -> 0.00004f;
            case MARS -> 0.0003f;
            case VENUS -> -0.00005f;
            case EUROPA -> 0.0001f;
            case JUPITER -> 0.0009f;
            case SATURN -> 0.0008f;
        };
        float tilt = switch (b) {
            case EARTH -> 0.41f;
            case MARS -> 0.44f;
            case SATURN -> 0.47f;
            case JUPITER -> 0.05f;
            default -> 0.1f;
        };
        return new Quaternionf().rotationZ(tilt).rotateY(time * rate + b.ordinal() * 1.7f);
    }

    /**
     * @param c        centre in render space
     * @param r        radius in render space
     * @param L        direction toward the sun (render space)
     * @param alpha    overall opacity (sky view of the overworld fades planets with daylight)
     * @param segments sphere tessellation (longitude count; latitude = half)
     */
    public static void draw(VertexConsumerProvider cp, Matrix4f m, Body b, Vector3f c, float r, Vector3f L, float time,
                            float pt, float alpha, int segments, Vector3f impactOverride) {
        float e = ClientPlanets.elapsed(b, pt);
        if (e >= 0) {
            PlanetBlastFx.draw(cp, m, b, c, r, L, time, e, alpha, segments, impactOverride);
            return;
        }
        surface(cp, m, b, c, r, L, time, alpha, segments, 1f, 0f, null);
    }

    /**
     * The intact surface (also used during the crack phase of a blast, with {@code heat} > 0 and an
     * impact direction).
     */
    public static void surface(VertexConsumerProvider cp, Matrix4f m, Body b, Vector3f c, float r, Vector3f L, float time,
                               float alpha, int segments, float scale, float heat, Vector3f impact) {
        Vector3f l = new Vector3f(L).normalize();
        Quaternionf q = spin(b, time);
        float rr = r * scale;
        int lon = segments, lat = Math.max(8, segments / 2);
        boolean solid = alpha >= 0.999f;
        float ambient = b == Body.VENUS ? 0.06f : 0.025f;
        if (b == Body.SATURN) rings(cp, m, c, rr, q, l, alpha, false);

        VertexConsumer vc = cp.getBuffer(solid ? BeamRenderLayers.solid(albedo(b)) : BeamRenderLayers.translucent(albedo(b)));
        sphere(vc, m, c, rr, q, lat, lon, (n, out) -> {
            float k = lit(n, l, ambient);
            out[0] = k * (1f + 0.12f * warm(n, l)); out[1] = k * (1f + 0.04f * warm(n, l)); out[2] = k; out[3] = alpha;
        }, true);

        if (b == Body.EARTH) {
            // city lights on the night side
            VertexConsumer night = cp.getBuffer(BeamRenderLayers.textured(EARTH_NIGHT));
            sphere(night, m, c, rr * 1.0005f, q, lat, lon, (n, out) -> {
                float d = 1f - PlanetDrawMath.smooth(-0.25f, 0.08f, n.dot(l));
                out[0] = d * alpha; out[1] = d * 0.85f * alpha; out[2] = d * 0.6f * alpha; out[3] = alpha;
            }, true);
            // clouds drift faster than the ground
            VertexConsumer clouds = cp.getBuffer(BeamRenderLayers.translucent(EARTH_CLOUDS));
            Quaternionf cq = new Quaternionf(q).rotateY(time * 0.00012f);
            sphere(clouds, m, c, rr * 1.006f, cq, lat, lon, (n, out) -> {
                float k = lit(n, l, 0.01f);
                out[0] = k; out[1] = k; out[2] = k * 1.03f; out[3] = 0.95f * alpha;
            }, true);
        } else if (b == Body.VENUS) {
            VertexConsumer clouds = cp.getBuffer(BeamRenderLayers.translucent(VENUS_CLOUDS));
            Quaternionf cq = new Quaternionf(q).rotateY(time * 0.0004f);
            sphere(clouds, m, c, rr * 1.004f, cq, lat, lon, (n, out) -> {
                float k = lit(n, l, 0.04f);
                out[0] = k; out[1] = k * 0.96f; out[2] = k * 0.85f; out[3] = 0.85f * alpha;
            }, true);
        }

        if (heat > 0f && impact != null) PlanetBlastFx.cracks(cp, m, c, rr, q, impact, heat, lat, lon, alpha);

        float[] atmo = atmosphere(b);
        if (atmo != null) limb(cp, m, c, rr * (b == Body.EARTH ? 1.018f : 1.03f), l, atmo, alpha, lat, lon);
        if (b == Body.SATURN) rings(cp, m, c, rr, q, l, alpha, true);
    }

    static float lit(Vector3f n, Vector3f l, float ambient) {
        float d = n.dot(l);
        return ambient + (1f - ambient) * PlanetDrawMath.smooth(-0.12f, 0.45f, d) * (0.55f + 0.45f * Math.max(0, d));
    }

    static float warm(Vector3f n, Vector3f l) {
        float d = n.dot(l);
        return (float) Math.exp(-(d / 0.16f) * (d / 0.16f));
    }

    /** Atmospheric limb: a thin shell that brightens toward the edge, lit side only, reddened at the terminator. */
    static void limb(VertexConsumerProvider cp, Matrix4f m, Vector3f c, float r, Vector3f l, float[] col, float alpha, int lat, int lon) {
        // a smooth gradient: half the surface's resolution is plenty, and only the rim (faces seen
        // nearly edge-on) carries any light, so the face-on middle is skipped
        lat = Math.max(8, lat / 2);
        lon = Math.max(16, lon / 2);
        float[] g = grid(lat, lon);
        int w = lon + 1, n = (lat + 1) * w;
        float[] buf = scratch(n * 8);
        for (int k = 0; k < n; k++) {
            float nx = g[k * 3], ny = g[k * 3 + 1], nz = g[k * 3 + 2];
            float px = nx * r + c.x, py = ny * r + c.y, pz = nz * r + c.z;
            float len = (float) Math.sqrt(px * px + py * py + pz * pz);
            float facing = len > 0 ? (nx * px + ny * py + nz * pz) / len : 0f;
            float edge = (float) Math.pow(1f - Math.abs(facing), 2.2);
            float nl = nx * l.x + ny * l.y + nz * l.z;
            float light = PlanetDrawMath.smooth(-0.35f, 0.3f, nl);
            float sunset = (float) Math.exp(-(nl / 0.18f) * (nl / 0.18f));
            int o = k * 8;
            buf[o] = px; buf[o + 1] = py; buf[o + 2] = pz;
            buf[o + 3] = Math.min(1f, col[0] + sunset * 0.6f);
            buf[o + 4] = col[1] * (1f - sunset * 0.3f);
            buf[o + 5] = col[2] * (1f - sunset * 0.6f);
            buf[o + 6] = Math.min(1f, edge * light * col[3]) * alpha;
            buf[o + 7] = facing;
        }
        VertexConsumer vc = cp.getBuffer(BeamRenderLayers.GLOW);
        for (int i = 0; i < lat; i++) {
            for (int j = 0; j < lon; j++) {
                int k00 = i * w + j, k01 = k00 + 1, k10 = k00 + w, k11 = k10 + 1;
                float amax = Math.max(Math.max(buf[k00 * 8 + 6], buf[k01 * 8 + 6]), Math.max(buf[k10 * 8 + 6], buf[k11 * 8 + 6]));
                if (amax < 0.002f) continue;
                for (int k : new int[]{k00, k01, k11, k10}) {
                    int o = k * 8;
                    vc.vertex(m, buf[o], buf[o + 1], buf[o + 2]).color(buf[o + 3], buf[o + 4], buf[o + 5], buf[o + 6]).next();
                }
            }
        }
    }

    /** @param front draw only the half nearer the camera than the planet's centre (else the far half). */
    static void rings(VertexConsumerProvider cp, Matrix4f m, Vector3f c, float r, Quaternionf q, Vector3f l, float alpha, boolean front) {
        VertexConsumer vc = cp.getBuffer(BeamRenderLayers.translucent(SATURN_RINGS));
        Vector3f n = q.transform(new Vector3f(0, 1, 0));
        Vector3f a = q.transform(new Vector3f(1, 0, 0)), bb = q.transform(new Vector3f(0, 0, 1));
        float inner = r * 1.24f, outer = r * 2.3f;
        int seg = 160;
        float litFace = 0.35f + 0.65f * Math.abs(n.dot(l));
        float cDist = c.length();
        for (int k = 0; k < seg; k++) {
            float t0 = k / (float) seg * MathHelper.TAU, t1 = (k + 1) / (float) seg * MathHelper.TAU;
            float tm = (t0 + t1) * 0.5f, rm = (inner + outer) * 0.5f;
            Vector3f mid = new Vector3f(a).mul(MathHelper.cos(tm) * rm).add(new Vector3f(bb).mul(MathHelper.sin(tm) * rm)).add(c);
            if ((mid.length() < cDist) != front) continue;
            for (int side = 0; side < 2; side++) {
                float[] th = side == 0 ? new float[]{t0, t1} : new float[]{t1, t0};
                ringVert(vc, m, c, a, bb, inner, th[0], 0f, litFace, l, r, alpha);
                ringVert(vc, m, c, a, bb, inner, th[1], 0f, litFace, l, r, alpha);
                ringVert(vc, m, c, a, bb, outer, th[1], 1f, litFace, l, r, alpha);
                ringVert(vc, m, c, a, bb, outer, th[0], 1f, litFace, l, r, alpha);
            }
        }
    }

    private static void ringVert(VertexConsumer vc, Matrix4f m, Vector3f c, Vector3f a, Vector3f b, float rad, float th, float v,
                                 float lit, Vector3f l, float planetR, float alpha) {
        Vector3f off = new Vector3f(a).mul(MathHelper.cos(th) * rad).add(new Vector3f(b).mul(MathHelper.sin(th) * rad));
        float along = off.dot(l);
        float perp = new Vector3f(off).sub(new Vector3f(l).mul(along)).length();
        float shade = along < 0 && perp < planetR ? 0.08f : 1f;
        Vector3f p = off.add(c);
        float k = lit * shade;
        vc.vertex(m, p.x, p.y, p.z).color(k, k * 0.96f, k * 0.9f, alpha).texture(th / MathHelper.TAU * 8f, v).light(LIGHT).normal(0, 1, 0).next();
    }

    public interface Shade {
        void apply(Vector3f n, float[] out);
    }

    /** A UV sphere (equirectangular texture), vertex colour from {@code shade} per unit normal. */
    public static void sphere(VertexConsumer vc, Matrix4f m, Vector3f c, float r, Quaternionf spin, int lat, int lon, Shade shade) {
        sphere(vc, m, c, r, spin, lat, lon, shade, false);
    }

    /**
     * @param cull skip quads facing away from the camera (the camera is the origin of sky space), so a
     *             sphere never needs the depth buffer to hide its own far side
     */
    public static void sphere(VertexConsumer vc, Matrix4f m, Vector3f c, float r, Quaternionf spin, int lat, int lon, Shade shade, boolean cull) {
        // every grid point is transformed and shaded once and shared by its four quads
        float[] g = grid(lat, lon);
        int w = lon + 1, n = (lat + 1) * w;
        float[] buf = scratch(n * 8);
        float[] col = new float[4];
        Vector3f nv = new Vector3f();
        for (int k = 0; k < n; k++) {
            nv.set(g[k * 3], g[k * 3 + 1], g[k * 3 + 2]);
            spin.transform(nv);
            col[0] = col[1] = col[2] = col[3] = 0f;
            shade.apply(nv, col);
            int o = k * 8;
            buf[o] = nv.x * r + c.x; buf[o + 1] = nv.y * r + c.y; buf[o + 2] = nv.z * r + c.z;
            buf[o + 3] = Math.min(1f, col[0]); buf[o + 4] = Math.min(1f, col[1]); buf[o + 5] = Math.min(1f, col[2]); buf[o + 6] = col[3];
            // facing term for culling: normal . (point direction from the camera)
            float len = (float) Math.sqrt(buf[o] * buf[o] + buf[o + 1] * buf[o + 1] + buf[o + 2] * buf[o + 2]);
            buf[o + 7] = len > 0 ? (nv.x * buf[o] + nv.y * buf[o + 1] + nv.z * buf[o + 2]) / len : 0f;
        }
        for (int i = 0; i < lat; i++) {
            float v0 = i / (float) lat, v1 = (i + 1) / (float) lat;
            for (int j = 0; j < lon; j++) {
                int k00 = i * w + j, k01 = k00 + 1, k10 = k00 + w, k11 = k10 + 1;
                // keep a sliver past the limb so the edge never shows gaps
                if (cull && (buf[k00 * 8 + 7] + buf[k01 * 8 + 7] + buf[k10 * 8 + 7] + buf[k11 * 8 + 7]) * 0.25f > 0.12f) continue;
                float u0 = j / (float) lon, u1 = (j + 1) / (float) lon;
                emit(vc, m, buf, k00, u0, v0);
                emit(vc, m, buf, k01, u1, v0);
                emit(vc, m, buf, k11, u1, v1);
                emit(vc, m, buf, k10, u0, v1);
            }
        }
    }

    private static void emit(VertexConsumer vc, Matrix4f m, float[] b, int k, float u, float v) {
        int o = k * 8;
        vc.vertex(m, b[o], b[o + 1], b[o + 2]).color(b[o + 3], b[o + 4], b[o + 5], b[o + 6]).texture(u, v).light(LIGHT).normal(0, 1, 0).next();
    }

    private static final java.util.Map<Long, float[]> GRIDS = new java.util.HashMap<>();
    private static float[] SCRATCH = new float[0];

    /** Unit vectors of an equirectangular grid, (lat+1) x (lon+1) points, cached per resolution. */
    static float[] grid(int lat, int lon) {
        return GRIDS.computeIfAbsent(((long) lat << 32) | lon, k -> {
            float[] g = new float[(lat + 1) * (lon + 1) * 3];
            for (int i = 0; i <= lat; i++) {
                for (int j = 0; j <= lon; j++) {
                    Vector3f u = PlanetDrawMath.unit(j / (float) lon, i / (float) lat);
                    int o = (i * (lon + 1) + j) * 3;
                    g[o] = u.x; g[o + 1] = u.y; g[o + 2] = u.z;
                }
            }
            return g;
        });
    }

    private static float[] scratch(int n) {
        if (SCRATCH.length < n) SCRATCH = new float[n];
        return SCRATCH;
    }
}
