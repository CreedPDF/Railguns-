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
 * of violet light. The opening is a real see-through portal into space (Immersive Portals). The
 * railgun's beam holds it open, with energy tendrils clawing at the rim.
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


    private record Bolt(Vec3d from, Vec3d to, long start, long seed) {}

    private static final List<Bolt> BOLTS = new ArrayList<>();
    private static final VertexConsumerProvider.Immediate BUFFER = VertexConsumerProvider.immediate(new BufferBuilder(1 << 22));

    /** Rim around an arbitrary outline (points relative to render space). */
    public static void rimAt(VertexConsumerProvider buf, Matrix4f m, Vector3f c, Vector3f[] outline, float time, float collapse, int id) {
        rim(buf, m, c, outline, time, collapse, id);
    }

    /** The tear's outline at the current size, in render space. */
    private static Vector3f[] outline(RailgunEntity rg, Vector3f c, float R, float time) {
        float[][] pts = com.creedpdf.orbitalrailgun.space.RiftShape.outline(
                rg.getUuid().getMostSignificantBits() ^ 0x5EED_0F_A215L, R * 0.9f);
        Vector3f[] out = new Vector3f[pts.length];
        for (int i = 0; i < pts.length; i++) {
            float wob = 1f + 0.012f * MathHelper.sin(time * 0.07f + i * 0.9f);
            out[i] = new Vector3f(c.x + pts[i][0] * wob, c.y, c.z + pts[i][1] * wob);
        }
        return out;
    }

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
                Vector3f[] ol = outline(rg, c, R, time);
                for (int k = 0; k < 9; k++) {
                    Vector3f to = new Vector3f(ol[rand.nextInt(ol.length)]).add(0, (rand.nextFloat() - 0.5f) * 2f, 0);
                    Vector3f from = new Vector3f(c).add(0, -6f - rand.nextFloat() * 10f, 0);
                    BeamRenderer.arc(BeamRenderer.glow(BUFFER), m, from, to, rand, 0.22f, 0.8f, 0.6f, 1f, 0.75f * a);
                }
            }
        }

        // ---- the tear: a crack of light zig-zags across the sky, then rips apart ----
        if (t >= RailgunEntity.RIFT_OPEN - 6 && t < RailgunEntity.RIFT_OPEN + 30) {
            float f = MathHelper.clamp((t - RailgunEntity.RIFT_OPEN + 6) / 36f, 0f, 1f);
            Random rand = Random.create(rg.getId() * 31L + (long) (time / 2));
            Vector3f[] ol = outline(rg, c, (float) RailgunEntity.RIFT_RADIUS, time);
            int n = ol.length;
            float reach = Math.min(1f, f * 2.5f);
            for (int end = 0; end < 2; end++) {
                Vector3f tip = ol[end == 0 ? 0 : n / 2];
                Vector3f a1 = new Vector3f(c).lerp(tip, reach);
                for (int k = 0; k < 3; k++) {
                    BeamRenderer.arc(BeamRenderer.glow(BUFFER), m, new Vector3f(c), a1, rand, 0.6f * (1 - f) + 0.2f, 0.95f, 0.85f, 1f, 1f - f * 0.6f);
                }
            }
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, c, 6f + 30f * f, 0.85f, 0.7f, 1f, (1f - f) * 0.8f);
            float w = 6f + 110f * f;
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, c, up, w, w + 9f, 0.7f, 0.45f, 1f, 0.9f * (1 - f), time * 0.02f);
        }

        // the opening itself is an Immersive Portals portal into real space; we draw the torn rim around it
        if (R > 0.1f) rim(BUFFER, m, c, outline(rg, c, R, time), time, collapse, rg.getId());

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

    private static void rim(VertexConsumerProvider cp, Matrix4f m, Vector3f c, Vector3f[] pts, float time, float collapse, int id) {
        VertexConsumer g = BeamRenderer.glow(cp);
        int n = pts.length;
        float bright = 1f + collapse * 1.4f;
        // outward normal at every vertex (average of the two edge normals, pointing away from the centre)
        Vector3f[] nrm = new Vector3f[n];
        for (int i = 0; i < n; i++) {
            Vector3f prev = pts[(i - 1 + n) % n], next = pts[(i + 1) % n];
            Vector3f t = new Vector3f(next).sub(prev);
            Vector3f nn = new Vector3f(t.z, 0, -t.x);
            if (nn.lengthSquared() < 1e-6f) nn.set(pts[i].x - c.x, 0, pts[i].z - c.z);
            nn.normalize();
            if (nn.dot(pts[i].x - c.x, 0, pts[i].z - c.z) < 0) nn.negate();
            nrm[i] = nn;
        }
        // shredded spikes of light torn outward from the edge (two layers, re-rolled slowly)
        for (int layer = 0; layer < 2; layer++) {
            Random rand = Random.create(id * 7919L + layer * 104729L + (long) (time / 6));
            int step = 1 + layer;
            for (int i = 0; i < n; i += step) {
                int j = (i + step) % n;
                Vector3f b0 = pts[i], b1 = pts[j];
                float len = 2.5f + rand.nextFloat() * (5f + 6f * layer);
                Vector3f nm = new Vector3f(nrm[i]).add(nrm[j]).normalize();
                Vector3f tip = new Vector3f(b0).add(b1).mul(0.5f).add(new Vector3f(nm).mul(len)).add(0, (rand.nextFloat() - 0.6f) * 4f, 0);
                float a = Math.min(1f, (0.95f - 0.35f * layer) * bright);
                g.vertex(m, b0.x, b0.y, b0.z).color(0.95f, 0.85f, 1f, a).next();
                g.vertex(m, b1.x, b1.y, b1.z).color(0.95f, 0.85f, 1f, a).next();
                g.vertex(m, tip.x, tip.y, tip.z).color(0.55f, 0.25f, 1f, 0f).next();
                g.vertex(m, tip.x, tip.y, tip.z).color(0.55f, 0.25f, 1f, 0f).next();
            }
        }
        // bright torn edge + volumetric halo bands above and below the opening
        band(g, m, pts, nrm, -0.6f, 0.9f, 0f, 1f, 0.92f, 1f, Math.min(1f, bright));
        for (int k = -2; k <= 2; k++) {
            band(g, m, pts, nrm, 0f, 7f + 1.5f * Math.abs(k), k * 1.6f, 0.6f, 0.3f, 1f, 0.22f * bright / (1 + Math.abs(k)));
        }
        band(g, m, pts, nrm, 4f, 26f, -0.5f, 0.4f, 0.15f, 0.85f, 0.07f * bright);
        // energy crawling along the rim
        Random rand = Random.create((long) (time / 2) * 131L + id);
        int arcs = 22 + (int) (collapse * 24);
        for (int k = 0; k < arcs; k++) {
            int i0 = rand.nextInt(n), i1 = (i0 + 2 + rand.nextInt(9)) % n;
            Vector3f s0 = new Vector3f(pts[i0]).add(0, (rand.nextFloat() - 0.5f) * 2f, 0);
            Vector3f s1 = new Vector3f(pts[i1]).add(0, (rand.nextFloat() - 0.5f) * 2f, 0);
            BeamRenderer.arc(g, m, s0, s1, rand, 0.16f, 0.85f, 0.72f, 1f, 0.9f);
        }
    }

    /** A strip along the outline from {@code in} to {@code out} blocks past the edge (alpha fades outward). */
    private static void band(VertexConsumer vc, Matrix4f m, Vector3f[] pts, Vector3f[] nrm, float in, float out, float dy,
                             float r, float g, float b, float a) {
        int n = pts.length;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            Vector3f p0 = pts[i], p1 = pts[j], n0 = nrm[i], n1 = nrm[j];
            vc.vertex(m, p0.x + n0.x * in, p0.y + dy, p0.z + n0.z * in).color(r, g, b, a).next();
            vc.vertex(m, p1.x + n1.x * in, p1.y + dy, p1.z + n1.z * in).color(r, g, b, a).next();
            vc.vertex(m, p1.x + n1.x * out, p1.y + dy, p1.z + n1.z * out).color(r, g, b, 0f).next();
            vc.vertex(m, p0.x + n0.x * out, p0.y + dy, p0.z + n0.z * out).color(r, g, b, 0f).next();
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
