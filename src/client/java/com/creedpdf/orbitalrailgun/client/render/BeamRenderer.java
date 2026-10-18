package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Procedural 3D geometry for the railgun beam. Everything here is real geometry emitted into
 * {@link BeamRenderLayers#BEAM}; there are no textures and no camera-facing billboards.
 *
 * <h2>Building blocks</h2>
 * <ul>
 *   <li>{@link #tube}: an N-sided cylinder between two points, subdivided along its length so the
 *       radius can ripple (a travelling sine "pressure wave") and taper at the muzzle.</li>
 *   <li>{@link #sphere}: a UV sphere, used for the muzzle charge-orb and the impact fireball.</li>
 *   <li>{@link #ring}: a flat annulus in an arbitrary plane, used for the magnetic "coil rings"
 *       that race down the beam and the ground shockwave.</li>
 * </ul>
 *
 * <h2>Why several shells?</h2>
 * The beam is 4 concentric tubes: a near-white core, a hot red body, and two increasingly wide,
 * increasingly transparent halos. With additive blending (see BeamRenderLayers) their light sums,
 * giving a bright centre that falls off smoothly outward: the classic volumetric glow look,
 * without any post-processing or custom shaders.
 *
 * <h2>Coordinate space</h2>
 * When an EntityRenderer runs, the MatrixStack is already translated to the entity's
 * interpolated position relative to the camera. So all positions here are <i>relative to the
 * entity</i>: {@code worldPos - entity.getLerpedPos(tickDelta)}. The matrix then takes care of
 * camera translation and view rotation.
 */
public final class BeamRenderer {
    private BeamRenderer() {}

    private static final int TUBE_SIDES = 14;
    private static final int MAX_SEGMENTS = 96;

    public static void render(RailgunEntity railgun, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers) {
        Vec3d origin = railgun.getLerpedPos(tickDelta);
        Vector3f muzzle = toLocal(railgun.getMuzzlePos(tickDelta), origin);
        Vector3f target = toLocal(Vec3d.ofCenter(railgun.getTarget()), origin);

        VertexConsumer vc = consumers.getBuffer(BeamRenderLayers.BEAM);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float time = railgun.age + tickDelta;

        if (railgun.getState() == RailgunState.CHARGING) {
            renderCharging(vc, matrix, muzzle, target, railgun.getCharge(), time);
        } else if (railgun.getState() == RailgunState.FIRING) {
            float progress = MathHelper.clamp((railgun.getStateTicks() + tickDelta) / RailgunConfig.FIRE_TICKS, 0f, 1f);
            renderFiring(vc, matrix, muzzle, target, progress, time);
        }
    }

    /**
     * Charging: a thin targeting laser that thickens and pulses faster as charge rises, plus an
     * energy orb swelling at the muzzle.
     */
    private static void renderCharging(VertexConsumer vc, Matrix4f m, Vector3f muzzle, Vector3f target, float charge, float time) {
        float pulse = 0.75f + 0.25f * MathHelper.sin(time * (0.35f + charge * 1.4f));
        float radius = 0.025f + 0.11f * charge * charge;

        tube(vc, m, muzzle, target, radius, 1.0f, 0.35f, 0.3f, 0.65f * pulse, 0f, time);
        tube(vc, m, muzzle, target, radius * 2.6f, 1.0f, 0.04f, 0.02f, (0.10f + 0.25f * charge) * pulse, 0.08f * charge, time);

        float orb = 0.12f + 0.75f * charge;
        sphere(vc, m, muzzle, orb * 0.45f, 1.0f, 0.85f, 0.8f, 0.5f + 0.4f * charge);
        sphere(vc, m, muzzle, orb, 1.0f, 0.12f, 0.06f, 0.30f * pulse);
        sphere(vc, m, muzzle, orb * 1.8f, 0.9f, 0.02f, 0.02f, 0.10f * charge);

        // Converging rings: inflow toward the muzzle, faster as charge builds.
        Vector3f dir = new Vector3f(target).sub(muzzle).normalize();
        for (int i = 0; i < 3; i++) {
            float phase = ((time * (0.03f + 0.05f * charge)) + i / 3f) % 1f;
            float r = orb * (2.6f - 2.0f * phase);
            float a = phase * 0.35f * charge;
            ring(vc, m, muzzle, dir, r, r + 0.05f + 0.05f * charge, 1.0f, 0.1f, 0.05f, a);
        }
    }

    /** Firing: full-power shot that blooms, flickers, and decays over FIRE_TICKS. */
    private static void renderFiring(VertexConsumer vc, Matrix4f m, Vector3f muzzle, Vector3f target, float progress, float time) {
        float intensity = (float) Math.pow(1f - progress, 1.5);
        float flicker = 0.85f + 0.15f * MathHelper.sin(time * 7.3f) * MathHelper.sin(time * 3.1f);
        float i = intensity * flicker;
        // Instant bloom on the first ticks, then the column thins out.
        float base = 0.25f + 1.1f * intensity + (progress < 0.08f ? 0.8f * (1f - progress / 0.08f) : 0f);

        tube(vc, m, muzzle, target, base * 0.30f, 1.0f, 0.92f, 0.88f, 0.95f * i, 0.04f, time);
        tube(vc, m, muzzle, target, base * 0.65f, 1.0f, 0.30f, 0.18f, 0.65f * i, 0.10f, time);
        tube(vc, m, muzzle, target, base * 1.35f, 1.0f, 0.05f, 0.02f, 0.32f * i, 0.16f, time);
        tube(vc, m, muzzle, target, base * 2.60f, 0.8f, 0.00f, 0.00f, 0.12f * i, 0.22f, time);

        // Magnetic accelerator rings racing from the muzzle to the target.
        Vector3f delta = new Vector3f(target).sub(muzzle);
        float length = delta.length();
        Vector3f dir = new Vector3f(delta).normalize();
        float spacing = 6f;
        float offset = (time * 2.5f) % spacing;
        for (float d = offset; d < length; d += spacing) {
            Vector3f c = new Vector3f(dir).mul(d).add(muzzle);
            float r = base * 1.7f;
            ring(vc, m, c, dir, r, r + 0.18f, 1.0f, 0.2f, 0.1f, 0.45f * i);
        }

        // Muzzle flash and impact fireball.
        sphere(vc, m, muzzle, base * 0.9f, 1.0f, 0.9f, 0.85f, 0.7f * i);
        sphere(vc, m, muzzle, base * 1.8f, 1.0f, 0.1f, 0.05f, 0.25f * i);
        sphere(vc, m, target, base * 1.6f, 1.0f, 0.95f, 0.9f, 0.8f * i);
        sphere(vc, m, target, base * 3.5f, 1.0f, 0.15f, 0.05f, 0.35f * i);

        // Ground shockwave expanding outward in the horizontal plane.
        float wave = 1.0f + progress * 16f;
        ring(vc, m, new Vector3f(target).add(0, -0.45f, 0), new Vector3f(0, 1, 0), wave, wave + 1.4f,
                1.0f, 0.25f, 0.1f, 0.55f * (1f - progress));
    }

    // =========================================================================================
    // Geometry primitives
    // =========================================================================================

    /**
     * Emits a cylinder from {@code a} to {@code b}.
     *
     * <p>We build an orthonormal frame (dir, u, v) around the beam axis. Each ring of the tube
     * is {@code center + (u*cos θ + v*sin θ) * radius}. Adjacent rings are stitched into quads.
     * Radius per ring = base radius × muzzle taper × (1 + wobble × travelling sine), which makes
     * energy visibly pulse down the beam.</p>
     */
    private static void tube(VertexConsumer vc, Matrix4f m, Vector3f a, Vector3f b, float radius,
                             float r, float g, float bl, float alpha, float wobble, float time) {
        if (alpha <= 0.003f || radius <= 0f) return;
        Vector3f axis = new Vector3f(b).sub(a);
        float length = axis.length();
        if (length < 1e-3f) return;
        Vector3f dir = new Vector3f(axis).div(length);
        Vector3f u = new Vector3f(), v = new Vector3f();
        basis(dir, u, v);

        int segments = MathHelper.clamp((int) (length / 2.5f), 1, MAX_SEGMENTS);
        float[] cos = new float[TUBE_SIDES + 1], sin = new float[TUBE_SIDES + 1];
        for (int k = 0; k <= TUBE_SIDES; k++) {
            double ang = k * (Math.PI * 2.0 / TUBE_SIDES);
            cos[k] = (float) Math.cos(ang);
            sin[k] = (float) Math.sin(ang);
        }

        Vector3f c0 = new Vector3f(), c1 = new Vector3f();
        for (int s = 0; s < segments; s++) {
            float t0 = (float) s / segments, t1 = (float) (s + 1) / segments;
            float r0 = radius * profile(t0 * length, wobble, time);
            float r1 = radius * profile(t1 * length, wobble, time);
            c0.set(dir).mul(t0 * length).add(a);
            c1.set(dir).mul(t1 * length).add(a);
            for (int k = 0; k < TUBE_SIDES; k++) {
                emit(vc, m, c0, u, v, cos[k], sin[k], r0, r, g, bl, alpha);
                emit(vc, m, c0, u, v, cos[k + 1], sin[k + 1], r0, r, g, bl, alpha);
                emit(vc, m, c1, u, v, cos[k + 1], sin[k + 1], r1, r, g, bl, alpha);
                emit(vc, m, c1, u, v, cos[k], sin[k], r1, r, g, bl, alpha);
            }
        }
    }

    /** Radius multiplier along the beam: soft 0.6-block taper at the muzzle + travelling wave. */
    private static float profile(float distance, float wobble, float time) {
        float taper = MathHelper.clamp(distance / 0.6f, 0.25f, 1f);
        return taper * (1f + wobble * MathHelper.sin(distance * 0.9f - time * 1.6f));
    }

    /** UV sphere (latitude/longitude grid). */
    private static void sphere(VertexConsumer vc, Matrix4f m, Vector3f c, float radius,
                               float r, float g, float b, float alpha) {
        if (alpha <= 0.003f || radius <= 0f) return;
        final int lat = 8, lon = 14;
        for (int i = 0; i < lat; i++) {
            double th0 = Math.PI * i / lat - Math.PI / 2, th1 = Math.PI * (i + 1) / lat - Math.PI / 2;
            for (int j = 0; j < lon; j++) {
                double ph0 = 2 * Math.PI * j / lon, ph1 = 2 * Math.PI * (j + 1) / lon;
                spherePoint(vc, m, c, radius, th0, ph0, r, g, b, alpha);
                spherePoint(vc, m, c, radius, th0, ph1, r, g, b, alpha);
                spherePoint(vc, m, c, radius, th1, ph1, r, g, b, alpha);
                spherePoint(vc, m, c, radius, th1, ph0, r, g, b, alpha);
            }
        }
    }

    private static void spherePoint(VertexConsumer vc, Matrix4f m, Vector3f c, float radius, double theta, double phi,
                                    float r, float g, float b, float a) {
        float ct = (float) Math.cos(theta);
        vc.vertex(m,
                c.x + radius * ct * (float) Math.cos(phi),
                c.y + radius * (float) Math.sin(theta),
                c.z + radius * ct * (float) Math.sin(phi)).color(r, g, b, a).next();
    }

    /** Flat annulus centred on {@code c}, perpendicular to {@code normal}. */
    private static void ring(VertexConsumer vc, Matrix4f m, Vector3f c, Vector3f normal, float inner, float outer,
                             float r, float g, float b, float alpha) {
        if (alpha <= 0.003f || outer <= 0f) return;
        Vector3f n = new Vector3f(normal).normalize();
        Vector3f u = new Vector3f(), v = new Vector3f();
        basis(n, u, v);
        final int sides = 32;
        for (int k = 0; k < sides; k++) {
            double a0 = 2 * Math.PI * k / sides, a1 = 2 * Math.PI * (k + 1) / sides;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            emit(vc, m, c, u, v, c0, s0, inner, r, g, b, alpha);
            emit(vc, m, c, u, v, c1, s1, inner, r, g, b, alpha);
            emit(vc, m, c, u, v, c1, s1, outer, r, g, b, 0f); // outer edge fades to nothing
            emit(vc, m, c, u, v, c0, s0, outer, r, g, b, 0f);
        }
    }

    /** Emits one vertex at {@code center + (u*cos + v*sin) * radius}. */
    private static void emit(VertexConsumer vc, Matrix4f m, Vector3f center, Vector3f u, Vector3f v,
                             float cos, float sin, float radius, float r, float g, float b, float a) {
        vc.vertex(m,
                center.x + (u.x * cos + v.x * sin) * radius,
                center.y + (u.y * cos + v.y * sin) * radius,
                center.z + (u.z * cos + v.z * sin) * radius).color(r, g, b, a).next();
    }

    /** Builds two unit vectors perpendicular to {@code dir} and to each other. */
    private static void basis(Vector3f dir, Vector3f u, Vector3f v) {
        Vector3f helper = Math.abs(dir.y) < 0.99f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
        dir.cross(helper, u).normalize();
        dir.cross(u, v).normalize();
    }

    private static Vector3f toLocal(Vec3d world, Vec3d origin) {
        return new Vector3f((float) (world.x - origin.x), (float) (world.y - origin.y), (float) (world.z - origin.z));
    }
}
