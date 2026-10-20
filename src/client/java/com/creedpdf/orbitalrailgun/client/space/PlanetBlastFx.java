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
 *   <li>lava jets erupt from the fractures as the core gives way;</li>
 *   <li>detonation: a blinding flash with an anamorphic lens streak, the crust breaks into ~420
 *       tumbling fragments with molten faces and glowing trails, a roiling fireball of hundreds of
 *       cloud sprites cooling along a black-body ramp into dark sun-lit smoke, thousands of
 *       velocity-stretched ejecta streaks, plasma shells and shockwave rings;</li>
 *   <li>forever after: a smouldering cinder inside a debris disk that flattens and shears into rings.</li>
 * </ol>
 */
public final class PlanetBlastFx {
    private PlanetBlastFx() {}

    private static final int LIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    public static final Identifier CRACKS = BodyDraw.tex("planet_cracks");
    public static final Identifier PLASMA = BodyDraw.tex("plasma");
    public static final Identifier DUST = BodyDraw.tex("dust");
    public static final Identifier FIRE = BodyDraw.tex("fire_cloud");
    public static final Identifier SMOKE = BodyDraw.tex("smoke");
    public static final Identifier STREAK = BodyDraw.tex("streak");
    public static final Identifier FLARE = BodyDraw.tex("flare_streak");
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
            jets(cp, m, b, c, r * scale, impact, e, heat, collapse, alpha);
            return;
        }
        float d = e - Blast.DETONATE;
        Vector3f ringN = new Vector3f(0.25f, 1f, -0.18f).rotateY(b.ordinal()).normalize();

        // opaque fragments first (they write depth), then alpha-blended smoke, then additive light on top
        shards(cp, m, b, c, r, L, time, d, alpha);
        disk(cp, m, b, c, r, L, ringN, d, alpha);
        smoke(cp, m, b, c, r, L, d, alpha);
        shardTrails(cp, m, b, c, r, d, alpha);
        fireball(cp, m, b, c, r, d, alpha);
        ejecta(cp, m, b, c, r, L, d, alpha);

        VertexConsumer g = cp.getBuffer(BeamRenderLayers.GLOW);
        // flash
        if (d < 90) {
            float f = d / 90f;
            glowBall(g, m, c, r * (1.5f + 7f * f), 1f, 1f, 0.97f, (1f - f) * (1f - f) * alpha);
            glowBall(g, m, c, r * (3f + 16f * f), 1f, 0.85f, 0.6f, 0.45f * (1f - f) * alpha);
            glowBall(g, m, c, r * (6f + 30f * f), 1f, 0.6f, 0.3f, 0.18f * (1f - f) * alpha);
        }
        // the exposed, dying core: white-hot, then a smouldering cinder that never quite goes out
        {
            float f = MathHelper.clamp(d / 900f, 0f, 1f);
            float[] col = new float[3];
            blackbody(1f - 0.8f * f, col);
            float throb = 0.85f + 0.15f * MathHelper.sin(d * 0.21f) * MathHelper.sin(d * 0.077f);
            glowBall(g, m, c, r * 0.5f * (1f - 0.6f * f), col[0], col[1], col[2], (1f - 0.75f * f) * throb * alpha);
            glowBall(g, m, c, r * 1.3f * (1f - 0.4f * f), col[0], col[1] * 0.6f, col[2] * 0.4f, 0.4f * (1f - 0.8f * f) * throb * alpha);
        }
        // plasma shells
        if (d < 600) {
            float f = d / 600f;
            float ease = 1f - (float) Math.exp(-d / 150f);
            float rr = r * (1.1f + 7.5f * ease);
            float a = (1f - f) * (1f - f) * alpha;
            VertexConsumer pl = cp.getBuffer(BeamRenderLayers.textured(PLASMA));
            Quaternionf q = new Quaternionf().rotationY(d * 0.002f).rotateX(0.4f);
            BodyDraw.sphere(pl, m, c, rr, q, com.creedpdf.orbitalrailgun.client.config.Quality.segs(64, 16), com.creedpdf.orbitalrailgun.client.config.Quality.segs(128, 32), (n, out) -> {
                out[0] = a; out[1] = a * (0.75f - 0.35f * f); out[2] = a * (0.45f - 0.35f * f); out[3] = a;
            });
            Quaternionf q2 = new Quaternionf().rotationY(-d * 0.003f).rotateZ(0.7f);
            float rr2 = rr * 0.82f;
            BodyDraw.sphere(pl, m, c, rr2, q2, com.creedpdf.orbitalrailgun.client.config.Quality.segs(56, 14), com.creedpdf.orbitalrailgun.client.config.Quality.segs(112, 28), (n, out) -> {
                out[0] = a * 0.9f; out[1] = a * 0.9f * (0.9f - 0.6f * f); out[2] = a * 0.9f * (0.7f - 0.6f * f); out[3] = a;
            });
        }
        // shockwave rings racing out along the equator
        if (d < 800) {
            float f = d / 800f;
            float ease = 1f - (1f - f) * (1f - f);
            VertexConsumer sh = cp.getBuffer(BeamRenderLayers.textured(SHOCK));
            float w = r * (1.3f + 30f * ease);
            ring(sh, m, c, ringN, w, w * 1.18f, 1f, 0.85f, 0.65f, (1f - f) * alpha);
            float w2 = r * (1.2f + 20f * ease);
            ring(sh, m, c, ringN, w2, w2 * 1.05f, 1f, 1f, 1f, 0.7f * (1f - f) * alpha);
            if (d < 320) {
                float f3 = d / 320f;
                float w3 = r * (1.1f + 10f * f3);
                ring(sh, m, c, new Vector3f(ringN).rotateX(0.9f), w3, w3 * 1.1f, 0.6f, 0.75f, 1f, 0.6f * (1f - f3) * alpha);
                ring(sh, m, c, new Vector3f(ringN).rotateZ(-1.1f), w3 * 0.8f, w3 * 0.88f, 1f, 0.7f, 0.4f, 0.45f * (1f - f3) * alpha);
            }
        }
        // the lens: anamorphic streak + starburst at the flash
        flare(cp, m, c, r, d, alpha);
    }

    // ---- colour ------------------------------------------------------------------------------------

    /** Black-body-ish ramp: 0 black, 0.25 deep red, 0.5 orange, 0.75 yellow, 1 blue-white. */
    static void blackbody(float t, float[] out) {
        t = MathHelper.clamp(t, 0f, 1f);
        float[][] k = {{0, 0, 0, 0}, {0.25f, 0.55f, 0.06f, 0.02f}, {0.5f, 1f, 0.38f, 0.07f}, {0.75f, 1f, 0.78f, 0.38f},
                {0.92f, 1f, 0.95f, 0.82f}, {1f, 0.92f, 0.95f, 1f}};
        for (int i = 1; i < k.length; i++) {
            if (t <= k[i][0]) {
                float f = (t - k[i - 1][0]) / (k[i][0] - k[i - 1][0]);
                for (int j = 0; j < 3; j++) out[j] = MathHelper.lerp(f, k[i - 1][j + 1], k[i][j + 1]);
                return;
            }
        }
        out[0] = k[k.length - 1][1]; out[1] = k[k.length - 1][2]; out[2] = k[k.length - 1][3];
    }

    // ---- pre-detonation eruptions ------------------------------------------------------------------

    /** Lava jets and spray bursting out of the fractures while the core gives way. */
    static void jets(VertexConsumerProvider cp, Matrix4f m, Body b, Vector3f c, float r, Vector3f impact, float e, float heat,
                     float collapse, float alpha) {
        if (heat < 0.25f) return;
        Random rand = Random.create(b.ordinal() * 31337L + 5);
        Vector3f imp = new Vector3f(impact).normalize();
        float spread = Math.min(1f, heat) * 3.4f;
        VertexConsumer vc = cp.getBuffer(BeamRenderLayers.textured(STREAK));
        float[] col = new float[3];
        int n = com.creedpdf.orbitalrailgun.client.config.Quality.scale(260, 60);
        for (int i = 0; i < n; i++) {
            rand.setSeed(b.ordinal() * 31337L + i * 0x9E3779B97F4A7C15L);
            Vector3f dir = randomDir(rand);
            float ang = (float) Math.acos(MathHelper.clamp(dir.dot(imp), -1f, 1f));
            float born = rand.nextFloat();
            float period = 18f + rand.nextFloat() * 30f;
            float phase = rand.nextFloat() * period;
            float speed = 0.6f + rand.nextFloat() * 1.4f;
            if (ang > spread * (0.6f + 0.4f * born)) continue;
            float local = ((e + phase) % period) / period;          // each vent pulses
            float burst = MathHelper.sin(local * MathHelper.PI);
            float len = r * (0.04f + 0.22f * heat + 0.9f * collapse) * speed * burst;
            if (len <= 0) continue;
            Vector3f base = new Vector3f(dir).mul(r * 1.0f).add(c);
            Vector3f tilt = new Vector3f(rand.nextFloat() - 0.5f, rand.nextFloat() - 0.5f, rand.nextFloat() - 0.5f).mul(0.35f);
            Vector3f out = new Vector3f(dir).add(tilt).normalize();
            blackbody(0.75f + 0.25f * collapse - 0.3f * local, col);
            float a = alpha * burst * Math.min(1f, (heat - 0.25f) * 2f);
            streak(vc, m, base, out, len, r * (0.012f + 0.02f * collapse) * (0.6f + speed * 0.4f), col[0], col[1], col[2], a);
            // spray falling back as glowing droplets
            for (int k = 0; k < 3; k++) {
                Vector3f sd = new Vector3f(out).add(new Vector3f(rand.nextFloat() - 0.5f, rand.nextFloat() - 0.5f, rand.nextFloat() - 0.5f)).normalize();
                float sl = len * (0.3f + 0.5f * rand.nextFloat());
                Vector3f sp = new Vector3f(base).add(new Vector3f(out).mul(len * local * 0.6f));
                streak(vc, m, sp, sd, sl * 0.4f, r * 0.006f, col[0], col[1] * 0.8f, col[2] * 0.6f, a * 0.6f);
            }
        }
    }

    // ---- detonation layers -------------------------------------------------------------------------

    /** Roiling fire: hundreds of camera-facing cloud sprites, white-hot at first and cooling through the black-body ramp. */
    private static void fireball(VertexConsumerProvider cp, Matrix4f m, Body b, Vector3f c, float r, float d, float alpha) {
        if (d > 1100) return;
        Random rand = Random.create(b.ordinal() * 7121L + 3);
        VertexConsumer vc = cp.getBuffer(BeamRenderLayers.textured(FIRE));
        float cover = (float) Math.pow(1f / Math.max(0.2f, com.creedpdf.orbitalrailgun.client.config.Quality.fx()), 0.3);
        float ease = 1f - (float) Math.exp(-d / 130f);
        float[] col = new float[3];
        float in = Math.min(1f, d / 5f);
        for (int i = 0, N = com.creedpdf.orbitalrailgun.client.config.Quality.scale(420, 90); i < N; i++) {
            rand.setSeed(b.ordinal() * 7919L + 420L + i * 0x9E3779B97F4A7C15L);
            Vector3f dir = randomDir(rand);
            float s = rand.nextFloat();
            float life = 220f + rand.nextFloat() * 520f;
            float core = rand.nextFloat() < 0.25f ? 0.35f : 1f;     // a quarter hang around the core
            float dist = r * (0.15f + 5.2f * s * core * ease + d * 0.0006f * s);
            Vector3f turb = new Vector3f(MathHelper.sin(d * 0.011f + i), MathHelper.sin(d * 0.009f + i * 1.7f), MathHelper.cos(d * 0.013f + i * 0.6f)).mul(r * 0.12f * ease);
            Vector3f p = new Vector3f(dir).mul(dist).add(c).add(turb);
            float size = r * (0.45f + 0.95f * rand.nextFloat()) * (0.5f + 2.6f * ease) * (core < 1 ? 0.8f : 1f) * cover;
            float t = 1.08f - d / life - 0.2f * s;
            if (t <= 0.02f) continue;
            blackbody(t, col);
            float bright = 0.6f + 0.6f * t;
            float a = in * Math.min(1f, t * 3f) * 0.55f * alpha;
            float rot = rand.nextFloat() * MathHelper.TAU + d * 0.003f * (rand.nextFloat() - 0.5f);
            billboard(vc, m, p, size, rot, col[0] * bright, col[1] * bright, col[2] * bright, a);
        }
    }

    /** The smoke and pulverised rock left behind: dark, sun-lit, under-lit by the fire for a while. */
    private static void smoke(VertexConsumerProvider cp, Matrix4f m, Body b, Vector3f c, float r, Vector3f L, float d, float alpha) {
        if (d < 25) return;
        Random rand = Random.create(b.ordinal() * 6007L + 9);
        VertexConsumer vc = cp.getBuffer(BeamRenderLayers.translucent(SMOKE));
        float cover = (float) Math.pow(1f / Math.max(0.2f, com.creedpdf.orbitalrailgun.client.config.Quality.fx()), 0.3);
        Vector3f l = new Vector3f(L).normalize();
        float ease = 1f - (float) Math.exp(-d / 480f);
        float appear = MathHelper.clamp((d - 25) / 220f, 0f, 1f);
        float thin = 0.3f + 0.7f * (float) Math.exp(-d / 5000f);
        float[] col = new float[3];
        float under = MathHelper.clamp(1f - d / 700f, 0f, 1f);
        for (int i = 0, N = com.creedpdf.orbitalrailgun.client.config.Quality.scale(320, 70); i < N; i++) {
            rand.setSeed(b.ordinal() * 7919L + 320L + i * 0x9E3779B97F4A7C15L);
            Vector3f dir = randomDir(rand);
            float s = 0.25f + 0.75f * rand.nextFloat();
            float dist = r * (0.4f + 6.2f * s * ease + d * 0.0003f * s);
            Vector3f p = new Vector3f(dir).mul(dist).add(c);
            float size = r * (0.8f + 1.6f * rand.nextFloat()) * (0.7f + 2.2f * ease) * cover;
            float lit = 0.06f + 0.42f * Math.max(0f, dir.dot(l)) ;
            blackbody(0.55f * under * (1f - s * 0.5f), col);
            float rr = lit * 0.82f + col[0] * 0.55f, gg = lit * 0.74f + col[1] * 0.45f, bb = lit * 0.66f + col[2] * 0.35f;
            float a = 0.5f * appear * thin * alpha * (0.6f + 0.4f * rand.nextFloat());
            float rot = rand.nextFloat() * MathHelper.TAU + d * 0.0012f * (rand.nextFloat() - 0.5f);
            billboard(vc, m, p, size, rot, rr, gg, bb, a);
        }
    }

    /** Thousands of glowing ejecta streaks, stretched along their velocity, cooling as they fly. */
    private static void ejecta(VertexConsumerProvider cp, Matrix4f m, Body b, Vector3f c, float r, Vector3f L, float d, float alpha) {
        Random rand = Random.create(b.ordinal() * 9973L + 1);
        VertexConsumer vc = cp.getBuffer(BeamRenderLayers.textured(STREAK));
        Vector3f l = new Vector3f(L).normalize();
        float[] col = new float[3];
        float in = Math.min(1f, d / 3f);
        for (int i = 0, N = com.creedpdf.orbitalrailgun.client.config.Quality.scale(3600, 500); i < N; i++) {
            rand.setSeed(b.ordinal() * 7919L + 3600L + i * 0x9E3779B97F4A7C15L);
            Vector3f dir = randomDir(rand);
            float u = rand.nextFloat();
            float v = 0.4f + u * u * 4.2f;                              // a few very fast ones
            float travel = 9f * (1f - (float) Math.exp(-d / (260f + 200f * rand.nextFloat()))) + d * 0.0016f;
            Vector3f p = new Vector3f(dir).mul(r * (1f + v * travel)).add(c);
            float speedNow = v * ((float) Math.exp(-d / 220f) * 4f + 0.25f);
            float len = r * 0.06f * speedNow;
            float life = 380f + rand.nextFloat() * 900f;
            float t = 1.05f - d / life;
            float sun = Math.max(0f, dir.dot(l));
            float a;
            if (t > 0.05f) {
                blackbody(t, col);
                a = in * Math.min(1f, t * 2.5f) * alpha;
            } else {
                // cold rock: a faint sun-lit glint
                col[0] = col[1] = col[2] = 0.5f;
                a = 0.12f * sun * alpha;
            }
            if (a < 0.004f) continue;
            streak(vc, m, p, dir, Math.max(len, r * 0.02f), r * 0.008f * (0.5f + rand.nextFloat()), col[0], col[1], col[2], a);
        }
    }

    /** What is left: an accretion disk of debris that flattens and slowly shears into rings around the cinder. */
    private static void disk(VertexConsumerProvider cp, Matrix4f m, Body b, Vector3f c, float r, Vector3f L, Vector3f ringN, float d, float alpha) {
        if (d < 150) return;
        Random rand = Random.create(b.ordinal() * 4441L + 7);
        VertexConsumer vc = cp.getBuffer(BeamRenderLayers.translucent(DUST));
        Vector3f n = new Vector3f(ringN).normalize();
        Vector3f u = new Vector3f(n).cross(Math.abs(n.y) < 0.9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0)).normalize();
        Vector3f w = new Vector3f(n).cross(u).normalize();
        Vector3f l = new Vector3f(L).normalize();
        float appear = MathHelper.clamp((d - 150) / 900f, 0f, 1f);
        float flat = (float) Math.exp(-(d - 150) / 2600f);
        for (int i = 0, N = com.creedpdf.orbitalrailgun.client.config.Quality.scale(1800, 300); i < N; i++) {
            rand.setSeed(b.ordinal() * 7919L + 1800L + i * 0x9E3779B97F4A7C15L);
            float rho = r * (1.6f + 10f * (float) Math.pow(rand.nextFloat(), 1.6f));
            float th0 = rand.nextFloat() * MathHelper.TAU;
            float omega = 0.004f * (float) Math.pow(r * 1.6f / rho, 1.5f);   // Keplerian shear
            float th = th0 + omega * d;
            float h = (rand.nextFloat() - 0.5f) * r * 6f * flat + (rand.nextFloat() - 0.5f) * r * 0.25f;
            Vector3f p = new Vector3f(u).mul(MathHelper.cos(th) * rho).add(new Vector3f(w).mul(MathHelper.sin(th) * rho)).add(new Vector3f(n).mul(h)).add(c);
            Vector3f rad = new Vector3f(p).sub(c).normalize();
            float lit = 0.08f + 0.5f * Math.max(0f, rad.dot(l));
            float band = 0.6f + 0.4f * MathHelper.sin(rho / r * 3.1f + 1.3f);
            float size = r * (0.25f + 0.6f * rand.nextFloat());
            float a = 0.28f * appear * band * alpha;
            billboard(vc, m, p, size, rand.nextFloat() * MathHelper.TAU, lit * 0.9f, lit * 0.8f, lit * 0.7f, a);
        }
    }

    /** Glowing tails behind every fragment while it is still hot and fast. */
    private static void shardTrails(VertexConsumerProvider cp, Matrix4f m, Body b, Vector3f c, float r, float d, float alpha) {
        if (d > 1000) return;
        VertexConsumer vc = cp.getBuffer(BeamRenderLayers.textured(STREAK));
        float ease = 1f - (float) Math.exp(-d / 300f);
        float drift = d * 0.00045f;
        float[] col = new float[3];
        float heat = 1f - d / 1000f;
        blackbody(0.35f + 0.6f * heat, col);
        for (Shard s : shards(b)) {
            float dist = r * (s.speed * (0.25f + 6.5f * ease) + drift * s.speed * 40f);
            Vector3f centre = new Vector3f(s.dir).mul(r + dist).add(c);
            float speed = s.speed * ((float) Math.exp(-d / 300f) * 6.5f / 300f + 0.018f) * r * 60f;
            float len = Math.min(r * 3f, speed);
            Vector3f tail = new Vector3f(s.dir).mul(-len).add(centre);
            streak(vc, m, tail, s.dir, len, r * 0.05f * (0.5f + s.speed * 0.4f), col[0], col[1], col[2], heat * heat * 0.7f * alpha);
        }
    }

    /** Anamorphic lens streak and a slowly turning starburst while the flash is blinding. */
    private static void flare(VertexConsumerProvider cp, Matrix4f m, Vector3f c, float r, float d, float alpha) {
        if (d > 160) return;
        float f = d / 160f;
        float a = (1f - f) * (1f - f) * alpha;
        VertexConsumer vc = cp.getBuffer(BeamRenderLayers.textured(FLARE));
        Vector3f view = new Vector3f(c).normalize();
        Vector3f right = new Vector3f(m.m00(), m.m10(), m.m20());
        if (right.lengthSquared() < 1e-6f) right.set(1, 0, 0);
        right.sub(new Vector3f(view).mul(right.dot(view))).normalize();
        Vector3f up = new Vector3f(right).cross(view).normalize();
        float dist = c.length();
        float len = Math.max(r * 60f, dist * 1.4f) * (0.5f + 0.5f * Math.min(1f, d / 8f));
        quad(vc, m, c, new Vector3f(right).mul(len), new Vector3f(up).mul(r * 1.6f), 0.55f, 0.75f, 1f, a);
        quad(vc, m, c, new Vector3f(right).mul(len * 0.45f), new Vector3f(up).mul(r * 0.7f), 1f, 0.95f, 0.9f, a);
        for (int k = 0; k < 6; k++) {
            float th = k * MathHelper.PI / 6f + d * 0.002f + 0.3f;
            Vector3f ax = new Vector3f(right).mul(MathHelper.cos(th)).add(new Vector3f(up).mul(MathHelper.sin(th)));
            Vector3f ay = new Vector3f(ax).cross(view).normalize();
            quad(vc, m, c, ax.mul(r * (14f + 6f * (k % 2))), ay.mul(r * 0.5f), 1f, 0.9f, 0.75f, a * 0.55f);
        }
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
        }, true);
        if (heat > 0.6f) {
            float a = (heat - 0.6f) * 0.5f * alpha;
            VertexConsumer g = cp.getBuffer(BeamRenderLayers.GLOW);
            glowBall(g, m, c, r * 1.01f, 1f, 0.35f + 0.4f * white, 0.1f + 0.5f * white, Math.min(0.5f, a));
        }
    }

    // ---- fragments --------------------------------------------------------------------------------

    private record Shard(Vector3f dir, float speed, Vector3f axis, float spin, List<Vector3f[]> tris) {}

    private static final Map<Body, List<Shard>> SHARDS = new EnumMap<>(Body.class);
    private static int shardDetail = -1;

    private static List<Shard> shards(Body b) {
        int detail = com.creedpdf.orbitalrailgun.client.config.Quality.geo() >= 0.9f ? 2 : com.creedpdf.orbitalrailgun.client.config.Quality.geo() >= 0.6f ? 1 : 0;
        if (detail != shardDetail) {
            SHARDS.clear();
            shardDetail = detail;
        }
        return SHARDS.computeIfAbsent(b, PlanetBlastFx::buildShards);
    }

    /** Icosphere (5120 triangles) cut into ~420 Voronoi chunks. */
    private static List<Shard> buildShards(Body b) {
        List<Vector3f[]> tris = icosphere(shardDetail == 2 ? 4 : 3);
        Random rand = Random.create(b.ordinal() * 104729L + 17);
        int n = shardDetail == 2 ? 420 : shardDetail == 1 ? 220 : 140;
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
        // two passes (rock faces, then molten glow): one shared buffer can only hold one layer at a time
        for (int pass = 0; pass < 2; pass++) {
            VertexConsumer vc = pass == 0
                    ? cp.getBuffer(alpha >= 0.999f ? BeamRenderLayers.solid(BodyDraw.albedo(b)) : BeamRenderLayers.translucent(BodyDraw.albedo(b)))
                    : cp.getBuffer(BeamRenderLayers.GLOW);
            Vector3f tmp = new Vector3f();
            for (Shard s : shards(b)) {
                float dist = r * (s.speed * (0.25f + 6.5f * ease) + drift * s.speed * 40f);
                Vector3f centre = new Vector3f(s.dir).mul(r + dist).add(c);
                Quaternionf rot = new Quaternionf().rotationAxis(s.spin * d * 0.0035f, s.axis.x, s.axis.y, s.axis.z).mul(q);
                Vector3f apexLocal = new Vector3f(s.dir).mul(-0.62f * r);
                Vector3f apex = rot.transform(new Vector3f(apexLocal)).add(centre);
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
                    if (pass == 0) {
                        if (Math.abs(uu[0] - uu[1]) > 0.5f || Math.abs(uu[0] - uu[2]) > 0.5f) {
                            for (int k = 0; k < 3; k++) if (uu[k] < 0.5f) uu[k] += 1f;
                        }
                        Vector3f nrm = rot.transform(new Vector3f(t[0]).add(t[1]).add(t[2]).normalize());
                        float k = BodyDraw.lit(nrm, l, 0.03f);
                        for (int v = 0; v < 4; v++) {
                            int idx = Math.min(v, 2);
                            vc.vertex(m, p[idx].x, p[idx].y, p[idx].z).color(k, k, k, alpha).texture(uu[idx], vv[idx]).light(LIGHT).normal(0, 1, 0).next();
                        }
                    } else {
                        float ga = hot * alpha;
                        for (int e2 = 0; e2 < 3; e2++) {
                            Vector3f a = p[e2], bb = p[(e2 + 1) % 3];
                            vc.vertex(m, a.x, a.y, a.z).color(hr, hg, hb, ga * 0.6f).next();
                            vc.vertex(m, bb.x, bb.y, bb.z).color(hr, hg, hb, ga * 0.6f).next();
                            vc.vertex(m, apex.x, apex.y, apex.z).color(hr, hg * 1.05f, hb + 0.1f, ga).next();
                            vc.vertex(m, apex.x, apex.y, apex.z).color(hr, hg * 1.05f, hb + 0.1f, ga).next();
                        }
                    }
                }
            }
        }
    }

    // ---- primitives -------------------------------------------------------------------------------

    public static void glowBall(VertexConsumer g, Matrix4f m, Vector3f c, float r, float cr, float cg, float cb, float a) {
        if (a <= 0.003f) return;
        int lon = com.creedpdf.orbitalrailgun.client.config.Quality.segs(48, 16), lat = lon / 2;
        float[] grid = BodyDraw.grid(lat, lon);
        int w = lon + 1;
        for (int i = 0; i < lat; i++) {
            for (int j = 0; j < lon; j++) {
                int k00 = i * w + j;
                int[] ks = {k00, k00 + 1, k00 + w + 1, k00 + w};
                for (int k : ks) {
                    float nx = grid[k * 3], ny = grid[k * 3 + 1], nz = grid[k * 3 + 2];
                    float px = nx * r + c.x, py = ny * r + c.y, pz = nz * r + c.z;
                    float len = (float) Math.sqrt(px * px + py * py + pz * pz);
                    float facing = len > 0 ? Math.abs(nx * px + ny * py + nz * pz) / len : 1f;
                    g.vertex(m, px, py, pz).color(cr, cg, cb, a * facing * facing).next();
                }
            }
        }
    }

    static void ring(VertexConsumer vc, Matrix4f m, Vector3f c, Vector3f normal, float inner, float outer, float r, float g, float b, float a) {
        Vector3f n = new Vector3f(normal).normalize();
        Vector3f u = new Vector3f(n).cross(Math.abs(n.y) < 0.9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0)).normalize();
        Vector3f v = new Vector3f(n).cross(u).normalize();
        int seg = com.creedpdf.orbitalrailgun.client.config.Quality.segs(256, 64);
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

    /** Quad centred on p spanning +-ax, +-ay (texture U along ax). */
    static void quad(VertexConsumer vc, Matrix4f m, Vector3f p, Vector3f ax, Vector3f ay, float r, float g, float b, float a) {
        if (a <= 0.003f) return;
        float[][] k = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
        for (float[] q : k) {
            float x = p.x + ax.x * q[0] + ay.x * q[1], y = p.y + ax.y * q[0] + ay.y * q[1], z = p.z + ax.z * q[0] + ay.z * q[1];
            vc.vertex(m, x, y, z).color(r, g, b, a).texture(q[2], q[3]).light(LIGHT).normal(0, 1, 0).next();
        }
    }

    /** Velocity-stretched streak from {@code tail} along {@code dir}, turned to face the camera (texture U: 0 tail .. 1 head). */
    public static void streak(VertexConsumer vc, Matrix4f m, Vector3f tail, Vector3f dir, float len, float width, float r, float g, float b, float a) {
        if (a <= 0.003f) return;
        Vector3f head = new Vector3f(dir).mul(len).add(tail);
        Vector3f mid = new Vector3f(tail).add(head).mul(0.5f);
        Vector3f side = new Vector3f(dir).cross(mid);
        if (side.lengthSquared() < 1e-9f) return;
        side.normalize().mul(width);
        vc.vertex(m, tail.x - side.x, tail.y - side.y, tail.z - side.z).color(r, g, b, a).texture(0, 0).light(LIGHT).normal(0, 1, 0).next();
        vc.vertex(m, head.x - side.x, head.y - side.y, head.z - side.z).color(r, g, b, a).texture(1, 0).light(LIGHT).normal(0, 1, 0).next();
        vc.vertex(m, head.x + side.x, head.y + side.y, head.z + side.z).color(r, g, b, a).texture(1, 1).light(LIGHT).normal(0, 1, 0).next();
        vc.vertex(m, tail.x + side.x, tail.y + side.y, tail.z + side.z).color(r, g, b, a).texture(0, 1).light(LIGHT).normal(0, 1, 0).next();
    }

    public static void blackbodyPublic(float t, float[] out) {
        blackbody(t, out);
    }

    public static void billboardPublic(VertexConsumer vc, Matrix4f m, Vector3f p, float size, float rot, float r, float g, float b, float a) {
        billboard(vc, m, p, size, rot, r, g, b, a);
    }

    static Vector3f randomDir(Random rand) {
        float z = rand.nextFloat() * 2f - 1f;
        float th = rand.nextFloat() * MathHelper.TAU;
        float s = (float) Math.sqrt(1f - z * z);
        return new Vector3f(MathHelper.cos(th) * s, z, MathHelper.sin(th) * s);
    }

    /** Subdivided icosahedron, unit radius. */
    public static List<Vector3f[]> icosphere(int subdiv) {
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
