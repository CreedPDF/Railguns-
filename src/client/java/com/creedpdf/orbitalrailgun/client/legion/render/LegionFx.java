package com.creedpdf.orbitalrailgun.client.legion.render;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.legion.SwarmPaths;
import com.creedpdf.orbitalrailgun.legion.entity.AutonomousWeaponEntity;
import com.creedpdf.orbitalrailgun.legion.entity.SwarmState;
import com.creedpdf.orbitalrailgun.legion.network.LegionPackets;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Arsenal Legion's world effects, drawn as raw additive vertex quads (shader-pack safe, no textures):
 * <ul>
 *   <li><b>afterimage trails</b> - every blade drags a tapering ribbon of its own colour (by material; enchanted runs
 *   rose). In formation the trail is kept relative to its owner, so it traces the swarm's shape instead of smearing
 *   when you run; striking it is a long white-cored streak in world space;</li>
 *   <li><b>telekinetic tethers</b> - faint wavering threads from the owner's chest to each blade, flaring in attack;</li>
 *   <li><b>the Legion sigil</b> - a turning rune circle at the owner's feet (double ring, tick marks, two counter-
 *   rotating triangles); in a Blade Storm it swells out under the vortex;</li>
 *   <li><b>impacts</b> - crossed crescent slash arcs, a shock ring and a spray of sparks where a blade passes
 *   through; <b>executions</b> add tilted shock rings, a pillar of light and a bigger spray; <b>parries</b> a bright
 *   ring and sparks thrown back along the shot;</li>
 *   <li><b>Blade Storm</b> - rising spiral ribbons whirling round the owner and shock rings racing out across the
 *   ground; a formation change sends a ring pulse out from the owner.</li>
 * </ul>
 */
public final class LegionFx {
    private LegionFx() {}

    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 19));
    private static final Random RND = Random.create();
    private static final int TRAIL = 9;
    private static long ticks;

    private record Sample(Vec3d pos, Vec3d owner) {}

    private record Burst(byte kind, Vec3d at, Vec3d dir, float[] color, long t0, long seed, int source) {}

    private static final Map<Integer, ArrayDeque<Sample>> TRAILS = new HashMap<>();
    private static final List<Burst> BURSTS = new ArrayList<>();

    // ------------------------------------------------------------------------------------------- events

    public static void burst(byte kind, Vec3d at, Vec3d dir, int source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        float[] col = {1f, 0.3f, 0.08f};
        if (mc.world != null && mc.world.getEntityById(source) instanceof AutonomousWeaponEntity w) col = LegionVisual.color(w.getStack());
        if (BURSTS.size() < 256) BURSTS.add(new Burst(kind, at, dir, col, ticks, RND.nextLong(), source));
    }

    public static void clear() {
        TRAILS.clear();
        BURSTS.clear();
    }

    private static List<AutonomousWeaponEntity> weapons(MinecraftClient mc) {
        Vec3d c = mc.gameRenderer.getCamera().getPos();
        return mc.world.getEntitiesByClass(AutonomousWeaponEntity.class, new Box(c, c).expand(96), e -> !e.isRemoved());
    }

    public static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.isPaused()) return;
        ticks++;
        BURSTS.removeIf(b -> ticks - b.t0() > life(b.kind()));
        List<AutonomousWeaponEntity> ws = weapons(mc);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (AutonomousWeaponEntity e : ws) {
            seen.add(e.getId());
            PlayerEntity o = e.owner();
            SwarmPaths.Pose p = LegionVisual.pose(e, 1f);
            ArrayDeque<Sample> q = TRAILS.computeIfAbsent(e.getId(), k -> new ArrayDeque<>());
            q.addLast(new Sample(center(p), o == null ? Vec3d.ZERO : o.getPos()));
            int max = e.state() == SwarmState.STRIKE ? TRAIL + 3 : TRAIL;
            while (q.size() > max) q.removeFirst();
        }
        TRAILS.keySet().retainAll(seen);
    }

    private static int life(byte kind) {
        return switch (kind) {
            case LegionPackets.FX_EXECUTE -> 18;
            case LegionPackets.FX_STORM -> 32;
            case LegionPackets.FX_FORMATION -> 14;
            default -> 10;
        };
    }

    /** The middle of the blade (the harness sits 0.3 up and the blade runs on along the aim). */
    static Vec3d center(SwarmPaths.Pose p) {
        return p.pos().add(0, 0.3, 0).add(p.aim().multiply(0.65));
    }

    // ------------------------------------------------------------------------------------------- drawing

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || KrlCompat.shadowPass()) return;
        List<AutonomousWeaponEntity> ws = weapons(mc);
        if (ws.isEmpty() && BURSTS.isEmpty()) return;
        float pt = ctx.tickDelta();
        Vec3d c = ctx.camera().getPos();
        Matrix4f m = ctx.matrixStack().peek().getPositionMatrix();
        VertexConsumer g = BUF.getBuffer(BeamRenderLayers.GLOW);
        float time = mc.world.getTime() + pt;

        Map<UUID, List<AutonomousWeaponEntity>> byOwner = new HashMap<>();
        for (AutonomousWeaponEntity e : ws) {
            if (e.ownerUuid() != null) byOwner.computeIfAbsent(e.ownerUuid(), k -> new ArrayList<>()).add(e);
            trail(g, m, c, e, pt);
        }
        for (Map.Entry<UUID, List<AutonomousWeaponEntity>> en : byOwner.entrySet()) {
            PlayerEntity o = mc.world.getPlayerByUuid(en.getKey());
            if (o == null) continue;
            Vec3d feet = o.getLerpedPos(pt);
            AutonomousWeaponEntity any = en.getValue().get(0);
            boolean storm = any.state() == SwarmState.STORM;
            double st = storm ? time - any.stormStart() : 0;
            sigil(g, m, c, feet, time, storm ? Math.sin(Math.PI * MathHelper.clamp(st / SwarmPaths.STORM_TICKS, 0, 1)) : 0);
            if (storm) vortex(g, m, c, o, feet, st, time);
            Vec3d chest = feet.add(0, o.getHeight() * 0.58, 0);
            for (AutonomousWeaponEntity e : en.getValue()) tether(g, m, c, chest, e, pt, time);
        }
        for (Iterator<Burst> it = BURSTS.iterator(); it.hasNext(); ) {
            Burst b = it.next();
            float age = ticks - b.t0() + pt;
            switch (b.kind()) {
                case LegionPackets.FX_PIERCE -> pierce(g, m, c, b, age, false);
                case LegionPackets.FX_EXECUTE -> pierce(g, m, c, b, age, true);
                case LegionPackets.FX_PARRY -> parry(g, m, c, b, age);
                case LegionPackets.FX_STORM -> stormBurst(g, m, c, b, age);
                case LegionPackets.FX_FORMATION -> pulse(g, m, c, b, age);
                default -> {
                }
            }
        }
        float fs = RenderSystem.getShaderFogStart(), fe = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(1e7f);
        RenderSystem.setShaderFogEnd(2e7f);
        BUF.draw();
        RenderSystem.setShaderFogStart(fs);
        RenderSystem.setShaderFogEnd(fe);
    }

    // ---- primitives (camera at the origin)

    static void v(VertexConsumer g, Matrix4f m, Vector3f p, float r, float gg, float b, float a) {
        g.vertex(m, p.x, p.y, p.z).color(r, gg, b, a).next();
    }

    static Vector3f rel(Vec3d p, Vec3d c) {
        return new Vector3f((float) (p.x - c.x), (float) (p.y - c.y), (float) (p.z - c.z));
    }

    /** A strip a->b, its width wa at a and wb at b, turned about its axis to face the camera. */
    static void strip(VertexConsumer g, Matrix4f m, Vector3f a, Vector3f b, float wa, float wb, float[] col, float aa, float ab) {
        Vector3f d = new Vector3f(b).sub(a), mid = new Vector3f(a).add(b).mul(0.5f);
        Vector3f side = d.cross(mid, new Vector3f());
        if (side.lengthSquared() < 1e-10f) return;
        side.normalize();
        Vector3f sa = new Vector3f(side).mul(wa * 0.5f), sb = new Vector3f(side).mul(wb * 0.5f);
        v(g, m, new Vector3f(a).sub(sa), col[0], col[1], col[2], aa);
        v(g, m, new Vector3f(b).sub(sb), col[0], col[1], col[2], ab);
        v(g, m, new Vector3f(b).add(sb), col[0], col[1], col[2], ab);
        v(g, m, new Vector3f(a).add(sa), col[0], col[1], col[2], aa);
    }

    static void ring(VertexConsumer g, Matrix4f m, Vector3f c, Vector3f u, Vector3f w, float r0, float r1, int segs, float a0, float a1, float[] col,
                     float alpha0, float alpha1) {
        for (int i = 0; i < segs; i++) {
            float t0 = a0 + (a1 - a0) * i / segs, t1 = a0 + (a1 - a0) * (i + 1) / segs;
            Vector3f d0 = new Vector3f(u).mul(MathHelper.cos(t0)).add(new Vector3f(w).mul(MathHelper.sin(t0)));
            Vector3f d1 = new Vector3f(u).mul(MathHelper.cos(t1)).add(new Vector3f(w).mul(MathHelper.sin(t1)));
            v(g, m, new Vector3f(d0).mul(r0).add(c), col[0], col[1], col[2], alpha0);
            v(g, m, new Vector3f(d1).mul(r0).add(c), col[0], col[1], col[2], alpha0);
            v(g, m, new Vector3f(d1).mul(r1).add(c), col[0], col[1], col[2], alpha1);
            v(g, m, new Vector3f(d0).mul(r1).add(c), col[0], col[1], col[2], alpha1);
        }
    }

    static Vector3f[] basis(Vector3f d) {
        Vector3f u = Math.abs(d.y) < 0.9f ? new Vector3f(0, 1, 0).cross(d, new Vector3f()) : new Vector3f(1, 0, 0).cross(d, new Vector3f());
        u.normalize();
        return new Vector3f[]{u, d.cross(u, new Vector3f()).normalize()};
    }

    private static final Vector3f X = new Vector3f(1, 0, 0), Z = new Vector3f(0, 0, 1);
    private static final float[] WHITE = {1f, 0.95f, 0.88f}, EMBER = {1f, 0.18f, 0.05f}, GOLD = {1f, 0.68f, 0.16f};

    // ---- trails

    private static void trail(VertexConsumer g, Matrix4f m, Vec3d c, AutonomousWeaponEntity e, float pt) {
        ArrayDeque<Sample> q = TRAILS.get(e.getId());
        if (q == null || q.size() < 2) return;
        PlayerEntity o = e.owner();
        boolean relative = e.state().analytic() && o != null;
        Vec3d ownerNow = o == null ? Vec3d.ZERO : o.getLerpedPos(pt);
        List<Vector3f> pts = new ArrayList<>(q.size() + 1);
        for (Sample s : q) pts.add(rel(relative ? s.pos().subtract(s.owner()).add(ownerNow) : s.pos(), c));
        pts.add(rel(center(LegionVisual.pose(e, pt)), c));
        float[] col = LegionVisual.color(e.getStack());
        boolean hot = !e.state().analytic() || e.state() == SwarmState.STORM;
        float w0 = hot ? 0.5f : 0.26f, a0 = hot ? 0.75f : 0.42f;
        int n = pts.size();
        for (int i = 0; i < n - 1; i++) {
            float ka = (float) i / (n - 1), kb = (float) (i + 1) / (n - 1);
            strip(g, m, pts.get(i), pts.get(i + 1), w0 * ka * ka, w0 * kb * kb, col, a0 * ka, a0 * kb);
            if (hot) strip(g, m, pts.get(i), pts.get(i + 1), 0.09f * ka, 0.09f * kb, WHITE, ka, kb);
        }
    }

    // ---- tethers

    private static void tether(VertexConsumer g, Matrix4f m, Vec3d c, Vec3d chest, AutonomousWeaponEntity e, float pt, float time) {
        Vec3d tip = center(LegionVisual.pose(e, pt)).subtract(LegionVisual.pose(e, pt).aim().multiply(0.6));
        Vec3d d = tip.subtract(chest);
        double len = d.length();
        if (len < 0.3 || len > 24) return;
        Vec3d side = d.crossProduct(new Vec3d(0, 1, 0));
        side = side.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : side.normalize();
        Vec3d up = side.crossProduct(d).normalize();
        float[] col = LegionVisual.color(e.getStack());
        float a = e.state() == SwarmState.ORBIT ? 0.13f : 0.32f;
        int n = 10;
        Vector3f prev = rel(chest, c);
        for (int i = 1; i <= n; i++) {
            double s = i / (double) n;
            double wob = Math.sin(s * Math.PI) * (0.12 * Math.sin(time * 0.35 + s * 9 + e.getId()));
            double wob2 = Math.sin(s * Math.PI) * (0.08 * Math.cos(time * 0.27 + s * 7 + e.getId() * 0.7));
            Vector3f cur = rel(chest.add(d.multiply(s)).add(side.multiply(wob)).add(up.multiply(wob2)), c);
            strip(g, m, prev, cur, 0.035f, 0.035f, col, a, a);
            prev = cur;
        }
    }

    // ---- the sigil

    private static void sigil(VertexConsumer g, Matrix4f m, Vec3d c, Vec3d feet, float time, double storm) {
        Vector3f o = rel(feet.add(0, 0.04, 0), c);
        float R = (float) (1.25 + 4.2 * storm);
        float a = (float) (0.3 + 0.35 * storm);
        float spin = time * 0.02f;
        ring(g, m, o, X, Z, R, R + 0.07f, 48, 0, MathHelper.TAU, EMBER, a, a);
        ring(g, m, o, X, Z, R * 0.8f, R * 0.8f + 0.04f, 48, 0, MathHelper.TAU, EMBER, a * 0.8f, a * 0.8f);
        for (int i = 0; i < 24; i++) {
            float t = spin + i * MathHelper.TAU / 24;
            float r0 = R * (i % 3 == 0 ? 0.82f : 0.88f), r1 = R * 0.98f;
            Vector3f p0 = new Vector3f(MathHelper.cos(t) * r0, 0, MathHelper.sin(t) * r0).add(o);
            Vector3f p1 = new Vector3f(MathHelper.cos(t) * r1, 0, MathHelper.sin(t) * r1).add(o);
            flat(g, m, p0, p1, 0.04f, GOLD, a);
        }
        for (int tri = 0; tri < 2; tri++) {
            float base = (tri == 0 ? spin * -1.5f : spin * 1.5f + MathHelper.PI / 3);
            for (int k = 0; k < 3; k++) {
                float t0 = base + k * MathHelper.TAU / 3, t1 = base + (k + 1) * MathHelper.TAU / 3;
                float r = R * 0.78f;
                flat(g, m, new Vector3f(MathHelper.cos(t0) * r, 0, MathHelper.sin(t0) * r).add(o),
                        new Vector3f(MathHelper.cos(t1) * r, 0, MathHelper.sin(t1) * r).add(o), 0.05f, EMBER, a);
            }
        }
    }

    /** A flat ground line a->b (in the XZ plane). */
    private static void flat(VertexConsumer g, Matrix4f m, Vector3f a, Vector3f b, float w, float[] col, float al) {
        Vector3f d = new Vector3f(b).sub(a);
        Vector3f side = new Vector3f(-d.z, 0, d.x);
        if (side.lengthSquared() < 1e-10f) return;
        side.normalize().mul(w * 0.5f);
        v(g, m, new Vector3f(a).sub(side), col[0], col[1], col[2], al);
        v(g, m, new Vector3f(b).sub(side), col[0], col[1], col[2], al);
        v(g, m, new Vector3f(b).add(side), col[0], col[1], col[2], al);
        v(g, m, new Vector3f(a).add(side), col[0], col[1], col[2], al);
    }

    // ---- storm

    private static void vortex(VertexConsumer g, Matrix4f m, Vec3d c, PlayerEntity o, Vec3d feet, double st, float time) {
        double phase = MathHelper.clamp(st / SwarmPaths.STORM_TICKS, 0, 1);
        double R = 1.4 + 5.2 * Math.sin(Math.PI * phase);
        float a = (float) Math.sin(Math.PI * phase);
        for (int arm = 0; arm < 4; arm++) {
            Vector3f prev = null;
            for (int i = 0; i <= 24; i++) {
                double s = i / 24.0;
                double ang = time * 0.5 + arm * Math.PI / 2 + s * 5.5;
                double r = R * (0.45 + 0.6 * s);
                Vector3f p = rel(feet.add(Math.cos(ang) * r, s * (o.getHeight() + 2.6), Math.sin(ang) * r), c);
                if (prev != null) strip(g, m, prev, p, 0.35f * (float) s, 0.35f * (float) s, EMBER, 0.5f * a * (float) s, 0.5f * a * (float) s);
                prev = p;
            }
        }
    }

    // ---- bursts

    private static void pierce(VertexConsumer g, Matrix4f m, Vec3d c, Burst b, float age, boolean exec) {
        float life = life(b.kind());
        float k = age / life, f = 1f - k;
        Vector3f at = rel(b.at(), c);
        Vector3f d = new Vector3f((float) b.dir().x, (float) b.dir().y, (float) b.dir().z);
        if (d.lengthSquared() < 1e-6f) d.set(0, 1, 0);
        d.normalize();
        Vector3f[] uv = basis(d);
        float grow = 1f - f * f;
        // two crossed crescent slashes, perpendicular to the blade's path
        float sr = (exec ? 1.6f : 1.0f) * (0.5f + 0.8f * grow);
        ring(g, m, at, uv[0], d, sr, sr + 0.18f * f, 16, -1.2f, 1.2f, b.color(), 0f, f);
        ring(g, m, at, uv[1], d, sr * 0.9f, sr * 0.9f + 0.14f * f, 16, -1.2f, 1.2f, WHITE, 0f, f * 0.8f);
        // shock ring
        ring(g, m, at, uv[0], uv[1], (exec ? 3.2f : 1.8f) * grow, (exec ? 3.2f : 1.8f) * grow + 0.12f, 32, 0, MathHelper.TAU, b.color(), f * 0.8f, 0f);
        // sparks
        Random r = Random.create(b.seed());
        int n = exec ? 26 : 12;
        for (int i = 0; i < n; i++) {
            Vector3f s = new Vector3f(d).add(new Vector3f(r.nextFloat() - 0.5f, r.nextFloat() - 0.5f, r.nextFloat() - 0.5f).mul(1.6f)).normalize();
            float dist = (exec ? 3.4f : 2.0f) * grow * (0.4f + r.nextFloat());
            Vector3f p0 = new Vector3f(s).mul(dist).add(at), p1 = new Vector3f(s).mul(dist + 0.5f * f).add(at);
            strip(g, m, p0, p1, 0.06f * f, 0.0f, WHITE, f, 0f);
        }
        if (exec) {
            for (int i = 0; i < 3; i++) {
                Vector3f n0 = new Vector3f(r.nextFloat() - 0.5f, r.nextFloat() - 0.5f, r.nextFloat() - 0.5f).normalize();
                Vector3f[] w = basis(n0);
                float rr = 0.6f + 3f * grow * (1 + i * 0.3f);
                ring(g, m, at, w[0], w[1], rr, rr + 0.1f, 32, 0, MathHelper.TAU, i == 0 ? WHITE : b.color(), f, 0f);
            }
            Vector3f top = new Vector3f(at).add(0, 7f * grow, 0);
            strip(g, m, new Vector3f(at).sub(0, 0.5f, 0), top, 0.9f * f, 0.1f, b.color(), f * 0.7f, 0f);
            strip(g, m, new Vector3f(at).sub(0, 0.5f, 0), top, 0.2f * f, 0.02f, WHITE, f, 0f);
        }
    }

    private static void parry(VertexConsumer g, Matrix4f m, Vec3d c, Burst b, float age) {
        float f = 1f - age / life(b.kind());
        Vector3f at = rel(b.at(), c);
        Vector3f d = new Vector3f((float) b.dir().x, (float) b.dir().y, (float) b.dir().z);
        if (d.lengthSquared() < 1e-6f) d.set(0, 1, 0);
        d.normalize();
        Vector3f[] uv = basis(d);
        float rr = 0.3f + 1.4f * (1 - f * f);
        ring(g, m, at, uv[0], uv[1], rr, rr + 0.15f, 6, 0, MathHelper.TAU, GOLD, f, f * 0.3f);   // a hexagon
        ring(g, m, at, uv[0], uv[1], 0, rr * 0.6f, 12, 0, MathHelper.TAU, WHITE, f * 0.5f, 0f);
        Random r = Random.create(b.seed());
        for (int i = 0; i < 10; i++) {
            Vector3f s = new Vector3f(d).negate().add(new Vector3f(r.nextFloat() - 0.5f, r.nextFloat() - 0.5f, r.nextFloat() - 0.5f).mul(1.4f)).normalize();
            float dist = 1.8f * (1 - f) * (0.5f + r.nextFloat());
            strip(g, m, new Vector3f(s).mul(dist).add(at), new Vector3f(s).mul(dist + 0.35f * f).add(at), 0.05f * f, 0f, GOLD, f, 0f);
        }
    }

    private static void stormBurst(VertexConsumer g, Matrix4f m, Vec3d c, Burst b, float age) {
        float k = age / life(b.kind()), f = 1f - k;
        Vector3f at = rel(b.at().add(0, 0.06, 0), c);
        for (int i = 0; i < 3; i++) {
            float rr = (2 + i * 2.5f) * (1 - (1 - k) * (1 - k)) + 0.3f;
            ring(g, m, at, X, Z, rr, rr + 0.25f, 48, 0, MathHelper.TAU, i == 0 ? WHITE : EMBER, f * 0.8f, 0f);
        }
        strip(g, m, at, new Vector3f(at).add(0, 14f * (1 - f * f), 0), 1.6f * f, 0.2f, EMBER, f * 0.6f, 0f);
    }

    private static void pulse(VertexConsumer g, Matrix4f m, Vec3d c, Burst b, float age) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity o = mc.world == null ? null : mc.world.getEntityById(b.source());
        Vec3d base = o != null ? o.getLerpedPos(mc.getTickDelta()) : b.at();
        float k = age / life(b.kind()), f = 1f - k;
        float rr = 0.5f + 3f * (1 - f * f);
        ring(g, m, rel(base.add(0, 0.08, 0), c), X, Z, rr, rr + 0.2f, 40, 0, MathHelper.TAU, GOLD, f, 0f);
    }
}
