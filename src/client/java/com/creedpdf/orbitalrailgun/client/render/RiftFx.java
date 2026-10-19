package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The rift, high above the emplacement: a torn, jagged opening whose rim is shredded into spikes
 * of violet light, and behind it a real volumetric throat that twists up into the dimension
 * beyond, walled with swirling nebula and ending at a blazing star. Fully 3D, lit planets hang
 * inside the throat. The railgun's beam holds it open, with energy tendrils clawing at the rim.
 */
public final class RiftFx {
    private RiftFx() {}

    public static final Identifier WALL = RailgunMod.id("textures/effect/rift_wall.png");
    public static final Identifier SPACE = RailgunMod.id("textures/effect/rift_space.png");
    public static final Identifier SUN = RailgunMod.id("textures/effect/star_surface.png");
    public static final Identifier RINGS = RailgunMod.id("textures/effect/planet_rings.png");
    public static final Identifier P_GAS = RailgunMod.id("textures/effect/planet_gas.png");
    public static final Identifier P_OCEAN = RailgunMod.id("textures/effect/planet_ocean.png");
    public static final Identifier P_LAVA = RailgunMod.id("textures/effect/planet_lava.png");
    public static final Identifier P_ICE = RailgunMod.id("textures/effect/planet_ice.png");

    private static final float DEPTH = 78f;       // throat length (blocks)

    private record Bolt(Vec3d from, Vec3d to, long start, long seed) {}

    private static final List<Bolt> BOLTS = new ArrayList<>();
    private static final VertexConsumerProvider.Immediate BUFFER = VertexConsumerProvider.immediate(new BufferBuilder(1 << 22));

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(RiftFx::render);
    }

    public static void bolt(ClientWorld world, Vec3d from, Vec3d to) {
        BOLTS.add(new Bolt(from, to, world.getTime(), world.random.nextLong()));
    }

    private static void render(WorldRenderContext ctx) {
        ClientWorld world = ctx.world();
        if (world == null) return;
        float td = ctx.tickDelta();
        long now = world.getTime();
        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = ctx.matrixStack().peek().getPositionMatrix();
        boolean any = false;
        for (Entity e : world.getEntities()) {
            if (!(e instanceof RailgunEntity rg) || rg.getState() != RailgunState.RIFT) continue;
            any = true;
            rift(rg, rg.getStateTicks() + td, m, cam, now + td);
        }
        BOLTS.removeIf(b -> now - b.start > 9 || now < b.start);
        for (Bolt b : BOLTS) {
            any = true;
            float a = 1f - (now - b.start + td) / 9f;
            Random rand = Random.create(b.seed + (now - b.start) / 2);
            Vector3f from = rel(b.from, cam), to = rel(b.to, cam);
            double ang = rand.nextDouble() * Math.PI * 2;
            from.add((float) Math.cos(ang) * 18f, -1f, (float) Math.sin(ang) * 18f);
            for (int k = 0; k < 3; k++) {
                BeamRenderer.arc(BeamRenderer.glow(BUFFER), m, from, to, rand, 0.4f - k * 0.1f, 0.8f + 0.1f * k, 0.5f + 0.2f * k, 1f, a);
            }
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, to, 2.6f, 0.75f, 0.45f, 1f, 0.8f * a);
        }
        if (!any) return;
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        BUFFER.draw();
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }

    private static void rift(RailgunEntity rg, float t, Matrix4f m, Vec3d cam, float time) {
        Vector3f c = rel(rg.getRiftPos(1f), cam);
        Vector3f up = new Vector3f(0, 1, 0);
        float R = RailgunEntity.riftRadius(t);
        float open = R / (float) RailgunEntity.RIFT_RADIUS;
        float collapse = t > RailgunEntity.RIFT_COLLAPSE ? (t - RailgunEntity.RIFT_COLLAPSE) / (RailgunEntity.RIFT_SNAP - RailgunEntity.RIFT_COLLAPSE) : 0f;

        // ---- the beam holding it open ----
        if (t > RailgunEntity.RIFT_FIRE && t < RailgunEntity.RIFT_SNAP + 4) {
            float a = t < RailgunEntity.RIFT_FIRE + 4 ? (t - RailgunEntity.RIFT_FIRE) / 4f
                    : t > RailgunEntity.RIFT_COLLAPSE ? Math.max(0.2f, 1f - collapse) : 1f;
            float flick = 0.85f + 0.15f * MathHelper.sin(time * 5.3f);
            Vector3f mz = rel(rg.getMuzzlePos(1f), cam);
            BeamRenderer.Beam b = new BeamRenderer.Beam(mz, new Vector3f(c).add(0, 2f, 0));
            float w = t < RailgunEntity.RIFT_OPENED ? 1.6f : 1.2f;
            BeamRenderer.texTube(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_CORE), m, b, 0.9f * w, 1f, 0.94f, 1f, a * flick, 5f, time * 3f, 0f, 0.05f, time);
            BeamRenderer.texTube(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_PLASMA), m, b, 1.9f * w, 0.72f, 0.42f, 1f, 0.85f * a, 10f, time * 2f, time * 0.05f, 0.1f, time);
            BeamRenderer.texTube(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_HALO), m, b, 3.6f * w, 0.5f, 0.22f, 0.95f, 0.5f * a, 16f, time, 0f, 0.2f, time);
            BeamRenderer.glowTube(BeamRenderer.glow(BUFFER), m, b, 8f * w, 0.45f, 0.15f, 0.85f, 0.1f * a, time);
            BeamRenderer.helix(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_HELIX), m, b, 3f, 1.1f, 0.12f, time * 0.4f, 0.6f, 0.8f, 1f, 0.75f * a, time);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, mz, 2.6f, 0.85f, 0.7f, 1f, 0.85f * a);
            // tendrils clawing the rift open
            if (R > 1f) {
                Random rand = Random.create((long) (time / 2) * 977L + rg.getId());
                for (int k = 0; k < 7; k++) {
                    float th = rand.nextFloat() * MathHelper.TAU;
                    float rr = R * edge(th, 0, time) * (0.9f + rand.nextFloat() * 0.15f);
                    Vector3f to = new Vector3f(c.x + MathHelper.cos(th) * rr, c.y + (rand.nextFloat() - 0.5f) * 2f, c.z + MathHelper.sin(th) * rr);
                    Vector3f from = new Vector3f(c).add(0, -6f - rand.nextFloat() * 10f, 0);
                    BeamRenderer.arc(BeamRenderer.glow(BUFFER), m, from, to, rand, 0.22f, 0.8f, 0.6f, 1f, 0.75f * a);
                }
            }
        }

        // ---- the tear: a crack of light zig-zags across the sky, then rips apart ----
        if (t >= RailgunEntity.RIFT_OPEN - 6 && t < RailgunEntity.RIFT_OPEN + 30) {
            float f = MathHelper.clamp((t - RailgunEntity.RIFT_OPEN + 6) / 36f, 0f, 1f);
            Random rand = Random.create(rg.getId() * 31L + (long) (time / 2));
            for (int k = 0; k < 6; k++) {
                float th = k * MathHelper.TAU / 6f + 0.4f;
                float len = (float) RailgunEntity.RIFT_RADIUS * 1.6f * Math.min(1f, f * 2.5f);
                Vector3f a0 = new Vector3f(c), a1 = new Vector3f(c.x + MathHelper.cos(th) * len, c.y + (rand.nextFloat() - 0.5f) * 3f, c.z + MathHelper.sin(th) * len);
                BeamRenderer.arc(BeamRenderer.glow(BUFFER), m, a0, a1, rand, 0.5f * (1 - f) + 0.15f, 0.95f, 0.85f, 1f, 1f - f * 0.7f);
            }
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, c, 6f + 30f * f, 0.85f, 0.7f, 1f, (1f - f) * 0.8f);
            float w = 6f + 110f * f;
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, c, up, w, w + 9f, 0.7f, 0.45f, 1f, 0.9f * (1 - f), time * 0.02f);
        }

        if (R > 0.1f) {
            throat(m, c, R, open, time, collapse);
            rim(m, c, R, time, collapse, rg.getId());
        }

        // ---- slam shut ----
        if (t >= RailgunEntity.RIFT_SNAP && t < RailgunEntity.RIFT_SNAP + 40) {
            float f = (t - RailgunEntity.RIFT_SNAP) / 40f;
            float e = 1f - (1f - f) * (1f - f);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, c, 5f + 18f * f, 0.95f, 0.85f, 1f, Math.max(0, 1f - f * 2.5f));
            float w = 4f + 140f * e;
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, c, up, w, w + 10f, 0.75f, 0.5f, 1f, 0.9f * (1 - f), time * 0.02f);
            float w2 = 3f + 80f * e;
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, c, up, w2, w2 + 4f, 0.95f, 0.9f, 1f, 0.7f * (1 - f), -time * 0.03f);
        }
    }

    /** Throat radius profile: bulges out past the opening, then narrows toward the star. */
    private static float profile(float h) {
        float bulge = 1f + 0.55f * MathHelper.sin(Math.min(h / 26f, 1f) * MathHelper.HALF_PI);
        float narrow = 1f - 0.72f * smooth((h - 28f) / (DEPTH - 28f));
        return bulge * narrow;
    }

    private static float edge(float th, float h, float time) {
        return 1f + 0.08f * MathHelper.sin(5 * th + h * 0.08f + time * 0.03f) + 0.05f * MathHelper.sin(9 * th - h * 0.05f - time * 0.05f)
                + 0.035f * MathHelper.sin(17 * th + time * 0.09f) + 0.02f * MathHelper.sin(29 * th - time * 0.15f);
    }

    private static void throat(Matrix4f m, Vector3f c, float R, float open, float time, float collapse) {
        int rings = 18, seg = 64;
        float depth = DEPTH * Math.max(0.15f, open);
        VertexConsumer wall = BUFFER.getBuffer(BeamRenderLayers.solid(WALL));
        Vector3f[][] pts = new Vector3f[rings + 1][seg + 1];
        float[] hs = new float[rings + 1];
        for (int i = 0; i <= rings; i++) {
            float f = i / (float) rings;
            float h = f * f * depth + f * 1.5f;
            hs[i] = h;
            float twist = h * 0.02f + time * 0.004f;
            float pr = R * profile(h / Math.max(0.15f, open));
            for (int j = 0; j <= seg; j++) {
                float th = j / (float) seg * MathHelper.TAU;
                float rr = pr * edge(th, h, time);
                pts[i][j] = new Vector3f(c.x + MathHelper.cos(th + twist) * rr, c.y + h, c.z + MathHelper.sin(th + twist) * rr);
            }
        }
        for (int i = 0; i < rings; i++) {
            float f0 = i / (float) rings, f1 = (i + 1) / (float) rings;
            float k0 = shade(f0, collapse), k1 = shade(f1, collapse);
            for (int j = 0; j < seg; j++) {
                float u0 = j / (float) seg * 4f, u1 = (j + 1) / (float) seg * 4f;
                float v0 = hs[i] / 20f - time * 0.006f, v1 = hs[i + 1] / 20f - time * 0.006f;
                for (int side = 0; side < 2; side++) {
                    Vector3f a = pts[i][j], b = pts[i][j + 1], cc = pts[i + 1][j + 1], d = pts[i + 1][j];
                    if (side == 1) { Vector3f tmp = b; b = a; a = tmp; tmp = cc; cc = d; d = tmp; }
                    float ua = side == 0 ? u0 : u1, ub = side == 0 ? u1 : u0;
                    BeamRenderer.texVertRaw(wall, m, a, k0 * 0.85f, k0 * 0.75f, k0, 1f, ua, v0);
                    BeamRenderer.texVertRaw(wall, m, b, k0 * 0.85f, k0 * 0.75f, k0, 1f, ub, v0);
                    BeamRenderer.texVertRaw(wall, m, cc, k1 * 0.85f, k1 * 0.75f, k1, 1f, ub, v1);
                    BeamRenderer.texVertRaw(wall, m, d, k1 * 0.85f, k1 * 0.75f, k1, 1f, ua, v1);
                }
            }
        }
        // far end: deep space cap and the star that lights everything inside
        Vector3f top = new Vector3f(c).add(0, hs[rings], 0);
        VertexConsumer cap = BUFFER.getBuffer(BeamRenderLayers.solid(SPACE));
        for (int j = 0; j < seg; j++) {
            Vector3f a = pts[rings][j], b = pts[rings][j + 1];
            for (int side = 0; side < 2; side++) {
                Vector3f[] q = side == 0 ? new Vector3f[]{top, a, b, b} : new Vector3f[]{b, b, a, top};
                for (Vector3f v : q) {
                    BeamRenderer.texVertRaw(cap, m, v, 1f, 1f, 1f, 1f, 0.5f + (v.x - top.x) / (R * 1.6f), 0.5f + (v.z - top.z) / (R * 1.6f));
                }
            }
        }
        Vector3f star = new Vector3f(top).add(0, -3f, 0);
        float sr = R * 0.16f * (1f + collapse);
        BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, star, sr * 0.85f, 1f, 0.98f, 0.92f, 1f);
        BeamRenderer.texSphere(BeamRenderer.tex(BUFFER, SUN), m, star, sr, 0.95f, 0.85f, 1f, 1f, time * 0.002f, time * 0.001f);
        BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, star, sr * 2.2f, 0.75f, 0.7f, 1f, 0.35f);
        BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, star, sr * 5f, 0.55f, 0.45f, 1f, 0.12f);

        // planets, hanging at different depths in the throat
        if (open > 0.6f) {
            float sc = R / (float) RailgunEntity.RIFT_RADIUS;
            Quaternionf s1 = new Quaternionf().rotationY(time * 0.003f), s2 = new Quaternionf().rotationZ(0.25f).rotateY(time * 0.006f);
            Vector3f gasC = new Vector3f(c).add(-0.42f * R, 34f * sc, 0.12f * R);
            PlanetDraw.planet(BUFFER, m, P_GAS, gasC, 7.5f * sc, s1, new Vector3f(star).sub(gasC), 0.06f, new float[]{1f, 0.8f, 0.55f}, 0.8f);
            PlanetDraw.rings(BUFFER, m, RINGS, gasC, new Vector3f(0.3f, 1f, 0.25f), 10f * sc, 17f * sc, new Vector3f(star).sub(gasC), 7.5f * sc);
            Vector3f oceanC = new Vector3f(c).add(0.45f * R, 18f * sc, -0.2f * R);
            PlanetDraw.planet(BUFFER, m, P_OCEAN, oceanC, 4.2f * sc, s2, new Vector3f(star).sub(oceanC), 0.05f, new float[]{0.4f, 0.65f, 1f}, 1.2f);
            Vector3f moonC = new Vector3f(oceanC).add(6f * sc * MathHelper.cos(time * 0.01f), 1f * sc, 6f * sc * MathHelper.sin(time * 0.01f));
            PlanetDraw.planet(BUFFER, m, P_ICE, moonC, 1.1f * sc, s1, new Vector3f(star).sub(moonC), 0.05f, null, 0f);
            Vector3f lavaC = new Vector3f(c).add(0.12f * R, 52f * sc, -0.34f * R);
            PlanetDraw.planet(BUFFER, m, P_LAVA, lavaC, 2.6f * sc, s2, new Vector3f(star).sub(lavaC), 0.18f, new float[]{1f, 0.45f, 0.2f}, 0.7f);
        }
    }

    private static float shade(float f, float collapse) {
        // brighter right at the torn edge and again near the star, dark in between
        float k = 0.55f + 0.45f * (float) Math.exp(-f * 9f) + 0.35f * f * f;
        return Math.min(1f, k * (1f + collapse * 0.8f));
    }

    private static void rim(Matrix4f m, Vector3f c, float R, float time, float collapse, int id) {
        VertexConsumer g = BeamRenderer.glow(BUFFER);
        int seg = 96;
        float bright = 1f + collapse * 1.4f;
        float[] rr = new float[seg + 1];
        for (int i = 0; i <= seg; i++) rr[i] = R * profile(0) * edge(i / (float) seg * MathHelper.TAU, 0, time);
        float twist = time * 0.004f;
        // shredded spikes of light torn outward from the edge (two layers, re-rolled slowly)
        for (int layer = 0; layer < 2; layer++) {
            Random rand = Random.create(id * 7919L + layer * 104729L + (long) (time / 6));
            for (int i = 0; i < seg; i += 1 + layer) {
                float th0 = i / (float) seg * MathHelper.TAU + twist, th1 = (i + 1 + layer) / (float) seg * MathHelper.TAU + twist;
                int i1 = Math.min(seg, i + 1 + layer);
                float len = 1.08f + rand.nextFloat() * (0.25f + 0.25f * layer);
                float thm = (th0 + th1) * 0.5f + (rand.nextFloat() - 0.5f) * 0.05f;
                float rm = (rr[i] + rr[i1]) * 0.5f * len;
                Vector3f b0 = new Vector3f(c.x + MathHelper.cos(th0) * rr[i], c.y, c.z + MathHelper.sin(th0) * rr[i]);
                Vector3f b1 = new Vector3f(c.x + MathHelper.cos(th1) * rr[i1], c.y, c.z + MathHelper.sin(th1) * rr[i1]);
                Vector3f tip = new Vector3f(c.x + MathHelper.cos(thm) * rm, c.y + (rand.nextFloat() - 0.6f) * 4f, c.z + MathHelper.sin(thm) * rm);
                float a = Math.min(1f, (0.95f - 0.35f * layer) * bright);
                g.vertex(m, b0.x, b0.y, b0.z).color(0.95f, 0.85f, 1f, a).next();
                g.vertex(m, b1.x, b1.y, b1.z).color(0.95f, 0.85f, 1f, a).next();
                g.vertex(m, tip.x, tip.y, tip.z).color(0.55f, 0.25f, 1f, 0f).next();
                g.vertex(m, tip.x, tip.y, tip.z).color(0.55f, 0.25f, 1f, 0f).next();
            }
        }
        // bright torn edge + volumetric halo bands above and below the opening
        band(g, m, c, rr, seg, twist, 0.96f, 1.04f, 0f, 1f, 0.92f, 1f, Math.min(1f, bright));
        for (int k = -2; k <= 2; k++) {
            band(g, m, c, rr, seg, twist, 1.0f, 1.45f + 0.12f * Math.abs(k), k * 1.6f, 0.6f, 0.3f, 1f, 0.22f * bright / (1 + Math.abs(k)));
        }
        band(g, m, c, rr, seg, twist, 1.3f, 2.6f, -0.5f, 0.4f, 0.15f, 0.85f, 0.07f * bright);
        // lightning crawling along the rim and leaping across it
        Random rand = Random.create((long) (time / 2) * 131L + id);
        int arcs = 18 + (int) (collapse * 24);
        for (int k = 0; k < arcs; k++) {
            int i0 = rand.nextInt(seg), i1 = (i0 + 2 + rand.nextInt(k % 5 == 0 ? 40 : 7)) % seg;
            float a0 = i0 / (float) seg * MathHelper.TAU + twist, a1 = i1 / (float) seg * MathHelper.TAU + twist;
            Vector3f s0 = new Vector3f(c.x + MathHelper.cos(a0) * rr[i0], c.y + (rand.nextFloat() - 0.5f) * 2f, c.z + MathHelper.sin(a0) * rr[i0]);
            Vector3f s1 = new Vector3f(c.x + MathHelper.cos(a1) * rr[i1], c.y + (rand.nextFloat() - 0.5f) * 2f, c.z + MathHelper.sin(a1) * rr[i1]);
            BeamRenderer.arc(g, m, s0, s1, rand, 0.16f, 0.85f, 0.72f, 1f, 0.9f);
        }
    }

    private static void band(VertexConsumer vc, Matrix4f m, Vector3f c, float[] rr, int seg, float twist, float k0, float k1, float dy,
                             float r, float g, float b, float a) {
        for (int i = 0; i < seg; i++) {
            float a0 = i / (float) seg * MathHelper.TAU + twist, a1 = (i + 1) / (float) seg * MathHelper.TAU + twist;
            float c0 = MathHelper.cos(a0), s0 = MathHelper.sin(a0), c1 = MathHelper.cos(a1), s1 = MathHelper.sin(a1);
            vc.vertex(m, c.x + c0 * rr[i] * k0, c.y + dy, c.z + s0 * rr[i] * k0).color(r, g, b, a).next();
            vc.vertex(m, c.x + c1 * rr[i + 1] * k0, c.y + dy, c.z + s1 * rr[i + 1] * k0).color(r, g, b, a).next();
            vc.vertex(m, c.x + c1 * rr[i + 1] * k1, c.y + dy, c.z + s1 * rr[i + 1] * k1).color(r, g, b, 0f).next();
            vc.vertex(m, c.x + c0 * rr[i] * k1, c.y + dy, c.z + s0 * rr[i] * k1).color(r, g, b, 0f).next();
        }
    }

    private static float smooth(float f) {
        f = MathHelper.clamp(f, 0f, 1f);
        return f * f * (3f - 2f * f);
    }

    private static Vector3f rel(Vec3d p, Vec3d cam) {
        return new Vector3f((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z));
    }
}
