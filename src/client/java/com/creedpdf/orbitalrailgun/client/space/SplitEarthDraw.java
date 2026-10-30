package com.creedpdf.orbitalrailgun.client.space;

import com.creedpdf.orbitalrailgun.astraea.Timeline;
import com.creedpdf.orbitalrailgun.client.astraea.AstraeaDraw;
import com.creedpdf.orbitalrailgun.client.astraea.AstraeaState;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.space.Body;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Random;

/**
 * The Earth when ASTRAEA has cut it - wherever the Earth is drawn (from space, from the other planets, in the strike's
 * cutscene):
 * <ul>
 *   <li><b>while the lance drills</b>: the whole planet, with a molten seam racing round it along the cut (fracture
 *   lines branching off it, the crust glowing) and the lance's white-hot spot at its head;</li>
 *   <li><b>split</b>: two hemispheres drifting apart along the cut's normal and tumbling slowly, the real surface
 *   (day side, city lights, clouds) clipped cleanly at the rim; on each, the Blender cut face - crust, molten mantle,
 *   outer and inner core; torn chunks of planet tumbling in the gap; a glowing haze between the halves; a shockwave
 *   racing out in the plane of the cut and a blinding flash at first.</li>
 * </ul>
 */
public final class SplitEarthDraw {
    private SplitEarthDraw() {}

    /** Rotation from the Space dimension's frame to the frame being drawn (set by SpaceSky for each render). */
    public static Quaternionf toLocal = new Quaternionf();
    /** Set by the strike's cutscene: drives the split at cinematic speed (ticks since the split, or -1). */
    public static double cineSince = -1;

    private static final int LIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    private static final Vector3f FRACTURE_N = new Vector3f((float) Math.sin(Math.toRadians(70)), (float) Math.cos(Math.toRadians(70)), 0f);

    public static boolean active(float pt) {
        return AstraeaState.split() && AstraeaState.sinceSplit(pt) >= 0 || AstraeaState.drill(pt) >= 0 || cineSince >= 0;
    }

    public static void draw(VertexConsumerProvider cp, Matrix4f m, Vector3f c, float r, Vector3f L, float time, float pt, float alpha, int segments) {
        Vector3f l = new Vector3f(L).normalize();
        double drill = AstraeaState.drill(pt);
        AstraeaState.Strike s = AstraeaState.strike();
        if (drill >= 0 && cineSince < 0) {
            Vec3d nn = s.normal();
            drilling(cp, m, c, r, l, time, pt, alpha, segments, toLocal.transform(new Vector3f((float) nn.x, (float) nn.y, (float) nn.z)), drill, s);
            return;
        }
        Vec3d nn = AstraeaState.split() ? AstraeaState.normal : s != null ? s.normal() : new Vec3d(0, 0, 1);
        Vector3f n = toLocal.transform(new Vector3f((float) nn.x, (float) nn.y, (float) nn.z)).normalize();
        double since = cineSince >= 0 ? cineSince : AstraeaState.sinceSplit(pt);
        int strikes = Math.max(1, AstraeaState.strikes);
        double sep = cineSince >= 0 ? Timeline.separation(since * 10, strikes) : Timeline.separation(since, strikes);
        split(cp, m, c, r, l, time, alpha, segments, n, (float) since, (float) sep);
    }

    // ------------------------------------------------------------------------------------------- the drill

    static void drilling(VertexConsumerProvider cp, Matrix4f m, Vector3f c, float r, Vector3f l, float time, float pt, float alpha, int segments,
                         Vector3f n, double progress, AstraeaState.Strike s) {
        n.normalize();
        BodyDraw.surface(cp, m, Body.EARTH, c, r, l, time, alpha, segments, 1f, 0f, null);
        // the cut's frame: u through the bearer's spot, w along the cut
        Vec3d up = Body.EARTH.surfaceNormal(s.anchor().x, s.anchor().z);
        Vector3f u = toLocal.transform(new Vector3f((float) up.x, (float) up.y, (float) up.z)).normalize();
        Vector3f w = new Vector3f(u).cross(n).normalize();
        double a0 = Timeline.orbitAngle(Timeline.S_DRILL);
        double span = Math.PI * 2 * progress;
        // the fracture overlay, revealed round the circle as the cut goes
        Quaternionf qf = new Quaternionf().rotationTo(FRACTURE_N, n);
        VertexConsumer fv = cp.getBuffer(BeamRenderLayers.textured(AstraeaDraw.FRACTURE));
        sphere(fv, m, c, r * 1.002f, qf, null, 0f, 96, 192, (dir, out) -> {
            double ang = Math.atan2(dir.dot(w), dir.dot(u)) - a0;
            ang = ((ang % (Math.PI * 2)) + Math.PI * 2) % (Math.PI * 2);
            float k = ang <= span ? 1f : (float) Math.max(0, 1 - (ang - span) / 0.25);
            float a = k * (0.55f + 0.45f * (float) progress) * alpha;
            out[0] = a; out[1] = a; out[2] = a; out[3] = a;
        });
        // the seam itself and the white-hot head where the lance is boring
        VertexConsumer g = cp.getBuffer(BeamRenderLayers.GLOW);
        int segs = 220;
        Vector3f prev = null;
        for (int i = 0; i <= segs; i++) {
            double ang = a0 + span * i / segs;
            Vector3f d = new Vector3f(u).mul((float) Math.cos(ang)).add(new Vector3f(w).mul((float) Math.sin(ang)));
            Vector3f p = new Vector3f(d).mul(r * 1.004f).add(c);
            if (prev != null && facing(p, d)) {
                AstraeaDraw.strip(g, m, prev, p, r * 0.03f, r * 0.03f, AstraeaDraw.EMBER, 0.45f * alpha, 0.45f * alpha);
                AstraeaDraw.strip(g, m, prev, p, r * 0.008f, r * 0.008f, AstraeaDraw.WARM, alpha, alpha);
            }
            prev = p;
        }
        double ah = a0 + span;
        Vector3f hd = new Vector3f(u).mul((float) Math.cos(ah)).add(new Vector3f(w).mul((float) Math.sin(ah)));
        Vector3f head = new Vector3f(hd).mul(r * 1.01f).add(c);
        if (facing(head, hd)) {
            float fl = 0.8f + 0.2f * MathHelper.sin(time * 3.1f);
            AstraeaDraw.glowBall(g, m, head, r * 0.09f * fl, AstraeaDraw.WHITE, alpha);
            AstraeaDraw.glowBall(g, m, head, r * 0.25f * fl, AstraeaDraw.EMBER, 0.5f * alpha);
            // molten spray thrown off the cut
            Random rnd = new Random((long) (time / 2));
            for (int k = 0; k < 20; k++) {
                Vector3f dir = new Vector3f(hd).add((rnd.nextFloat() - 0.5f), (rnd.nextFloat() - 0.5f), (rnd.nextFloat() - 0.5f)).normalize();
                Vector3f tip = new Vector3f(head).add(new Vector3f(dir).mul(r * (0.08f + rnd.nextFloat() * 0.25f)));
                AstraeaDraw.strip(g, m, head, tip, r * 0.012f, r * 0.002f, AstraeaDraw.EMBER, 0.8f * alpha, 0f);
            }
        }
    }

    /** Is a point on the sphere's surface (with outward direction d) on the side facing the camera at the origin? */
    private static boolean facing(Vector3f p, Vector3f d) {
        return p.dot(d) < 0;
    }

    // ------------------------------------------------------------------------------------------- the split

    static void split(VertexConsumerProvider cp, Matrix4f m, Vector3f c, float r, Vector3f l, float time, float alpha, int segments, Vector3f n,
                      float since, float sep) {
        Vector3f axis = new Vector3f(n).cross(l);
        if (axis.lengthSquared() < 1e-6f) axis.set(0, 1, 0).cross(n);
        axis.normalize();
        float tumble = 0.24f * (1f - (float) Math.exp(-since / (cineSince >= 0 ? 150f : 1500f)));
        Quaternionf spin = BodyDraw.spin(Body.EARTH, time);
        float heat = 0.35f + 0.65f * (float) Math.exp(-since / (cineSince >= 0 ? 300f : 2400f));
        for (int s = -1; s <= 1; s += 2) {
            Vector3f cs = new Vector3f(n).mul(s * sep * r * 0.5f).add(c);
            Quaternionf qt = new Quaternionf().rotationAxis(s * tumble, axis);
            // the face looks toward the other half
            Vector3f face = qt.transform(new Vector3f(n).mul(-s)).normalize();
            half(cp, m, cs, r, l, qt, spin, face, heat, alpha, segments);
        }
        gap(cp, m, c, r, l, n, axis, since, sep, heat, alpha, time);
    }

    /**
     * One hemisphere: the Earth's surface on the far side of a face whose normal is {@code face} (the face looks that
     * way), and the Blender cut face over it.
     */
    public static void half(VertexConsumerProvider cp, Matrix4f m, Vector3f c, float r, Vector3f l, Quaternionf tumble, Quaternionf spin, Vector3f face,
                            float heat, float alpha, int segments) {
        Quaternionf q = new Quaternionf(tumble).mul(spin);
        int lon = Math.max(64, segments), lat = Math.max(32, segments / 2);
        boolean solid = alpha >= 0.999f;
        VertexConsumer day = cp.getBuffer(solid ? BeamRenderLayers.solid(BodyDraw.albedo(Body.EARTH)) : BeamRenderLayers.translucent(BodyDraw.albedo(Body.EARTH)));
        sphere(day, m, c, r, q, face, 0f, lat, lon, (d, out) -> {
            float k = BodyDraw.lit(d, l, 0.03f);
            out[0] = k * (1f + 0.12f * BodyDraw.warm(d, l)); out[1] = k; out[2] = k; out[3] = alpha;
        });
        VertexConsumer night = cp.getBuffer(BeamRenderLayers.textured(BodyDraw.EARTH_NIGHT));
        sphere(night, m, c, r * 1.0005f, q, face, 0f, lat, lon, (d, out) -> {
            float k = 1f - PlanetDrawMath.smooth(-0.25f, 0.08f, d.dot(l));
            out[0] = k * alpha; out[1] = k * 0.85f * alpha; out[2] = k * 0.6f * alpha; out[3] = alpha;
        });
        VertexConsumer clouds = cp.getBuffer(BeamRenderLayers.translucent(BodyDraw.EARTH_CLOUDS));
        sphere(clouds, m, c, r * 1.006f, new Quaternionf(q).rotateY(0.3f), face, 0.004f, lat, lon, (d, out) -> {
            float k = BodyDraw.lit(d, l, 0.01f);
            out[0] = k; out[1] = k; out[2] = k * 1.03f; out[3] = 0.9f * alpha;
        });
        // the crust near the cut glowing where it cracked
        VertexConsumer fr = cp.getBuffer(BeamRenderLayers.textured(AstraeaDraw.FRACTURE));
        Quaternionf qf = new Quaternionf().rotationTo(FRACTURE_N, face);
        sphere(fr, m, c, r * 1.003f, qf, face, 0.002f, lat, lon, (d, out) -> {
            float a = heat * alpha;
            out[0] = a; out[1] = a; out[2] = a; out[3] = a;
        });
        // the cut face
        KrlMesh cut = KrlModels.EARTH_CUT;
        // only when it faces the camera (at the origin): otherwise the hemisphere hides it
        if (cut != null && -(face.x * c.x + face.y * c.y + face.z * c.z) > 0f) {
            MatrixStack ms = new MatrixStack();
            ms.peek().getPositionMatrix().set(m);
            ms.translate(c.x, c.y, c.z);
            ms.multiply(new Quaternionf().rotationTo(0f, 1f, 0f, face.x, face.y, face.z));
            ms.scale(r * 0.999f, r, r * 0.999f);
            float lf = Math.max(0f, face.dot(l));
            cut.lod(0).renderNoOverlay(ms, cp.getBuffer(solid ? BeamRenderLayers.solid(KrlModels.CUT_TEX) : BeamRenderLayers.translucent(KrlModels.CUT_TEX)), LIGHT, null, (nx, ny, nz, o) -> {
                float k = 0.35f + 0.65f * lf;
                o[0] = k; o[1] = k; o[2] = k; o[3] = 1f;
            });
            cut.glow(0).renderNoOverlay(ms, cp.getBuffer(BeamRenderLayers.textured(KrlModels.CUT_GLOW)), LIGHT, null, (nx, ny, nz, o) -> {
                float g = (0.55f + 0.45f * heat) * alpha;
                o[0] = g; o[1] = g; o[2] = g; o[3] = g;
            });
        }
    }

    /** Between the halves: the haze of molten rock, torn chunks tumbling, the shockwave and the first flash. */
    private static void gap(VertexConsumerProvider cp, Matrix4f m, Vector3f c, float r, Vector3f l, Vector3f n, Vector3f axis, float since, float sep,
                            float heat, float alpha, float time) {
        Vector3f u = new Vector3f(axis), w = new Vector3f(n).cross(axis).normalize();
        VertexConsumer g = cp.getBuffer(BeamRenderLayers.GLOW);
        // haze in the plane of the cut
        VertexConsumer hz = cp.getBuffer(BeamRenderLayers.textured(AstraeaDraw.HAZE));
        float ha = (0.15f + 0.6f * heat) * alpha;
        AstraeaDraw.quad(hz, m, c, u, w, r * 1.25f, time * 0.0004f, AstraeaDraw.EMBER, ha);
        AstraeaDraw.quad(hz, m, c, u, w, r * 1.05f, -time * 0.0007f, AstraeaDraw.GOLD, ha * 0.6f);
        AstraeaDraw.ring(g, m, c, u, w, r * 0.95f, r * 1.12f, 96, AstraeaDraw.EMBER, 0.35f * heat * alpha, 0f);
        // the shockwave and the flash
        if (since < 600) {
            float k = since / 600f;
            float rr = r * (1.05f + k * 6f);
            AstraeaDraw.ring(g, m, c, u, w, rr, rr + r * (0.35f + k), 120, AstraeaDraw.WARM, (1 - k) * (1 - k) * alpha, 0f);
            AstraeaDraw.ring(g, m, c, u, w, rr * 0.8f, rr * 0.8f + r * 0.2f, 120, AstraeaDraw.WHITE, (1 - k) * (1 - k) * 0.6f * alpha, 0f);
        }
        if (since < 60) {
            float k = 1f - since / 60f;
            AstraeaDraw.glowBall(g, m, c, r * (1.2f + 2.5f * (1 - k)), AstraeaDraw.WHITE, k * alpha);
        }
        // chunks of planet
        KrlMesh chunks = KrlModels.EARTH_CHUNKS;
        if (chunks == null) return;
        Random rnd = new Random(4242);
        MatrixStack ms = new MatrixStack();
        ms.peek().getPositionMatrix().set(m);
        for (int i = 0; i < 36; i++) {
            float ang = rnd.nextFloat() * MathHelper.TAU;
            float rad = r * (0.25f + rnd.nextFloat() * 1.05f);
            float drift = 1f + (float) (1 - Math.exp(-since / 900.0)) * (0.2f + rnd.nextFloat() * 0.6f);
            float along = (rnd.nextFloat() - 0.5f) * sep * r * 0.8f;
            Vector3f p = new Vector3f(c).add(new Vector3f(u).mul(MathHelper.cos(ang) * rad * drift)).add(new Vector3f(w).mul(MathHelper.sin(ang) * rad * drift))
                    .add(new Vector3f(n).mul(along));
            float sc = r * (0.012f + rnd.nextFloat() * 0.05f);
            String part = "c" + rnd.nextInt(8);
            float spinA = time * (0.002f + rnd.nextFloat() * 0.01f) + rnd.nextFloat() * 6f;
            Vector3f ax = new Vector3f(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).normalize();
            ms.push();
            ms.translate(p.x, p.y, p.z);
            ms.multiply(new Quaternionf().rotationAxis(spinA, ax));
            ms.scale(sc, sc, sc);
            KrlMesh.Pose only = (pn, st) -> pn.equals(part);
            chunks.lod(1).renderNoOverlay(ms, cp.getBuffer(alpha >= 0.999f ? BeamRenderLayers.solid(KrlModels.CHUNK_TEX) : BeamRenderLayers.translucent(KrlModels.CHUNK_TEX)), LIGHT, only, (nx, ny, nz, o) -> {
                float k = 0.2f + 0.8f * Math.max(0f, nx * l.x + ny * l.y + nz * l.z);
                o[0] = k; o[1] = k; o[2] = k; o[3] = 1f;
            });
            chunks.glow(1).renderNoOverlay(ms, cp.getBuffer(BeamRenderLayers.textured(KrlModels.CHUNK_GLOW)), LIGHT, only, (nx, ny, nz, o) -> {
                float k = heat * alpha;
                o[0] = k; o[1] = k; o[2] = k; o[3] = k;
            });
            ms.pop();
        }
    }

    // ------------------------------------------------------------------------------------------- the clipped sphere

    interface Shade {
        void apply(Vector3f dir, float[] out);
    }

    /**
     * A UV sphere (equirectangular texture) rotated by q. With {@code face} set, the half on the face's side is folded
     * onto the rim (so the sphere ends cleanly at the cut; {@code lip} lifts the rim slightly to tuck under the face).
     * Back-facing quads are skipped (the camera is at the origin).
     */
    static void sphere(VertexConsumer vc, Matrix4f m, Vector3f c, float r, Quaternionf q, Vector3f face, float lip, int lat, int lon, Shade shade) {
        float[] g = BodyDraw.grid(lat, lon);
        int wdt = lon + 1, n = (lat + 1) * wdt;
        float[] b = new float[n * 8];
        float[] col = new float[4];
        Vector3f d = new Vector3f();
        for (int k = 0; k < n; k++) {
            d.set(g[k * 3], g[k * 3 + 1], g[k * 3 + 2]);
            q.transform(d);
            if (face != null) {
                float f = d.dot(face);
                if (f > -lip) {
                    d.sub(face.x * f, face.y * f, face.z * f);
                    if (d.lengthSquared() < 1e-8f) d.set(face).cross(0, 1, 0);
                    d.normalize();
                    d.sub(face.x * lip, face.y * lip, face.z * lip);
                }
            }
            col[0] = col[1] = col[2] = col[3] = 0f;
            shade.apply(d, col);
            int o = k * 8;
            b[o] = d.x * r + c.x;
            b[o + 1] = d.y * r + c.y;
            b[o + 2] = d.z * r + c.z;
            b[o + 3] = Math.min(1f, col[0]);
            b[o + 4] = Math.min(1f, col[1]);
            b[o + 5] = Math.min(1f, col[2]);
            b[o + 6] = col[3];
            float len = (float) Math.sqrt(b[o] * b[o] + b[o + 1] * b[o + 1] + b[o + 2] * b[o + 2]);
            b[o + 7] = len > 0 ? (d.x * b[o] + d.y * b[o + 1] + d.z * b[o + 2]) / len : 0f;
        }
        for (int i = 0; i < lat; i++) {
            float v0 = i / (float) lat, v1 = (i + 1) / (float) lat;
            for (int j = 0; j < lon; j++) {
                int k00 = i * wdt + j, k01 = k00 + 1, k10 = k00 + wdt, k11 = k10 + 1;
                if ((b[k00 * 8 + 7] + b[k01 * 8 + 7] + b[k10 * 8 + 7] + b[k11 * 8 + 7]) * 0.25f > 0.12f) continue;
                float u0 = j / (float) lon, u1 = (j + 1) / (float) lon;
                emit(vc, m, b, k00, u0, v0);
                emit(vc, m, b, k01, u1, v0);
                emit(vc, m, b, k11, u1, v1);
                emit(vc, m, b, k10, u0, v1);
            }
        }
    }

    private static void emit(VertexConsumer vc, Matrix4f m, float[] b, int k, float u, float v) {
        int o = k * 8;
        vc.vertex(m, b[o], b[o + 1], b[o + 2]).color(b[o + 3], b[o + 4], b[o + 5], b[o + 6]).texture(u, v).light(LIGHT).normal(0, 1, 0).next();
    }
}
