package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Procedural 3D geometry for the railgun beam. Everything is real geometry (tubes, ribbons,
 * spheres, rings); nothing is a camera-facing billboard.
 *
 * <h2>Layers of the firing beam (inside out)</h2>
 * <ol>
 *   <li>White-hot <b>core</b> tube, textured with streaking filaments, scrolling fast.</li>
 *   <li>Two <b>plasma</b> tubes with the turbulent red texture, scrolling at different speeds
 *       and twisting in opposite directions, so the surface churns instead of sliding.</li>
 *   <li>A wispy textured <b>halo</b>, then two very faint untextured glow shells for bloom.</li>
 *   <li>Three textured <b>helix ribbons</b> spiralling around the beam.</li>
 *   <li><b>Accelerator rings</b> racing from the muzzle to the target.</li>
 *   <li>Muzzle fireball, impact fireball and expanding ground shockwaves at the target.</li>
 * </ol>
 * There is no beam before the shot: while charging, only a swelling plasma orb at the muzzle,
 * collapsing rings and procedural lightning arcs crawling up the barrel are drawn.
 *
 * <h2>Texture mapping</h2>
 * Tubes map U around the circumference and V along the length (in blocks / texture repeat
 * length), offset by time, so plasma streams from the muzzle to the target. Rings map U around,
 * V across the band. All layers use additive blending (see {@link BeamRenderLayers}).
 *
 * <h2>Coordinate space</h2>
 * The MatrixStack arrives translated to the entity's interpolated position relative to the
 * camera, so all positions here are entity-relative.
 */
public final class BeamRenderer {
    private BeamRenderer() {}

    private static final int SIDES = 16;
    private static final int MAX_SEGMENTS = 160;
    private static final int LIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;

    public static void render(RailgunEntity railgun, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers) {
        Vec3d origin = railgun.getLerpedPos(tickDelta);
        Vector3f muzzle = toLocal(railgun.getMuzzlePos(tickDelta), origin);
        Vector3f target = toLocal(Vec3d.ofCenter(railgun.getTarget()), origin);
        Matrix4f m = matrices.peek().getPositionMatrix();
        float time = railgun.age + tickDelta;
        Beam beam = new Beam(muzzle, target);
        if (beam.length < 0.5f) return;

        if (railgun.getState() == RailgunState.CHARGING) {
            renderChargeUp(consumers, m, beam, railgun, railgun.getCharge(), time);
        } else if (railgun.getState() == RailgunState.FIRING) {
            float p = MathHelper.clamp((railgun.getStateTicks() + tickDelta) / RailgunConfig.FIRE_TICKS, 0f, 1f);
            renderFiring(consumers, m, beam, p, time);
        }
    }

    // =========================================================================================
    // Charging
    // =========================================================================================

    /** Charging: no beam yet, only energy gathering at the muzzle and arcs along the barrel. */
    private static void renderChargeUp(VertexConsumerProvider cp, Matrix4f m, Beam b, RailgunEntity railgun, float c, float time) {
        float pulse = 0.75f + 0.25f * MathHelper.sin(time * (0.3f + c * 1.2f));

        // Swelling plasma orb at the muzzle.
        float orb = 0.5f + 4.6f * c;
        texSphere(tex(cp, BeamRenderLayers.TEX_PLASMA), m, b.a, orb * 0.7f,
                1f, 0.5f, 0.4f, 0.75f, time * 0.05f, time * 0.03f);
        glowSphere(glow(cp), m, b.a, orb * 0.35f, 1f, 0.95f, 0.9f, 0.5f + 0.5f * c);
        glowSphere(glow(cp), m, b.a, orb, 1f, 0.12f, 0.06f, 0.22f * pulse);
        glowSphere(glow(cp), m, b.a, orb * 1.9f, 0.9f, 0.03f, 0.02f, 0.08f * c);

        // Collapsing rings: energy pouring into the muzzle.
        VertexConsumer ringTex = tex(cp, BeamRenderLayers.TEX_SHOCKWAVE);
        for (int i = 0; i < 4; i++) {
            float phase = ((time * (0.02f + 0.05f * c)) + i / 4f) % 1f;
            float r = orb * (3.2f - 2.6f * phase);
            texRing(ringTex, m, b.a, b.dir, r, r + 0.4f + 0.8f * c, 1f, 0.3f, 0.2f, phase * 0.8f * c, time * 0.01f);
        }

        // Lightning arcs crawling up the barrel, re-rolled every 2 ticks.
        Random rand = Random.create(railgun.getId() * 31L + (long) (time / 2));
        VertexConsumer glow = glow(cp);
        int arcs = 3 + (int) (c * 12);
        Vector3f back = new Vector3f(b.dir).mul((float) -RailgunConfig.BARREL_LENGTH);
        for (int i = 0; i < arcs; i++) {
            float along = 0.15f + rand.nextFloat() * 0.8f;
            Vector3f start = new Vector3f(back).mul(along).add(b.a)
                    .add(new Vector3f(b.u).mul((rand.nextFloat() - 0.5f) * 3f))
                    .add(new Vector3f(b.v).mul((rand.nextFloat() - 0.5f) * 3f));
            Vector3f end = rand.nextFloat() < 0.5f ? new Vector3f(b.a)
                    : new Vector3f(back).mul(rand.nextFloat()).add(b.a).add(new Vector3f(b.v).mul(2f));
            arc(glow, m, start, end, rand, 0.05f + 0.05f * c, 1f, 0.75f, 0.7f, 0.9f);
        }
    }

    // =========================================================================================
    // Firing
    // =========================================================================================

    private static void renderFiring(VertexConsumerProvider cp, Matrix4f m, Beam b, float p, float time) {
        // Envelope: a blinding ignition flash, a long sustained burn, then a fast collapse.
        float sustain = p < 0.6f ? 1f : (float) Math.pow(1f - (p - 0.6f) / 0.4f, 1.6);
        float flicker = 0.9f + 0.1f * MathHelper.sin(time * 7.3f) * MathHelper.sin(time * 3.1f);
        float i = sustain * flicker;
        float bloom = p < 0.05f ? 3.0f * (1f - p / 0.05f) : 0f;
        float B = 1.0f + 0.9f * sustain + bloom;
        float throb = 1f + 0.08f * MathHelper.sin(time * 2.2f);

        // Beam body, inside out.
        texTube(tex(cp, BeamRenderLayers.TEX_CORE), m, b, 0.9f * B, 1f, 0.97f, 0.94f, Math.min(1f, 1.2f * i), 5f, time * 3.4f, 0f, 0.06f, time);
        texTube(tex(cp, BeamRenderLayers.TEX_CORE), m, b, 1.3f * B, 1f, 0.75f, 0.65f, 0.6f * i, 7f, time * 2.8f, 0.5f, 0.08f, time + 13f);
        texTube(tex(cp, BeamRenderLayers.TEX_PLASMA), m, b, 1.8f * B * throb, 1f, 0.45f, 0.33f, 0.9f * i, 10f, time * 2.2f, time * 0.06f, 0.12f, time);
        texTube(tex(cp, BeamRenderLayers.TEX_PLASMA), m, b, 2.2f * B, 1f, 0.28f, 0.2f, 0.6f * i, 14f, time * 1.4f, -time * 0.09f, 0.16f, time + 40f);
        texTube(tex(cp, BeamRenderLayers.TEX_HALO), m, b, 3.4f * B * throb, 1f, 0.25f, 0.18f, 0.6f * i, 16f, time * 0.8f, time * 0.03f, 0.22f, time);
        texTube(tex(cp, BeamRenderLayers.TEX_HALO), m, b, 4.6f * B, 0.9f, 0.15f, 0.1f, 0.3f * i, 22f, time * 0.5f, -time * 0.02f, 0.28f, time + 7f);
        glowTube(glow(cp), m, b, 6.0f * B, 1f, 0.06f, 0.02f, 0.12f * i, time);
        glowTube(glow(cp), m, b, 9.5f * B, 0.9f, 0.03f, 0.01f, 0.05f * i, time);
        glowTube(glow(cp), m, b, 15f * B, 0.8f, 0.02f, 0.01f, 0.022f * i, time);

        // Two counter-rotating sets of helix ribbons.
        helix(tex(cp, BeamRenderLayers.TEX_HELIX), m, b, 2.6f * B, 1.0f * B, 0.22f, time * 0.45f, 1f, 0.6f, 0.5f, 0.85f * i, time);
        helix(tex(cp, BeamRenderLayers.TEX_HELIX), m, b, 3.6f * B, 0.6f * B, -0.13f, -time * 0.3f, 1f, 0.35f, 0.25f, 0.55f * i, time + 20f);

        // Lightning crawling around the beam, re-rolled every 2 ticks.
        Random rand = Random.create((long) (time / 2) * 977L + 13L);
        int arcs = (int) (6 + 14 * i);
        for (int k = 0; k < arcs; k++) {
            float d0 = rand.nextFloat() * b.length;
            float d1 = Math.min(b.length, d0 + 4f + rand.nextFloat() * 14f);
            float a0 = rand.nextFloat() * MathHelper.TAU, a1 = a0 + (rand.nextFloat() - 0.5f) * 2.5f;
            float r0 = (2.2f + rand.nextFloat() * 3f) * B, r1 = (2.2f + rand.nextFloat() * 3f) * B;
            Vector3f s0 = new Vector3f(b.dir).mul(d0).add(b.a)
                    .add(new Vector3f(b.u).mul(MathHelper.cos(a0) * r0)).add(new Vector3f(b.v).mul(MathHelper.sin(a0) * r0));
            Vector3f s1 = new Vector3f(b.dir).mul(d1).add(b.a)
                    .add(new Vector3f(b.u).mul(MathHelper.cos(a1) * r1)).add(new Vector3f(b.v).mul(MathHelper.sin(a1) * r1));
            arc(glow(cp), m, s0, s1, rand, 0.07f + 0.05f * i, 1f, 0.8f, 0.75f, 0.9f * i);
        }

        // Accelerator rings racing down the beam.
        float spacing = 6f;
        float offset = (time * 5f) % spacing;
        for (float d = offset; d < b.length; d += spacing) {
            Vector3f c = new Vector3f(b.dir).mul(d).add(b.a);
            texRing(tex(cp, BeamRenderLayers.TEX_SHOCKWAVE), m, c, b.dir, 2.6f * B, 4.4f * B, 1f, 0.5f, 0.4f, 0.65f * i, time * 0.03f + d * 0.01f);
        }

        // Muzzle: fireball plus shock collars blasting off the end of the barrel.
        texSphere(tex(cp, BeamRenderLayers.TEX_PLASMA), m, b.a, 2.8f * B, 1f, 0.6f, 0.5f, 0.9f * i, time * 0.07f, time * 0.04f);
        glowSphere(glow(cp), m, b.a, 1.8f * B, 1f, 0.97f, 0.92f, 0.95f * i);
        glowSphere(glow(cp), m, b.a, 5.5f * B, 1f, 0.15f, 0.05f, 0.25f * i);
        for (int k = 0; k < 3; k++) {
            float q = p * 3.2f - k * 0.18f;
            if (q <= 0f || q >= 1f) continue;
            float rr = 2f + q * 26f;
            Vector3f c = new Vector3f(b.dir).mul(q * 6f).add(b.a);
            texRing(tex(cp, BeamRenderLayers.TEX_SHOCKWAVE), m, c, b.dir, rr, rr + 2.5f + q * 4f, 1f, 0.6f, 0.5f, 0.9f * (1f - q), time * 0.02f);
        }

        // Impact: fireball, expanding blast dome and rolling ground shockwaves.
        texSphere(tex(cp, BeamRenderLayers.TEX_PLASMA), m, b.b, 6.5f * B * throb, 1f, 0.55f, 0.45f, 0.95f * i, time * 0.06f, -time * 0.05f);
        glowSphere(glow(cp), m, b.b, 4.2f * B, 1f, 0.97f, 0.92f, 0.95f * i);
        glowSphere(glow(cp), m, b.b, 11f * B, 1f, 0.2f, 0.06f, 0.3f * i);
        glowSphere(glow(cp), m, b.b, 20f * B, 1f, 0.06f, 0.02f, 0.10f * i);
        float dome = Math.min(1f, p * 2.2f);
        texSphere(tex(cp, BeamRenderLayers.TEX_HALO), m, b.b, 4f + dome * 22f, 1f, 0.35f, 0.25f, 0.55f * (1f - dome), time * 0.03f, time * 0.02f);
        Vector3f ground = new Vector3f(b.b).add(0, -0.4f, 0);
        Vector3f up = new Vector3f(0, 1, 0);
        float w1 = 4f + p * 90f;
        texRing(tex(cp, BeamRenderLayers.TEX_SHOCKWAVE), m, ground, up, w1, w1 + 8f, 1f, 0.55f, 0.45f, 0.95f * (1f - p), time * 0.01f);
        if (p > 0.1f) {
            float q = (p - 0.1f) / 0.9f;
            float w2 = 3f + q * 60f;
            texRing(tex(cp, BeamRenderLayers.TEX_SHOCKWAVE), m, new Vector3f(ground).add(0, 0.2f, 0), up, w2, w2 + 5f, 1f, 0.4f, 0.3f, 0.8f * (1f - q), -time * 0.01f);
        }
        if (p > 0.25f) {
            float q = (p - 0.25f) / 0.75f;
            float w3 = 2f + q * 36f;
            texRing(tex(cp, BeamRenderLayers.TEX_SHOCKWAVE), m, new Vector3f(ground).add(0, 0.4f, 0), up, w3, w3 + 3f, 1f, 0.6f, 0.45f, 0.7f * (1f - q), time * 0.015f);
        }
    }

    /*
     * Buffers are fetched right before each primitive. With the entity VertexConsumerProvider,
     * requesting a different RenderLayer finishes the previous layer's buffer, so a consumer
     * must never be reused after another layer has been requested.
     */
    private static VertexConsumer tex(VertexConsumerProvider cp, Identifier texture) {
        return cp.getBuffer(BeamRenderLayers.textured(texture));
    }

    private static VertexConsumer glow(VertexConsumerProvider cp) {
        return cp.getBuffer(BeamRenderLayers.GLOW);
    }

    // =========================================================================================
    // Geometry primitives
    // =========================================================================================

    /** Beam frame: endpoints, unit direction and two perpendicular unit vectors. */
    private static final class Beam {
        final Vector3f a, b, dir, u = new Vector3f(), v = new Vector3f();
        final float length;

        Beam(Vector3f a, Vector3f b) {
            this.a = a;
            this.b = b;
            Vector3f d = new Vector3f(b).sub(a);
            this.length = d.length();
            this.dir = length > 1e-4f ? d.div(length) : new Vector3f(0, 1, 0);
            basis(dir, u, v);
        }
    }

    private static int segments(float length) {
        return MathHelper.clamp((int) (length / 3f), 2, MAX_SEGMENTS);
    }

    /** Radius multiplier along a tube: soft taper at the muzzle, flare at the far end, wobble. */
    private static float profile(float d, float length, float wobble, float time) {
        float taper = MathHelper.clamp(d / 2.0f, 0.3f, 1f);
        float flare = 1f + 0.25f * MathHelper.clamp((d - (length - 4f)) / 4f, 0f, 1f);
        return taper * flare * (1f + wobble * MathHelper.sin(d * 0.7f - time * 1.3f));
    }

    /**
     * Textured tube. U wraps once around the circumference (+ uOffset, which twists the pattern),
     * V = distance / vScale - vScroll, so the texture streams along the beam.
     */
    private static void texTube(VertexConsumer vc, Matrix4f m, Beam bm, float radius, float r, float g, float b,
                                float alpha, float vScale, float vScroll, float uOffset, float wobble, float time) {
        if (alpha <= 0.003f || radius <= 0f) return;
        int segs = segments(bm.length);
        float[] cos = new float[SIDES + 1], sin = new float[SIDES + 1];
        for (int k = 0; k <= SIDES; k++) {
            double ang = k * (Math.PI * 2.0 / SIDES);
            cos[k] = (float) Math.cos(ang);
            sin[k] = (float) Math.sin(ang);
        }
        Vector3f c0 = new Vector3f(), c1 = new Vector3f();
        for (int s = 0; s < segs; s++) {
            float d0 = bm.length * s / segs, d1 = bm.length * (s + 1) / segs;
            float r0 = radius * profile(d0, bm.length, wobble, time);
            float r1 = radius * profile(d1, bm.length, wobble, time);
            c0.set(bm.dir).mul(d0).add(bm.a);
            c1.set(bm.dir).mul(d1).add(bm.a);
            float v0 = d0 / vScale - vScroll, v1 = d1 / vScale - vScroll;
            for (int k = 0; k < SIDES; k++) {
                float u0 = (float) k / SIDES + uOffset, u1 = (float) (k + 1) / SIDES + uOffset;
                texVert(vc, m, c0, bm.u, bm.v, cos[k], sin[k], r0, r, g, b, alpha, u0, v0);
                texVert(vc, m, c0, bm.u, bm.v, cos[k + 1], sin[k + 1], r0, r, g, b, alpha, u1, v0);
                texVert(vc, m, c1, bm.u, bm.v, cos[k + 1], sin[k + 1], r1, r, g, b, alpha, u1, v1);
                texVert(vc, m, c1, bm.u, bm.v, cos[k], sin[k], r1, r, g, b, alpha, u0, v1);
            }
        }
    }

    /** Untextured glow tube (soft bloom shells). */
    private static void glowTube(VertexConsumer vc, Matrix4f m, Beam bm, float radius, float r, float g, float b,
                                 float alpha, float time) {
        if (alpha <= 0.003f || radius <= 0f) return;
        int segs = Math.max(2, segments(bm.length) / 2);
        Vector3f c0 = new Vector3f(), c1 = new Vector3f();
        for (int s = 0; s < segs; s++) {
            float d0 = bm.length * s / segs, d1 = bm.length * (s + 1) / segs;
            float r0 = radius * profile(d0, bm.length, 0.06f, time);
            float r1 = radius * profile(d1, bm.length, 0.06f, time);
            c0.set(bm.dir).mul(d0).add(bm.a);
            c1.set(bm.dir).mul(d1).add(bm.a);
            for (int k = 0; k < SIDES; k++) {
                double a0 = k * (Math.PI * 2.0 / SIDES), a1 = (k + 1) * (Math.PI * 2.0 / SIDES);
                float ca0 = (float) Math.cos(a0), sa0 = (float) Math.sin(a0), ca1 = (float) Math.cos(a1), sa1 = (float) Math.sin(a1);
                glowVert(vc, m, c0, bm.u, bm.v, ca0, sa0, r0, r, g, b, alpha);
                glowVert(vc, m, c0, bm.u, bm.v, ca1, sa1, r0, r, g, b, alpha);
                glowVert(vc, m, c1, bm.u, bm.v, ca1, sa1, r1, r, g, b, alpha);
                glowVert(vc, m, c1, bm.u, bm.v, ca0, sa0, r1, r, g, b, alpha);
            }
        }
    }

    /**
     * Three textured ribbons spiralling around the beam. Each ribbon is a strip lying on the
     * cylinder of radius {@code radius}: at angle theta(d) = d * twist + spin + k*120deg, the
     * ribbon spans +/- width/2 along the circle's tangent.
     */
    private static void helix(VertexConsumer vc, Matrix4f m, Beam bm, float radius, float width, float twist, float spin,
                              float r, float g, float b, float alpha, float time) {
        if (alpha <= 0.003f) return;
        int segs = Math.min(MAX_SEGMENTS * 2, Math.max(8, (int) (bm.length / 1.2f)));
        Vector3f c = new Vector3f(), p0 = new Vector3f(), p1 = new Vector3f(), q0 = new Vector3f(), q1 = new Vector3f();
        for (int k = 0; k < 3; k++) {
            float phase = k * MathHelper.TAU / 3f;
            for (int s = 0; s < segs; s++) {
                float d0 = bm.length * s / segs, d1 = bm.length * (s + 1) / segs;
                float t0 = d0 * twist + spin + phase, t1 = d1 * twist + spin + phase;
                float rr0 = radius * profile(d0, bm.length, 0f, time), rr1 = radius * profile(d1, bm.length, 0f, time);
                ribbonEdge(c.set(bm.dir).mul(d0).add(bm.a), bm, t0, rr0, width * 0.5f, p0, p1);
                ribbonEdge(c.set(bm.dir).mul(d1).add(bm.a), bm, t1, rr1, width * 0.5f, q0, q1);
                float v0 = d0 / 6f - time * 0.15f, v1 = d1 / 6f - time * 0.15f;
                texVertRaw(vc, m, p0, r, g, b, alpha, 0f, v0);
                texVertRaw(vc, m, p1, r, g, b, alpha, 1f, v0);
                texVertRaw(vc, m, q1, r, g, b, alpha, 1f, v1);
                texVertRaw(vc, m, q0, r, g, b, alpha, 0f, v1);
            }
        }
    }

    private static void ribbonEdge(Vector3f center, Beam bm, float theta, float radius, float halfWidth,
                                   Vector3f outA, Vector3f outB) {
        float ct = MathHelper.cos(theta), st = MathHelper.sin(theta);
        // Point on the circle and the circle's tangent at that point.
        float px = (bm.u.x * ct + bm.v.x * st) * radius, py = (bm.u.y * ct + bm.v.y * st) * radius, pz = (bm.u.z * ct + bm.v.z * st) * radius;
        float tx = -bm.u.x * st + bm.v.x * ct, ty = -bm.u.y * st + bm.v.y * ct, tz = -bm.u.z * st + bm.v.z * ct;
        outA.set(center.x + px - tx * halfWidth, center.y + py - ty * halfWidth, center.z + pz - tz * halfWidth);
        outB.set(center.x + px + tx * halfWidth, center.y + py + ty * halfWidth, center.z + pz + tz * halfWidth);
    }

    /** Textured flat annulus: U around (+ uOffset rotates it), V from inner (0) to outer (1) edge. */
    private static void texRing(VertexConsumer vc, Matrix4f m, Vector3f c, Vector3f normal, float inner, float outer,
                                float r, float g, float b, float alpha, float uOffset) {
        if (alpha <= 0.003f || outer <= 0f) return;
        Vector3f n = new Vector3f(normal).normalize();
        Vector3f u = new Vector3f(), v = new Vector3f();
        basis(n, u, v);
        final int sides = 48;
        for (int k = 0; k < sides; k++) {
            double a0 = 2 * Math.PI * k / sides, a1 = 2 * Math.PI * (k + 1) / sides;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float u0 = (float) k / sides * 4f + uOffset, u1 = (float) (k + 1) / sides * 4f + uOffset;
            texVert(vc, m, c, u, v, c0, s0, inner, r, g, b, alpha, u0, 0f);
            texVert(vc, m, c, u, v, c1, s1, inner, r, g, b, alpha, u1, 0f);
            texVert(vc, m, c, u, v, c1, s1, outer, r, g, b, alpha, u1, 1f);
            texVert(vc, m, c, u, v, c0, s0, outer, r, g, b, alpha, u0, 1f);
        }
    }

    /** Textured UV sphere (U = longitude, V = latitude), offsets animate the plasma. */
    private static void texSphere(VertexConsumer vc, Matrix4f m, Vector3f c, float radius, float r, float g, float b,
                                  float alpha, float uOffset, float vOffset) {
        if (alpha <= 0.003f || radius <= 0f) return;
        final int lat = 10, lon = 18;
        for (int i = 0; i < lat; i++) {
            double th0 = Math.PI * i / lat - Math.PI / 2, th1 = Math.PI * (i + 1) / lat - Math.PI / 2;
            float v0 = (float) i / lat + vOffset, v1 = (float) (i + 1) / lat + vOffset;
            for (int j = 0; j < lon; j++) {
                double ph0 = 2 * Math.PI * j / lon, ph1 = 2 * Math.PI * (j + 1) / lon;
                float u0 = (float) j / lon * 2f + uOffset, u1 = (float) (j + 1) / lon * 2f + uOffset;
                spherePoint(vc, m, c, radius, th0, ph0, r, g, b, alpha, u0, v0, true);
                spherePoint(vc, m, c, radius, th0, ph1, r, g, b, alpha, u1, v0, true);
                spherePoint(vc, m, c, radius, th1, ph1, r, g, b, alpha, u1, v1, true);
                spherePoint(vc, m, c, radius, th1, ph0, r, g, b, alpha, u0, v1, true);
            }
        }
    }

    /** Untextured glow sphere. */
    private static void glowSphere(VertexConsumer vc, Matrix4f m, Vector3f c, float radius, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f || radius <= 0f) return;
        final int lat = 8, lon = 14;
        for (int i = 0; i < lat; i++) {
            double th0 = Math.PI * i / lat - Math.PI / 2, th1 = Math.PI * (i + 1) / lat - Math.PI / 2;
            for (int j = 0; j < lon; j++) {
                double ph0 = 2 * Math.PI * j / lon, ph1 = 2 * Math.PI * (j + 1) / lon;
                spherePoint(vc, m, c, radius, th0, ph0, r, g, b, alpha, 0, 0, false);
                spherePoint(vc, m, c, radius, th0, ph1, r, g, b, alpha, 0, 0, false);
                spherePoint(vc, m, c, radius, th1, ph1, r, g, b, alpha, 0, 0, false);
                spherePoint(vc, m, c, radius, th1, ph0, r, g, b, alpha, 0, 0, false);
            }
        }
    }

    private static void spherePoint(VertexConsumer vc, Matrix4f m, Vector3f c, float radius, double theta, double phi,
                                    float r, float g, float b, float a, float u, float v, boolean textured) {
        float ct = (float) Math.cos(theta);
        float x = c.x + radius * ct * (float) Math.cos(phi);
        float y = c.y + radius * (float) Math.sin(theta);
        float z = c.z + radius * ct * (float) Math.sin(phi);
        if (textured) {
            vc.vertex(m, x, y, z).color(r, g, b, a).texture(u, v).light(LIGHT).normal(0, 1, 0).next();
        } else {
            vc.vertex(m, x, y, z).color(r, g, b, a).next();
        }
    }

    /**
     * A jagged lightning bolt from start to end: the straight path is split into segments whose
     * interior points are displaced randomly perpendicular to it; each segment is drawn as two
     * crossed thin quads so it reads from any angle.
     */
    private static void arc(VertexConsumer vc, Matrix4f m, Vector3f start, Vector3f end, Random rand, float thickness,
                            float r, float g, float b, float alpha) {
        final int n = 9;
        Vector3f d = new Vector3f(end).sub(start);
        float len = d.length();
        if (len < 0.2f) return;
        Vector3f dir = new Vector3f(d).div(len);
        Vector3f u = new Vector3f(), v = new Vector3f();
        basis(dir, u, v);
        Vector3f[] pts = new Vector3f[n + 1];
        for (int i = 0; i <= n; i++) {
            float t = (float) i / n;
            Vector3f p = new Vector3f(d).mul(t).add(start);
            if (i > 0 && i < n) {
                float jitter = len * 0.12f;
                p.add(new Vector3f(u).mul((rand.nextFloat() - 0.5f) * jitter)).add(new Vector3f(v).mul((rand.nextFloat() - 0.5f) * jitter));
            }
            pts[i] = p;
        }
        for (int i = 0; i < n; i++) {
            Vector3f a = pts[i], bb = pts[i + 1];
            for (Vector3f side : new Vector3f[]{u, v}) {
                Vector3f o = new Vector3f(side).mul(thickness);
                quad(vc, m, new Vector3f(a).sub(o), new Vector3f(a).add(o), new Vector3f(bb).add(o), new Vector3f(bb).sub(o), r, g, b, alpha);
                Vector3f o2 = new Vector3f(side).mul(thickness * 4f);
                quad(vc, m, new Vector3f(a).sub(o2), new Vector3f(a).add(o2), new Vector3f(bb).add(o2), new Vector3f(bb).sub(o2), r, 0.2f, 0.15f, alpha * 0.25f);
            }
        }
    }

    // =========================================================================================
    // Vertex helpers
    // =========================================================================================

    private static void texVert(VertexConsumer vc, Matrix4f m, Vector3f center, Vector3f u, Vector3f v,
                                float cos, float sin, float radius, float r, float g, float b, float a, float tu, float tv) {
        vc.vertex(m,
                center.x + (u.x * cos + v.x * sin) * radius,
                center.y + (u.y * cos + v.y * sin) * radius,
                center.z + (u.z * cos + v.z * sin) * radius)
                .color(r, g, b, a).texture(tu, tv).light(LIGHT).normal(0, 1, 0).next();
    }

    private static void texVertRaw(VertexConsumer vc, Matrix4f m, Vector3f p, float r, float g, float b, float a, float tu, float tv) {
        vc.vertex(m, p.x, p.y, p.z).color(r, g, b, a).texture(tu, tv).light(LIGHT).normal(0, 1, 0).next();
    }

    private static void glowVert(VertexConsumer vc, Matrix4f m, Vector3f center, Vector3f u, Vector3f v,
                                 float cos, float sin, float radius, float r, float g, float b, float a) {
        vc.vertex(m,
                center.x + (u.x * cos + v.x * sin) * radius,
                center.y + (u.y * cos + v.y * sin) * radius,
                center.z + (u.z * cos + v.z * sin) * radius).color(r, g, b, a).next();
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3,
                             float r, float g, float b, float a) {
        vc.vertex(m, p0.x, p0.y, p0.z).color(r, g, b, a).next();
        vc.vertex(m, p1.x, p1.y, p1.z).color(r, g, b, a).next();
        vc.vertex(m, p2.x, p2.y, p2.z).color(r, g, b, a).next();
        vc.vertex(m, p3.x, p3.y, p3.z).color(r, g, b, a).next();
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
